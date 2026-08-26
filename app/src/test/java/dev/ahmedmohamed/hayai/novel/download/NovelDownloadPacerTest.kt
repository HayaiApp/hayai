package dev.ahmedmohamed.hayai.novel.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelDownloadPacerTest {
    @Test
    fun `requests to one source are spaced from the prior permit`() = runBlocking {
        val clock = FakeClock()
        val sleeper = FakeSleeper(clock)
        val pacer = NovelDownloadPacer(clock, sleeper)

        pacer.awaitPermit(sourceId = 1L, delayMillis = 1_000L, isUnmetered = false)
        clock.advance(400L)
        pacer.awaitPermit(sourceId = 1L, delayMillis = 1_000L, isUnmetered = false)
        clock.advance(250L)
        pacer.awaitPermit(sourceId = 1L, delayMillis = 1_000L, isUnmetered = false)

        assertEquals(listOf(600L, 750L), sleeper.delays)
    }

    @Test
    fun `source schedules are independent`() = runBlocking {
        val clock = FakeClock()
        val sleeper = FakeSleeper(clock, advanceClock = false)
        val pacer = NovelDownloadPacer(clock, sleeper)

        pacer.awaitPermit(sourceId = 1L, delayMillis = 7_000L, isUnmetered = false)
        pacer.awaitPermit(sourceId = 2L, delayMillis = 500L, isUnmetered = false)
        pacer.awaitPermit(sourceId = 1L, delayMillis = 7_000L, isUnmetered = false)
        pacer.awaitPermit(sourceId = 2L, delayMillis = 500L, isUnmetered = false)

        assertEquals(listOf(7_000L, 500L), sleeper.delays)
    }

    @Test
    fun `cancelling a wait cancels promptly without consuming a permit`() = runBlocking {
        val clock = FakeClock()
        val sleeper = FakeSleeper(clock)
        val pacer = NovelDownloadPacer(clock, sleeper)
        pacer.awaitPermit(sourceId = 1L, delayMillis = 1_000L, isUnmetered = false)
        sleeper.block = true

        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            pacer.awaitPermit(sourceId = 1L, delayMillis = 1_000L, isUnmetered = false)
        }
        waiting.cancelAndJoin()

        assertTrue(waiting.isCancelled)
        sleeper.block = false
        clock.advance(250L)
        pacer.awaitPermit(sourceId = 1L, delayMillis = 1_000L, isUnmetered = false)
        assertEquals(listOf(1_000L, 750L), sleeper.delays)
    }

    @Test
    fun `unmetered and zero delay requests bypass pacing`() = runBlocking {
        val clock = FakeClock()
        val sleeper = FakeSleeper(clock)
        val pacer = NovelDownloadPacer(clock, sleeper)

        repeat(2) {
            pacer.awaitPermit(sourceId = 1L, delayMillis = 1_000L, isUnmetered = true)
            pacer.awaitPermit(sourceId = 2L, delayMillis = 0L, isUnmetered = false)
        }

        assertTrue(sleeper.delays.isEmpty())
    }

    private class FakeClock : NovelDownloadMonotonicClock {
        var now = 0L
        override fun nowMillis(): Long = now
        fun advance(millis: Long) {
            now += millis
        }
    }

    private class FakeSleeper(
        private val clock: FakeClock,
        private val advanceClock: Boolean = true,
    ) : NovelDownloadSleeper {
        val delays = mutableListOf<Long>()
        var block = false

        override suspend fun sleep(delayMillis: Long) {
            delays += delayMillis
            if (block) awaitCancellation()
            if (advanceClock) clock.advance(delayMillis)
        }
    }
}
