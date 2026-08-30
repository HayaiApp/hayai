package dev.ahmedmohamed.hayai.novel.integration

import com.pushtorefresh.storio.sqlite.queries.RawQuery
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.isNovelSource
import java.util.Locale

/** Resolves manga and novel identity from live capabilities plus durable Hayai evidence. */
class NovelContentIdentity internal constructor(
    private val sourceIsNovel: (Long) -> Boolean,
    private val hasDurableNovelEvidence: (Manga) -> Boolean,
) {
    constructor(
        database: DatabaseHelper,
        sourceManager: SourceManager,
    ) : this(
        sourceIsNovel = { sourceId -> sourceManager.getOrStub(sourceId).isNovelSource() },
        hasDurableNovelEvidence = DatabaseNovelIdentity(database)::matches,
    )

    fun isNovel(manga: Manga): Boolean = sourceIsNovel(manga.source) || hasDurableNovelEvidence(manga)

    fun contentKind(manga: Manga): ContentKind = if (isNovel(manga)) ContentKind.Novel else ContentKind.Manga
}

private class DatabaseNovelIdentity(
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
