package dev.ahmedmohamed.hayai.adult.eh.settings

import dev.ahmedmohamed.hayai.adult.eh.domain.EhCategory
import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EhPreferencesTest {
    @Test
    fun `category exclusions use SY order and converge`() {
        val store = MemoryPreferenceStore()
        val preferences = EhPreferences(store)
        val excluded = linkedSetOf(EhCategory.Doujinshi, EhCategory.NonH, EhCategory.Misc)

        preferences.setExcludedCategories(excluded)

        assertEquals(excluded, preferences.excludedCategories())
        assertEquals(
            "true,false,false,false,false,true,false,false,false,true",
            store.value<String>("eh_enabled_categories"),
        )
    }

    @Test
    fun `malformed category preference safely shows all`() {
        val preferences = EhPreferences(MemoryPreferenceStore("eh_enabled_categories" to "true,false"))

        assertTrue(preferences.excludedCategories().isEmpty())
    }

    @Test
    fun `language matrix round trips in SY order without Japanese original`() {
        val store = MemoryPreferenceStore()
        val preferences = EhPreferences(store)
        val selections = EhLanguage.entries.associateWithTo(linkedMapOf()) { language ->
            EhLanguageSelection(original = true, translated = language == EhLanguage.Japanese, rewritten = language == EhLanguage.Other)
        }

        preferences.setLanguageSelections(selections)

        val restored = preferences.languageSelections()
        assertEquals(false, restored.getValue(EhLanguage.Japanese).original)
        assertEquals(true, restored.getValue(EhLanguage.Japanese).translated)
        assertEquals(true, restored.getValue(EhLanguage.English).original)
        assertEquals(true, restored.getValue(EhLanguage.Other).rewritten)
        assertEquals(17, store.value<String>("eh_settings_languages")?.lines()?.size)
    }

    @Test
    fun `malformed language matrix safely disables every language filter`() {
        val preferences = EhPreferences(MemoryPreferenceStore("eh_settings_languages" to "true*false"))

        assertTrue(preferences.languageSelections().values.all { it == EhLanguageSelection() })
    }
}

private class MemoryPreferenceStore(
    vararg initial: Pair<String, Any>,
) : PreferenceStore {
    private val values = initial.toMap().toMutableMap()
    private val preferences = mutableMapOf<String, MemoryPreference<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> value(key: String): T? = values[key] as? T

    override fun getString(key: String, defaultValue: String): Preference<String> = preference(key, defaultValue)
    override fun getLong(key: String, defaultValue: Long): Preference<Long> = preference(key, defaultValue)
    override fun getInt(key: String, defaultValue: Int): Preference<Int> = preference(key, defaultValue)
    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = preference(key, defaultValue)
    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = preference(key, defaultValue)
    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = preference(key, defaultValue)
    override fun <T> getObject(key: String, defaultValue: T, serializer: (T) -> String, deserializer: (String) -> T): Preference<T> =
        preference(key, defaultValue)
    override fun getAll(): Map<String, *> = values.toMap()

    @Suppress("UNCHECKED_CAST")
    private fun <T> preference(key: String, defaultValue: T): Preference<T> =
        preferences.getOrPut(key) {
            MemoryPreference(
                key,
                defaultValue,
                { values[key] as? T ?: defaultValue },
                { values[key] = it as Any },
                { values.remove(key) },
                { key in values },
            )
        } as Preference<T>
}

private class MemoryPreference<T>(
    private val key: String,
    private val defaultValue: T,
    private val read: () -> T,
    private val write: (T) -> Unit,
    private val remove: () -> Unit,
    private val contains: () -> Boolean,
) : Preference<T> {
    private val state = MutableStateFlow(read())
    override fun key(): String = key
    override fun get(): T = read()
    override fun set(value: T) { write(value); state.value = value }
    override fun isSet(): Boolean = contains()
    override fun delete() { remove(); state.value = defaultValue }
    override fun defaultValue(): T = defaultValue
    override fun changes(): Flow<T> = state
    override fun stateIn(scope: CoroutineScope): StateFlow<T> = state
}
