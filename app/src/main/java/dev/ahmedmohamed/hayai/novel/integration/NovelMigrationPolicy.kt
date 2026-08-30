package dev.ahmedmohamed.hayai.novel.integration

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.isNovelSource

class NovelMigrationPolicy internal constructor(
    private val mangaLookup: (Long) -> Manga?,
    private val contentIdentity: NovelContentIdentity,
) {
    constructor(
        database: DatabaseHelper,
        contentIdentity: NovelContentIdentity,
    ) : this(
        mangaLookup = { mangaId -> database.getManga(mangaId).executeAsBlocking() },
        contentIdentity = contentIdentity,
    )

    fun contentKind(manga: Manga): ContentKind = contentIdentity.contentKind(manga)

    fun contentKind(mangaIds: Collection<Long>): ContentKind? {
        if (mangaIds.isEmpty()) return null
        val kinds =
            mangaIds
                .distinct()
                .mapNotNull(mangaLookup)
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

    fun accepts(source: CatalogueSource): Boolean = accepts(source.isNovelSource())

    fun accepts(extension: Extension): Boolean =
        when (extension) {
            is Extension.Installed -> {
                val declaredKinds = extension.sources.map { it.isNovelSource() }.toSet()
                if (declaredKinds.isEmpty()) accepts(extension.isNovel) else (this == Novel) in declaredKinds
            }
            else -> accepts(extension.isNovel)
        }

    companion object {
        fun fromPosition(position: Int): ContentKind = entries.getOrElse(position) { Manga }
    }
}
