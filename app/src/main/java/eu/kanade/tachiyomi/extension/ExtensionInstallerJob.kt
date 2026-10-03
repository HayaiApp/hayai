package eu.kanade.tachiyomi.extension

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.ahmedmohamed.hayai.extension.install.ExtensionInstallBatch
import dev.ahmedmohamed.hayai.extension.install.QueuedExtensionCancellations
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.system.jobIsRunning
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.tryToSetForeground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

class ExtensionInstallerJob(
    val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    private val notifier = ExtensionInstallNotifier(context.localeContext)

    private val preferences: PreferencesHelper = Injekt.get()

    private var activeInstalls = CopyOnWriteArrayList<String>()

    val extensionManager: ExtensionManager = Injekt.get()

    private var job: Job? = null
    private val batch = ExtensionInstallBatch()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.progressNotificationBuilder.build()
        val id = Notifications.ID_EXTENSION_PROGRESS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    override suspend fun doWork(): Result {
        tryToSetForeground()

        instance = WeakReference(this)

        context.notificationManager.cancel(Notifications.ID_UPDATES_TO_EXTS)
        val showUpdated = inputData.getInt(KEY_SHOW_UPDATED, -1)
        val showUpdatedNotification = showUpdated > -1
        val reRunUpdateCheck = showUpdated > 0

        if (!showUpdatedNotification && !preferences.hasPromptedBeforeUpdateAll().get()) {
            context.toast(R.string.some_extensions_may_prompt)
            preferences.hasPromptedBeforeUpdateAll().set(true)
        }

        val json = inputData.getString(KEY_EXTENSION) ?: return Result.failure()

        val infos =
            try {
                Json.decodeFromString<Array<ExtensionManager.ExtensionInfo>>(json)
            } catch (e: Exception) {
                Timber.e(e, "Cannot decode string")
                null
            } ?: return Result.failure()
        val list =
            infos.filter {
                if (QueuedExtensionCancellations.contains(it.pkgName)) return@filter false
                val installedExt =
                    extensionManager.installedExtensionsFlow.value.find { installed ->
                        installed.pkgName == it.pkgName
                    } ?: return@filter false
                installedExt.versionCode < it.versionCode || installedExt.libVersion < it.libVersion
            }

        activeInstalls = CopyOnWriteArrayList(list.map { it.pkgName })
        var installed = 0
        val installedExtensions = mutableListOf<ExtensionManager.ExtensionInfo>()
        val failedExtensions = mutableListOf<ExtensionManager.ExtensionInfo>()
        try {
            coroutineScope {
                job = launchIO {
                    val byPackage = list.associateBy { it.pkgName }
                    batch.run(byPackage.keys.toList()) { pkgName ->
                        val extension = byPackage.getValue(pkgName)
                        try {
                            if (QueuedExtensionCancellations.contains(pkgName) || batch.wasCancelled(pkgName)) return@run
                            extensionManager.installExtension(extension, this).collect { info ->
                                synchronized(installedExtensions) {
                                    if (info.first.isCompleted()) {
                                        activeInstalls.remove(extension.pkgName)
                                        if (info.first == InstallStep.Installed) {
                                            installedExtensions.add(extension)
                                            installed++
                                            val prefCount = preferences.extensionUpdatesCount().get()
                                            preferences.extensionUpdatesCount().set(max(prefCount - 1, 0))
                                        } else {
                                            failedExtensions.add(extension)
                                        }
                                    }
                                    notifier.showProgressNotification(installed, list.size)
                                }
                            }
                        } finally {
                            activeInstalls.remove(pkgName)
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                activeInstalls.toList().forEach { extensionManager.cancelInstallation(it) }
                activeInstalls.clear()
                if (isStopped || job?.isCancelled == true) {
                    extensionManager.emitToInstaller("Finished", (InstallStep.Error to null))
                    context.notificationManager.cancel(Notifications.ID_EXTENSION_PROGRESS)
                }
                if (instance?.get() == this@ExtensionInstallerJob) instance = null
            }
        }

        if (showUpdatedNotification && installedExtensions.size > 0) {
            notifier.showUpdatedNotification(installedExtensions, preferences.hideNotificationContent())
        }
        if (reRunUpdateCheck || failedExtensions.isNotEmpty()) {
            ExtensionUpdateJob.runJobAgain(context, NetworkType.CONNECTED, false)
        }

        activeInstalls.forEach { extensionManager.cleanUpInstallation(it) }
        activeInstalls.clear()
        val hasChain =
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(context).getWorkInfosByTag(TAG).get().any {
                    it.state == WorkInfo.State.BLOCKED
                }
            }
        if (!hasChain) {
            extensionManager.emitToInstaller("Finished", (InstallStep.Installed to null))
        }
        if (instance?.get() == this) {
            instance = null
        }
        if (!hasChain) {
            context.notificationManager.cancel(Notifications.ID_EXTENSION_PROGRESS)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "ExtensionInstaller"

        /**
         * Key that defines what should be updated.
         */
        const val KEY_EXTENSION = "extension"
        const val KEY_SHOW_UPDATED = "show_updated"

        private var instance: WeakReference<ExtensionInstallerJob>? = null

        fun start(
            context: Context,
            extensions: List<Extension.Available>,
            showUpdatedExtension: Int = -1,
        ) {
            startJob(context, extensions.map(ExtensionManager::ExtensionInfo), showUpdatedExtension)
        }

        fun startJob(
            context: Context,
            info: List<ExtensionManager.ExtensionInfo>,
            showUpdatedExtension: Int = -1,
        ) {
            if (info.isEmpty()) return
            QueuedExtensionCancellations.start(info.map { it.pkgName })
            // chunked to satisfy input limits
            val requests =
                info.chunked(32).map {
                    OneTimeWorkRequestBuilder<ExtensionInstallerJob>()
                        .addTag(TAG)
                        .setInputData(
                            workDataOf(
                                KEY_EXTENSION to Json.encodeToString(it.toTypedArray()),
                                KEY_SHOW_UPDATED to showUpdatedExtension,
                            ),
                        ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                }
            var workContinuation =
                WorkManager
                    .getInstance(context)
                    .beginUniqueWork(TAG, ExistingWorkPolicy.REPLACE, requests.first())
            for (i in 1 until requests.size) {
                workContinuation = workContinuation.then(requests[i])
            }
            workContinuation.enqueue()
        }

        fun activeInstalls(): List<String>? = instance?.get()?.activeInstalls

        fun removeActiveInstall(pkgName: String) = instance?.get()?.activeInstalls?.remove(pkgName)

        fun cancelInstallation(pkgName: String) {
            QueuedExtensionCancellations.cancel(pkgName)
            instance?.get()?.batch?.cancel(pkgName)
            removeActiveInstall(pkgName)
        }

        fun stop(context: Context) {
            instance?.get()?.job?.cancel()
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }

        fun isRunning(context: Context) = WorkManager.getInstance(context).jobIsRunning(TAG)
    }
}
