package dev.ahmedmohamed.hayai.novel.integration

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.isNovelSource

class NovelMigrationPolicy(
    private val database: DatabaseHelper,
    private val sourceManager: SourceManager,
) {
    fun contentKind(manga: Manga): ContentKind =
        if (sourceManager.getOrStub(manga.source).isNovelSource()) ContentKind.Novel else ContentKind.Manga

    fun contentKind(mangaIds: Collection<Long>): ContentKind? {
        if (mangaIds.isEmpty()) return null
        val kinds =
            mangaIds
                .distinct()
                .mapNotNull { database.getManga(it).executeAsBlocking() }
                .map(::contentKind)
                .distinct()
        return kinds.singleOrNull()
    }

    fun <T : CatalogueSource> compatibleSources(
        mangaIds: Collection<Long>,
        sources: List<T>,
    ): List<T> {
        val kind = contentKind(mangaIds) ?: return sources
        return sources.filter { source -> kind.accepts(source.isNovelSource()) }
    }

    fun <T : CatalogueSource> compatibleSources(
        manga: Manga,
        sources: List<T>,
    ): List<T> {
        val kind = contentKind(manga)
        return sources.filter { source -> kind.accepts(source.isNovelSource()) }
    }
}

enum class ContentKind {
    Manga,
    Novel,
    ;

    fun accepts(isNovelSource: Boolean): Boolean = (this == Novel) == isNovelSource
}
