package yokai.domain.source.browse.filter

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/** Immutable representation of the complete refinement used by source browse. */
data class RefinementSnapshot(
    val query: String,
    val filters: FilterTreeSnapshot,
)

data class FilterTreeSnapshot(val nodes: List<FilterNodeSnapshot>)

data class FilterNodeSnapshot(
    val name: String,
    val kind: FilterKind,
    val value: FilterValueSnapshot,
)

enum class FilterKind {
    HEADER,
    SEPARATOR,
    SELECT,
    TEXT,
    CHECKBOX,
    TRI_STATE,
    GROUP,
    AUTO_COMPLETE,
    SORT,
}

sealed interface FilterValueSnapshot {
    data object Stateless : FilterValueSnapshot
    data class Index(val index: Int, val label: String?) : FilterValueSnapshot
    data class Text(val value: String) : FilterValueSnapshot
    data class Checked(val value: Boolean) : FilterValueSnapshot
    data class TriState(val value: Int) : FilterValueSnapshot
    data class Group(val children: List<FilterNodeSnapshot>) : FilterValueSnapshot
    data class AutoComplete(val values: List<String>) : FilterValueSnapshot
    data class Sort(val index: Int?, val ascending: Boolean?, val label: String?) : FilterValueSnapshot
}

data class FilterPath(val indices: List<Int>) {
    fun child(index: Int) = FilterPath(indices + index)

    companion object {
        val Root = FilterPath(emptyList())
    }
}

enum class ConstraintSemantic { QUERY, VALUE, INCLUDE, EXCLUDE, SORT }

sealed interface ConstraintRemoval {
    data class ResetPath(val path: FilterPath) : ConstraintRemoval
    data class RemoveAutoCompleteValue(val path: FilterPath, val value: String) : ConstraintRemoval
    data object ClearQuery : ConstraintRemoval
}

data class ActiveConstraint(
    val path: FilterPath?,
    val breadcrumb: List<String>,
    val label: String,
    val value: String,
    val semantic: ConstraintSemantic,
    val removal: ConstraintRemoval,
)

/**
 * Recursive, source-default-aware operations for filter trees.
 *
 * The source API exposes mutable filter instances. Browse code should move those instances only
 * across the source boundary; UI and persistence compare immutable snapshots instead.
 */
object FilterTree {

    fun capture(filters: FilterList): FilterTreeSnapshot =
        FilterTreeSnapshot(filters.map(::captureNode))

    fun capture(filter: Filter<*>): FilterNodeSnapshot = captureNode(filter)

    fun materialize(snapshot: FilterTreeSnapshot, freshFilters: () -> FilterList): FilterList =
        freshFilters().also { apply(snapshot, it) }

    fun apply(snapshot: FilterTreeSnapshot, filters: FilterList) {
        snapshot.nodes.zip(filters).forEach { (node, filter) -> apply(node, filter) }
    }

    fun apply(snapshot: FilterNodeSnapshot, filter: Filter<*>): Boolean = applyNode(snapshot, filter)

    fun resetPath(filters: FilterList, baseline: FilterTreeSnapshot, path: FilterPath): Boolean {
        val target = findFilter(filters, path) ?: return false
        val default = findNode(baseline, path) ?: return false
        return applyNode(default, target)
    }

    fun removeAutoCompleteValue(filters: FilterList, path: FilterPath, value: String): Boolean {
        val filter = findFilter(filters, path) as? Filter.AutoComplete ?: return false
        if (value !in filter.state) return false
        filter.state = filter.state - value
        return true
    }

    fun findFilter(filters: FilterList, path: FilterPath): Filter<*>? {
        var children: List<*> = filters
        var current: Filter<*>? = null
        path.indices.forEach { index ->
            current = children.getOrNull(index) as? Filter<*> ?: return null
            children = (current as? Filter.Group<*>)?.state ?: emptyList<Any?>()
        }
        return current
    }

    fun indexedFilters(filters: FilterList): List<Pair<FilterPath, Filter<*>>> = buildList {
        fun visit(children: List<*>, parent: FilterPath) {
            children.forEachIndexed { index, candidate ->
                val filter = candidate as? Filter<*> ?: return@forEachIndexed
                val path = parent.child(index)
                add(path to filter)
                if (filter is Filter.Group<*>) visit(filter.state, path)
            }
        }
        visit(filters, FilterPath.Root)
    }

