package dev.ahmedmohamed.hayai.extension.install

import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionInstallLifecycleTest {
    @Test
    fun `cancelling a queued package never starts it and preserves other installs`() = runBlocking {
        val batch = ExtensionInstallBatch(parallelism = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val work = launch {
            batch.run(listOf("active", "cancelled", "next")) { pkg ->
                started += pkg
                if (pkg == "active") {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        entered.await()
        batch.cancel("cancelled")
        release.complete(Unit)
        withTimeout(2_000) { work.join() }
        assertEquals(listOf("active", "next"), started)
    }

    @Test
    fun `cancelling active work terminates it without cancelling another package`() = runBlocking {
        val batch = ExtensionInstallBatch(parallelism = 1)
        val entered = CompletableDeferred<Unit>()
        var activeStopped = false
        var nextCompleted = false
        val work = launch {
            batch.run(listOf("active", "next")) { pkg ->
                if (pkg == "active") {
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        activeStopped = true
                    }
                } else {
                    nextCompleted = true
                }
            }
        }
        entered.await()
        batch.cancel("active")
        withTimeout(2_000) { work.join() }
        assertTrue(activeStopped)
        assertTrue(nextCompleted)
    }

    @Test
    fun `cancellation before batch creation is respected`() = runBlocking {
        val batch = ExtensionInstallBatch()
        batch.cancel("cancelled")
        val started = mutableListOf<String>()
        batch.run(listOf("cancelled", "other")) { started += it }
        assertEquals(listOf("other"), started)
    }

    @Test
    fun `replaced operation cannot delete retry or publish stale state`() {
        val operations = ExtensionInstallOperations()
        val old = Job()
        val retry = Job()
        operations.replace("pkg", old)
        operations.replace("pkg", retry)
        var staleCleanup = false
        assertTrue(old.isCancelled)
        assertFalse(operations.isCurrent("pkg", old))
        assertFalse(operations.finish("pkg", old) { staleCleanup = true })
        assertFalse(staleCleanup)
        assertTrue(operations.isCurrent("pkg", retry))
        assertTrue(operations.cancel("pkg"))
        assertTrue(retry.isCancelled)
    }

    @Test
    fun `cancelled owner cannot register a late platform session`() {
        val operations = ExtensionInstallOperations()
        val owner = Job()
        operations.replace("pkg", owner)
        owner.cancel()
        var registered = false
        assertFalse(operations.withCurrent("pkg") { registered = true })
        assertFalse(registered)
    }

    @Test
    fun `cancel after completion cleans orphan resources without cancelling the completed owner`() {
        val operations = ExtensionInstallOperations()
        val owner = Job()
        operations.replace("pkg", owner)
        operations.finish("pkg", owner)
        owner.complete()
        var cleaned = false
        assertFalse(operations.cancel("pkg") { cleaned = true })
        assertTrue(cleaned)
        assertFalse(owner.isCancelled)
        assertTrue(operations.latest("pkg") === owner)
    }

    @Test
    fun `delayed old terminal event cannot finish the replacement collector`() = runBlocking {
        val old = Job()
        val retry = Job()
        val events = MutableSharedFlow<ExtensionInstallEvent>()
        val received = mutableListOf<InstallStep>()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            events.filter { it.belongsTo("pkg", retry) }.transformWhile {
                emit(it.info.first)
                !it.info.first.isCompleted()
            }.collect { received += it }
        }
        events.emit(ExtensionInstallEvent("pkg", InstallStep.Error to null, old))
        assertTrue(collection.isActive)
        events.emit(ExtensionInstallEvent("pkg", InstallStep.Downloading to null, retry))
        events.emit(ExtensionInstallEvent("pkg", InstallStep.Installed to null, old))
        assertTrue(collection.isActive)
        events.emit(ExtensionInstallEvent("pkg", InstallStep.Installed to null, retry))
        withTimeout(2_000) { collection.join() }
        assertEquals(listOf(InstallStep.Downloading, InstallStep.Installed), received)
    }

    @Test
    fun `public row projection drops old attempt events after a retry starts`() {
        val old = Job()
        val retry = Job()
        assertFalse(ExtensionInstallEvent("pkg", InstallStep.Error to null, old).isVisible(retry, retry))
        assertFalse(ExtensionInstallEvent("pkg", InstallStep.Loading to null, old).isVisible(null, old))
        assertTrue(ExtensionInstallEvent("pkg", InstallStep.Error to null, old).isVisible(null, old))
        assertFalse(ExtensionInstallEvent("pkg", InstallStep.Error to null, old).isVisible(null, retry))
        assertTrue(ExtensionInstallEvent("Finished/pkg", InstallStep.Installed to null, null).isVisible(retry, retry))
    }

    @Test
    fun `delayed older terminal events stay hidden after replacement has completed`() = runBlocking {
        val operations = ExtensionInstallOperations()
        val old = Job()
        val retry = Job()
        operations.replace("pkg", old)
        operations.replace("pkg", retry)
        val events = MutableSharedFlow<ExtensionInstallEvent>()
        val received = mutableListOf<InstallStep>()
        val projected = launch(start = CoroutineStart.UNDISPATCHED) {
            events.filter { it.isVisible(operations.current(it.packageName), operations.latest(it.packageName)) }
                .collect { received += it.info.first }
        }
        events.emit(ExtensionInstallEvent("pkg", InstallStep.Installed to null, retry))
        operations.finish("pkg", retry)
        retry.complete()
        events.emit(ExtensionInstallEvent("pkg", InstallStep.Error to null, old))
        events.emit(ExtensionInstallEvent("pkg", InstallStep.Installed to null, old))
        projected.cancel()
        projected.join()
        assertEquals(listOf(InstallStep.Installed), received)
    }

    @Test
    fun `missing callbacks and disappeared sessions have bounded retry deadlines`() {
        val wait = ExtensionInstallWait(100)
        assertFalse(wait.expired(100 + ExtensionInstallWait.TIMEOUT_MILLIS - 1))
        assertTrue(wait.expired(100 + ExtensionInstallWait.TIMEOUT_MILLIS))
        assertFalse(wait.sessionDisappeared(200, false))
        assertFalse(wait.sessionDisappeared(2_000, true))
        assertFalse(wait.sessionDisappeared(2_500, false))
        assertFalse(wait.sessionDisappeared(5_499, false))
        assertTrue(wait.sessionDisappeared(5_500, false))
    }

    @Test
    fun `all cancellable phases expose cancel without requiring session progress`() {
        listOf(InstallStep.Pending, InstallStep.Downloading, InstallStep.Loading).forEach {
            assertTrue(ExtensionInstallActions.canCancel(it, false))
        }
        assertTrue(ExtensionInstallActions.canCancel(InstallStep.Installing, true))
        assertFalse(ExtensionInstallActions.canCancel(InstallStep.Installing, false))
        listOf(null, InstallStep.Installed, InstallStep.Error, InstallStep.Done).forEach {
            assertFalse(ExtensionInstallActions.canCancel(it, true))
        }
    }

    @Test
    fun `worker cancellation before start persists until an intentional retry`() {
        QueuedExtensionCancellations.start(listOf("queued.fixture"))
        QueuedExtensionCancellations.cancel("queued.fixture")
        assertTrue(QueuedExtensionCancellations.contains("queued.fixture"))
        QueuedExtensionCancellations.start(listOf("unrelated.fixture"))
        assertTrue(QueuedExtensionCancellations.contains("queued.fixture"))
        QueuedExtensionCancellations.start(listOf("queued.fixture"))
        assertFalse(QueuedExtensionCancellations.contains("queued.fixture"))
    }
}
