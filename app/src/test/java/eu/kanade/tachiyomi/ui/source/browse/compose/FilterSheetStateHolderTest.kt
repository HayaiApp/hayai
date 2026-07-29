package eu.kanade.tachiyomi.ui.source.browse.compose

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.source.browse.filter.FilterTree

class FilterSheetStateHolderTest {

    private class Check(name: String, default: Boolean) : Filter.CheckBox(name, default)

    @Test
    fun `reset restores source defaults and clears the complete refinement`() {
        val defaults = FilterList(Check("Enabled", false))
        val draft = FilterList(Check("Enabled", true))
        val holder = FilterSheetStateHolder(
            draftFilters = draft,
            baseline = FilterTree.capture(defaults),
            savedSearches = emptyList(),
            sourceQuery = "hero",
        )

        assertEquals(2, holder.state.value.activeConstraints.size)

        holder.reset()

        assertFalse((holder.draftFilters().single() as Filter.CheckBox).state)
        assertTrue(holder.sourceQuery().isEmpty())
        assertTrue(holder.state.value.activeConstraints.isEmpty())
    }
}
