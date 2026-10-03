package dev.ahmedmohamed.hayai.source.search

import dev.ahmedmohamed.hayai.testing.MemoryPreferenceStore
import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferenceKeys
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.source.searchhistory.FilterApplyResult
import eu.kanade.tachiyomi.ui.source.searchhistory.SearchHistoryEntry
import eu.kanade.tachiyomi.ui.source.searchhistory.applyTo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSourceSearchMigrationTest {
    @Test
    fun `complete query and nested filter state move to the upstream owner without mutating source filters`() {
        val defaults = filters(changed = false)
        val before = SavedSourceFilterCodec.capture(defaults)
        val desired = filters(changed = true)
        val old = preset(query = "  exact query  ", filters = SavedSourceFilterCodec.capture(desired))
        val store = store(old)

        assertEquals(1, migrate(store) { defaults })

        val target = saved(store).single()
        assertEquals(old.name, target.name)
        assertEquals(old.query, target.query)
        assertEquals(old.sourceId, target.sourceId)
        assertFalse(target.showOnAllSources)
        assertTrue(target.id > 0)
        val replayed = filters(changed = false)
        assertEquals(FilterApplyResult.ALL, target.filters.applyTo(replayed, strict = true))
        assertEquals(SavedSourceFilterCodec.capture(desired), SavedSourceFilterCodec.capture(replayed))
        assertEquals(before, SavedSourceFilterCodec.capture(defaults))
        assertEquals("[]", pending(store))
        assertEquals(0, migrate(store) { defaults })
        assertEquals(listOf(target), saved(store))
    }

    @Test
    fun `query only searches migrate with empty source filters`() {
        val store = store(preset())

        assertEquals(1, migrate(store))
        assertEquals("query", saved(store).single().query)
        assertEquals(emptyList<Any>(), saved(store).single().filters)
        assertEquals("[]", pending(store))
    }

    @Test
    fun `a default preset resets active nondefault controls through the upstream apply path`() {
        val defaults = filters(changed = false)
        val store = store(preset(filters = SavedSourceFilterCodec.capture(defaults)))

        assertEquals(1, migrate(store) { defaults })
        val target = saved(store).single()
        assertTrue(target.filters.isNotEmpty())
        val active = filters(changed = true)
        assertEquals(FilterApplyResult.ALL, target.filters.applyTo(active, strict = true))
        assertEquals(SavedSourceFilterCodec.capture(defaults), SavedSourceFilterCodec.capture(active))
        assertEquals("query", target.query)
        assertEquals("[]", pending(store))
    }

    @Test
    fun `blank query presets with controls have an activating reset payload`() {
        val defaults = filters(changed = false)
        val store = store(preset(query = "", filters = SavedSourceFilterCodec.capture(defaults)))

        assertEquals(1, migrate(store) { defaults })
        val target = saved(store).single()
        assertTrue(target.query.isBlank())
        assertTrue(target.filters.isNotEmpty())
        val active = filters(changed = true)
        assertEquals(FilterApplyResult.ALL, target.filters.applyTo(active, strict = true))
        assertEquals(SavedSourceFilterCodec.capture(defaults), SavedSourceFilterCodec.capture(active))
    }

    @Test
    fun `blank query presets without controls remain pending because upstream cannot activate them`() {
        val store = store(preset(query = ""))
        val original = pending(store)

        assertEquals(0, migrate(store))
        assertEquals(original, pending(store))
        assertTrue(saved(store).isEmpty())
    }

    @Test
    fun `a lone unset sort remains pending because an empty payload cannot reset an active sort`() {
        val store = store(preset(filters = listOf(SavedFilterState(listOf(0), "sort-none", ""))))
        val original = pending(store)

        assertEquals(0, migrate(store) { FilterList(Sort("Order", null)) })
        assertEquals(original, pending(store))
        assertTrue(saved(store).isEmpty())
    }

    @Test
    fun `stale positional schema leaves the entire original preset pending`() {
        val old = preset(filters = listOf(SavedFilterState(listOf(0), "select", "2")))
        val store = store(old)
        val original = pending(store)

        assertEquals(0, migrate(store) { FilterList(Choice("Rating", 0, arrayOf("Only"))) })
        assertEquals(original, pending(store))
        assertTrue(saved(store).isEmpty())
    }

    @Test
    fun `a cleared sort that upstream cannot represent remains recoverable`() {
        val old = preset(filters = listOf(SavedFilterState(listOf(0), "sort-none", "")))
        val store = store(old)
        val original = pending(store)
        val defaults = FilterList(Sort("Order", Filter.Sort.Selection(0, true)))

        assertEquals(0, migrate(store) { defaults })
        assertEquals(original, pending(store))
        assertEquals(Filter.Sort.Selection(0, true), (defaults[0] as Sort).state)
        assertTrue(saved(store).isEmpty())
    }

    @Test
    fun `duplicate filter names cannot silently change which filter is selected`() {
        val old = preset(filters = SavedSourceFilterCodec.capture(FilterList(Check("Same", false), Check("Same", true))))
        val store = store(old)
        val original = pending(store)

        assertEquals(0, migrate(store) { FilterList(Check("Same", false), Check("Same", false)) })
        assertEquals(original, pending(store))
        assertTrue(saved(store).isEmpty())
    }

    @Test
    fun `a conflicting upstream search keeps its contents and the imported search receives a unique name`() {
        val existing = SearchHistoryEntry(25L, "upstream query", sourceId = SOURCE_ID, name = "Favourite")
        val store = store(preset(name = "favourite"))
        store.getString(PreferenceKeys.savedSearches).set(Json.encodeToString(listOf(existing)))

        assertEquals(1, migrate(store))
        assertEquals(existing, saved(store).first())
        assertEquals("favourite (imported 1)", saved(store).last().name)
        assertEquals("query", saved(store).last().query)
        assertEquals("[]", pending(store))
    }

    @Test
    fun `another source may keep the same name without a migration rename`() {
        val existing = SearchHistoryEntry(25L, "other query", sourceId = SOURCE_ID + 1, name = "Favourite")
        val store = store(preset())
        store.getString(PreferenceKeys.savedSearches).set(Json.encodeToString(listOf(existing)))

        assertEquals(1, migrate(store))
        assertEquals(existing, saved(store).first())
        assertEquals("Favourite", saved(store).last().name)
    }

    @Test
    fun `an identical upstream entry is reused rather than duplicated`() {
        val existing = SearchHistoryEntry(25L, "query", sourceId = SOURCE_ID, name = "Favourite")
        val store = store(preset())
        val originalTarget = Json.encodeToString(listOf(existing))
        store.getString(PreferenceKeys.savedSearches).set(originalTarget)

        assertEquals(0, migrate(store))
        assertEquals(originalTarget, store.getString(PreferenceKeys.savedSearches).get())
        assertEquals("[]", pending(store))
    }

    @Test
    fun `a canonical id collision never overwrites either search`() {
        val old = preset()
        val existing = SearchHistoryEntry(SavedSourceSearchMigration.migratedId(old.sourceId, old.id), "different", sourceId = SOURCE_ID, name = "Other")
        val store = store(old)
        val original = pending(store)
        store.getString(PreferenceKeys.savedSearches).set(Json.encodeToString(listOf(existing)))

        assertEquals(0, migrate(store))
        assertEquals(listOf(existing), saved(store))
        assertEquals(original, pending(store))
    }

    @Test
    fun `an interrupted cleanup retries without another canonical entry`() {
        val backing = store(preset())
        val original = pending(backing)
        var interruptCleanup = true
        val interrupted = object : PreferenceStore by backing {
            override fun getString(key: String, defaultValue: String): Preference<String> {
                val preference = backing.getString(key, defaultValue)
                if (key != SavedSourceSearchMigration.LEGACY_KEY) return preference
                return object : Preference<String> by preference {
                    override fun set(value: String) {
                        if (interruptCleanup) {
                            interruptCleanup = false
                            error("Interrupted migration cleanup")
                        }
                        preference.set(value)
                    }
                }
            }
        }

        assertNotNull(runCatching { migrate(interrupted) }.exceptionOrNull())
        val target = saved(backing).single()
        assertEquals(original, pending(backing))
        assertEquals(0, migrate(interrupted))
        assertEquals(listOf(target), saved(backing))
        assertEquals("[]", pending(backing))
    }

    @Test
    fun `unknown malformed and unavailable source entries are preserved when a valid sibling migrates`() {
        val unknown = Json.parseToJsonElement("""{"id":"future","sourceId":7,"name":"Future","query":"q","filters":[],"newField":{"value":1}}""")
        val malformed = JsonPrimitive("unrecognized preset")
        val unavailable = Json.parseToJsonElement(Json.encodeToString(preset(id = "missing", sourceId = SOURCE_ID + 1)))
        val valid = Json.parseToJsonElement(Json.encodeToString(preset()))
        val store = MemoryPreferenceStore(SavedSourceSearchMigration.LEGACY_KEY to JsonArray(listOf(unknown, malformed, unavailable, valid)).toString())

        assertEquals(1, migrate(store))
        assertEquals(JsonArray(listOf(unknown, malformed, unavailable)), Json.parseToJsonElement(pending(store)))
        assertEquals(1, saved(store).size)
    }

    @Test
    fun `invalid JSON never replaces either preference with an empty list`() {
        val badLegacy = MemoryPreferenceStore(SavedSourceSearchMigration.LEGACY_KEY to "{broken")
        assertEquals(0, migrate(badLegacy))
        assertEquals("{broken", pending(badLegacy))

        val badTarget = store(preset())
        val original = pending(badTarget)
        badTarget.getString(PreferenceKeys.savedSearches).set("{broken")
        assertEquals(0, migrate(badTarget))
        assertEquals(original, pending(badTarget))
        assertEquals("{broken", badTarget.getString(PreferenceKeys.savedSearches).get())
    }

    @Test
    fun `an unknown canonical schema is not reserialized by the migration`() {
        val store = store(preset())
        val original = pending(store)
        val target = """[{"id":20,"query":"upstream","filters":[],"name":"New","futureField":true}]"""
        store.getString(PreferenceKeys.savedSearches).set(target)

        assertEquals(0, migrate(store))
        assertEquals(original, pending(store))
        assertEquals(target, store.getString(PreferenceKeys.savedSearches).get())
    }

    @Test
    fun `unavailable filter definitions do not change existing preferences`() {
        val store = store(preset())
        val original = pending(store)

        assertEquals(0, migrate(store) { error("Source cannot supply filters") })
        assertEquals(original, pending(store))
        assertTrue(saved(store).isEmpty())
    }

    @Test
    fun `migration remains bounded and preserves oversized legacy data for recovery`() {
        val store = store(*(1..101).map { preset(id = it.toString(), name = "Preset $it") }.toTypedArray())
        val original = pending(store)

        assertEquals(0, migrate(store))
        assertEquals(original, pending(store))
        assertTrue(saved(store).isEmpty())
    }

    @Test
    fun `duplicate legacy identities are preserved rather than collapsed`() {
        val store = store(preset(), preset(query = "other query"))
        val original = pending(store)

        assertEquals(0, migrate(store))
        assertEquals(original, pending(store))
        assertTrue(saved(store).isEmpty())
    }

    @Test
    fun `restoring app preference data keeps canonical and pending searches without duplicate import`() {
        val old = preset()
        val missing = preset(id = "missing", sourceId = SOURCE_ID + 1)
        val store = store(old, missing)
        assertEquals(1, migrate(store))
        val existing = saved(store).single()
        val restored = MemoryPreferenceStore(*store.getAll().map { it.key to requireNotNull(it.value) }.toTypedArray())

        assertEquals(0, migrate(restored))
        assertEquals(listOf(existing), saved(restored))
        assertEquals(listOf(missing), Json.decodeFromString<List<SavedSourceSearch>>(pending(restored)))
        // An older backup can restore the pre-conversion preference beside the new canonical list.
        restored.getString(SavedSourceSearchMigration.LEGACY_KEY).set(Json.encodeToString(listOf(old, missing)))
        assertEquals(0, migrate(restored))
        assertEquals(listOf(existing), saved(restored))
        assertEquals(listOf(missing), Json.decodeFromString<List<SavedSourceSearch>>(pending(restored)))
    }

    private fun migrate(store: PreferenceStore, defaults: () -> FilterList = { FilterList() }): Int =
        SavedSourceSearchMigration(store).migrate(SOURCE_ID, defaults) { name, number -> "$name (imported $number)" }

    private fun store(vararg old: SavedSourceSearch): MemoryPreferenceStore =
        MemoryPreferenceStore(SavedSourceSearchMigration.LEGACY_KEY to Json.encodeToString(old.toList()))

    private fun saved(store: PreferenceStore): List<SearchHistoryEntry> =
        Json.decodeFromString(store.getString(PreferenceKeys.savedSearches, "[]").get())

    private fun pending(store: PreferenceStore): String = store.getString(SavedSourceSearchMigration.LEGACY_KEY).get()

    private fun preset(
        id: String = "old-id",
        sourceId: Long = SOURCE_ID,
        name: String = "Favourite",
        query: String = "query",
        filters: List<SavedFilterState> = emptyList(),
    ): SavedSourceSearch = SavedSourceSearch(id, sourceId, name, query, filters)

    private fun filters(changed: Boolean): FilterList =
        FilterList(
            Check("Completed", changed),
            Tri("Status", if (changed) 2 else 0),
            Text("Author", if (changed) "Hayai" else ""),
            Choice("Rating", if (changed) 2 else 0),
            Sort("Order", Filter.Sort.Selection(if (changed) 1 else 0, !changed)),
            Group("Tags", listOf(Check("Adventure", changed), Group("Nested", listOf(Text("Fandom", if (changed) "Novel" else ""))))),
        )

    private class Check(name: String, state: Boolean) : Filter.CheckBox(name, state)
    private class Tri(name: String, state: Int) : Filter.TriState(name, state)
    private class Text(name: String, state: String) : Filter.Text(name, state)
    private class Choice(name: String, state: Int, values: Array<String> = arrayOf("A", "B", "C")) : Filter.Select<String>(name, values, state)
    private class Sort(name: String, state: Filter.Sort.Selection?) : Filter.Sort(name, arrayOf("Name", "Date"), state)
    private class Group(name: String, state: List<Filter<*>>) : Filter.Group<Filter<*>>(name, state)

    private companion object {
        const val SOURCE_ID = 7L
    }
}
