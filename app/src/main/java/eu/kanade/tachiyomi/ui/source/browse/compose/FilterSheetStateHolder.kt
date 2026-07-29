package eu.kanade.tachiyomi.ui.source.browse.compose

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import yokai.domain.source.browse.filter.ActiveConstraint
import yokai.domain.source.browse.filter.ConstraintRemoval
import yokai.domain.source.browse.filter.FilterKind
import yokai.domain.source.browse.filter.FilterPath
import yokai.domain.source.browse.filter.FilterTree
import yokai.domain.source.browse.filter.FilterTreeSnapshot
import yokai.domain.source.browse.filter.FilterValueSnapshot
import yokai.domain.source.browse.filter.kind
import yokai.domain.source.browse.filter.models.SavedSearch

internal data class FilterUiOption(val index: Int, val label: String)

internal data class FilterUiNode(
    val path: FilterPath,
    val name: String,
    val kind: FilterKind,
    val value: FilterValueSnapshot,
    val baselineValue: FilterValueSnapshot?,
    val options: List<FilterUiOption> = emptyList(),
    val hint: String = "",
    val validPrefixes: List<String> = emptyList(),
    val children: List<FilterUiNode> = emptyList(),
) {
    val isActive: Boolean get() = baselineValue != value
}

internal data class FilterUiState(
    val nodes: List<FilterUiNode>,
    val activeConstraints: List<ActiveConstraint>,
    val savedSearches: List<SavedSearch>,
    val sourceQuery: String,
    val revision: Int,
)

/** Owns the sheet's isolated mutable source objects and only publishes immutable UI values. */
internal class FilterSheetStateHolder(
    draftFilters: FilterList,
    private val baseline: FilterTreeSnapshot,
    savedSearches: List<SavedSearch>,
    sourceQuery: String,
) {
    private var draft = draftFilters
    private var query = sourceQuery
    private var searches = savedSearches
    private var revision = 0
    private val mutableState = MutableStateFlow(buildState())

    val state: StateFlow<FilterUiState> = mutableState.asStateFlow()

    fun draftFilters(): FilterList = draft

    fun sourceQuery(): String = query

    fun updateSavedSearches(value: List<SavedSearch>) {
        searches = value
        publish()
    }

    fun loadPreset(filters: FilterList, sourceQuery: String) {
        draft = filters
        query = sourceQuery
        publish()
    }

    fun reset() {
        FilterTree.apply(baseline, draft)
        query = ""
        publish()
    }

    fun removeConstraint(removal: ConstraintRemoval) {
        when (removal) {
            ConstraintRemoval.ClearQuery -> query = ""
            is ConstraintRemoval.ResetPath -> FilterTree.resetPath(draft, baseline, removal.path)
            is ConstraintRemoval.RemoveAutoCompleteValue ->
                FilterTree.removeAutoCompleteValue(draft, removal.path, removal.value)
        }
        publish()
    }

    fun toggleCheckbox(path: FilterPath) = mutate<Filter.CheckBox>(path) {
        FilterMutations.toggleCheckbox(it)
    }

    fun setTriState(path: FilterPath, value: Int) = mutate<Filter.TriState>(path) {
        FilterMutations.setTriStateExact(it, value)
    }

    fun setSelect(path: FilterPath, index: Int) = mutate<Filter.Select<*>>(path) {
        FilterMutations.setSelect(it, index)
    }

    fun setText(path: FilterPath, value: String) = mutate<Filter.Text>(path) {
        FilterMutations.setText(it, value)
    }

    fun toggleSort(path: FilterPath, index: Int) = mutate<Filter.Sort>(path) {
        FilterMutations.toggleSort(it, index)
    }

    fun setAutoComplete(path: FilterPath, value: String, state: AutoCompleteTagState) =
        mutate<Filter.AutoComplete>(path) { FilterMutations.setAutoCompleteTagState(it, value, state) }

    fun addAutoComplete(path: FilterPath, value: String): Boolean {
        val filter = FilterTree.findFilter(draft, path) as? Filter.AutoComplete ?: return false
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        if ((trimmed.startsWith("-") || trimmed.startsWith("~")) &&
            filter.validPrefixes.none(trimmed::startsWith)
        ) {
            return false
        }
        val added = FilterMutations.addAutoCompleteTag(filter, trimmed)
        if (added) publish()
        return added
    }

    fun removeAutoComplete(path: FilterPath, value: String) = mutate<Filter.AutoComplete>(path) {
        FilterMutations.removeAutoCompleteTag(it, value)
    }

    private inline fun <reified T : Filter<*>> mutate(path: FilterPath, block: (T) -> Unit) {
        val filter = FilterTree.findFilter(draft, path) as? T ?: return
        block(filter)
        publish()
    }

    private fun publish() {
        revision += 1
        mutableState.value = buildState()
    }

    private fun buildState(): FilterUiState {
        val snapshot = FilterTree.capture(draft)
        return FilterUiState(
            nodes = buildNodes(draft, snapshot, baseline, FilterPath.Root),
            activeConstraints = FilterTree.activeConstraints(baseline, snapshot, query),
            savedSearches = searches,
            sourceQuery = query,
            revision = revision,
        )
    }

    private fun buildNodes(
        filters: List<*>,
        snapshot: FilterTreeSnapshot,
        defaults: FilterTreeSnapshot,
        parent: FilterPath,
    ): List<FilterUiNode> = filters.mapIndexedNotNull { index, candidate ->
        val filter = candidate as? Filter<*> ?: return@mapIndexedNotNull null
        val path = parent.child(index)
        val node = snapshot.nodes.getOrNull(index) ?: return@mapIndexedNotNull null
        val default = defaults.nodes.getOrNull(index)?.takeIf { it.kind == node.kind }
        val children = if (filter is Filter.Group<*> && node.value is FilterValueSnapshot.Group) {
            buildNodes(
                filters = filter.state,
                snapshot = FilterTreeSnapshot(node.value.children),
                defaults = FilterTreeSnapshot(
                    (default?.value as? FilterValueSnapshot.Group)?.children.orEmpty(),
                ),
                parent = path,
            )
        } else {
            emptyList()
        }
        FilterUiNode(
            path = path,
            name = filter.name,
            kind = filter.kind(),
            value = node.value,
            baselineValue = default?.value,
            options = when (filter) {
                is Filter.Select<*> -> filter.values.mapIndexed { optionIndex, value ->
                    FilterUiOption(optionIndex, value.toString())
                }
                is Filter.Sort -> filter.values.mapIndexed(::FilterUiOption)
                is Filter.AutoComplete -> filter.values.mapIndexed(::FilterUiOption)
                else -> emptyList()
            },
            hint = (filter as? Filter.AutoComplete)?.hint.orEmpty(),
            validPrefixes = (filter as? Filter.AutoComplete)?.validPrefixes.orEmpty(),
            children = children,
        )
    }
}
