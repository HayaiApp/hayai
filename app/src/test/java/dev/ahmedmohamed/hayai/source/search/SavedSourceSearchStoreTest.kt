package dev.ahmedmohamed.hayai.source.search

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedSourceSearchStoreTest {
    @Test
    fun `filter state is restored by structure and rejects incompatible values`() {
        val original = FilterList(Check("Completed", true), Choice("Rating", 2), Group("Tags", listOf(Text("Fandom", "Hayai"))))
        val states = SavedSourceFilterCodec.capture(original)
        val restored = FilterList(Check("Localized", false), Choice("Changed", 0), Group("Changed", listOf(Text("Changed", ""))))

        assertEquals(3, SavedSourceFilterCodec.restore(restored, states))
        assertEquals(true, (restored[0] as Check).state)
        assertEquals(2, (restored[1] as Choice).state)
        assertEquals("Hayai", ((restored[2] as Group).state[0] as Text).state)
        assertEquals(0, SavedSourceFilterCodec.restore(FilterList(Choice("Short", 0, arrayOf("Only"))), listOf(SavedFilterState(listOf(0), "select", "2"))))
    }

    private class Check(name: String, state: Boolean) : Filter.CheckBox(name, state)
    private class Text(name: String, state: String) : Filter.Text(name, state)
    private class Choice(name: String, state: Int, values: Array<String> = arrayOf("A", "B", "C")) : Filter.Select<String>(name, values, state)
    private class Group(name: String, state: List<Filter<*>>) : Filter.Group<Filter<*>>(name, state)
}
