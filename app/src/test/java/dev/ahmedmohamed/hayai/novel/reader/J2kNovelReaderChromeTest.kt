package dev.ahmedmohamed.hayai.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class J2kNovelReaderChromeTest {
    @Test
    fun `fullscreen chrome overlays content without pushing the viewer`() {
        assertEquals(
            0,
            novelViewerTopPadding(
                overlaysSystemBars = true,
                systemBarTop = 48,
                toolbarHeight = 120,
            ),
        )
    }

    @Test
    fun `non fullscreen chrome overlays content without pushing the viewer`() {
        assertEquals(
            0,
            novelViewerTopPadding(
                overlaysSystemBars = false,
                systemBarTop = 48,
                toolbarHeight = 120,
            ),
        )
    }
}
