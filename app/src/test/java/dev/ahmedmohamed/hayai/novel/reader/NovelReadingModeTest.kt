package dev.ahmedmohamed.hayai.novel.reader

import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import dev.ahmedmohamed.hayai.testing.MemoryPreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelReadingModeTest {
    @Test
    fun `existing scroll and infinite preferences retain their behavior`() {
        assertEquals("scroll", HayaiPreferences(MemoryPreferenceStore()).novelReadingMode.get())
        assertEquals(
            "continuous",
            HayaiPreferences(MemoryPreferenceStore("pref_novel_infinite_scroll" to true)).novelReadingMode.get(),
        )
        assertEquals(
            "paged",
            HayaiPreferences(MemoryPreferenceStore("pref_novel_layout_mode" to "paged", "pref_novel_infinite_scroll" to true)).novelReadingMode.get(),
        )
    }

    @Test
    fun `explicit reading mode wins over restored legacy flags`() {
        val store = MemoryPreferenceStore("pref_novel_layout_mode" to "paged", "pref_novel_infinite_scroll" to true)
        HayaiPreferences(store).novelReadingMode.set("scroll")
        assertEquals("scroll", HayaiPreferences(store).novelReadingMode.get())
        HayaiPreferences(store).novelReadingMode.set("continuous")
        assertEquals("continuous", HayaiPreferences(store).novelReadingMode.get())
    }

    @Test
    fun `both horizontal scroll modes support native selectable text`() {
        listOf(NovelLayoutMode.Scroll, NovelLayoutMode.Continuous).forEach { layout ->
            val plan = NovelRenderPlan.resolve(NovelRenderingBackend.Native, layout, NovelWritingDirection.Horizontal)
            assertTrue(plan is NovelRenderPlan.NativeContinuous)
            assertEquals(layout, plan.layout)
        }
    }

    @Test
    fun `paged and vertical plans preserve layout with web rendering`() {
        NovelLayoutMode.entries.forEach { layout ->
            val plan = NovelRenderPlan.resolve(NovelRenderingBackend.Native, layout, NovelWritingDirection.VerticalRl)
            assertTrue(plan is NovelRenderPlan.Web)
            assertEquals(layout, plan.layout)
        }
    }
}
