package dev.ahmedmohamed.hayai.migration

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hippo.unifile.UniFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/** Rebuilds typed quotes from every retained legacy store without mutating the source data. */
class LegacyQuoteJsonMigration(
    private val context: Context,
) {
    fun runIfNeeded(db: SupportSQLiteDatabase) {
        invalidateSupersededMarkers(db)
        if (isComplete(db)) return
        val roots = legacyStorageRoots()
        var unreadableFiles = 0
        val quoteFiles =
            roots
                .orEmpty()
                .mapNotNull { root -> root.findFile(QUOTES_DIRECTORY)?.takeIf { it.isDirectory } }
                .flatMap { directory -> directory.listFiles().orEmpty().asIterable() }
                .filter { file -> file.isFile && FILE_NAME.matches(file.name.orEmpty()) }
                .distinctBy { file -> file.uri.toString() }
                .mapNotNull { file ->
                    runCatching {
                        LegacyQuoteJsonFile(file.name.orEmpty(), file.openInputStream().bufferedReader().use { it.readText() })
                    }.onFailure { error ->
                        unreadableFiles++
                        Timber.e(error, "Could not read legacy quote file %s", file.name)
                    }.getOrNull()
                }

        db.beginTransaction()
        try {
            val archivedResult = restoreArchivedQuotes(db)
            val jsonResult =
                LegacyQuoteJsonImporter.import(
                    files = quoteFiles,
                    resolveMangaId = { mangaId, novelNames -> resolveMangaId(db, mangaId, novelNames) },
                    insertIfAbsent = { quote -> insertIfAbsent(db, quote) },
                )
            val complete = archivedResult.pendingRows == 0 && roots != null && jsonResult.complete && unreadableFiles == 0
            if (complete) {
                db.execSQL(
                    "INSERT OR REPLACE INTO hayai_novel_import_runs(plan_id, status, completed_at) VALUES (?, 'complete', ?)",
                    arrayOf<Any?>(PLAN_ID, System.currentTimeMillis()),
                )
            } else {
                Timber.w(
                    "Legacy quote recovery remains pending: %d archive row(s), %d JSON file(s), storage available=%s",
                    archivedResult.pendingRows,
                    jsonResult.pendingFiles + unreadableFiles,
                    roots != null,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun restoreArchivedQuotes(db: SupportSQLiteDatabase): ArchivedQuoteRecoveryResult {
        var discoveredRows = 0
        var insertedRows = 0
        var pendingRows = 0
        db.query(
            "SELECT payload_json FROM hayai_legacy_rows WHERE table_name = 'series_quotes' ORDER BY legacy_key",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                discoveredRows++
                val quote = runCatching { LegacyQuoteJsonCodec.decodeArchivedRow(cursor.getString(0)) }.getOrNull()
                val mangaId = quote?.let { resolveMangaId(db, it.mangaId, listOf(it.novelName)) }
                if (quote == null || mangaId == null) {
                    pendingRows++
                    continue
                }
                runCatching { insertIfAbsent(db, quote.copy(mangaId = mangaId)) }
                    .onSuccess { inserted -> if (inserted) insertedRows++ }
                    .onFailure {
                        pendingRows++
                        Timber.e(it, "Could not recover archived quote %s", quote.id)
                    }
            }
        }
        return ArchivedQuoteRecoveryResult(discoveredRows, insertedRows, pendingRows)
    }

    private fun legacyStorageRoots(): List<UniFile>? {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return null
        val persistedUri =
            PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString(LEGACY_STORAGE_PREFERENCE, null)
                ?.takeIf(String::isNotBlank)
        val roots = mutableListOf<UniFile>()
        if (persistedUri != null) {
            val persistedRoot = UniFile.fromUri(context, persistedUri.toUri())?.takeIf { it.exists() && it.isDirectory } ?: return null
            roots += persistedRoot
        }
        val defaultRoot = UniFile.fromFile(File(Environment.getExternalStorageDirectory(), LEGACY_ROOT_DIRECTORY))
        if (defaultRoot?.exists() == true && defaultRoot.isDirectory) roots.add(defaultRoot)
        return roots.distinctBy { root -> root.uri.toString() }.takeIf(List<UniFile>::isNotEmpty)
    }

    private fun isComplete(db: SupportSQLiteDatabase): Boolean =
        db.query("SELECT 1 FROM hayai_novel_import_runs WHERE plan_id = ?", arrayOf(PLAN_ID)).use { cursor -> cursor.moveToFirst() }

    private fun invalidateSupersededMarkers(db: SupportSQLiteDatabase) {
        db.execSQL(
            "DELETE FROM hayai_novel_import_runs WHERE plan_id IN (?, ?, ?)",
            arrayOf<Any?>("legacy-quote-json-v1", "legacy-quote-json-v2", "legacy-quote-recovery-v3"),
        )
    }

    private fun resolveMangaId(
        db: SupportSQLiteDatabase,
        legacyMangaId: Long,
        novelNames: List<String>,
    ): Long? {
        val normalizedNames = novelNames.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
        if (normalizedNames.size != 1) return null
        val novelName = normalizedNames.single()
        db.query("SELECT title FROM mangas WHERE _id = ?", arrayOf(legacyMangaId)).use { cursor ->
            if (cursor.moveToFirst() && cursor.getString(0).equals(novelName, ignoreCase = true)) return legacyMangaId
        }
        return db.query("SELECT _id FROM mangas WHERE title = ? COLLATE NOCASE ORDER BY _id", arrayOf(novelName)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val match = cursor.getLong(0)
            if (cursor.moveToNext()) null else match
        }
    }

    private fun insertIfAbsent(
        db: SupportSQLiteDatabase,
        quote: LegacyQuoteJsonRow,
    ): Boolean {
        val values =
            ContentValues(9).apply {
                put("quote_id", quote.id)
                put("manga_id", quote.mangaId)
                put("novel_name", quote.novelName)
                put("chapter_name", quote.chapterName)
                put("displayed_content", quote.displayedContent)
                put("original_content", quote.originalContent)
                put("translated_content", quote.translatedContent)
                put("language", quote.language)
                put("timestamp", quote.timestamp)
            }
        val inserted = db.insert("hayai_quotes", SQLiteDatabase.CONFLICT_IGNORE, values)
        if (inserted >= 0) return true
        val alreadyPresent =
            db.query("SELECT 1 FROM hayai_quotes WHERE quote_id = ?", arrayOf(quote.id)).use { cursor -> cursor.moveToFirst() }
        check(alreadyPresent) { "Failed to restore legacy quote ${quote.id}" }
        return false
    }

    private companion object {
        const val PLAN_ID = "legacy-quote-recovery-v4"
        const val LEGACY_STORAGE_PREFERENCE = "__APP_STATE_storage_dir"
        const val LEGACY_ROOT_DIRECTORY = "Hayai"
        const val QUOTES_DIRECTORY = "quotes"
        val FILE_NAME = Regex("novel_[0-9]+\\.json")
    }
}

internal data class ArchivedQuoteRecoveryResult(
    val discoveredRows: Int,
    val insertedRows: Int,
    val pendingRows: Int,
)

internal data class LegacyQuoteJsonFile(
    val name: String,
    val json: String,
)

internal data class LegacyQuoteJsonImportResult(
    val complete: Boolean,
    val discoveredQuotes: Int,
    val insertedQuotes: Int,
    val pendingFiles: Int,
)

@Serializable
internal data class LegacyQuoteJsonDocument(
    val novelId: Long,
    val quotes: List<LegacyQuoteJsonRow> = emptyList(),
)

@Serializable
internal data class LegacyQuoteJsonRow(
    val id: String,
    val novelName: String,
    val chapterName: String,
    @SerialName("content") val displayedContent: String,
    val originalContent: String? = null,
    val translatedContent: String? = null,
    val language: String? = null,
    val timestamp: Long = 0L,
    @kotlinx.serialization.Transient val mangaId: Long = 0L,
)

@Serializable
private data class ArchivedLegacyQuoteRow(
    @SerialName("quote_id") val id: String,
    @SerialName("manga_id") val mangaId: Long,
    @SerialName("novel_name") val novelName: String,
    @SerialName("chapter_name") val chapterName: String,
    @SerialName("displayed_content") val displayedContent: String,
    @SerialName("original_content") val originalContent: String? = null,
    @SerialName("translated_content") val translatedContent: String? = null,
    val language: String? = null,
    val timestamp: Long = 0L,
) {
    fun toQuote() =
        LegacyQuoteJsonRow(
            id = id,
            mangaId = mangaId,
            novelName = novelName,
            chapterName = chapterName,
            displayedContent = displayedContent,
            originalContent = originalContent,
            translatedContent = translatedContent,
            language = language,
            timestamp = timestamp,
        )
}

internal object LegacyQuoteJsonCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(value: String): LegacyQuoteJsonDocument = json.decodeFromString(value)

    fun decodeArchivedRow(value: String): LegacyQuoteJsonRow = json.decodeFromString<ArchivedLegacyQuoteRow>(value).toQuote()
}

internal object LegacyQuoteJsonImporter {
    fun import(
        files: List<LegacyQuoteJsonFile>,
        resolveMangaId: (Long, List<String>) -> Long?,
        insertIfAbsent: (LegacyQuoteJsonRow) -> Boolean,
    ): LegacyQuoteJsonImportResult {
        var discoveredQuotes = 0
        var insertedQuotes = 0
        var pendingFiles = 0
        files.forEach { file ->
            val fileMangaId = file.name.removePrefix("novel_").removeSuffix(".json").toLongOrNull()
            val document = runCatching { LegacyQuoteJsonCodec.decode(file.json) }.getOrNull()
            if (fileMangaId == null || document == null || document.novelId != fileMangaId) {
                pendingFiles++
                return@forEach
            }
            if (document.quotes.isEmpty()) return@forEach
            val mangaId =
                resolveMangaId(document.novelId, document.quotes.map { quote -> quote.novelName })
            if (mangaId == null) {
                pendingFiles++
                return@forEach
            }
            discoveredQuotes += document.quotes.size
            val imported =
                runCatching {
                    document.quotes.count { quote -> insertIfAbsent(quote.copy(mangaId = mangaId)) }
                }.getOrElse {
                    pendingFiles++
                    return@forEach
                }
            insertedQuotes += imported
        }
        return LegacyQuoteJsonImportResult(
            complete = pendingFiles == 0,
            discoveredQuotes = discoveredQuotes,
            insertedQuotes = insertedQuotes,
            pendingFiles = pendingFiles,
        )
    }
}
