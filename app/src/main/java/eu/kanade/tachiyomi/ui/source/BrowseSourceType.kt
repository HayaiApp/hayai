package eu.kanade.tachiyomi.ui.source

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.isNovelSource
import yokai.i18n.MR

/**
 * The two top-level source families surfaced as Novel/Manga tabs in [BrowseController].
 *
 * Used to:
 *  - filter the catalogue list to only the active type
 *  - key the per-type "pinned" set ([eu.kanade.tachiyomi.data.preference.PreferencesHelper.pinnedNovelCatalogues] / [eu.kanade.tachiyomi.data.preference.PreferencesHelper.pinnedMangaCatalogues])
 *  - key the per-type "last used" source ([eu.kanade.tachiyomi.data.preference.PreferencesHelper.lastUsedNovelSource] / [eu.kanade.tachiyomi.data.preference.PreferencesHelper.lastUsedMangaSource])
 */
enum class BrowseSourceType(val stringRes: StringResource) {
    Manga(MR.strings.manga),
    Novel(MR.strings.novel),
    ;

    companion object {
        fun fromOrdinal(ordinal: Int): BrowseSourceType =
            entries.getOrNull(ordinal) ?: Manga
    }
}

/** Classifies a [CatalogueSource] into one of the [BrowseSourceType] tabs. */
val CatalogueSource.browseType: BrowseSourceType
    get() = if (isNovelSource()) BrowseSourceType.Novel else BrowseSourceType.Manga

/**
 * Per-type pinned catalogues preference selector. Avoids open-coding the same
 * `when (type)` switch in every read/write site (Browse list, openCatalogue, pinCatalogue,
 * SourcePresenter).
 */
fun PreferencesHelper.pinnedCataloguesFor(type: BrowseSourceType): Preference<Set<String>> =
    when (type) {
        BrowseSourceType.Manga -> pinnedMangaCatalogues()
        BrowseSourceType.Novel -> pinnedNovelCatalogues()
    }

/** Per-type last-used source-id preference selector. */
fun PreferencesHelper.lastUsedSourceFor(type: BrowseSourceType): Preference<Long> =
    when (type) {
        BrowseSourceType.Manga -> lastUsedMangaSource()
        BrowseSourceType.Novel -> lastUsedNovelSource()
    }
