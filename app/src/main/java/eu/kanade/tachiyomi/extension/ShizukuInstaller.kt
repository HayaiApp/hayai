package eu.kanade.tachiyomi.extension

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.shizuku.IShellInterface
import eu.kanade.tachiyomi.extension.shizuku.ShellInterface
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller.Companion.EXTRA_DOWNLOAD_ID
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import timber.log.Timber
import uy.kohesive.injekt.injectLazy
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

class ShizukuInstaller(
    private val context: Context,
    val finishedQueue: (ShizukuInstaller) -> Unit,
) {
    private val extensionManager: ExtensionManager by injectLazy()

    private var waitingInstall = AtomicReference<Entry>(null)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiverRegistered = false

    // Raw shell execution via Shizuku.newProcess was removed upstream (made private) in
    // shizuku-api 13.1.x, so installs now go through a bound UserService that talks to the
    // hidden PackageInstaller APIs directly, same approach as upstream Mihon.
    private var shellInterface: IShellInterface? = null

    private val shizukuArgs by lazy {
        Shizuku
            .UserServiceArgs(ComponentName(context, ShellInterface::class.java))
            .tag("shizuku_service")
            .processNameSuffix("shizuku_service")
            .debuggable(BuildConfig.DEBUG)
            .daemon(false)
            .version(2)
    }

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                shellInterface = IShellInterface.Stub.asInterface(service)
                ready = true
                checkQueue()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                shellInterface = null
            }
        }

    private val statusIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE,
        )

    private val installResultReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                continueQueue(status == PackageInstaller.STATUS_SUCCESS)
            }
        }

    private val cancelReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1).takeIf { it >= 0 } ?: return
                cancelQueue(downloadId)
            }
        }

    data class Entry(
        val downloadId: Long,
        val pkgName: String,
        val uri: Uri,
    )

    private val queue = Collections.synchronizedList(mutableListOf<Entry>())

    private val shizukuDeadListener =
        Shizuku.OnBinderDeadListener {
            Timber.d("Shizuku was killed prematurely")
            finishedQueue(this)
        }

    fun isInQueue(pkgName: String) = queue.any { it.pkgName == pkgName }

    private val shizukuPermissionListener =
        object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(
                requestCode: Int,
                grantResult: Int,
            ) {
                if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        Shizuku.bindUserService(shizukuArgs, serviceConnection)
                    } else {
                        finishedQueue(this@ShizukuInstaller)
                    }
                    Shizuku.removeRequestPermissionResultListener(this)
                }
            }
        }

    var ready = false

    init {
        Shizuku.addBinderDeadListener(shizukuDeadListener)

        ContextCompat.registerReceiver(
            context,
            installResultReceiver,
            IntentFilter(ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true

        require(Shizuku.pingBinder() && (context.isPackageInstalled(shizukuPkgName) || Sui.isSui())) {
            finishedQueue(this)
            context.getString(R.string.ext_installer_shizuku_stopped)
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Shizuku.bindUserService(shizukuArgs, serviceConnection)
        } else {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    fun processEntry(entry: Entry) {
        if (!extensionManager.isInstallActive(entry.downloadId)) {
            continueQueue(false)
            return
        }
        extensionManager.setInstalling(entry.downloadId, entry.uri.hashCode())
        ioScope.launch {
            try {
                if (!extensionManager.isInstallActive(entry.downloadId, entry.uri.hashCode())) {
                    continueQueue(false)
                    return@launch
                }
                val shell = shellInterface ?: throw IllegalStateException("Shizuku service is not bound")
                context.contentResolver.openAssetFileDescriptor(entry.uri, "r")!!.use {
                    shell.install(it, statusIntent.intentSender)
                }
                context.contentResolver.delete(entry.uri, null, null)
            } catch (e: Exception) {
                Timber.e(e, "Failed to install extension ${entry.downloadId} ${entry.uri}")
                continueQueue(false)
            }
        }
    }

    /**
     * Checks the queue. The provided service will be stopped if the queue is empty.
     * Will not be run when not ready.
     *
     * @see ready
     */
    fun checkQueue() {
        if (!ready) return
        var finished = false
        val nextEntry = synchronized(queue) {
            if (queue.isEmpty()) {
                finished = waitingInstall.get() == null
                null
            } else {
                queue.first().takeIf { waitingInstall.compareAndSet(null, it) }?.also { queue.removeAt(0) }
            }
        }
        // Manager takes the operation lock; never call it while holding the queue monitor.
        if (finished) finishedQueue(this)
        nextEntry?.let(::processEntry)
    }

    /**
     * Tells the queue to continue processing the next entry and updates the install step
     * of the completed entry ([waitingInstall]) to [ExtensionManager].
     * @see waitingInstall
     */
    fun continueQueue(succeeded: Boolean) {
        val completedEntry = waitingInstall.getAndSet(null)
        if (completedEntry != null) {
            extensionManager.setInstallationResult(completedEntry.downloadId, succeeded)
            checkQueue()
        }
    }

    /**
     * Add an item to install queue.
     *
     * @param downloadId Download ID as known by [ExtensionManager]
     * @param uri Uri of APK to install
     */
    fun addToQueue(
        downloadId: Long,
        pkgName: String,
        uri: Uri,
    ) {
        queue.add(Entry(downloadId, pkgName, uri))
        checkQueue()
    }

    /**
     * Cancels queue for the provided download ID if exists.
     *
     * @param downloadId Download ID as known by [ExtensionManager]
     */
    private fun cancelQueue(downloadId: Long) {
        cancelQueued(downloadId)
    }

    /** Committed sessions cannot be recalled; remove only this waiting package. */
    fun cancelQueued(downloadId: Long): Boolean {
        val removed = synchronized(queue) { queue.removeAll { it.downloadId == downloadId } }
        if (removed) checkQueue()
        return removed
    }

    // Don't cancel if entry is already started installing
    fun cancelEntry(entry: Entry): Boolean = getActiveEntry() != entry

    fun getActiveEntry(): Entry? = waitingInstall.get()

    fun onDestroy() {
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        if (Shizuku.pingBinder()) {
            try {
                Shizuku.unbindUserService(shizukuArgs, serviceConnection, true)
            } catch (e: Exception) {
                Timber.w(e, "Failed to unbind shizuku service")
            }
        }
        if (receiverRegistered) {
            receiverRegistered = false
            context.unregisterReceiver(installResultReceiver)
        }
        ioScope.cancel()
        LocalBroadcastManager.getInstance(context).unregisterReceiver(cancelReceiver)
        val abandoned = synchronized(queue) { queue.toList().also { queue.clear() } }
        abandoned.forEach { extensionManager.setInstallationResult(it.downloadId, false) }
        waitingInstall.set(null)
    }

    companion object {
        const val shizukuPkgName = "moe.shizuku.privileged.api"
        const val downloadLink = "https://shizuku.rikka.app/download"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 14045
        private const val ACTION_INSTALL_RESULT = "${BuildConfig.APPLICATION_ID}.ACTION_SHIZUKU_INSTALL_RESULT"

        fun isShizukuRunning(): Boolean = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }
}
