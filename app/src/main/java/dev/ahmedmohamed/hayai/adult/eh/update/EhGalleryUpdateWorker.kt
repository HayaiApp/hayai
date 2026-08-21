package dev.ahmedmohamed.hayai.adult.eh.update

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ahmedmohamed.hayai.adult.eh.network.EhHttpGateway
import dev.ahmedmohamed.hayai.adult.eh.persistence.HayaiEhPersistenceStore
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.tryToSetForeground
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class EhGalleryUpdateWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val notifier = EhGalleryUpdateNotifier(appContext)

    override suspend fun doWork(): Result {
        if (!HayaiPreferences(Injekt.get()).hentaiFeaturesEnabled.get()) return Result.success()
        val persistence = Injekt.get<HayaiEhPersistenceStore>()
        val stateStore = EhGalleryUpdateStateStore(applicationContext, persistence)
        if (stateStore.policy().wifiOnly && !applicationContext.isConnectedToWifi()) return Result.success()
        return try {
            try {
                tryToSetForeground()
            } catch (denied: SecurityException) {
                Timber.w(denied, "Notification permission denied for E-Hentai updater")
            }
            val runtime = EhGalleryUpdateRuntime(
                database = Injekt.get<DatabaseHelper>(),
                sourceManager = Injekt.get<SourceManager>(),
                gateway = Injekt.get<EhHttpGateway>(),
                persistence = persistence,
                stateStore = stateStore,
                downloadManager = Injekt.get<DownloadManager>(),
                renameJournal = EhDownloadRenameJournal(applicationContext),
                downloadTreeMerger = EhDownloadTreeMerger(DownloadProvider(applicationContext)),
            )
            val run = EhGalleryUpdateCoordinator(runtime).run { candidate, current, total ->
                notifier.progress(id, candidate, current, total)
            }
            stateStore.recordStats(run.stats)
            notifier.complete(run)
            if (run.shouldRetry && runAttemptCount < MAX_TRANSIENT_RETRIES) Result.retry() else Result.success()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            Timber.e(failure, "E-Hentai gallery updater failed")
            if (runAttemptCount < MAX_TRANSIENT_RETRIES) Result.retry() else Result.failure()
        } finally {
            notifier.cancelProgress()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(EhGalleryUpdateNotifier.PROGRESS_ID, notifier.foreground(id), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(EhGalleryUpdateNotifier.PROGRESS_ID, notifier.foreground(id))
        }

    companion object {
        const val PERIODIC_WORK_NAME = "hayai.eh.gallery-update.periodic"
        const val MANUAL_WORK_NAME = "hayai.eh.gallery-update.manual"
        const val WORK_TAG = "hayai.eh.gallery-update"
        private const val MAX_TRANSIENT_RETRIES = 3

        fun schedule(context: Context, policy: EhGalleryUpdatePolicy) {
            val persistence = Injekt.get<HayaiEhPersistenceStore>()
            EhGalleryUpdateStateStore(context, persistence).setPolicy(policy)
            val manager = WorkManager.getInstance(context)
            if (policy.intervalHours == 0) {
                manager.cancelUniqueWork(PERIODIC_WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<EhGalleryUpdateWorker>(policy.intervalHours.toLong(), TimeUnit.HOURS)
                .setConstraints(policy.constraints())
                .addTag(WORK_TAG)
                .build()
            manager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun runNow(context: Context, policy: EhGalleryUpdatePolicy? = null) {
            val resolved = policy ?: EhGalleryUpdateStateStore(context, Injekt.get()).policy()
            val request = OneTimeWorkRequestBuilder<EhGalleryUpdateWorker>()
                .setConstraints(resolved.constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC_WORK_NAME)
                cancelUniqueWork(MANUAL_WORK_NAME)
            }
        }

        private fun EhGalleryUpdatePolicy.constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(requiresCharging)
            .build()
    }
}
