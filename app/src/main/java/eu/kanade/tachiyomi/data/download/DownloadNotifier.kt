package eu.kanade.tachiyomi.data.download

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import yokai.util.koin.injectLazy
import yokai.i18n.MR
import yokai.util.lang.getString
import java.util.regex.*
import android.R as AR

/**
 * DownloadNotifier is used to show notifications when downloading one or multiple chapters.
 *
 * @param context context of application
 */
internal class DownloadNotifier(private val context: Context) {

    private val preferences: PreferencesHelper by injectLazy()

    /**
     * Notification builder.
     */
    private val notification by lazy {
        NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADER)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
    }

    /**
     * Status of download. Used for correct notification icon.
     */
    private var isDownloading = false

    /**
     * Updated when error is thrown
     */
    var errorThrown = false

    /**
     * Shows a notification from this builder.
     *
     * @param id the id of the notification.
     */
    private fun NotificationCompat.Builder.show(id: Int = Notifications.ID_DOWNLOAD_CHAPTER, refreshGroupSummary: Boolean = true) {
        if (!context.hasNotificationPermission()) return
        setGroup(GROUP_KEY_DOWNLOADS)
        context.notificationManager.notify(id, build())
        // Re-posting the group summary is only needed when a notification in the group actually
        // starts/stops existing, not on every progress tick: onProgressChange() calls show() once
        // per downloaded page, and re-notifying the summary that often causes the OS's silent
        // notification bucket to visibly reshuffle/flicker on every page.
        if (refreshGroupSummary) {
            showGroupSummary()
        }
    }

    /**
     * Explicitly declares a group summary for the downloader channel's notifications, with the
     * app's own icon. Without this, letting the OS auto-bundle 2+ notifications from this channel
     * (e.g. a download in progress alongside a connection warning) can end up showing a generic
     * fallback icon on the collapsed summary card instead of ours.
     */
    private fun showGroupSummary() {
        if (!context.hasNotificationPermission()) return
        val summary = NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADER)
            .setSmallIcon(R.drawable.ic_file_download_24dp)
            .setColor(ContextCompat.getColor(context, R.color.secondaryTachiyomi))
            .setGroup(GROUP_KEY_DOWNLOADS)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()
        context.notificationManager.notify(Notifications.ID_DOWNLOAD_GROUP_SUMMARY, summary)
    }

    /**
     * Dismiss the downloader's notification. Downloader error notifications use a different id, so
     * those can only be dismissed by the user.
     */
    fun dismiss() {
        context.notificationManager.cancel(Notifications.ID_DOWNLOAD_CHAPTER)
        context.notificationManager.cancel(Notifications.ID_DOWNLOAD_PAUSED)
        // The group summary posted by showGroupSummary() is never dismissed on its own - without
        // this it's left behind as a "ghost" notification with no children once the last
        // downloader notification is cancelled.
        context.notificationManager.cancel(Notifications.ID_DOWNLOAD_GROUP_SUMMARY)
    }

    /**
     * Dismiss only the paused notification.
     */
    fun dismissPaused() {
        context.notificationManager.cancel(Notifications.ID_DOWNLOAD_PAUSED)
    }

    fun setPlaceholder(download: Download?): NotificationCompat.Builder {
        val context = context.localeContext
        with(notification) {
            // Check if first call.
            if (!isDownloading) {
                // Android's own animated download icon (a bouncing arrow), same as Mihon/Yokai
                // use here specifically - unlike the branded icon used everywhere else, this one
                // only exists as the system drawable; a custom vector can't reproduce it.
                setSmallIcon(AR.drawable.stat_sys_download)
                setAutoCancel(false)
                clearActions()
                setOngoing(true)
                // Open download manager when clicked
                setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
                color = ContextCompat.getColor(context, R.color.secondaryTachiyomi)
                isDownloading = true
                // Pause action
                addAction(
                    R.drawable.ic_pause_24dp,
                    context.getString(MR.strings.pause),
                    NotificationReceiver.pauseDownloadsPendingBroadcast(context),
                )
            }

            if (download != null && !preferences.hideNotificationContent().get()) {
                val title = download.manga.title.chop(15)
                val quotedTitle = Pattern.quote(title)
                val name = download.chapter.preferredChapterName(context, download.manga, preferences)
                val chapter = name.replaceFirst(
                    "$quotedTitle[\\s]*[-]*[\\s]*"
                        .toRegex(RegexOption.IGNORE_CASE),
                    "",
                )
                setContentTitle("$title - $chapter".chop(30))
                setContentText(context.getString(MR.strings.downloading))
            } else {
                setContentTitle(context.getString(MR.strings.downloading))
                setContentText(null)
            }
            setProgress(0, 0, true)
            setStyle(null)
        }
        return notification
    }

    /**
     * Called when download progress changes.
     *
     * @param download download object containing download information.
     */
    fun onProgressChange(download: Download) {
        // Create notification
        with(notification) {
            // Check if first call.
            val isFirstCall = !isDownloading
            if (isFirstCall) {
                setSmallIcon(AR.drawable.stat_sys_download)
                setAutoCancel(false)
                clearActions()
                setOngoing(true)
                // Open download manager when clicked
                color = ContextCompat.getColor(context, R.color.secondaryTachiyomi)
                setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
                isDownloading = true
                // Pause action
                addAction(
                    R.drawable.ic_pause_24dp,
                    context.getString(MR.strings.pause),
                    NotificationReceiver.pauseDownloadsPendingBroadcast(context),
                )
            }

            val downloadingProgressText =
                context.localeContext.getString(MR.strings.downloading_progress)
                    .format(download.downloadedImages, download.pages!!.size)

            if (preferences.hideNotificationContent().get()) {
                setContentTitle(downloadingProgressText)
            } else {
                val title = download.manga.title.chop(15)
                val quotedTitle = Pattern.quote(title)
                val name = download.chapter.preferredChapterName(context, download.manga, preferences)
                val chapter = name.replaceFirst(
                    "$quotedTitle[\\s]*[-]*[\\s]*".toRegex(RegexOption.IGNORE_CASE),
                    "",
                )
                setContentTitle("$title - $chapter".chop(30))
                setContentText(downloadingProgressText)
            }
            setStyle(null)
            setProgress(download.pages!!.size, download.downloadedImages, false)

            // Displays the progress bar on notification
            show(refreshGroupSummary = isFirstCall)
        }
    }

    /**
     * Show notification when download is paused.
     */
    fun onDownloadPaused() {
        val context = context.localeContext
        with(notification) {
            setContentTitle(context.getString(MR.strings.paused))
            setContentText(context.getString(MR.strings.download_paused))
            setSmallIcon(R.drawable.ic_pause_24dp)
            setAutoCancel(false)
            setOngoing(false)
            setProgress(0, 0, false)
            color = ContextCompat.getColor(context, R.color.secondaryTachiyomi)
            clearActions()
            // Open download manager when clicked
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            // Resume action
            addAction(
                R.drawable.ic_play_arrow_24dp,
                context.getString(MR.strings.resume),
                NotificationReceiver.resumeDownloadsPendingBroadcast(context),
            )
            // Clear action
            addAction(
                R.drawable.ic_close_24dp,
                context.getString(MR.strings.cancel_all),
                NotificationReceiver.clearDownloadsPendingBroadcast(context),
            )
            show(Notifications.ID_DOWNLOAD_PAUSED)
        }

        // Reset initial values
        isDownloading = false
    }

    /**
     * Called when the downloader receives a warning.
     *
     * @param reason the text to show.
     */
    fun onWarning(reason: String) {
        val context = context.localeContext
        with(notification) {
            setContentTitle(context.getString(MR.strings.downloads))
            setContentText(reason)
            color = ContextCompat.getColor(context, R.color.secondaryTachiyomi)
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setOngoing(false)
            setAutoCancel(true)
            clearActions()
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            setProgress(0, 0, false)

            show(Notifications.ID_DOWNLOAD_CHAPTER_ERROR)
        }

        // Reset download information
        isDownloading = false
    }

    /**
     * Called when the downloader has too many downloads from one source.
     */
    fun massDownloadWarning() {
        val context = context.localeContext
        val notification = context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER) {
            setContentTitle(context.getString(MR.strings.warning))
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(MR.strings.download_queue_size_warning)),
            )
            setContentIntent(
                NotificationHandler.openUrl(
                    context,
                    LibraryUpdateNotifier.HELP_WARNING_URL,
                ),
            )
            setTimeoutAfter(30000)
            setGroup(GROUP_KEY_DOWNLOADS)
        }
            .build()

        if (!context.hasNotificationPermission()) return
        context.notificationManager.notify(
            Notifications.ID_DOWNLOAD_SIZE_WARNING,
            notification,
        )
        showGroupSummary()
    }

    private fun Context.hasNotificationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Called when the downloader receives an error. It's shown as a separate notification to avoid
     * being overwritten.
     *
     * @param error string containing error information.
     * @param chapter string containing chapter title.
     */
    fun onError(
        error: String? = null,
        chapter: String? = null,
        mangaTitle: String? = null,
        customIntent: Intent? = null,
    ) {
        // Create notification
        val context = context.localeContext
        with(notification) {
            setContentTitle(
                mangaTitle?.plus(": $chapter") ?: context.getString(MR.strings.download_error),
            )
            setContentText(error ?: context.getString(MR.strings.could_not_download_unexpected_error))
            setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    error ?: context.getString(MR.strings.could_not_download_unexpected_error),
                ),
            )
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setCategory(NotificationCompat.CATEGORY_ERROR)
            setOngoing(false)
            clearActions()
            setAutoCancel(true)
            if (customIntent != null) {
                setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        customIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            }
            color = ContextCompat.getColor(context, R.color.secondaryTachiyomi)
            setProgress(0, 0, false)

            show(Notifications.ID_DOWNLOAD_CHAPTER_ERROR)
        }

        // Reset download information
        errorThrown = true
        isDownloading = false
    }

    companion object {
        private const val GROUP_KEY_DOWNLOADS = "app.rokku.download"
    }
}
