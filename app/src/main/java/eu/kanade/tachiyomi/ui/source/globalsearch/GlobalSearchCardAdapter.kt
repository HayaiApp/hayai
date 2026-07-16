package eu.kanade.tachiyomi.ui.source.globalsearch

import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import yokai.util.koin.injectLazy
import yokai.domain.ui.UiPreferences

/**
 * Adapter that holds the manga items from search results.
 *
 * @param controller instance of [GlobalSearchController].
 */
class GlobalSearchCardAdapter(val controller: GlobalSearchController) :
    FlexibleAdapter<GlobalSearchMangaItem>(null, controller, true) {

    /**
     * Listen for browse item clicks.
     */
    val mangaClickListener: OnMangaClickListener = controller
    private val uiPreferences: UiPreferences by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()
    val showOutlines = uiPreferences.outlineOnCovers().get()

    /**
     * Listener which should be called when user clicks browse.
     * Note: Should only be handled by [GlobalSearchController]
     */
    interface OnMangaClickListener {
        fun onMangaClick(manga: Manga)
        fun onMangaLongClick(position: Int, adapter: GlobalSearchCardAdapter)
    }
}
