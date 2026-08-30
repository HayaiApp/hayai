package dev.ahmedmohamed.hayai.novel.integration

import com.pushtorefresh.storio.sqlite.queries.RawQuery
import dev.ahmedmohamed.hayai.novel.statistics.NovelChapterStatStore
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlin.math.ceil

/**
 * Deep module exposing novel presentation facts to small J2K adapters.
 * J2K remains authoritative for titles, chapters, history, and progress.
 */
class NovelJ2kIntegration(
    database: DatabaseHelper,
    private val contentIdentity: NovelContentIdentity,
) {
    private val stats = NovelChapterStatStore(database)
    private val database = database

    fun isNovel(manga: Manga): Boolean = contentIdentity.isNovel(manga)

    fun chapterPresentations(
        manga: Manga,
        chapters: Collection<Chapter>,
    ): Map<Long, NovelChapterPresentation> {
        if (!isNovel(manga)) return emptyMap()
        val ids = chapters.mapNotNull(Chapter::id)
        val counts = stats.getMany(ids)
        return chapters.mapNotNull { chapter ->
            val id = chapter.id ?: return@mapNotNull null
            val statistic = counts[id]
            val progress = if (chapter.read) 100 else chapter.last_page_read.coerceIn(0, 100)
            id to NovelChapterPresentation(
                progressPercent = progress,
                wordCount = statistic?.wordCount,
                estimatedMinutes = statistic?.estimatedMinutes(),
                remainingMinutes = statistic?.remainingMinutes(progress),
            )
        }.toMap()
    }

    fun libraryStatistics(mangas: Collection<LibraryManga>): NovelLibraryStatistics {
        val novels = mangas.distinctBy(LibraryManga::id).filter(::isNovel)
        val mangaIds = novels.mapNotNull(LibraryManga::id)
        var measuredChapters = 0L
        var totalWords = 0L
        var wordsRead = 0L
        mangaIds.chunked(MAX_QUERY_ARGUMENTS).forEach { ids ->
            if (ids.isEmpty()) return@forEach
            val placeholders = ids.joinToString(",") { "?" }
            database.lowLevel().rawQuery(
                RawQuery.builder().query(
                    "SELECT COUNT(*), COALESCE(SUM(s.word_count), 0), " +
                        "COALESCE(SUM(CASE WHEN c.read = 1 THEN s.word_count " +
                        "ELSE (s.word_count * MIN(MAX(c.last_page_read, 0), 100) / 100) END), 0) " +
                        "FROM hayai_novel_chapter_stats s JOIN chapters c ON c._id = s.chapter_id " +
                        "WHERE c.manga_id IN ($placeholders)",
                ).args(*ids.toTypedArray()).build(),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    measuredChapters += cursor.getLong(0)
                    totalWords += cursor.getLong(1)
                    wordsRead += cursor.getLong(2)
                }
            }
        }
        return NovelLibraryStatistics(
            titles = novels.size,
            chapters = novels.sumOf { it.totalChapters.toLong() },
            chaptersRead = novels.sumOf { it.read.toLong() },
            measuredChapters = measuredChapters,
            totalWords = totalWords,
            wordsRead = wordsRead,
            estimatedMinutes = minutesFor(totalWords),
            minutesRead = minutesFor(wordsRead),
        )
    }

    private fun minutesFor(words: Long): Long =
        if (words <= 0) 0 else ceil(words.toDouble() / DEFAULT_WORDS_PER_MINUTE).toLong().coerceAtLeast(1)

    private companion object {
        const val DEFAULT_WORDS_PER_MINUTE = 250
        const val MAX_QUERY_ARGUMENTS = 900
    }
}

data class NovelChapterPresentation(
    val progressPercent: Int,
    val wordCount: Long?,
    val estimatedMinutes: Int?,
    val remainingMinutes: Int?,
)

data class NovelLibraryStatistics(
    val titles: Int,
    val chapters: Long,
    val chaptersRead: Long,
    val measuredChapters: Long,
    val totalWords: Long,
    val wordsRead: Long,
    val estimatedMinutes: Long,
    val minutesRead: Long,
)
