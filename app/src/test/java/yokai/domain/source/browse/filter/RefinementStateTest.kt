package yokai.domain.source.browse.filter

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.source.browse.applyAllSave
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.source.browse.filter.models.RawSavedSearch

class RefinementStateTest {

    private class Check(name: String, default: Boolean) : Filter.CheckBox(name, default)
    private class Tri(name: String, default: Int) : Filter.TriState(name, default)
    private class Select(name: String, values: Array<String>, default: Int) :
        Filter.Select<String>(name, values, default)
    private class Sort(name: String, values: Array<String>, default: Selection?) :
        Filter.Sort(name, values, default)
    private class Auto(name: String, default: List<String> = emptyList()) : Filter.AutoComplete(
        name = name,
        values = listOf("action", "drama", "romance"),
        validPrefixes = listOf("-", "~"),
        state = default,
    )
    private class Group(name: String, children: List<Filter<*>>) :
        Filter.Group<Filter<*>>(name, children)

    private fun filterFactory(): FilterList = FilterList(
        Check("Default enabled", true),
        Select("Mode", arrayOf("Any", "Exact", "Fuzzy"), 1),
        Sort("Order", arrayOf("Popular", "Updated"), Filter.Sort.Selection(1, true)),
        Group(
            "Tags",
            listOf(
                Group(
                    "Genres",
                    listOf(
                        Tri("Action", Filter.TriState.STATE_EXCLUDE),
                        Auto("Tag search"),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `deep snapshots use source defaults and reset only the requested path`() {
        val defaults = filterFactory()
        val baseline = FilterTree.capture(defaults)
        val draft = FilterTree.materialize(baseline, ::filterFactory)

        val triPath = FilterPath(listOf(3, 0, 0))
        val tri = FilterTree.findFilter(draft, triPath) as Filter.TriState
        tri.state = Filter.TriState.STATE_INCLUDE

        assertFalse(FilterTree.capture(draft) == baseline)
        assertTrue(FilterTree.resetPath(draft, baseline, triPath))
        assertEquals(Filter.TriState.STATE_EXCLUDE, tri.state)
        assertEquals(baseline, FilterTree.capture(draft))
    }

    @Test
    fun `materialized draft and pager filters never share mutable instances`() {
        val baseline = FilterTree.capture(filterFactory())
        val draft = FilterTree.materialize(baseline, ::filterFactory)
        val pager = FilterTree.materialize(baseline, ::filterFactory)

        assertNotSame(draft[0], pager[0])
        (draft[0] as Filter.CheckBox).state = false
        assertTrue((pager[0] as Filter.CheckBox).state)
    }

    @Test
    fun `active constraints describe tri-state leaves instead of universal defaults`() {
        val baseline = FilterTree.capture(filterFactory())
        val draft = FilterTree.materialize(baseline, ::filterFactory)
        (FilterTree.findFilter(draft, FilterPath(listOf(3, 0, 0))) as Filter.TriState).state =
            Filter.TriState.STATE_INCLUDE

        val constraints = FilterTree.activeConstraints(baseline, FilterTree.capture(draft), "")

        assertEquals(1, constraints.size)
        assertEquals("Action", constraints.single().value)
        assertEquals(ConstraintSemantic.INCLUDE, constraints.single().semantic)
    }

    @Test
    fun `V2 round trip preserves nested defaults sort direction and literal prefixes`() {
        val serializer = FilterSerializer()
        val filters = filterFactory()
        val auto = FilterTree.findFilter(filters, FilterPath(listOf(3, 0, 1))) as Filter.AutoComplete
        auto.state = listOf("action", "-drama", "~romance")
        val sort = filters[2] as Filter.Sort
        sort.state = Filter.Sort.Selection(0, false)

        val encoded = serializer.serializeV2(filters)
        val restored = filterFactory()
        serializer.deserializeV2(restored, encoded)

        assertEquals(FilterTree.capture(filters), FilterTree.capture(restored))
        assertEquals(
            listOf("action", "-drama", "~romance"),
            (FilterTree.findFilter(restored, FilterPath(listOf(3, 0, 1))) as Filter.AutoComplete).state,
        )
    }

    @Test
    fun `each saved search decodes into a fresh filter tree`() {
        val serializer = FilterSerializer()
        val json = Json
        val first = filterFactory().also { (it[0] as Filter.CheckBox).state = false }
        val second = filterFactory().also { (it[1] as Filter.Select<*>).state = 2 }
        val raw = listOf(
            RawSavedSearch(1, 9, "First", "hero", json.encodeToString(serializer.serializeV2(first))),
            RawSavedSearch(2, 9, "Second", "villain", json.encodeToString(serializer.serializeV2(second))),
        )

        val restored = raw.applyAllSave(::filterFactory, json, serializer)

        assertNotSame(restored[0].filters, restored[1].filters)
        assertFalse((restored[0].filters!![0] as Filter.CheckBox).state)
        assertEquals(2, (restored[1].filters!![1] as Filter.Select<*>).state)
        assertEquals("hero", restored[0].query)
        assertEquals("villain", restored[1].query)
    }

    @Test
    fun `V2 follows unique names and option labels when a source reorders filters`() {
        val serializer = FilterSerializer()
        val original = FilterList(
            Check("First", false),
            Check("Second", true),
            Select("Mode", arrayOf("Any", "Exact", "Fuzzy"), 2),
        )
        val reordered = FilterList(
            Select("Mode", arrayOf("Fuzzy", "Any", "Exact"), 1),
            Check("Second", false),
            Check("First", true),
        )

        serializer.deserializeV2(reordered, serializer.serializeV2(original))

        assertEquals(0, (reordered[0] as Filter.Select<*>).state)
        assertTrue((reordered[1] as Filter.CheckBox).state)
        assertFalse((reordered[2] as Filter.CheckBox).state)
    }

    @Test
    fun `legacy positional presets remain readable`() {
        val serializer = FilterSerializer()
        val original = FilterList(Check("Enabled", false), Select("Mode", arrayOf("A", "B"), 1))
        val restored = FilterList(Check("Enabled", true), Select("Mode", arrayOf("A", "B"), 0))

        serializer.deserialize(restored, serializer.serialize(original))

        assertFalse((restored[0] as Filter.CheckBox).state)
        assertEquals(1, (restored[1] as Filter.Select<*>).state)
    }
}
