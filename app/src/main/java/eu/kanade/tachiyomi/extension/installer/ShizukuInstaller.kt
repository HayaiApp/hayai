package eu.kanade.tachiyomi.extension.installer

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.installer.shizuku.IShellInterface
import eu.kanade.tachiyomi.extension.installer.shizuku.ShellInterface
import eu.kanade.tachiyomi.util.system.isShizukuInstalled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import yokai.i18n.MR
import yokai.util.lang.getString

class ShizukuInstaller(
    context: Context,
    finishedQueue: (Installer) -> Unit,
) : Installer(context, finishedQueue) {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var shellInterface: IShellInterface? = null

    private val shizukuArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context, ShellInterface::class.java))
            .tag("shizuku_service")
            .processNameSuffix("shizuku_service")
            .debuggable(BuildConfig.DEBUG)
            .daemon(false)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellInterface = IShellInterface.Stub.asInterface(service)
            ready = true
            checkQueue()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellInterface = null
        }
    }

    private val installResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

            if (status != PackageInstaller.STATUS_SUCCESS) {
                Logger.e { "Failed to install extension $packageName: $message" }
            }
            continueQueue(status == PackageInstaller.STATUS_SUCCESS)
        }
    }

    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        Logger.d { "Shizuku was killed prematurely" }
        finishedQueue(this)
    }

    private val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    checkQueue()
                    Shizuku.bindUserService(shizukuArgs, connection)
                } else {
                    finishedQueue(this@ShizukuInstaller)
                }
                Shizuku.removeRequestPermissionResultListener(this)
            }
        }
    }

    override var ready = false

    init {
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        require(Shizuku.pingBinder() && context.isShizukuInstalled) {
            finishedQueue(this)
            context.getString(MR.strings.ext_installer_shizuku_stopped)
        }

        ContextCompat.registerReceiver(
            context,
            installResultReceiver,
            IntentFilter(ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Shizuku.bindUserService(shizukuArgs, connection)
        } else {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    override fun processEntry(entry: Entry) {
        super.processEntry(entry)
        ioScope.launch {
            try {
                context.contentResolver.openAssetFileDescriptor(entry.uri, "r").use {
                    shellInterface?.install(it) ?: throw IllegalStateException("Shizuku service not connected")
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to install extension ${entry.downloadId} ${entry.uri}" }
                continueQueue(false)
            }
        }
    }

    // Don't cancel if entry is already started installing
    override fun cancelEntry(entry: Entry): Boolean = getActiveEntry() != entry

    override fun onDestroy() {
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        if (Shizuku.pingBinder()) {
            try {
                Shizuku.unbindUserService(shizukuArgs, connection, true)
            } catch (e: Exception) {
                Logger.w(e) { "Failed to unbind shizuku service" }
            }
        }
        try {
            context.unregisterReceiver(installResultReceiver)
        } catch (e: Exception) {
            Logger.w(e) { "Failed to unregister shizuku install result receiver" }
        }
        ioScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val shizukuPkgName = "moe.shizuku.privileged.api"
        const val downloadLink = "https://shizuku.rikka.app/download"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 14045
        fun isShizukuRunning(): Boolean {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    }
}

const val ACTION_INSTALL_RESULT = "${BuildConfig.APPLICATION_ID}.ACTION_INSTALL_RESULT"
