package dev.ahmedmohamed.hayai.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class J2kNovelReaderChromeTest {
    @Test
    fun `fullscreen overlay reserves the status bar and toolbar`() {
        assertEquals(
            168,
            novelViewerTopPadding(
                overlaysSystemBars = true,
                systemBarTop = 48,
                toolbarHeight = 120,
            ),
        )
    }

    @Test
    fun `non fullscreen layout does not reserve the status bar twice`() {
        assertEquals(
            120,
            novelViewerTopPadding(
                overlaysSystemBars = false,
                systemBarTop = 48,
                toolbarHeight = 120,
            ),
        )
    }
}
