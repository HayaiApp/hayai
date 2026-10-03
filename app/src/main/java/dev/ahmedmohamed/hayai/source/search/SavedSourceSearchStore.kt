package dev.ahmedmohamed.hayai.source.search

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.Serializable

@Serializable
data class SavedSourceSearch(
    val id: String,
    val sourceId: Long,
    val name: String,
    val query: String,
    val filters: List<SavedFilterState>,
)

@Serializable
data class SavedFilterState(
    val path: List<Int>,
    val kind: String,
    val value: String,
    val extra: Boolean = false,
)

object SavedSourceFilterCodec {
    fun capture(filters: FilterList): List<SavedFilterState> = buildList {
        filters.forEachIndexed { index, filter -> capture(filter, listOf(index), this) }
    }

    fun restore(filters: FilterList, states: List<SavedFilterState>): Int {
        var restored = 0
        states.forEach { saved ->
            val filter = resolve(filters, saved.path) ?: return@forEach
            val applied = when {
                saved.kind == "check" && filter is Filter.CheckBox -> saved.value.toBooleanStrictOrNull()?.let { filter.state = it; true }
                saved.kind == "tri" && filter is Filter.TriState -> saved.value.toIntOrNull()?.takeIf { it in 0..2 }?.let { filter.state = it; true }
                saved.kind == "text" && filter is Filter.Text -> saved.value.take(MAX_TEXT_LENGTH).let { filter.state = it; true }
                saved.kind == "select" && filter is Filter.Select<*> -> saved.value.toIntOrNull()?.takeIf { it in filter.values.indices }?.let { filter.state = it; true }
                saved.kind == "sort" && filter is Filter.Sort -> saved.value.toIntOrNull()?.takeIf { it in filter.values.indices }?.let { filter.state = Filter.Sort.Selection(it, saved.extra); true }
                saved.kind == "sort-none" && filter is Filter.Sort -> true.also { filter.state = null }
                else -> null
            } == true
            if (applied) restored++
        }
        return restored
    }

    private fun capture(filter: Filter<*>, path: List<Int>, result: MutableList<SavedFilterState>) {
        when (filter) {
            is Filter.CheckBox -> result += SavedFilterState(path, "check", filter.state.toString())
            is Filter.TriState -> result += SavedFilterState(path, "tri", filter.state.toString())
            is Filter.Text -> result += SavedFilterState(path, "text", filter.state.take(MAX_TEXT_LENGTH))
            is Filter.Select<*> -> result += SavedFilterState(path, "select", filter.state.toString())
            is Filter.Sort -> {
                val selection = filter.state
                result += if (selection == null) SavedFilterState(path, "sort-none", "") else SavedFilterState(path, "sort", selection.index.toString(), selection.ascending)
            }
            is Filter.Group<*> -> filter.state.forEachIndexed { index, child -> (child as? Filter<*>)?.let { capture(it, path + index, result) } }
            else -> Unit
        }
    }

    private fun resolve(filters: FilterList, path: List<Int>): Filter<*>? {
        if (path.isEmpty()) return null
        var filter: Filter<*> = filters.getOrNull(path.first()) ?: return null
        path.drop(1).forEach { index ->
            filter = (filter as? Filter.Group<*>)?.state?.getOrNull(index) as? Filter<*> ?: return null
        }
        return filter
    }

    private const val MAX_TEXT_LENGTH = 8_000
}
