package dev.ahmedmohamed.hayai.novel.reader

import eu.kanade.tachiyomi.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelSelectionActionMenuTest {
    @Test
    fun `every selection action has exactly one menu entry`() {
        assertEquals(
            NovelSelectionAction.entries.toSet(),
            NovelSelectionActionMenu.entries.map { it.action }.toSet(),
        )
        assertEquals(NovelSelectionAction.entries.size, NovelSelectionActionMenu.entries.size)
    }

    @Test
    fun `menu ids are unique and unknown ids do not resolve`() {
        val ids = NovelSelectionActionMenu.entries.map { it.itemId }
        assertEquals(ids.size, ids.toSet().size)
        assertNull(NovelSelectionActionMenu.actionFor(Int.MIN_VALUE))
    }

    @Test
    fun `reader actions stay visible and web search stays in overflow`() {
        val entry = NovelSelectionActionMenu.entries.single { it.action == NovelSelectionAction.SearchWeb }
        assertEquals(NovelSelectionMenuPlacement.Overflow, entry.placement)
        NovelSelectionActionMenu.entries
            .filterNot { it.action == NovelSelectionAction.SearchWeb }
            .forEach { assertEquals(NovelSelectionMenuPlacement.Always, it.placement) }
    }

    @Test
    fun `every reader selection action has a semantic icon`() {
        NovelSelectionActionMenu.entries.forEach { entry ->
            assertTrue(entry.iconRes != 0)
        }
    }

    @Test
    fun `quote uses the compact floating-toolbar label`() {
        val quote = NovelSelectionActionMenu.entries.single { it.action == NovelSelectionAction.SaveQuote }

        assertEquals(R.string.hayai_novel_reader_quote, quote.titleRes)
    }

    @Test
    fun `reader actions use the selected alternative category ahead of platform actions`() {
        NovelSelectionActionMenu.entries.indices.forEach { index ->
            assertEquals(
                index,
                NovelSelectionActionMenu.orderFor(index),
            )
        }
    }
}
