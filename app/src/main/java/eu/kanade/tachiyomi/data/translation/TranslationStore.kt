package eu.kanade.tachiyomi.data.translation

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import tachiyomi.domain.translation.model.TranslationStatus
import tachiyomi.domain.translation.model.TranslationTask
import yokai.data.Database
import java.io.File

class TranslationStore(
    context: Context,
    private val database: Database,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val storeFile = File(context.filesDir, "translation_queue.json")
    private var legacyMigrated = false

    fun save(queue: List<TranslationTask>) {
        migrateLegacyIfNeeded()
        saveToDatabase(queue)
        storeFile.delete()
    }

    fun load(): List<TranslationTask> {
        migrateLegacyIfNeeded()
        return database.series_knowledgeQueries.loadTranslationQueue(::mapTask).executeAsList()
    }

    private fun migrateLegacyIfNeeded() {
        if (legacyMigrated) return
        legacyMigrated = true
        if (!storeFile.exists()) return

        val tasks = loadLegacy()
        if (tasks.isNotEmpty()) {
            saveToDatabase(tasks)
        }
        storeFile.delete()
    }

    private fun saveToDatabase(queue: List<TranslationTask>) {
        database.transaction {
            database.series_knowledgeQueries.clearTranslationQueue()
            queue.forEachIndexed { index, task ->
                database.series_knowledgeQueries.insertTranslationQueueTask(
                    task.id,
                    task.mangaId,
                    task.chapterId,
                    task.chapterName,
                    task.mangaTitle,
                    task.sourceLanguage,
                    task.targetLanguage,
                    task.engineId,
                    task.priority.toLong(),
                    task.status.name,
                    task.errorMessage,
                    task.retryCount.toLong(),
                    task.createdAt,
                    task.forceRetranslate,
                    index.toLong(),
                )
            }
        }
    }

    private fun loadLegacy(): List<TranslationTask> {
        return runCatching {
            json.decodeFromString(ListSerializer(StoredTask.serializer()), storeFile.readText()).map {
                it.toTask()
            }
        }.getOrDefault(emptyList())
    }

    private fun mapTask(
        taskId: Long,
        mangaId: Long,
        chapterId: Long,
        chapterName: String,
        mangaTitle: String,
        sourceLanguage: String,
        targetLanguage: String,
        engineId: Long,
        priority: Long,
        status: String,
        errorMessage: String?,
        retryCount: Long,
        createdAt: Long,
        forceRetranslate: Boolean,
        positionIndex: Long,
    ): TranslationTask {
        return TranslationTask(
            id = taskId,
            chapterId = chapterId,
            mangaId = mangaId,
            chapterName = chapterName,
            mangaTitle = mangaTitle,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            engineId = engineId,
            priority = priority.toInt(),
            status = runCatching { TranslationStatus.valueOf(status) }.getOrDefault(TranslationStatus.QUEUED),
            errorMessage = errorMessage,
            retryCount = retryCount.toInt(),
            createdAt = createdAt,
            forceRetranslate = forceRetranslate,
        )
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
    ) {
        fun toTask(): TranslationTask {
            return TranslationTask(
                id = id,
                chapterId = chapterId,
                mangaId = mangaId,
                chapterName = chapterName,
                mangaTitle = mangaTitle,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                engineId = engineId,
                priority = priority,
                status = runCatching { TranslationStatus.valueOf(status) }.getOrDefault(TranslationStatus.QUEUED),
                errorMessage = errorMessage,
                retryCount = retryCount,
                createdAt = createdAt,
                forceRetranslate = forceRetranslate,
            )
        }
    }
}