    fun activeConstraints(
        baseline: FilterTreeSnapshot,
        current: FilterTreeSnapshot,
        query: String,
    ): List<ActiveConstraint> = buildList {
        if (query.isNotBlank()) {
            add(
                ActiveConstraint(
                    path = null,
                    breadcrumb = emptyList(),
                    label = "Search",
                    value = query,
                    semantic = ConstraintSemantic.QUERY,
                    removal = ConstraintRemoval.ClearQuery,
                ),
            )
        }
        collectConstraints(baseline.nodes, current.nodes, FilterPath.Root, emptyList(), this)
    }

    private fun captureNode(filter: Filter<*>): FilterNodeSnapshot = FilterNodeSnapshot(
        name = filter.name,
        kind = filter.kind(),
        value = when (filter) {
            is Filter.Header, is Filter.Separator -> FilterValueSnapshot.Stateless
            is Filter.Select<*> -> FilterValueSnapshot.Index(
                index = filter.state,
                label = filter.values.getOrNull(filter.state)?.toString(),
            )
            is Filter.Text -> FilterValueSnapshot.Text(filter.state)
            is Filter.CheckBox -> FilterValueSnapshot.Checked(filter.state)
            is Filter.TriState -> FilterValueSnapshot.TriState(filter.state)
            is Filter.Group<*> -> FilterValueSnapshot.Group(
                filter.state.mapNotNull { (it as? Filter<*>)?.let(::captureNode) },
            )
            is Filter.AutoComplete -> FilterValueSnapshot.AutoComplete(filter.state.toList())
            is Filter.Sort -> FilterValueSnapshot.Sort(
                index = filter.state?.index,
                ascending = filter.state?.ascending,
                label = filter.state?.index?.let(filter.values::getOrNull),
            )
        },
    )

    private fun applyNode(snapshot: FilterNodeSnapshot, filter: Filter<*>): Boolean {
        if (snapshot.kind != filter.kind()) return false
        return when {
            filter is Filter.Select<*> && snapshot.value is FilterValueSnapshot.Index -> {
                val value = snapshot.value
                val index = value.index.takeIf {
                    it in filter.values.indices &&
                        (value.label == null || filter.values[it].toString() == value.label)
                }
                    ?: value.label?.let { label -> filter.values.indexOfFirst { it.toString() == label } }
                        ?.takeIf { it >= 0 }
                    ?: return false
                filter.state = index
                true
            }
            filter is Filter.Text && snapshot.value is FilterValueSnapshot.Text -> {
                filter.state = snapshot.value.value
                true
            }
            filter is Filter.CheckBox && snapshot.value is FilterValueSnapshot.Checked -> {
                filter.state = snapshot.value.value
                true
            }
            filter is Filter.TriState && snapshot.value is FilterValueSnapshot.TriState -> {
                filter.state = snapshot.value.value
                true
            }
            filter is Filter.Group<*> && snapshot.value is FilterValueSnapshot.Group -> {
                snapshot.value.children.zip(filter.state).forEach { (child, target) ->
                    (target as? Filter<*>)?.let { applyNode(child, it) }
                }
                true
            }
            filter is Filter.AutoComplete && snapshot.value is FilterValueSnapshot.AutoComplete -> {
                filter.state = snapshot.value.values.toList()
                true
            }
            filter is Filter.Sort && snapshot.value is FilterValueSnapshot.Sort -> {
                val value = snapshot.value
                if (value.index == null || value.ascending == null) {
                    filter.state = null
                    true
                } else {
                    val index = value.index.takeIf {
                        it in filter.values.indices &&
                            (value.label == null || filter.values[it] == value.label)
                    }
                        ?: value.label?.let { filter.values.indexOf(it) }?.takeIf { it >= 0 }
                        ?: return false
                    filter.state = Filter.Sort.Selection(index, value.ascending)
                    true
                }
            }
            snapshot.value is FilterValueSnapshot.Stateless -> true
            else -> false
        }
    }

