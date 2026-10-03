package dev.ahmedmohamed.hayai.source.search

import eu.kanade.tachiyomi.data.preference.PreferenceKeys
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.source.searchhistory.FilterApplyResult
import eu.kanade.tachiyomi.ui.source.searchhistory.SavedFilter
import eu.kanade.tachiyomi.ui.source.searchhistory.SearchHistoryEntry
import eu.kanade.tachiyomi.ui.source.searchhistory.applyTo
import eu.kanade.tachiyomi.ui.source.searchhistory.diffFromDefault
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Migrates the old Hayai preference into J2K's saved-search owner without discarding unresolved presets. */
class SavedSourceSearchMigration(
    preferenceStore: PreferenceStore,
) {
    private val legacy = preferenceStore.getString(LEGACY_KEY, "[]")
    private val savedSearches = preferenceStore.getString(PreferenceKeys.savedSearches, "[]")

    fun migrate(
        sourceId: Long,
        defaultFilters: () -> FilterList,
        importedName: (String, Int) -> String,
    ): Int = synchronized(lock) {
        val original = legacy.get()
        val pending = runCatching { Json.parseToJsonElement(original) as? JsonArray }.getOrNull()
            ?: return@synchronized 0
        if (pending.isEmpty() || pending.size > MAX_LEGACY_SEARCHES) return@synchronized 0
        val decoded = pending.map { runCatching { Json.decodeFromJsonElement<SavedSourceSearch>(it) }.getOrNull() }
        val duplicateIds = decoded.filterNotNull().groupingBy { it.sourceId to it.id }.eachCount().filterValues { it > 1 }.keys
        val current = runCatching { Json.decodeFromString<List<SearchHistoryEntry>>(savedSearches.get()) }.getOrNull()
            ?: return@synchronized 0
        val updated = current.toMutableList()
        val resolved = mutableSetOf<Int>()
        val defaults by lazy { runCatching(defaultFilters).getOrNull() }

        decoded.forEachIndexed { index, old ->
            if (old == null || old.sourceId != sourceId || (old.sourceId to old.id) in duplicateIds) return@forEachIndexed
            if (old.id.isBlank() || old.id.length > 64 || old.name.isBlank() || old.name.length > 80 || old.query.length > 2_000) return@forEachIndexed
            val filters = defaults?.let { SavedSourceSearchFilterMigration.convert(old.filters, it) } ?: return@forEachIndexed
            // Upstream skips the apply callback for empty payloads; a blank query also has no submission to activate it.
            if (filters.isEmpty() && (old.query.isBlank() || SavedSourceFilterCodec.capture(requireNotNull(defaults)).isNotEmpty())) return@forEachIndexed
            val id = migratedId(old.sourceId, old.id)
            val existing = updated.find { it.id == id }
            if (existing != null) {
                // The target was written before a process stopped or the old backup was restored again.
                if (existing.sourceId == sourceId && existing.query == old.query && existing.filters == filters && existing.name != null) {
                    resolved += index
                }
                return@forEachIndexed
            }
            val sameName = updated.filter { it.sourceId == sourceId && it.name?.equals(old.name, ignoreCase = true) == true }
            if (sameName.any { it.query == old.query && it.filters == filters }) {
                resolved += index
                return@forEachIndexed
            }
            val name = if (sameName.isEmpty()) {
                old.name
            } else {
                (1..(updated.size + 1))
                    .map { importedName(old.name, it) }
                    .firstOrNull { candidate -> updated.none { it.sourceId == sourceId && it.name?.equals(candidate, ignoreCase = true) == true } }
                    ?: return@forEachIndexed
            }
            updated += SearchHistoryEntry(id = id, query = old.query, filters = filters, sourceId = sourceId, name = name)
            resolved += index
        }

        if (resolved.isNotEmpty()) {
            // Write the canonical target first. Deterministic identities make a retry safe if cleanup is interrupted.
            if (updated != current) savedSearches.set(Json.encodeToString(updated))
            legacy.set(JsonArray(pending.filterIndexed { index, _ -> index !in resolved }).toString())
        }
        updated.size - current.size
    }

    companion object {
        const val LEGACY_KEY = "hayai_saved_source_searches_v1"
        private const val MAX_LEGACY_SEARCHES = 100
        private val lock = Any()

        internal fun migratedId(sourceId: Long, oldId: String): Long {
            val digest = MessageDigest.getInstance("SHA-256").digest("$sourceId\u0000$oldId".toByteArray(Charsets.UTF_8))
            return (ByteBuffer.wrap(digest).long and Long.MAX_VALUE) or (1L shl 62)
        }
    }
}

internal object SavedSourceSearchFilterMigration {
    fun convert(states: List<SavedFilterState>, defaults: FilterList): List<SavedFilter>? {
        if (states.map { it.path }.distinct().size != states.size) return null
        val restored = defaults.copyForMigration()
        if (SavedSourceFilterCodec.restore(restored, states) != states.size) return null
        val restoredStates = SavedSourceFilterCodec.capture(restored)
        val byPath = restoredStates.associateBy { it.path }
        if (states.any { byPath[it.path] != it }) return null
        // Include representable defaults so upstream invokes its apply callback and resets any active filters.
        val converted = restored.diffFromDefault(FilterList())
        val replayed = defaults.copyForMigration()
        if (converted.applyTo(replayed, strict = true) != FilterApplyResult.ALL) return null
        if (SavedSourceFilterCodec.capture(replayed) != restoredStates) return null
        return converted
    }

    private fun FilterList.copyForMigration(): FilterList = FilterList(map { it.copyForMigration() })

    private fun Filter<*>.copyForMigration(): Filter<*> = when (this) {
        is Filter.CheckBox -> object : Filter.CheckBox(name, state) {}
        is Filter.TriState -> object : Filter.TriState(name, state) {}
        is Filter.Text -> object : Filter.Text(name, state) {}
        is Filter.Select<*> -> object : Filter.Select<Any?>(name, values.map { it }.toTypedArray(), state) {}
        is Filter.Sort -> object : Filter.Sort(name, values.copyOf(), state?.copy()) {}
        is Filter.Group<*> -> object : Filter.Group<Any?>(name, state.map { (it as? Filter<*>)?.copyForMigration() ?: it }) {}
        is Filter.Header -> Filter.Header(name)
        is Filter.Separator -> Filter.Separator(name)
    }
}
