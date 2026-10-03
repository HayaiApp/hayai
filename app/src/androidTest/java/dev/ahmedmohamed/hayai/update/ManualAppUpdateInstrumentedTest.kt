package dev.ahmedmohamed.hayai.update

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.updater.AppUpdateNotifier
import eu.kanade.tachiyomi.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualAppUpdateInstrumentedTest {
    @Test
    fun completedDownloadPostsDismissibleInstallActionWithoutStartingAPackageSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val notifications = context.getSystemService(NotificationManager::class.java)
        val installer = context.packageManager.packageInstaller
        val sessionsBefore = installer.mySessions.map { it.sessionId }.toSet()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val notifier = AppUpdateNotifier(activity)
                notifier.onDownloadStarted()
                notifier.onDownloadFinished(Uri.parse("content://${context.packageName}.provider/manual-update-fixture.apk"))
            }
            instrumentation.waitForIdleSync()
            val notification = notifications.activeNotifications.single { it.id == Notifications.ID_INSTALL }.notification
            val install = notification.actions.single { it.title.toString() == context.getString(R.string.install) }
            assertTrue(install.actionIntent.isActivity)
            assertEquals(install.actionIntent, notification.contentIntent)
            assertFalse(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(sessionsBefore, installer.mySessions.map { it.sessionId }.toSet())
        } finally {
            notifications.cancel(Notifications.ID_INSTALL)
            notifications.cancel(Notifications.ID_UPDATER)
            scenario.close()
        }
    }
}
