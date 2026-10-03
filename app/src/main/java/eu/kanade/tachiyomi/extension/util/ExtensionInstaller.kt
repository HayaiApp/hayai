package eu.kanade.tachiyomi.extension.util

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.ahmedmohamed.hayai.extension.install.ExtensionInstallOperations
import dev.ahmedmohamed.hayai.extension.install.ExtensionInstallEvent
import dev.ahmedmohamed.hayai.extension.install.ExtensionInstallWait
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.ExtensionInstallerJob
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.ShizukuInstaller
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.extension.ExtensionIntallInfo
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * The installer which installs, updates and uninstalls the extensions.
 *
 * @param context The application context.
 */
internal class ExtensionInstaller(
    private val context: Context,
) {
    /**
     * The system's download manager
     */
    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /**
     * The broadcast receiver which listens to download completion events.
     */
    private val downloadReceiver = DownloadCompletionReceiver()

    /**
     * The currently requested downloads, with the package name (unique id) as key, and the id
     * returned by the download manager.
     */
    val activeDownloads = ConcurrentHashMap<String, Long>()
    private val operations = ExtensionInstallOperations()
    private val completedDownloads = ConcurrentHashMap.newKeySet<Long>()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var installer: ShizukuInstaller? = null

    private val shizukuInstaller: ShizukuInstaller?
        get() =
            installer ?: run {
                try {
                    installer =
                        ShizukuInstaller(context) {
                            it.onDestroy()
                            ioScope.launch {
                                delay(500.milliseconds)
                                _downloadsSharedFlow.emit(ExtensionInstallEvent("Finished", InstallStep.Installed to null, null))
                            }
                            installer = null
                        }
                } catch (e: Exception) {
                    ioScope.launchUI {
                        context.toast(e.message)
                    }
                }
                installer
            }

    /**
     * StateFlow used to notify the installation step of every download.
     */
    private val _downloadsSharedFlow = MutableSharedFlow<ExtensionInstallEvent>()
    val downloadSharedFlow = _downloadsSharedFlow
        .filter { it.isVisible(operations.current(it.packageName), operations.latest(it.packageName)) }
        .map { it.packageName to it.info }

    /** Map of download id to installer session id */
    val downloadInstallerMap = ConcurrentHashMap<String, Int>()

    fun emitToFlow(
        name: String,
        extensionInfo: ExtensionIntallInfo,
    ): Job {
        val owner = operations.current(name)
        return ioScope.launch {
            if (owner != null || name.startsWith("Finished") || name.startsWith("Uninstalled")) {
                _downloadsSharedFlow.emit(ExtensionInstallEvent(name, extensionInfo, owner))
            }
        }
    }

    /**
     * Adds the given extension to the downloads queue and returns a flow containing its
     * step in the installation process.
     *
     * @param url The url of the apk.
     * @param extension The extension to install.
     */
    suspend fun downloadAndInstall(
        url: String,
        extension: ExtensionManager.ExtensionInfo,
        scope: CoroutineScope,
    ): Flow<ExtensionIntallInfo> {
        val pkgName = extension.pkgName

        // Bail rather than let the download manager park the download until there's a connection,
        // it would otherwise sit on "pending" indefinitely with no way out of it
        if (!context.isOnline()) {
            return flowOf(ExtensionIntallInfo(InstallStep.Error, null))
        }

        return channelFlow {
            val owner = currentCoroutineContext()[Job]!!
            scope.coroutineContext.ensureActive()
            operations.replace(pkgName, owner) {
                cleanUpInstallation(pkgName)
                deleteDownload(pkgName)
            }
            // Subscribe before enqueue: private installs and failed downloads can finish immediately.
            launch(start = CoroutineStart.UNDISPATCHED) {
                _downloadsSharedFlow.filter { it.belongsTo(pkgName, owner) }.collect { send(it.info) }
            }
            try {
                downloadReceiver.register()
                val downloadUri = url.toUri()
                val request = DownloadManager.Request(downloadUri)
                    .setTitle(extension.name)
                    .setMimeType(APK_MIME)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, downloadUri.lastPathSegment)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                val id = downloadManager.enqueue(request)
                activeDownloads[pkgName] = id
                pollInstallation(pkgName, id, owner)
                awaitCancellation()
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                Timber.e(error)
                if (operations.isCurrent(pkgName, owner)) {
                    _downloadsSharedFlow.emit(ExtensionInstallEvent(pkgName, InstallStep.Error to null, owner))
                    send(InstallStep.Error to null)
                }
            } finally {
                operations.finish(pkgName, owner) {
                    cleanUpInstallation(pkgName)
                    deleteDownload(pkgName)
                }
            }
        }.flowOn(Dispatchers.IO).distinctUntilChanged().transformWhile {
            emit(it)
            !it.first.isCompleted()
        }
    }

    @SuppressLint("Range")
    private suspend fun pollInstallation(pkgName: String, id: Long, owner: Job) {
        val wait = ExtensionInstallWait(SystemClock.elapsedRealtime())
        val query = DownloadManager.Query().setFilterById(id)
        var missingDownloadAt: Long? = null
        while (operations.isCurrent(pkgName, owner)) {
            currentCoroutineContext().ensureActive()
            val now = SystemClock.elapsedRealtime()
            if (wait.expired(now)) {
                _downloadsSharedFlow.emit(ExtensionInstallEvent(pkgName, InstallStep.Error to null, owner))
                return
            }
            val sessionId = downloadInstallerMap[pkgName]
            val shizukuActive = installer?.getActiveEntry()?.pkgName == pkgName
            if (sessionId != null) {
                val session = context.packageManager.packageInstaller.getSessionInfo(sessionId)
                if (wait.sessionDisappeared(now, session != null || shizukuActive)) {
                    // A lost result broadcast must not leave an update on Installing forever.
                    _downloadsSharedFlow.emit(ExtensionInstallEvent(pkgName, InstallStep.Error to null, owner))
                    return
                }
                _downloadsSharedFlow.emit(ExtensionInstallEvent(pkgName, InstallStep.Installing to session, owner))
            } else if (id !in completedDownloads) {
                val status = downloadManager.query(query)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)) else null
                }
                when (status) {
                    DownloadManager.STATUS_PENDING -> _downloadsSharedFlow.emit(ExtensionInstallEvent(pkgName, InstallStep.Pending to null, owner))
                    DownloadManager.STATUS_RUNNING -> _downloadsSharedFlow.emit(ExtensionInstallEvent(pkgName, InstallStep.Downloading to null, owner))
                    DownloadManager.STATUS_SUCCESSFUL -> downloadReceiver.complete(id)
                    DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_FAILED -> {
                        _downloadsSharedFlow.emit(ExtensionInstallEvent(pkgName, InstallStep.Error to null, owner))
                        return
                    }
                    null -> {
                        val since = missingDownloadAt ?: now.also { missingDownloadAt = it }
                        if (now - since >= ExtensionInstallWait.RESULT_GRACE_MILLIS) {
                            _downloadsSharedFlow.emit(ExtensionInstallEvent(pkgName, InstallStep.Error to null, owner))
                            return
                        }
                    }
                }
                if (status != null) missingDownloadAt = null
            }
            delay(500.milliseconds)
        }
    }

    /**
     * Starts an intent to install the extension at the given uri.
     *
     * @param uri The uri of the extension to install.
     */
    fun installApk(
        downloadId: Long,
        uri: Uri,
    ) {
        val pkgName = activeDownloads.entries.find { it.value == downloadId }?.key
        val useActivity =
            (pkgName?.let { !ExtensionLoader.isExtensionInstalledByApp(context, pkgName) } ?: true) ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        val prefs: PreferencesHelper = Injekt.get()
        when (prefs.extensionInstaller().get()) {
            SHIZUKU -> {
                pkgName ?: return
                emitToFlow(pkgName, InstallStep.Loading to null)
                shizukuInstaller?.addToQueue(downloadId, pkgName, uri)
                    ?: setInstallationResult(pkgName, false, downloadId)
            }
            PRIVATE -> installPrivately(downloadId, pkgName, uri)
            else -> {
                val extensionManager = Injekt.get<ExtensionManager>()
                if (extensionManager.installedExtensionsFlow.value
                        .find { it.pkgName == pkgName }
                        ?.isShared == false
                ) {
                    installPrivately(downloadId, pkgName, uri)
                    return
                }
                val intent =
                    if (useActivity) {
                        Intent(context, ExtensionInstallActivity::class.java)
                    } else {
                        Intent(context, ExtensionInstallBroadcast::class.java)
                    }.setDataAndType(uri, APK_MIME)
                        .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (useActivity) {
                    context.startActivity(intent)
                } else {
                    context.sendBroadcast(intent)
                }
            }
        }
    }

    private fun installPrivately(
        downloadId: Long,
        pkgName: String?,
        uri: Uri,
    ) {
        pkgName ?: return
        val owner = operations.current(pkgName) ?: return
        CoroutineScope(owner + Dispatchers.IO).launch {
            val tempFile = File(context.cacheDir, "temp_$downloadId")
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    checkNotNull(input)
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                _downloadsSharedFlow.emit(ExtensionInstallEvent(pkgName, InstallStep.Installing to null, owner))
                currentCoroutineContext().ensureActive()
                val result = ExtensionLoader.installPrivateExtensionFile(context, tempFile)
                if (operations.isCurrent(pkgName, owner)) setInstallationResult(pkgName, result, downloadId)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                Timber.e(error, "Failed to read downloaded extension file.")
                if (operations.isCurrent(pkgName, owner)) setInstallationResult(pkgName, false, downloadId)
            } finally {
                tempFile.delete()
            }
        }
    }

    /**
     * Starts an intent to uninstall the extension by the given package name.
     *
     * @param pkgName The package name of the extension to uninstall
     */
    fun uninstallApk(pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            @Suppress("DEPRECATION")
            val intent =
                Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    /**
     * Sets the result of the installation of an extension.
     *
     * @param pkgName the name of the package being installed
     * @param sessionId The id of the package manager's session or other installer.
     */
    fun setInstalling(
        pkgName: String,
        sessionId: Int,
        expectedDownloadId: Long,
    ) {
        var registered = false
        operations.withCurrent(pkgName) {
            if (activeDownloads[pkgName] == expectedDownloadId) {
                downloadInstallerMap[pkgName] = sessionId
                emitToFlow(pkgName, ExtensionIntallInfo(InstallStep.Installing, null))
                registered = true
            }
        }
        if (!registered) abandonSession(sessionId)
    }

    fun isInstallActive(downloadId: Long, sessionId: Int? = null): Boolean {
        val pkgName = activeDownloads.entries.find { it.value == downloadId }?.key ?: return false
        var active = false
        operations.withCurrent(pkgName) {
            active = activeDownloads[pkgName] == downloadId && (sessionId == null || downloadInstallerMap[pkgName] == sessionId)
        }
        return active
    }

    fun abandonSession(sessionId: Int) {
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
    }

    suspend fun setPending(pkgName: String) {
        val owner = operations.current(pkgName) ?: return
        _downloadsSharedFlow.emit(ExtensionInstallEvent(pkgName, InstallStep.Pending to null, owner))
    }

    fun cancelInstallation(sessionId: Int) {
        val pkgName = downloadInstallerMap.entries.find { it.value == sessionId }?.key ?: return
        operations.withCurrent(pkgName) {
            if (downloadInstallerMap[pkgName] == sessionId) cancelInstallation(pkgName)
        }
    }

    fun cancelInstallation(pkgName: String) {
        var owner: Job? = null
        val cancelled = operations.cancel(pkgName) {
            owner = operations.current(pkgName)
            cleanUpInstallation(pkgName)
            deleteDownload(pkgName)
        }
        if (!cancelled) return
        ioScope.launch {
            _downloadsSharedFlow.emit(ExtensionInstallEvent(pkgName, InstallStep.Error to null, owner))
        }
    }

    fun cleanUpInstallation(pkgName: String) {
        activeDownloads[pkgName]?.let { installer?.cancelQueued(it) }
        val sessionId = downloadInstallerMap.remove(pkgName) ?: return
        abandonSession(sessionId)
    }

    /**
     * Sets the result of the installation of an extension.
     *
     * @param downloadId The id of the download.
     * @param result Whether the extension was installed or not.
     */
    fun setInstallationResult(
        pkgName: String,
        result: Boolean,
        expectedDownloadId: Long,
    ) {
        val step = if (result) InstallStep.Installed else InstallStep.Error
        operations.withCurrent(pkgName) { owner ->
            if (activeDownloads[pkgName] == expectedDownloadId) {
                downloadInstallerMap.remove(pkgName)
                ioScope.launch { _downloadsSharedFlow.emit(ExtensionInstallEvent(pkgName, step to null, owner)) }
            }
        }
    }

    /**
     * Deletes the download for the given package name.
     *
     * @param pkgName The package name of the download to delete.
     */
    private fun deleteDownload(pkgName: String) {
        val downloadId = activeDownloads.remove(pkgName)
        if (downloadId != null) {
            downloadManager.remove(downloadId)
            completedDownloads.remove(downloadId)
            ExtensionInstallerJob.removeActiveInstall(pkgName)
        }
        if (activeDownloads.isEmpty()) {
            downloadReceiver.unregister()
        }
    }

    /**
     * Receiver that listens to download status events.
     */
    private inner class DownloadCompletionReceiver : BroadcastReceiver() {
        /**
         * Whether this receiver is currently registered.
         */
        private var isRegistered = false

        /**
         * Registers this receiver if it's not already.
         */
        @Synchronized
        fun register() {
            if (isRegistered) return
            isRegistered = true

            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_EXPORTED)
        }

        /**
         * Unregisters this receiver if it's not already.
         */
        @Synchronized
        fun unregister() {
            if (!isRegistered) return
            isRegistered = false

            context.unregisterReceiver(this)
        }

        /**
         * Called when a download event is received. It looks for the download in the current active
         * downloads and notifies its installation step.
         */
        @SuppressLint("Range")
        override fun onReceive(
            context: Context,
            intent: Intent?,
        ) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0) ?: return
            complete(id)
        }

        fun complete(id: Long) {
            val pkgName = activeDownloads.entries.find { id == it.value }?.key ?: return
            val owner = operations.current(pkgName) ?: return
            if (!completedDownloads.add(id)) return
            try {
                val uri = downloadManager.getUriForDownloadedFile(id)
                if (uri == null) {
                    Timber.e("Couldn't locate downloaded APK")
                    setInstallationResult(pkgName, false, id)
                    return
                }
                if (!operations.isCurrent(pkgName, owner) || activeDownloads[pkgName] != id) return
                emitToFlow(pkgName, ExtensionIntallInfo(InstallStep.Loading, null))
                installApk(id, uri)
            } catch (error: Exception) {
                Timber.e(error)
                if (operations.isCurrent(pkgName, owner)) setInstallationResult(pkgName, false, id)
            }
        }
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_DOWNLOAD_ID = "ExtensionInstaller.extra.DOWNLOAD_ID"
        const val FILE_SCHEME = "file://"

        const val PACKAGE_INSTALLER = 0
        const val SHIZUKU = 1
        const val PRIVATE = 2
    }
}
