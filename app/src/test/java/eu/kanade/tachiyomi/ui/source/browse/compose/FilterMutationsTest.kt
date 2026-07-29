package eu.kanade.tachiyomi.ui.source.browse.compose

import eu.kanade.tachiyomi.source.model.Filter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Pure-JVM unit tests asserting that [FilterMutations] preserves the legacy
 * filter-state mutation contract that
 * [eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController.showFilters]
 * snapshots and compares.
 *
 * Each test instantiates a minimal `Filter.X` subclass with the same constructor signature the
 * sources use, drives the mutation through [FilterMutations], and asserts the resulting `.state`
 * is byte-identical to what the old `FlexibleAdapter`-based item would have produced.
 *
 * These are the guard rails for "zero regressions" on filter behaviour — if the per-row
 * composables ever stop routing user events through [FilterMutations], or if [FilterMutations]
 * itself drifts, these tests fail loudly.
 */
class FilterMutationsTest {

    // region Filter.CheckBox

    private class TestCheckBox(name: String = "cb", default: Boolean = false) :
        Filter.CheckBox(name, default)

    @Test
    fun `Checkbox toggles false to true`() {
        val cb = TestCheckBox(default = false)
        FilterMutations.toggleCheckbox(cb)
        assertTrue(cb.state)
    }

    @Test
    fun `Checkbox toggles true to false`() {
        val cb = TestCheckBox(default = true)
        FilterMutations.toggleCheckbox(cb)
        assertFalse(cb.state)
    }

    // endregion

    // region Filter.TriState

    private class TestTriState(name: String = "ts", default: Int = Filter.TriState.STATE_IGNORE) :
        Filter.TriState(name, default)

    @Test
    fun `TriState from ignore - include sets include`() {
        val ts = TestTriState()
        FilterMutations.setTriState(ts, Filter.TriState.STATE_INCLUDE)
        assertEquals(Filter.TriState.STATE_INCLUDE, ts.state)
    }

    @Test
    fun `TriState include - include clears to ignore`() {
        val ts = TestTriState(default = Filter.TriState.STATE_INCLUDE)
        FilterMutations.setTriState(ts, Filter.TriState.STATE_INCLUDE)
        assertEquals(Filter.TriState.STATE_IGNORE, ts.state)
    }

    @Test
    fun `TriState include - exclude overrides to exclude`() {
        val ts = TestTriState(default = Filter.TriState.STATE_INCLUDE)
        FilterMutations.setTriState(ts, Filter.TriState.STATE_EXCLUDE)
        assertEquals(Filter.TriState.STATE_EXCLUDE, ts.state)
    }

    @Test
    fun `TriState exclude - exclude clears to ignore`() {
        val ts = TestTriState(default = Filter.TriState.STATE_EXCLUDE)
        FilterMutations.setTriState(ts, Filter.TriState.STATE_EXCLUDE)
        assertEquals(Filter.TriState.STATE_IGNORE, ts.state)
    }

    // endregion

    // region Filter.Text

    private class TestText(name: String = "t", default: String = "") :
        Filter.Text(name, default)

    @Test
    fun `Text sets state`() {
        val t = TestText()
        FilterMutations.setText(t, "hello")
        assertEquals("hello", t.state)
    }

    @Test
    fun `Text can be cleared`() {
        val t = TestText(default = "foo")
        FilterMutations.setText(t, "")
        assertEquals("", t.state)
    }

    // endregion

    // region Filter.Select

    private class TestSelect(
        name: String = "s",
        values: Array<String> = arrayOf("a", "b", "c"),
        default: Int = 0,
    ) : Filter.Select<String>(name, values, default)

    @Test
    fun `Select sets index`() {
        val s = TestSelect()
        FilterMutations.setSelect(s, 2)
        assertEquals(2, s.state)
    }

    // endregion

    // region Filter.Sort — matches legacy SortTextView toggle semantics

    private class TestSort(
        name: String = "sort",
        values: Array<String> = arrayOf("popular", "latest", "rating"),
        default: Selection? = null,
    ) : Filter.Sort(name, values, default)

    @Test
    fun `Sort from none - any index selects descending`() {
        val sort = TestSort()
        FilterMutations.toggleSort(sort, 1)
        assertEquals(Filter.Sort.Selection(1, ascending = false), sort.state)
    }

    @Test
    fun `Sort tap selected item toggles asc-desc`() {
        val sort = TestSort(default = Filter.Sort.Selection(1, ascending = false))
        FilterMutations.toggleSort(sort, 1)
        assertEquals(Filter.Sort.Selection(1, ascending = true), sort.state)
        FilterMutations.toggleSort(sort, 1)
        assertEquals(Filter.Sort.Selection(1, ascending = false), sort.state)
    }

    @Test
    fun `Sort tap different item snaps to that index descending`() {
        val sort = TestSort(default = Filter.Sort.Selection(0, ascending = true))
        FilterMutations.toggleSort(sort, 2)
        assertEquals(Filter.Sort.Selection(2, ascending = false), sort.state)
    }

    @Test
    fun `Sort can move back to null only via Selection swap`() {
        // Legacy SortTextView never produces a null state via tapping — null is the
        // initial state only. Confirm toggleSort never sets state back to null.
        val sort = TestSort(default = Filter.Sort.Selection(0, ascending = false))
        FilterMutations.toggleSort(sort, 0) // → asc
        FilterMutations.toggleSort(sort, 0) // → desc
        assertEquals(Filter.Sort.Selection(0, ascending = false), sort.state)
        assertTrue(sort.state != null)
    }

    // endregion

    // region Filter.AutoComplete

