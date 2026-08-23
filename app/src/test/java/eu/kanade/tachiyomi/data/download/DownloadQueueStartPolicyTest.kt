package eu.kanade.tachiyomi.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueStartPolicyTest {
    @Test
    fun `explicit auto start resumes a stopped non-empty queue`() {
        assertTrue(
            DownloadQueueStartPolicy.shouldStart(
                autoStart = true,
                downloaderRunning = false,
            ),
        )
    }

    @Test
    fun `manual queueing preserves a stopped queue`() {
        assertFalse(
            DownloadQueueStartPolicy.shouldStart(
                autoStart = false,
                downloaderRunning = false,
            ),
        )
    }

    @Test
    fun `an already running downloader is not restarted`() {
        assertFalse(
            DownloadQueueStartPolicy.shouldStart(
                autoStart = true,
                downloaderRunning = true,
            ),
        )
    }
}
