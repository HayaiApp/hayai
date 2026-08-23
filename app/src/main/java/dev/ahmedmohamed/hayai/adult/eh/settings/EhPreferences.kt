package dev.ahmedmohamed.hayai.adult.eh.settings

import dev.ahmedmohamed.hayai.adult.eh.domain.EhCategory
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhConflictPolicy
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoritesSyncMode
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhSyncRequest
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
    /** SY's enhanced E-Hentai browse list. */
    val enhancedBrowseView: Preference<Boolean> = store.getBoolean("enhanced_e_hentai_view", true)
    /** Hayai's details metadata and page-preview section. */
    val enhancedGalleryDetails: Preference<Boolean> = store.getBoolean("hayai_eh_enhanced_gallery_details", true)
    val showSettingsUploadWarning: Preference<Boolean> = store.getBoolean("eh_showSettingsUploadWarning2", true)
    val favoritesReadOnly: Preference<Boolean> = store.getBoolean("eh_sync_read_only", false)
    val favoritesLenient: Preference<Boolean> = store.getBoolean("eh_lenient_sync", false)
    val favoritesConflictPolicy: Preference<String> = store.getString("hayai_eh_favorites_conflict_policy", "stop")

    private val settingsLanguages = store.getString("eh_settings_languages", DEFAULT_LANGUAGES)
    private val ehAppliedFingerprint = store.getString("hayai_eh_remote_settings_fingerprint_eh", "")
    private val exhAppliedFingerprint = store.getString("hayai_eh_remote_settings_fingerprint_exh", "")

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

    fun languageSelections(): Map<EhLanguage, EhLanguageSelection> {
        val rows = settingsLanguages.get().lines()
        if (rows.size != EhLanguage.entries.size) return defaultLanguages()
        val parsed = linkedMapOf<EhLanguage, EhLanguageSelection>()
        EhLanguage.entries.forEachIndexed { index, language ->
            val values = rows[index].split('*')
            if (values.size != 3 || values.any { it !in setOf("true", "false") }) return defaultLanguages()
            parsed[language] =
                EhLanguageSelection(
                    original = language.originalCode != null && values[0].toBoolean(),
                    translated = values[1].toBoolean(),
                    rewritten = values[2].toBoolean(),
                )
        }
        return parsed
    }

    fun setLanguageSelections(selections: Map<EhLanguage, EhLanguageSelection>) {
        require(selections.keys == EhLanguage.entries.toSet())
        settingsLanguages.set(
            EhLanguage.entries.joinToString("\n") { language ->
                val selection = selections.getValue(language)
                listOf(
                    language.originalCode != null && selection.original,
                    selection.translated,
                    selection.rewritten,
                ).joinToString("*")
            },
        )
    }

    fun remoteSettings(): EhRemoteSettings =
        EhRemoteSettings(
            imageQuality = EhImageQuality.fromPreference(imageQuality.get()),
            hentaiAtHome = EhHentaiAtHome.fromPreference(useHentaiAtHome.get()),
            japaneseTitles = useJapaneseTitle.get(),
            originalImages = useOriginalImages.get(),
            tagFilterThreshold = tagFilterThreshold.get().coerceIn(-9999, 0),
            tagWatchingThreshold = tagWatchingThreshold.get().coerceIn(0, 9999),
            languages = languageSelections(),
            excludedCategories = excludedCategories(),
        )

    fun appliedFingerprint(site: EhSite): String =
        when (site) {
            EhSite.EHentai -> ehAppliedFingerprint.get()
            EhSite.ExHentai -> exhAppliedFingerprint.get()
        }

    fun markRemoteSettingsApplied(site: EhSite, fingerprint: String) {
        require(fingerprint.length == 64 && fingerprint.all { it.isDigit() || it in 'a'..'f' })
        when (site) {
            EhSite.EHentai -> ehAppliedFingerprint.set(fingerprint)
            EhSite.ExHentai -> exhAppliedFingerprint.set(fingerprint)
        }
    }

    fun clearRemoteSettingsApplied() {
        ehAppliedFingerprint.delete()
        exhAppliedFingerprint.delete()
    }

    fun hasPendingRemoteSettings(site: EhSite): Boolean = appliedFingerprint(site) != remoteSettings().fingerprint()

    fun favoritesSyncRequest(): EhSyncRequest =
        EhSyncRequest(
            mode = if (favoritesReadOnly.get()) EhFavoritesSyncMode.RemoteOnly else EhFavoritesSyncMode.Bidirectional,
            conflictPolicy = when (favoritesConflictPolicy.get()) {
                "remote" -> EhConflictPolicy.PreferRemote
                "local" -> EhConflictPolicy.PreferLocal
                else -> EhConflictPolicy.StopForReview
            },
            lenient = favoritesLenient.get(),
        )

    private fun defaultLanguages(): Map<EhLanguage, EhLanguageSelection> =
        EhLanguage.entries.associateWithTo(linkedMapOf()) { EhLanguageSelection() }

    private companion object {
        val DEFAULT_LANGUAGES = List(EhLanguage.entries.size) { "false*false*false" }.joinToString("\n")
    }
}
