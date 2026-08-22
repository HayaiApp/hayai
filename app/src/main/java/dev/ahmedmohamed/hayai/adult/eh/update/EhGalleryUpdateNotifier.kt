package dev.ahmedmohamed.hayai.adult.eh.update

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications

class EhGalleryUpdateNotifier(
    private val context: Context,
) {
    fun foreground(workId: java.util.UUID): Notification = base()
        .setContentTitle(context.getString(R.string.hayai_eh_updating_galleries))
        .setContentText(context.getString(R.string.hayai_eh_preparing_updates))
        .setOngoing(true)
        .setProgress(0, 0, true)
        .addAction(0, context.getString(R.string.cancel), WorkManager.getInstance(context).createCancelPendingIntent(workId))
        .build()

    fun progress(workId: java.util.UUID, candidate: EhGalleryUpdateCandidate, current: Int, total: Int) {
        notify(
            PROGRESS_ID,
            base()
                .setContentTitle(context.getString(R.string.hayai_eh_updating_galleries))
                .setContentText(candidate.title.take(80))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(total.coerceAtLeast(1), current.coerceAtMost(total), false)
                .addAction(0, context.getString(R.string.cancel), WorkManager.getInstance(context).createCancelPendingIntent(workId))
                .build(),
        )
    }

    fun complete(run: EhGalleryUpdateRun) {
        cancelProgress()
        if (run.stats.newRevisions == 0 && run.stats.transientFailures == 0 && run.stats.permanentFailures == 0) return
        val summary = buildList {
            if (run.stats.newRevisions > 0) {
                add(context.resources.getQuantityString(R.plurals.hayai_eh_new_revisions, run.stats.newRevisions, run.stats.newRevisions))
            }
            if (run.stats.notFound > 0) {
                add(context.resources.getQuantityString(R.plurals.hayai_eh_unavailable_galleries, run.stats.notFound, run.stats.notFound))
            }
            val failures = run.stats.transientFailures + run.stats.permanentFailures
            if (failures > 0) add(context.resources.getQuantityString(R.plurals.hayai_eh_failed_galleries, failures, failures))
        }.joinToString(" · ")
        notify(
            COMPLETE_ID,
            base()
                .setContentTitle(context.getString(R.string.hayai_eh_update_complete))
                .setContentText(summary)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancelProgress() {
        NotificationManagerCompat.from(context).cancel(PROGRESS_ID)
    }

    private fun base(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, Notifications.CHANNEL_LIBRARY_PROGRESS)
            .setSmallIcon(R.drawable.ic_refresh_24dp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    @Suppress("MissingPermission")
    private fun notify(id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    companion object {
        const val PROGRESS_ID = 690_201
        const val COMPLETE_ID = 690_202
    }
}
