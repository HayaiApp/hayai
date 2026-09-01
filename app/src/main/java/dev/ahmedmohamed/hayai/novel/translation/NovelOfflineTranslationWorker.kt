package dev.ahmedmohamed.hayai.novel.translation

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ahmedmohamed.hayai.novel.download.NovelDownloadStore
import dev.ahmedmohamed.hayai.novel.reader.NovelReaderSession
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.SourceManager
import kotlinx.coroutines.CancellationException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class NovelOfflineTranslationWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val database = Injekt.get<DatabaseHelper>()
    private val network = Injekt.get<NetworkHelper>()
    private val store = SqliteNovelTranslationStore(database)
    private val settingsStore = NovelTranslationSettingsStore(appContext)

    override suspend fun doWork(): Result {
        try {
            setForeground(getForegroundInfo())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return Result.retry()
        }
        var completed = 0
        while (!isStopped) {
            val claim = store.claim(System.currentTimeMillis(), LEASE_MILLIS)
                ?: return if (store.hasUnfinishedJobs()) Result.retry() else Result.success()
            val settings = settingsStore.get()
            if (!claim.request.matches(settings)) {
                store.fail(
                    claim,
                    applicationContext.getString(R.string.hayai_novel_translation_settings_changed),
                    retryable = false,
                )
                continue
            }
            try {
                translate(claim, settings)
                completed++
                setProgress(androidx.work.workDataOf("completed" to completed))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val retryable = error !is IllegalArgumentException
                store.fail(claim, error.message ?: error.javaClass.simpleName, retryable)
                if (retryable) return Result.retry()
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification =
            NotificationCompat
                .Builder(applicationContext, Notifications.CHANNEL_COMMON)
                .setSmallIcon(R.drawable.ic_translate_24dp)
                .setContentTitle(applicationContext.getString(R.string.hayai_novel_translation_offline_progress))
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun translate(
        claim: ClaimedNovelTranslationJob,
        settings: NovelTranslationSettings,
    ) {
        val request = claim.request
        val chapter = requireNotNull(database.getChapter(request.locator.chapterId).executeAsBlocking())
        val manga = requireNotNull(database.getManga(requireNotNull(chapter.manga_id)).executeAsBlocking())
        require(chapter.url == request.locator.chapterUrl)
        require(manga.source == request.locator.sourceId && manga.url == request.locator.mangaUrl)
        val session =
            NovelReaderSession(
                applicationContext,
                database,
                Injekt.get<SourceManager>(),
                NovelDownloadStore(File(applicationContext.filesDir, "hayai/novel-downloads")),
                network,
            )
        val loaded = session.initialize(requireNotNull(manga.id), requireNotNull(chapter.id), recordHistory = false)
        val sourceText = NovelTranslationText.canonical(loaded.document)
        val sourceHash = NovelTranslationHash.sha256(sourceText)
        if (!request.force) {
            store.findCompleted(request.locator, request.targetLanguage, sourceHash)?.let { existing ->
                store.complete(claim, existing.copy(updatedAt = System.currentTimeMillis()))
                return
            }
        }
        val result = NovelTranslationService(network).translateText(sourceText, settings)
        check(result.complete) {
            applicationContext.getString(R.string.hayai_novel_translation_incomplete)
        }
        val now = System.currentTimeMillis()
        store.complete(
            claim,
            StoredNovelTranslation(
                locator = request.locator,
                sourceLanguage = settings.sourceLanguage,
                targetLanguage = settings.targetLanguage,
                sourceHash = sourceHash,
                translatedContent = result.text,
                engineId = settings.engine.name,
                detectedLanguage = result.detectedLanguage,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    companion object {
        private const val WORK_NAME = "hayai.novel.offline-translation"
        private const val NOTIFICATION_ID = -901
        private const val LEASE_MILLIS = 30L * 60 * 1000

        fun enqueueAll(
            context: Context,
            manga: Manga,
            chapters: List<Chapter>,
            settings: NovelTranslationSettings,
        ): Int {
            val checked = settings.validate()
            val selected = chapters.distinctBy(Chapter::id).filter { it.id != null }
            if (selected.isEmpty()) return 0
            val batchId = UUID.randomUUID().toString()
            val providerHash = checked.providerConfigHash()
            val requests =
                selected.mapIndexed { position, chapter ->
                    NovelTranslationJobRequest(
                        batchId = batchId,
                        locator =
                            NovelTranslationLocator(
                                chapterId = requireNotNull(chapter.id),
                                sourceId = manga.source,
                                mangaUrl = manga.url,
                                chapterUrl = chapter.url,
                            ),
                        sourceLanguage = checked.sourceLanguage,
                        targetLanguage = checked.targetLanguage,
                        engineId = checked.engine.name,
                        providerConfigHash = providerHash,
                        position = position,
                    )
                }
            val store = SqliteNovelTranslationStore(Injekt.get<DatabaseHelper>())
            val inserted = store.enqueue(requests)
            val work =
                OneTimeWorkRequestBuilder<NovelOfflineTranslationWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
                .result
                .get()
            return inserted
        }
    }
}

internal fun NovelTranslationSettings.providerConfigHash(): String =
    NovelTranslationHash.sha256("${engine.name}\u0000$sourceLanguage\u0000$targetLanguage\u0000$endpoint\u0000$model")

private fun NovelTranslationJobRequest.matches(settings: NovelTranslationSettings): Boolean =
    sourceLanguage == settings.sourceLanguage &&
        targetLanguage == settings.targetLanguage &&
        engineId == settings.engine.name &&
        providerConfigHash == settings.providerConfigHash()