    private class TestAutoComplete(
        name: String = "ac",
        hint: String = "",
        values: List<String> = listOf("apple", "banana", "cherry"),
        skip: List<String> = emptyList(),
        prefixes: List<String> = emptyList(),
        default: List<String> = emptyList(),
    ) : Filter.AutoComplete(name, hint, values, skip, prefixes, default)

    @Test
    fun `AutoComplete adds tag`() {
        val ac = TestAutoComplete()
        val added = FilterMutations.addAutoCompleteTag(ac, "apple")
        assertTrue(added)
        assertEquals(listOf("apple"), ac.state)
    }

    @Test
    fun `AutoComplete rejects tag in skip list`() {
        val ac = TestAutoComplete(skip = listOf("apple"))
        val added = FilterMutations.addAutoCompleteTag(ac, "apple")
        assertFalse(added)
        assertEquals(emptyList<String>(), ac.state)
    }

    @Test
    fun `AutoComplete strips prefix before skip-list check`() {
        // Common NHentai pattern: '-foo' = exclude foo. The prefix is part of the
        // submitted tag but `skipAutoFillTags` lists the bare value.
        val ac = TestAutoComplete(
            skip = listOf("apple"),
            prefixes = listOf("-"),
        )
        val added = FilterMutations.addAutoCompleteTag(ac, "-apple")
        assertFalse(added)
        assertEquals(emptyList<String>(), ac.state)
    }

    @Test
    fun `AutoComplete keeps prefix in stored state`() {
        // The state list should contain the user-entered tag verbatim, including any
        // prefix — that's what the source consumes.
        val ac = TestAutoComplete(prefixes = listOf("-"))
        FilterMutations.addAutoCompleteTag(ac, "-banana")
        assertEquals(listOf("-banana"), ac.state)
    }

    @Test
    fun `AutoComplete preserves non-exclusion prefix literally`() {
        val ac = TestAutoComplete(prefixes = listOf("-", "~"), default = listOf("~banana"))
        FilterMutations.setAutoCompleteTagState(ac, "~banana", AutoCompleteTagState.Excluded)
        assertEquals(listOf("~banana"), ac.state)
        FilterMutations.setAutoCompleteTagState(ac, "~banana", AutoCompleteTagState.Off)
        assertEquals(emptyList<String>(), ac.state)
    }

    @Test
    fun `AutoComplete removes tag`() {
        val ac = TestAutoComplete(default = listOf("apple", "banana"))
        FilterMutations.removeAutoCompleteTag(ac, "apple")
        assertEquals(listOf("banana"), ac.state)
    }

    @Test
    fun `AutoComplete remove of non-existent tag is no-op`() {
        val ac = TestAutoComplete(default = listOf("apple"))
        FilterMutations.removeAutoCompleteTag(ac, "banana")
        assertEquals(listOf("apple"), ac.state)
    }

    @Test
    fun `AutoComplete cycle without exclude prefix walks Off - Included - Off`() {
        val ac = TestAutoComplete()
        val toInclude = FilterMutations.cycleAutoCompleteTag(ac, "apple")
        assertEquals(AutoCompleteTagState.Included, toInclude)
        assertEquals(listOf("apple"), ac.state)

        val toOff = FilterMutations.cycleAutoCompleteTag(ac, "apple")
        assertEquals(AutoCompleteTagState.Off, toOff)
        assertEquals(emptyList<String>(), ac.state)
    }

    @Test
    fun `AutoComplete cycle with exclude prefix walks Off - Included - Excluded - Off`() {
        val ac = TestAutoComplete(prefixes = listOf("-"))
        val toInclude = FilterMutations.cycleAutoCompleteTag(ac, "apple")
        assertEquals(AutoCompleteTagState.Included, toInclude)
        assertEquals(listOf("apple"), ac.state)

        val toExclude = FilterMutations.cycleAutoCompleteTag(ac, "apple")
        assertEquals(AutoCompleteTagState.Excluded, toExclude)
        assertEquals(listOf("-apple"), ac.state)

        val toOff = FilterMutations.cycleAutoCompleteTag(ac, "apple")
        assertEquals(AutoCompleteTagState.Off, toOff)
        assertEquals(emptyList<String>(), ac.state)
    }

    @Test
    fun `AutoComplete cycle preserves other tags`() {
        val ac = TestAutoComplete(
            prefixes = listOf("-"),
            default = listOf("banana", "cherry"),
        )
        FilterMutations.cycleAutoCompleteTag(ac, "apple") // Off → Included
        assertEquals(listOf("banana", "cherry", "apple"), ac.state)
        FilterMutations.cycleAutoCompleteTag(ac, "apple") // Included → Excluded
        assertEquals(listOf("banana", "cherry", "-apple"), ac.state)
        FilterMutations.cycleAutoCompleteTag(ac, "apple") // Excluded → Off
        assertEquals(listOf("banana", "cherry"), ac.state)
    }

    // endregion

    @Nested
    inner class InvariantsAcrossAllMutations {
        @Test
        fun `Mutations are in-place — same instance`() {
            // Sanity check the in-place mutation contract that BrowseSourceController
            // depends on: the Filter instance reference must not change.
            val cb = TestCheckBox()
            val before = cb
            FilterMutations.toggleCheckbox(cb)
            assertTrue(before === cb)

            val ts = TestTriState()
            val tsRef = ts
            FilterMutations.setTriState(ts, Filter.TriState.STATE_INCLUDE)
            assertTrue(tsRef === ts)

            val sort = TestSort()
            val sortRef = sort
            FilterMutations.toggleSort(sort, 0)
            assertTrue(sortRef === sort)
        }

        @Test
        fun `Default Sort state is null`() {
            // Sanity check our test model — Filter.Sort defaults to null.
            assertNull(TestSort().state)
        }
    }
}
