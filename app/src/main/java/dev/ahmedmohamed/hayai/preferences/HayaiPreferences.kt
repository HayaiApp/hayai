package dev.ahmedmohamed.hayai.preferences

import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferenceStore

class HayaiPreferences(
    private val store: PreferenceStore,
) {
    val hentaiFeaturesEnabled: Preference<Boolean> =
        store.getBoolean(KEY_HENTAI_FEATURES, true)

    val lewdLibraryFilter: Preference<Int> =
        store.getInt(KEY_LEWD_LIBRARY_FILTER, LewdLibraryFilter.Show.persistedValue)

    companion object {
        const val KEY_HENTAI_FEATURES = "eh_is_hentai_enabled"
        const val KEY_LEWD_LIBRARY_FILTER = "pref_filter_library_lewd_v2"
    }
}

enum class LewdLibraryFilter(
    val persistedValue: Int,
) {
    Show(0),
    Hide(1),
    Only(2),
    ;

    companion object {
        fun fromPersistedValue(value: Int): LewdLibraryFilter = entries.firstOrNull { it.persistedValue == value } ?: Show
    }
}
