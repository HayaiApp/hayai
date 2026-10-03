package dev.ahmedmohamed.hayai.extension.install

import android.content.pm.PackageInstaller
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtensionInstallerOwnerTest {
    @Test
    fun cancelAfterCompletedInstallDoesNotPublishAnErrorForItsRow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = ExtensionInstaller(context)
        val operations = ExtensionInstaller::class.java.getDeclaredField("operations").let { field ->
            field.isAccessible = true
            field.get(installer) as ExtensionInstallOperations
        }
        val pkg = "dev.hayai.fixture.completed"
        val owner = Job()
        operations.replace(pkg, owner)
        operations.finish(pkg, owner)
        owner.complete()
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(500) { installer.downloadSharedFlow.first { it.first == pkg } }
        }
        installer.cancelInstallation(pkg)
        assertNull(event.await())
        assertFalse(owner.isCancelled)
    }

    @Test
    fun stalePlatformSessionIsAbandonedInsteadOfRegisteringAfterCancellation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val platform = context.packageManager.packageInstaller
        val installer = ExtensionInstaller(context)
        val session = platform.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        try {
            assertNotNull(platform.getSessionInfo(session))
            assertFalse(installer.isInstallActive(Long.MAX_VALUE))
            installer.setInstalling("dev.hayai.fixture.cancelled", session, Long.MAX_VALUE)
            awaitAbandonedSession(platform, session)
            assertFalse(installer.isInstallActive(Long.MAX_VALUE, session))
        } finally {
            runCatching { platform.abandonSession(session) }
        }
    }

    @Test
    fun resolvedOldDownloadCannotOverwriteOrFinishTheReplacementSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val platform = context.packageManager.packageInstaller
        val installer = ExtensionInstaller(context)
        val owner = Job()
        val operations = ExtensionInstaller::class.java.getDeclaredField("operations").let { field ->
            field.isAccessible = true
            field.get(installer) as ExtensionInstallOperations
        }
        val pkg = "dev.hayai.fixture.replacement"
        // Reproduce replacement after Manager resolved the old download ID to this package.
        operations.replace(pkg, owner)
        installer.activeDownloads[pkg] = Long.MAX_VALUE
        val retrySession = platform.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val oldSession = platform.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        try {
            installer.setInstalling(pkg, retrySession, Long.MAX_VALUE)
            installer.setInstalling(pkg, oldSession, Long.MAX_VALUE - 1)
            awaitAbandonedSession(platform, oldSession)
            assertEquals(retrySession, installer.downloadInstallerMap[pkg])
            installer.setInstallationResult(pkg, false, Long.MAX_VALUE - 1)
            assertEquals(retrySession, installer.downloadInstallerMap[pkg])
            assertNotNull(platform.getSessionInfo(retrySession))
        } finally {
            installer.activeDownloads.clear()
            installer.downloadInstallerMap.clear()
            operations.finish(pkg, owner)
            owner.cancel()
            runCatching { platform.abandonSession(retrySession) }
            runCatching { platform.abandonSession(oldSession) }
        }
    }

    private fun awaitAbandonedSession(platform: PackageInstaller, sessionId: Int) {
        // PackageInstallerService removes abandoned sessions on its install handler.
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (platform.getSessionInfo(sessionId) != null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25)
        }
        assertNull("The platform did not remove abandoned session $sessionId", platform.getSessionInfo(sessionId))
    }
}
