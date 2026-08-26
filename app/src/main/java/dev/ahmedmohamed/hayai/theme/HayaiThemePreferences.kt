package dev.ahmedmohamed.hayai.theme

import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferenceStore

class HayaiThemePreferences(
    store: PreferenceStore,
) {
    val customSeedEnabled: Preference<Boolean> =
        store.getBoolean(KEY_CUSTOM_SEED_ENABLED, false)
    val customSeedColor: Preference<Int> =
        store.getInt(KEY_CUSTOM_SEED_COLOR, DEFAULT_SEED_COLOR)

    companion object {
        const val KEY_CUSTOM_SEED_ENABLED = "hayai_theme_custom_seed_enabled"
        const val KEY_CUSTOM_SEED_COLOR = "hayai_theme_custom_seed_color"
        const val DEFAULT_SEED_COLOR = 0xFF1C67E9.toInt()
    }
}
