package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.storage.saveTo
import eu.kanade.tachiyomi.util.system.connectivityManager
import eu.kanade.tachiyomi.util.system.jobIsRunning
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.tryToSetForeground
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import okhttp3.Call
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.lang.ref.WeakReference

class AppDownloadInstallJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    private val notifier = AppUpdateNotifier(context.localeContext)
    private val network: NetworkHelper by injectLazy()
    private var runningCall: Call? = null
    val preferences = Injekt.get<PreferencesHelper>()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.onDownloadStarted().build()
        val id = Notifications.ID_UPDATER
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    override suspend fun doWork(): Result {
        val idleRun = inputData.getBoolean(IDLE_RUN, false)
        val url: String
        if (idleRun) {
            if (preferences.appShouldAutoUpdate() == ONLY_ON_UNMETERED &&
                context.connectivityManager.isActiveNetworkMetered
            ) {
                return Result.retry()
            }

            val result =
                withIOContext {
                    AppUpdateChecker().checkForUpdate(context, true, doExtrasAfterNewUpdate = false)
                }
            if (result is AppUpdateResult.NewUpdate) {
                AppUpdateNotifier(context.localeContext).cancel()
                AppUpdateNotifier.releasePageUrl = result.release.releaseLink
                url = result.release.downloadLink
            } else {
                return Result.success()
            }
        } else {
            url = inputData.getString(EXTRA_DOWNLOAD_URL) ?: return Result.failure()
        }

        tryToSetForeground()
        instance = WeakReference(this)

        withIOContext {
            downloadApk(url)
        }

        runningCall?.cancel()
        instance = null
        return Result.success()
    }

    /**
     * Called to start downloading apk of new update
     *
     * @param url url location of file
     */
    private suspend fun downloadApk(
        url: String,
    ) = coroutineScope {
        val progressListener =
            object : ProgressListener {
                // Progress of the download
                var savedProgress = 0

                // Keep track of the last notification sent to avoid posting too many.
                var lastTick = 0L

                override fun update(
                    bytesRead: Long,
                    contentLength: Long,
                    done: Boolean,
                ) {
                    val progress = (100 * (bytesRead.toFloat() / contentLength)).toInt()
                    val currentTime = System.currentTimeMillis()
                    if (progress > savedProgress && currentTime - 200 > lastTick) {
                        savedProgress = progress
                        lastTick = currentTime
                        notifier.onProgressChange(progress)
                    }
                }
            }

        try {
            // Download the new update.
            val call = network.client.newCachelessCallWithProgress(GET(url), progressListener)
            runningCall = call
            val response = call.await()
            if (isStopped) {
                cancel()
                return@coroutineScope
            }

            // File where the apk will be saved.
            val apkFile = File(context.externalCacheDir, "update.apk")

            if (response.isSuccessful) {
                response.body.source().saveTo(apkFile)
            } else {
                response.close()
                throw Exception("Unsuccessful response")
            }
            notifier.onDownloadFinished(apkFile.getUriCompat(context))
        } catch (error: Exception) {
            Timber.e(error)
            if (error is CancellationException ||
                isStopped ||
                (error is StreamResetException && error.errorCode == ErrorCode.CANCEL)
            ) {
                notifier.cancel()
            } else {
                notifier.onDownloadError(url)
            }
        }
    }

    companion object {
        private const val TAG = "AppDownloadInstaller"
        const val PACKAGE_INSTALLED_ACTION =
            "${BuildConfig.APPLICATION_ID}.SESSION_SELF_API_PACKAGE_INSTALLED"
        internal const val EXTRA_FILE_URI = "${BuildConfig.APPLICATION_ID}.AppInstaller.FILE_URI"
        internal const val EXTRA_NOTIFY_ON_INSTALL = "ACTION_ON_INSTALL"
        internal const val EXTRA_DOWNLOAD_URL = "DOWNLOAD_URL"
        internal const val NOTIFY_ON_INSTALL_KEY = "notify_on_install_complete"
        private const val IDLE_RUN = "idle_run"

        const val ALWAYS = 0
        const val ONLY_ON_UNMETERED = 1
        const val NEVER = 2

        private var instance: WeakReference<AppDownloadInstallJob>? = null

        fun start(
            context: Context,
            url: String?,
            notifyOnInstall: Boolean,
            waitUntilIdle: Boolean = false,
        ) {
            val data = Data.Builder()
            data.putString(EXTRA_DOWNLOAD_URL, url)
            data.putBoolean(EXTRA_NOTIFY_ON_INSTALL, notifyOnInstall)
            val request =
                OneTimeWorkRequestBuilder<AppDownloadInstallJob>()
                    .addTag(TAG)
                    .apply {
                        if (waitUntilIdle) {
                            data.putBoolean(IDLE_RUN, true)
                            val shouldAutoUpdate = Injekt.get<PreferencesHelper>().appShouldAutoUpdate()
                            val constraints =
                                Constraints
                                    .Builder()
                                    .setRequiredNetworkType(
                                        if (shouldAutoUpdate == ALWAYS) {
                                            NetworkType.CONNECTED
                                        } else {
                                            NetworkType.UNMETERED
                                        },
                                    ).setRequiresDeviceIdle(true)
                                    .build()
                            setConstraints(constraints)
                        } else {
                            setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        }
                        setInputData(data.build())
                    }.build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            instance?.get()?.runningCall?.cancel()
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
        }

        fun isRunning(context: Context) = WorkManager.getInstance(context).jobIsRunning(TAG)
    }
}
