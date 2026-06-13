package eu.kanade.tachiyomi.data.translation

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import yokai.data.Database
import java.io.File
import java.security.MessageDigest

class TranslationCache(
    context: Context,
    private val database: Database,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val legacyCacheDir = File(context.filesDir, "translations")
    private var legacyMigrated = false

    fun hasTranslation(mangaId: Long, chapterId: Long, targetLanguage: String): Boolean {
        migrateLegacyIfNeeded()
        return database.series_knowledgeQueries
            .findCachedTranslation(mangaId, chapterId, targetLanguage)
            .executeAsOneOrNull() != null
    }

    fun getTranslation(mangaId: Long, chapterId: Long, targetLanguage: String): CachedTranslation? {
        migrateLegacyIfNeeded()
        return database.series_knowledgeQueries.findCachedTranslation(
            mangaId,
            chapterId,
            targetLanguage,
            ::mapCachedTranslation,
        ).executeAsOneOrNull()
    }

    fun saveTranslation(
        mangaId: Long,
        chapterId: Long,
        sourceLanguage: String,
        targetLanguage: String,
        originalContent: String,
        translatedContent: String,
        engineId: String,
    ) {
        migrateLegacyIfNeeded()
        database.series_knowledgeQueries.upsertCachedTranslation(
            mangaId,
            chapterId,
            sourceLanguage,
            targetLanguage,
            originalContent.sha256(),
            translatedContent,
            engineId,
            System.currentTimeMillis(),
        )
    }

    fun deleteTranslation(mangaId: Long, chapterId: Long, targetLanguage: String) {
        migrateLegacyIfNeeded()
        database.series_knowledgeQueries.deleteCachedTranslation(mangaId, chapterId, targetLanguage)
    }

    fun clear() {
        database.series_knowledgeQueries.clearCachedTranslations()
        legacyCacheDir.deleteRecursively()
    }

    fun getCacheSize(): Long {
        migrateLegacyIfNeeded()
        return database.series_knowledgeQueries.cachedTranslationBytes().executeAsOne()
    }

    fun getCacheCount(): Int {
        migrateLegacyIfNeeded()
        return database.series_knowledgeQueries.countCachedTranslations().executeAsOne().toInt()
    }

    private fun migrateLegacyIfNeeded() {
        if (legacyMigrated) return
        legacyMigrated = true
        if (!legacyCacheDir.exists()) return

        val cached = legacyCacheDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { file ->
                runCatching { json.decodeFromString<CachedTranslation>(file.readText()) }.getOrNull()
            }
            .toList()

        if (cached.isNotEmpty()) {
            database.transaction {
                cached.forEach {
                    database.series_knowledgeQueries.upsertCachedTranslation(
                        it.mangaId,
                        it.chapterId,
                        it.sourceLanguage,
                        it.targetLanguage,
                        it.originalHash,
                        it.translatedContent,
                        it.engineId,
                        it.createdAt,
                    )
                }
            }
        }
        legacyCacheDir.deleteRecursively()
    }

    private fun mapCachedTranslation(
        mangaId: Long,
        chapterId: Long,
        sourceLanguage: String,
        targetLanguage: String,
        originalHash: String,
        translatedContent: String,
        engineId: String,
        createdAt: Long,
    ): CachedTranslation {
        return CachedTranslation(
            mangaId = mangaId,
            chapterId = chapterId,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            originalHash = originalHash,
            translatedContent = translatedContent,
            engineId = engineId,
            createdAt = createdAt,
        )
    }

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Serializable
    data class CachedTranslation(
        val chapterId: Long,
        val mangaId: Long,
        val sourceLanguage: String,
        val targetLanguage: String,
        val originalHash: String,
        val translatedContent: String,
        val engineId: String,
        val createdAt: Long,
    )
}
