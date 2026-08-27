package dev.ahmedmohamed.hayai.novel.integration

import com.pushtorefresh.storio.sqlite.queries.RawQuery
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.isNovelSource
import java.util.Locale

class NovelMigrationPolicy internal constructor(
    private val mangaLookup: (Long) -> Manga?,
    private val sourceIsNovel: (Long) -> Boolean,
    private val hasNovelIdentity: (Manga) -> Boolean,
) {
    constructor(
        database: DatabaseHelper,
        sourceManager: SourceManager,
    ) : this(
        mangaLookup = { mangaId -> database.getManga(mangaId).executeAsBlocking() },
        sourceIsNovel = { sourceId -> sourceManager.getOrStub(sourceId).isNovelSource() },
        hasNovelIdentity = DatabaseNovelMigrationIdentity(database)::matches,
    )

    fun contentKind(manga: Manga): ContentKind =
        if (sourceIsNovel(manga.source) || hasNovelIdentity(manga)) ContentKind.Novel else ContentKind.Manga

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

private class DatabaseNovelMigrationIdentity(
    private val database: DatabaseHelper,
) {
    fun matches(manga: Manga): Boolean {
        if (manga.hasNovelGenreIdentity()) return true
        val mangaId = manga.id ?: Long.MIN_VALUE
        return database
            .lowLevel()
            .rawQuery(
                RawQuery
                    .builder()
                    .query(
                        "SELECT 1 FROM hayai_novel_plugin_sources WHERE source_id = ? " +
                            "UNION ALL SELECT 1 FROM hayai_quotes WHERE manga_id = ? " +
                            "UNION ALL SELECT 1 FROM hayai_novel_highlights WHERE manga_id = ? " +
                            "UNION ALL SELECT 1 FROM hayai_novel_chapter_stats stats " +
                            "JOIN chapters ON chapters._id = stats.chapter_id WHERE chapters.manga_id = ? LIMIT 1",
                    ).args(manga.source, mangaId, mangaId, mangaId)
                    .build(),
            ).use { cursor -> cursor.moveToFirst() }
    }
}

internal fun Manga.hasNovelGenreIdentity(): Boolean =
    genre
        ?.split(',')
        ?.any { tag -> tag.trim().lowercase(Locale.US) in NOVEL_GENRES }
        ?: false

private val NOVEL_GENRES = setOf("novel", "light novel", "web novel")

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