    fun findNode(tree: FilterTreeSnapshot, path: FilterPath): FilterNodeSnapshot? {
        var children = tree.nodes
        var current: FilterNodeSnapshot? = null
        path.indices.forEach { index ->
            val node = children.getOrNull(index) ?: return null
            current = node
            children = (node.value as? FilterValueSnapshot.Group)?.children ?: emptyList()
        }
        return current
    }

    private fun collectConstraints(
        baseline: List<FilterNodeSnapshot>,
        current: List<FilterNodeSnapshot>,
        parentPath: FilterPath,
        parents: List<String>,
        destination: MutableList<ActiveConstraint>,
    ) {
        current.forEachIndexed { index, node ->
            val default = baseline.getOrNull(index)?.takeIf { it.kind == node.kind }
            val path = parentPath.child(index)
            if (node.value is FilterValueSnapshot.Group) {
                collectConstraints(
                    baseline = (default?.value as? FilterValueSnapshot.Group)?.children.orEmpty(),
                    current = node.value.children,
                    parentPath = path,
                    parents = parents + node.name,
                    destination = destination,
                )
                return@forEachIndexed
            }
            if (default?.value == node.value || node.value is FilterValueSnapshot.Stateless) return@forEachIndexed

            val breadcrumb = parents + node.name
            when (val value = node.value) {
                is FilterValueSnapshot.AutoComplete -> {
                    val defaultValues = (default?.value as? FilterValueSnapshot.AutoComplete)?.values.orEmpty()
                    value.values.filterNot(defaultValues::contains).forEach { entry ->
                        val excluded = entry.startsWith("-")
                        destination += ActiveConstraint(
                            path = path,
                            breadcrumb = breadcrumb,
                            label = node.name,
                            value = entry,
                            semantic = if (excluded) ConstraintSemantic.EXCLUDE else ConstraintSemantic.INCLUDE,
                            removal = ConstraintRemoval.RemoveAutoCompleteValue(path, entry),
                        )
                    }
                    if (value.values.any { it !in defaultValues }) return@forEachIndexed
                }
                else -> Unit
            }

            val rendered = if (node.value is FilterValueSnapshot.TriState) {
                node.name
            } else {
                node.value.displayValue().takeIf(String::isNotBlank) ?: node.name
            }
            destination += ActiveConstraint(
                path = path,
                breadcrumb = breadcrumb,
                label = node.name,
                value = rendered,
                semantic = when (node.value) {
                    is FilterValueSnapshot.TriState -> when (node.value.value) {
                        Filter.TriState.STATE_INCLUDE -> ConstraintSemantic.INCLUDE
                        Filter.TriState.STATE_EXCLUDE -> ConstraintSemantic.EXCLUDE
                        else -> ConstraintSemantic.VALUE
                    }
                    is FilterValueSnapshot.Sort -> ConstraintSemantic.SORT
                    else -> ConstraintSemantic.VALUE
                },
                removal = ConstraintRemoval.ResetPath(path),
            )
        }
    }
}

fun Filter<*>.kind(): FilterKind = when (this) {
    is Filter.Header -> FilterKind.HEADER
    is Filter.Separator -> FilterKind.SEPARATOR
    is Filter.Select<*> -> FilterKind.SELECT
    is Filter.Text -> FilterKind.TEXT
    is Filter.CheckBox -> FilterKind.CHECKBOX
    is Filter.TriState -> FilterKind.TRI_STATE
    is Filter.Group<*> -> FilterKind.GROUP
    is Filter.AutoComplete -> FilterKind.AUTO_COMPLETE
    is Filter.Sort -> FilterKind.SORT
}

fun FilterValueSnapshot.displayValue(): String = when (this) {
    FilterValueSnapshot.Stateless -> ""
    is FilterValueSnapshot.Index -> label ?: index.toString()
    is FilterValueSnapshot.Text -> value
    is FilterValueSnapshot.Checked -> if (value) "On" else "Off"
    is FilterValueSnapshot.TriState -> when (value) {
        Filter.TriState.STATE_INCLUDE -> "Include"
        Filter.TriState.STATE_EXCLUDE -> "Exclude"
        else -> "Neutral"
    }
    is FilterValueSnapshot.Group -> ""
    is FilterValueSnapshot.AutoComplete -> values.joinToString()
    is FilterValueSnapshot.Sort -> label?.let { if (ascending == true) "$it ↑" else "$it ↓" }.orEmpty()
}
