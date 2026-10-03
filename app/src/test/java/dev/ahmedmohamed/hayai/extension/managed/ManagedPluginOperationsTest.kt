package dev.ahmedmohamed.hayai.extension.managed

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedPluginOperationsTest {
    @Test
    fun `update all reserves each plugin once and runs in order`() = runBlocking {
        val operations = ManagedPluginOperations(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        assertTrue(operations.start("first", { throw AssertionError(it) }) {
            order += "first"
            entered.complete(Unit)
            release.await()
        })
        assertTrue(operations.start("second", { throw AssertionError(it) }) { order += "second" })
        assertFalse(operations.start("first", { throw AssertionError(it) }) { order += "duplicate" })
        entered.await()
        assertEquals(listOf("first"), order)
        assertEquals(setOf("first", "second"), operations.active.value)
        release.complete(Unit)
        withTimeout(2_000) { while (operations.active.value.isNotEmpty()) yield() }
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun `cancel stops actual plugin job clears reservation and does not report an error`() = runBlocking {
        val operations = ManagedPluginOperations(this)
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var failed = false
        var queuedRan = false
        operations.start("active", { failed = true }) {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        operations.start("queued", { failed = true }) { queuedRan = true }
        entered.await()
        operations.cancel("queued")
        operations.cancel("active")
        withTimeout(2_000) {
            stopped.await()
            while (operations.active.value.isNotEmpty()) yield()
        }
        assertFalse(failed)
        assertFalse(queuedRan)
        var retryRan = false
        assertTrue(operations.start("active", { failed = true }) { retryRan = true })
        withTimeout(2_000) { while (operations.active.value.isNotEmpty()) yield() }
        assertTrue(retryRan)
    }

    @Test
    fun `a failed plugin releases queue for remaining updates`() = runBlocking {
        val operations = ManagedPluginOperations(this)
        val failures = mutableListOf<String>()
        var nextRan = false
        operations.start("broken", { failures += it.message.orEmpty() }) { error("broken update") }
        operations.start("next", { throw AssertionError(it) }) { nextRan = true }
        withTimeout(2_000) { while (operations.active.value.isNotEmpty()) yield() }
        assertEquals(listOf("broken update"), failures)
        assertTrue(nextRan)
    }
}
