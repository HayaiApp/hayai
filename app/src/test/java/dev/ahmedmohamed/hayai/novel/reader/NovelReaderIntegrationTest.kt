package dev.ahmedmohamed.hayai.novel.reader

import eu.kanade.tachiyomi.source.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelReaderIntegrationTest {
    @Test
    fun `reader identity follows the source ABI instead of source naming heuristics`() {
        assertFalse(NovelReaderIdentity.isNovel(FakeSource("Novel Planet", false)))
        assertTrue(NovelReaderIdentity.isNovel(FakeSource("Books", true)))
    }

    @Test
    fun `progress remains percent based and completes at configured threshold`() {
        assertEquals(NovelProgressUpdate(0, 100, false), NovelReaderProgress.update(-20, 95))
        assertEquals(NovelProgressUpdate(94, 6, false), NovelReaderProgress.update(94, 95))
        assertEquals(NovelProgressUpdate(95, 0, true), NovelReaderProgress.update(95, 95))
        assertEquals(NovelProgressUpdate(100, 0, true), NovelReaderProgress.update(140, 95))
    }

    @Test
    fun `native is only selected for horizontal continuous documents`() {
        assertTrue(
            NovelRenderPlan.resolve(
                NovelRenderingBackend.Native,
                NovelLayoutMode.Continuous,
                NovelWritingDirection.Horizontal,
            ) is NovelRenderPlan.NativeContinuous,
        )
        assertTrue(
            NovelRenderPlan.resolve(
                NovelRenderingBackend.Native,
                NovelLayoutMode.Paged,
                NovelWritingDirection.Horizontal,
            ) is NovelRenderPlan.Web,
        )
        assertTrue(
            NovelRenderPlan.resolve(
                NovelRenderingBackend.Native,
                NovelLayoutMode.Continuous,
                NovelWritingDirection.VerticalRl,
            ) is NovelRenderPlan.Web,
        )
    }

    @Test
    fun `page presentation distinguishes percent from renderer page count`() {
        assertFalse(NovelPagePresentation(50).hasPageCount)
        assertTrue(NovelPagePresentation(50, 4, 9).hasPageCount)
    }

    private class FakeSource(
        override val name: String,
        override val isNovelSource: Boolean,
    ) : Source {
        override val id: Long = 1
    }
}
