package eu.kanade.tachiyomi.data.translation

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import tachiyomi.domain.translation.model.TranslationStatus
import tachiyomi.domain.translation.model.TranslationTask
import java.io.File

class TranslationStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val storeFile = File(context.filesDir, "translation_queue.json")

    fun save(queue: List<TranslationTask>) {
        val serializable = queue.map {
            StoredTask(
                id = it.id,
                chapterId = it.chapterId,
                mangaId = it.mangaId,
                chapterName = it.chapterName,
                mangaTitle = it.mangaTitle,
                sourceLanguage = it.sourceLanguage,
                targetLanguage = it.targetLanguage,
                engineId = it.engineId,
                priority = it.priority,
                status = it.status.name,
                errorMessage = it.errorMessage,
                retryCount = it.retryCount,
                createdAt = it.createdAt,
                forceRetranslate = it.forceRetranslate,
            )
        }
        storeFile.writeText(json.encodeToString(ListSerializer(StoredTask.serializer()), serializable))
    }

    fun load(): List<TranslationTask> {
        if (!storeFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StoredTask.serializer()), storeFile.readText()).map {
                TranslationTask(
                    id = it.id,
                    chapterId = it.chapterId,
                    mangaId = it.mangaId,
                    chapterName = it.chapterName,
                    mangaTitle = it.mangaTitle,
                    sourceLanguage = it.sourceLanguage,
                    targetLanguage = it.targetLanguage,
                    engineId = it.engineId,
                    priority = it.priority,
                    status = runCatching { TranslationStatus.valueOf(it.status) }.getOrDefault(TranslationStatus.QUEUED),
                    errorMessage = it.errorMessage,
                    retryCount = it.retryCount,
                    createdAt = it.createdAt,
                    forceRetranslate = it.forceRetranslate,
                )
            }
        }.getOrDefault(emptyList())
    }

    @Serializable
    private data class StoredTask(
        val id: Long,
        val chapterId: Long,
        val mangaId: Long,
        val chapterName: String,
        val mangaTitle: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val engineId: Long,
        val priority: Int,
        val status: String,
        val errorMessage: String?,
        val retryCount: Int,
        val createdAt: Long,
        val forceRetranslate: Boolean,
    )
}
