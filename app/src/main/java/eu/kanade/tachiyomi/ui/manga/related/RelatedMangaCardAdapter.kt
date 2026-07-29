package eu.kanade.tachiyomi.ui.manga.related

import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.domain.manga.models.Manga
import uy.kohesive.injekt.injectLazy
import yokai.domain.ui.UiPreferences

class RelatedMangaCardAdapter(val clickListener: OnMangaClickListener, val compact: Boolean = false) :
    FlexibleAdapter<RelatedMangaCardItem>(null, clickListener, true) {

    private val uiPreferences: UiPreferences by injectLazy()
    val showOutlines = uiPreferences.outlineOnCovers().get()

    interface OnMangaClickListener {
        fun onMangaClick(manga: Manga)
    }
}
