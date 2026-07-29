package eu.kanade.tachiyomi.ui.manga.related

import eu.kanade.tachiyomi.domain.manga.models.Manga

/**
 * Holds the related-manga fetch state for a manga's details screen. Rendered inline as part of
 * [eu.kanade.tachiyomi.ui.manga.MangaHeaderHolder] rather than as a separate scrollable header.
 */
class RelatedMangaHeaderItem(val mangaId: Long) {
    var isLoading = false
    var mangas: List<Manga> = emptyList()
}
