package eu.kanade.tachiyomi.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException

class CrashLogUtil(
    private val context: Context,
) {
    private val notificationBuilder =
        context.notificationBuilder(Notifications.CHANNEL_CRASH_LOGS) {
            setSmallIcon(R.drawable.ic_hayai_monochrome_launcher)
        }

    fun dumpLogs(stackTrace: String? = null) {
        try {
            val file = writeCrashLogFile(stackTrace)
            showNotification(file.getUriCompat(context))
        } catch (e: IOException) {
            context.toast("Failed to get logs")
        }
    }

    /** Writes the crash log file and immediately opens the share sheet for it. */
    fun shareLogs(stackTrace: String? = null) {
        try {
            val file = writeCrashLogFile(stackTrace)
            val uri = file.getUriCompat(context)
            val shareIntent =
                Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(null, uri)
                    type = "text/plain"
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share)))
        } catch (e: IOException) {
            context.toast("Failed to get logs")
        }
    }

    private fun writeCrashLogFile(stackTrace: String?): File {
        val file = context.createFileInCacheDir("tachiyomi_crash_logs.txt")
        file.appendText(getDebugInfo() + "\n\n")
        file.appendText(getExtensionsInfo() + "\n\n")
        stackTrace?.let { file.appendText("$it\n\n") }
        // Waited on so the file is complete before it's shared/notified - exec alone returns
        // while logcat is still appending to it
        Runtime.getRuntime().exec("logcat *:E -d -f ${file.absolutePath}").waitFor()
        return file
    }

    fun getDebugInfo(): String =
        """
        App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}, ${BuildConfig.COMMIT_SHA}, ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TIME})
        Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
        Android build ID: ${Build.DISPLAY}
        Device brand: ${Build.BRAND}
        Device manufacturer: ${Build.MANUFACTURER}
        Device name: ${Build.DEVICE}
        Device model: ${Build.MODEL}
        Device product name: ${Build.PRODUCT}
        """.trimIndent()

    private fun getExtensionsInfo(): String {
        val extensionManager: ExtensionManager = Injekt.get()
        val installedExtensions = extensionManager.installedExtensionsFlow.value
        val availableExtensions = extensionManager.availableExtensionsFlow.value

        val extensionInfoList = mutableListOf<String>()

        for (installedExtension in installedExtensions) {
            val availableExtension = availableExtensions.find { it.pkgName == installedExtension.pkgName }

            val hasUpdate = (availableExtension?.versionCode ?: 0) > installedExtension.versionCode
            if (hasUpdate || installedExtension.isObsolete) {
                val extensionInfo =
                    "Extension Name: ${installedExtension.name}\n" +
                        "Installed Version: ${installedExtension.versionName}\n" +
                        "Available Version: ${availableExtension?.versionName ?: "N/A"}\n" +
                        "Obsolete: ${installedExtension.isObsolete}\n"
                extensionInfoList.add(extensionInfo)
            }
        }
        if (extensionInfoList.isNotEmpty()) {
            extensionInfoList.add(0, "Extensions that are outdated, obsolete, or unofficial")
        }
        return extensionInfoList.joinToString("\n")
    }

    private fun showNotification(uri: Uri) {
        context.notificationManager.cancel(Notifications.ID_CRASH_LOGS)
        with(notificationBuilder) {
            setContentTitle(context.getString(R.string.crash_log_saved))

            // Clear old actions if they exist
            clearActions()

            addAction(
                R.drawable.ic_bug_report_24dp,
                context.getString(R.string.open_log),
                NotificationReceiver.openErrorOrSkippedLogPendingActivity(context, uri),
            )

            addAction(
                R.drawable.ic_share_24dp,
                context.getString(R.string.share),
                NotificationReceiver.shareCrashLogPendingBroadcast(context, uri, Notifications.ID_CRASH_LOGS),
            )

            context.notificationManager.notify(Notifications.ID_CRASH_LOGS, build())
        }
    }
}
