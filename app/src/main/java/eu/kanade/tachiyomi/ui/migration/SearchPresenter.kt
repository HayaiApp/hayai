package eu.kanade.tachiyomi.ui.migration

import dev.ahmedmohamed.hayai.novel.integration.NovelMigrationPolicy
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchItem
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchMangaItem
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchPresenter
import uy.kohesive.injekt.injectLazy

class SearchPresenter(
    initialQuery: String? = "",
    private val manga: Manga,
    sources: List<CatalogueSource>? = null,
) : GlobalSearchPresenter(initialQuery, sourcesToUse = sources) {
    private val novelMigrationPolicy: NovelMigrationPolicy by injectLazy()

    override fun getEnabledSources(): List<CatalogueSource> {
        // Put the source of the selected manga at the top
        return novelMigrationPolicy
            .compatibleSources(manga, super.getEnabledSources())
            .sortedByDescending { it.id == manga.source }
    }

    override fun createCatalogueSearchItem(
        source: CatalogueSource,
        results: List<GlobalSearchMangaItem>?,
    ): GlobalSearchItem {
        // Set the catalogue search item as highlighted if the source matches that of the selected manga
        return GlobalSearchItem(source, results, source.id == manga.source)
    }
}
