package dev.ahmedmohamed.hayai.adult.eh.settings

import dev.ahmedmohamed.hayai.adult.eh.domain.EhCategory
import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferenceStore

class EhPreferences(
    store: PreferenceStore,
) {
    val imageQuality: Preference<String> = store.getString("ehentai_quality", "auto")
    val useHentaiAtHome: Preference<Int> = store.getInt("eh_enable_hah", 0)
    val useJapaneseTitle: Preference<Boolean> = store.getBoolean("use_jp_title", false)
    val useOriginalImages: Preference<Boolean> = store.getBoolean("eh_useOrigImages", false)
    val tagFilterThreshold: Preference<Int> = store.getInt("eh_tag_filtering_value", 0)
    val tagWatchingThreshold: Preference<Int> = store.getInt("eh_tag_watching_value", 0)
    val watchedListDefault: Preference<Boolean> = store.getBoolean("eh_watched_list_default_state", false)
    val enhancedView: Preference<Boolean> = store.getBoolean("enhanced_e_hentai_view", true)

    private val enabledCategories =
        store.getString(
            "eh_enabled_categories",
            List(EhCategory.entries.size) { "false" }.joinToString(","),
        )

    fun excludedCategories(): Set<EhCategory> {
        val values = enabledCategories.get().split(',')
        if (values.size != EhCategory.entries.size) return emptySet()
        return EhCategory.entries.filterIndexedTo(linkedSetOf()) { index, _ ->
            values[index].trim().equals("true", ignoreCase = true)
        }
    }

    fun setExcludedCategories(categories: Set<EhCategory>) {
        enabledCategories.set(EhCategory.entries.joinToString(",") { (it in categories).toString() })
    }
}
