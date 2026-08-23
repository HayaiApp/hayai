package dev.ahmedmohamed.hayai.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `web search stays in overflow`() {
        val entry = NovelSelectionActionMenu.entries.single { it.action == NovelSelectionAction.SearchWeb }
        assertEquals(NovelSelectionMenuPlacement.Overflow, entry.placement)
        NovelSelectionActionMenu.entries
            .filterNot { it.action == NovelSelectionAction.SearchWeb }
            .forEach { assertEquals(NovelSelectionMenuPlacement.IfRoom, it.placement) }
    }
}
