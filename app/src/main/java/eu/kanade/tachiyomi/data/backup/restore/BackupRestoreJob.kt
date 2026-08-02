package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.backup.BackupConst
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.jobIsRunning
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.tryToSetForeground
import eu.kanade.tachiyomi.util.system.withIOContext
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import yokai.i18n.MR
import yokai.util.lang.getString

class BackupRestoreJob(val context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val notifier = BackupNotifier(context.localeContext)
    private val restorer = BackupRestorer(context, notifier)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_RESTORE_PROGRESS,
            notifier.showRestoreProgress(progress = -1).build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    override suspend fun doWork(): Result {
        val uriPath = inputData.getString(BackupConst.EXTRA_URI) ?: return Result.failure()
        val uri = Uri.parse(uriPath) ?: return Result.failure()

        val options = inputData.getBooleanArray(RESTORE_FLAGS_KEY)
            ?.let(RestoreOptions::fromBooleanArray)
            ?: RestoreOptions()

        tryToSetForeground()

        return withIOContext {
            try {
                restorer.restore(uri, options)
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    notifier.showRestoreError(context.getString(MR.strings.restoring_backup_canceled))
                    Result.success()
                } else {
                    Logger.e(e) { "Failed to restore backup" }
                    restorer.writeErrorLog()
                    notifier.showRestoreError(e.message)
                    Result.failure()
                }
            }
        }
    }

    companion object {
        private const val TAG = "BackupRestorer"
        private const val RESTORE_FLAGS_KEY = "restore_flags" // BooleanArray

        fun start(context: Context, uri: Uri, options: RestoreOptions = RestoreOptions()) {
            val inputData = workDataOf(
                BackupConst.EXTRA_URI to uri.toString(),
                RESTORE_FLAGS_KEY to options.asBooleanArray(),
            )
            val request = OneTimeWorkRequestBuilder<BackupRestoreJob>()
                .addTag(TAG)
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }

        fun isRunning(context: Context) = context.workManager.jobIsRunning(TAG)
    }
}
