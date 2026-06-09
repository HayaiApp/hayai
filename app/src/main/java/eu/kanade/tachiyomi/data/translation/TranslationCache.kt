package eu.kanade.tachiyomi.data.translation

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

class TranslationCache(
    context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDir = File(context.filesDir, "translations").apply { mkdirs() }

    fun hasTranslation(mangaId: Long, chapterId: Long, targetLanguage: String): Boolean {
        return getCacheFile(mangaId, chapterId, targetLanguage).exists()
    }

    fun getTranslation(mangaId: Long, chapterId: Long, targetLanguage: String): CachedTranslation? {
        val file = getCacheFile(mangaId, chapterId, targetLanguage)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<CachedTranslation>(file.readText()) }.getOrNull()
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
        val cached = CachedTranslation(
            chapterId = chapterId,
            mangaId = mangaId,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            originalHash = originalContent.sha256(),
            translatedContent = translatedContent,
            engineId = engineId,
            createdAt = System.currentTimeMillis(),
        )
        getCacheFile(mangaId, chapterId, targetLanguage).writeText(json.encodeToString(cached))
    }

    fun deleteTranslation(mangaId: Long, chapterId: Long, targetLanguage: String) {
        getCacheFile(mangaId, chapterId, targetLanguage).delete()
    }

    fun clear() {
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun getCacheSize(): Long = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun getCacheCount(): Int = cacheDir.walkTopDown().filter { it.isFile && it.extension == "json" }.count()

    private fun getCacheFile(mangaId: Long, chapterId: Long, targetLanguage: String): File {
        val mangaDir = File(cacheDir, mangaId.toString()).apply { mkdirs() }
        val safeLang = targetLanguage.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(mangaDir, "${chapterId}_$safeLang.json")
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
