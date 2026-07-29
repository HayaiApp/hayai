package eu.kanade.tachiyomi.ui.source.browse.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import yokai.domain.source.browse.filter.FilterTree
import yokai.domain.source.browse.filter.FilterValueSnapshot
import yokai.domain.source.browse.filter.models.SavedSearch

class FrictionlessFilterSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private class Tri(name: String) : Filter.TriState(name)
    private class Group(name: String, children: List<Filter<*>>) :
        Filter.Group<Filter<*>>(name, children)

    private fun filters() = FilterList(Group("Tags", listOf(Tri("Action"))))

    @Test
    fun universalSearchFindsNestedValuesAndChangesTriStateInline() {
        val filters = filters()
        val holder = FilterSheetStateHolder(
            draftFilters = filters,
            baseline = FilterTree.capture(filters),
            savedSearches = emptyList(),
            sourceQuery = "",
        )
        compose.setContent {
            MaterialTheme {
                FrictionlessFilterSheetContent(
                    holder = holder,
                    onDone = {},
                    onSave = {},
                    onLoadPreset = {},
                    onDeletePreset = {},
                )
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("Action")
        compose.waitUntil(3_000) {
            compose.onAllNodesWithText("Tags › Action").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Tags › Action").assertExists()
        compose.onNodeWithText("Exclude").performClick()

        val group = holder.state.value.nodes.single()
        val tri = group.children.single().value as FilterValueSnapshot.TriState
        assertEquals(Filter.TriState.STATE_EXCLUDE, tri.value)
    }

    @Test
    fun quickPresetTapRequestsPresetWithoutClosingTheSheet() {
        val filters = filters()
        var requestedId: Long? = null
        val holder = FilterSheetStateHolder(
            draftFilters = filters,
            baseline = FilterTree.capture(filters),
            savedSearches = listOf(SavedSearch(7, "Action only", "hero", filters())),
            sourceQuery = "",
        )
        compose.setContent {
            MaterialTheme {
                FrictionlessFilterSheetContent(
                    holder = holder,
                    onDone = {},
                    onSave = {},
                    onLoadPreset = { requestedId = it },
                    onDeletePreset = {},
                )
            }
        }

        compose.onNodeWithText("Action only").performClick()
        assertEquals(7L, requestedId)
        compose.onNodeWithText("Refine").assertExists()
    }
}
