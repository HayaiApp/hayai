package dev.ahmedmohamed.hayai.migration

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Promotes completed legacy translations while retaining every archived source row. */
object LegacyTranslationCacheMigration {
    private const val PLAN_ID = "legacy-translation-cache-v1"

    fun runIfNeeded(
        db: SupportSQLiteDatabase,
        requireComplete: Boolean,
    ): LegacyTranslationPromotionResult {
        if (isComplete(db)) return LegacyTranslationPromotionResult(0, 0, 0)
        val archived = readArchivedRows(db)
        if (archived.isEmpty()) return LegacyTranslationPromotionResult(0, 0, 0)

        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        return try {
            var promoted = 0
            var pending = 0
            archived.forEach { payload ->
                val row = runCatching { LegacyTranslationCacheCodec.decode(payload) }.getOrNull()
                val identity = row?.let { resolveIdentity(db, it) }
                if (row == null || identity == null || !row.isValid()) {
                    pending++
                    return@forEach
                }
                when (insertOrCompare(db, row, identity)) {
                    LegacyTranslationPromotionAction.Inserted -> promoted++
                    LegacyTranslationPromotionAction.ExactDuplicate -> Unit
                    LegacyTranslationPromotionAction.Conflict -> {
                        pending++
                        if (requireComplete) error("Legacy translation conflicts with an existing completed translation")
                    }
                }
            }
            if (pending == 0) {
                db.execSQL(
                    "INSERT OR REPLACE INTO hayai_novel_import_runs(plan_id, status, completed_at) VALUES (?, 'complete', ?)",
                    arrayOf<Any>(PLAN_ID, System.currentTimeMillis()),
                )
            } else if (requireComplete) {
                error("Could not safely promote $pending legacy translation row(s)")
            } else {
                Timber.w("Legacy translation promotion remains pending for %d row(s)", pending)
            }
            if (ownsTransaction) db.setTransactionSuccessful()
            LegacyTranslationPromotionResult(archived.size, promoted, pending)
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    private fun readArchivedRows(db: SupportSQLiteDatabase): List<String> =
        db.query(
            "SELECT payload_json FROM hayai_legacy_rows " +
                "WHERE table_name = 'chapter_translation_cache' ORDER BY legacy_key",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun resolveIdentity(
        db: SupportSQLiteDatabase,
        row: LegacyTranslationCacheRow,
    ): LegacyTranslationIdentity? =
        db.query(
            "SELECT mangas.source,mangas.url,chapters.url FROM chapters " +
                "JOIN mangas ON mangas._id = chapters.manga_id " +
                "WHERE chapters._id = ? AND mangas._id = ?",
            arrayOf<Any>(row.chapterId, row.mangaId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            LegacyTranslationIdentity(
                chapterId = row.chapterId,
                sourceId = cursor.getLong(0),
                mangaUrl = cursor.getString(1),
                chapterUrl = cursor.getString(2),
            )
        }

    private fun insertOrCompare(
        db: SupportSQLiteDatabase,
        row: LegacyTranslationCacheRow,
        identity: LegacyTranslationIdentity,
    ): LegacyTranslationPromotionAction {
        val existing =
            db.query(
                "SELECT source_id,manga_url,chapter_url,source_language,source_hash_sha256," +
                    "translated_content,content_format,engine_id,detected_language,created_at " +
                    "FROM hayai_novel_translations WHERE chapter_id = ? AND target_language = ?",
                arrayOf<Any>(identity.chapterId, row.targetLanguage),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                LegacyComparableTranslation(
                    sourceId = cursor.getLong(0),
                    mangaUrl = cursor.getString(1),
                    chapterUrl = cursor.getString(2),
                    sourceLanguage = cursor.getString(3),
                    sourceHash = cursor.getString(4),
                    translatedContent = cursor.getString(5),
                    contentFormat = cursor.getString(6),
                    engineId = cursor.getString(7),
                    detectedLanguage = if (cursor.isNull(8)) null else cursor.getString(8),
                    createdAt = cursor.getLong(9),
                )
            }
        val expected =
            LegacyComparableTranslation(
                sourceId = identity.sourceId,
                mangaUrl = identity.mangaUrl,
                chapterUrl = identity.chapterUrl,
                sourceLanguage = row.sourceLanguage,
                sourceHash = row.originalHash.lowercase(),
                translatedContent = row.translatedContent,
                contentFormat = "plain_text_v1",
                engineId = row.engineId,
                detectedLanguage = null,
                createdAt = row.createdAt,
            )
        if (existing != null) {
            return if (existing == expected) {
                LegacyTranslationPromotionAction.ExactDuplicate
            } else {
                LegacyTranslationPromotionAction.Conflict
            }
        }

        val values =
            ContentValues(13).apply {
                put("chapter_id", identity.chapterId)
                put("source_id", identity.sourceId)
                put("manga_url", identity.mangaUrl)
                put("chapter_url", identity.chapterUrl)
                put("source_language", row.sourceLanguage)
                put("target_language", row.targetLanguage)
                put("source_hash_sha256", row.originalHash.lowercase())
                put("translated_content", row.translatedContent)
                put("content_format", "plain_text_v1")
                put("engine_id", row.engineId)
                putNull("detected_language")
                put("created_at", row.createdAt)
                put("updated_at", row.createdAt)
            }
        check(db.insert("hayai_novel_translations", SQLiteDatabase.CONFLICT_ABORT, values) >= 0)
        return LegacyTranslationPromotionAction.Inserted
    }

    private fun isComplete(db: SupportSQLiteDatabase): Boolean =
        db.query(
            "SELECT 1 FROM hayai_novel_import_runs WHERE plan_id = ? AND status = 'complete'",
            arrayOf(PLAN_ID),
        ).use { it.moveToFirst() }
}

data class LegacyTranslationPromotionResult(
    val discovered: Int,
    val promoted: Int,
    val pending: Int,
)

@Serializable
internal data class LegacyTranslationCacheRow(
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("chapter_id") val chapterId: Long,
    @SerialName("source_language") val sourceLanguage: String,
    @SerialName("target_language") val targetLanguage: String,
    @SerialName("original_hash") val originalHash: String,
    @SerialName("translated_content") val translatedContent: String,
    @SerialName("engine_id") val engineId: String,
    @SerialName("created_at") val createdAt: Long,
) {
    fun isValid(): Boolean =
        mangaId > 0 &&
            chapterId > 0 &&
            sourceLanguage.length in 1..64 &&
            targetLanguage.length in 1..64 &&
            originalHash.matches(Regex("[0-9a-fA-F]{64}")) &&
            translatedContent.length in 1..2_000_000 &&
            engineId.length in 1..128 &&
            createdAt >= 0
}

internal object LegacyTranslationCacheCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(payload: String): LegacyTranslationCacheRow = json.decodeFromString(payload)
}

private data class LegacyTranslationIdentity(
    val chapterId: Long,
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
)

private data class LegacyComparableTranslation(
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
    val sourceLanguage: String,
    val sourceHash: String,
    val translatedContent: String,
    val contentFormat: String,
    val engineId: String,
    val detectedLanguage: String?,
    val createdAt: Long,
)

private enum class LegacyTranslationPromotionAction {
    Inserted,
    ExactDuplicate,
    Conflict,
}
