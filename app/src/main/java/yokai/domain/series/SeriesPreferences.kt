package yokai.domain.series

import eu.kanade.tachiyomi.core.preference.PreferenceStore
import yokai.domain.series.model.SeriesDisplaySection
import yokai.domain.series.model.TranslationMode

class SeriesPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun translationMode() = preferenceStore.getString("series_translation_mode", TranslationMode.SIMPLE.dbKey)
    fun advancedTranslationModel() = preferenceStore.getString("series_advanced_translation_model", "")
    fun advancedTranslationAutoCanon() = preferenceStore.getBoolean("series_advanced_translation_auto_canon", true)

    fun displaySection(section: SeriesDisplaySection) =
        preferenceStore.getBoolean("series_display_${section.key}", section.defaultVisible)
}
