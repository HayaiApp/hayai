package dev.ahmedmohamed.hayai.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReaderLauncherPageTest {
    @Test
    fun `reader launch page key is stable for activity handoff`() {
        assertEquals("dev.ahmedmohamed.hayai.reader.INITIAL_PAGE", ReaderLauncher.EXTRA_INITIAL_PAGE)
        assertEquals(0, ReaderLauncher.requireInitialPage(0))
        assertEquals(41, ReaderLauncher.requireInitialPage(41))
        assertThrows(IllegalArgumentException::class.java) { ReaderLauncher.requireInitialPage(-1) }
    }
}
