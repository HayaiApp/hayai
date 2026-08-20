package dev.ahmedmohamed.hayai.novel.reader

import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import dev.ahmedmohamed.hayai.novel.source.NovelDocumentLoader
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.chapter.ChapterSort

internal class NovelReaderSession(
    private val database: DatabaseHelper,
    private val sourceManager: SourceManager,
) {
    lateinit var manga: Manga
        private set
    lateinit var source: Source
        private set
    private lateinit var chapters: List<Chapter>
    private var chapterIndex = -1

    val currentChapter: Chapter
        get() = chapters[chapterIndex]
    val hasPrevious: Boolean
        get() = chapterIndex > 0
    val hasNext: Boolean
        get() = chapterIndex in 0 until chapters.lastIndex

    suspend fun initialize(
        mangaId: Long,
        chapterId: Long,
    ): LoadedNovelChapter {
        manga = requireNotNull(database.getManga(mangaId).executeAsBlocking()) { "Novel not found" }
        source =
            requireNotNull(sourceManager.get(manga.source)) { "Novel source is not installed" }
                .takeIf { it.isNovelSource }
                ?: error("This source does not provide novel text")

        val databaseChapters = database.getChapters(manga).executeAsBlocking()
        val selected = requireNotNull(databaseChapters.find { it.id == chapterId }) { "Novel chapter not found" }
        chapters = ChapterSort(manga).getChaptersSorted(databaseChapters, filterForReader = true, currentChapter = selected)
        chapterIndex = chapters.indexOfFirst { it.id == chapterId }
        if (chapterIndex < 0) {
            chapters = ChapterSort(manga).getChaptersSorted(databaseChapters, andFiltered = false)
            chapterIndex = chapters.indexOfFirst { it.id == chapterId }
        }
        require(chapterIndex >= 0) { "Novel chapter is unavailable with the current filters" }
        return loadCurrent()
    }

    suspend fun previous(): LoadedNovelChapter? {
        if (!hasPrevious) return null
        chapterIndex -= 1
        return loadCurrent()
    }

    suspend fun next(): LoadedNovelChapter? {
        if (!hasNext) return null
        chapterIndex += 1
        return loadCurrent()
    }

    fun saveProgress(
        chapter: Chapter,
        progress: Int,
        markReadAt: Int,
    ) {
        NovelProgress.apply(chapter, progress, markReadAt)
        database.updateChapterProgress(chapter).executeAsBlocking()
        database
            .upsertHistoryLastRead(
                History.create(chapter).apply { last_read = System.currentTimeMillis() },
            ).executeAsBlocking()
    }

    private suspend fun loadCurrent(): LoadedNovelChapter {
        val chapter = currentChapter
        val document = NovelDocumentLoader.load(source, chapter)
        database
            .upsertHistoryLastRead(
                History.create(chapter).apply { last_read = System.currentTimeMillis() },
            ).executeAsBlocking()
        return LoadedNovelChapter(
            manga = manga,
            chapter = chapter,
            document = document,
            position = chapterIndex,
            total = chapters.size,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
        )
    }
}

internal object NovelProgress {
    fun apply(
        chapter: Chapter,
        progress: Int,
        markReadAt: Int,
    ) {
        chapter.last_page_read = progress.coerceIn(0, 100)
        chapter.pages_left = 100 - chapter.last_page_read
        if (chapter.last_page_read >= markReadAt.coerceIn(1, 100)) {
            chapter.read = true
            chapter.pages_left = 0
        }
    }

    fun reset(chapter: Chapter) {
        chapter.last_page_read = 0
        chapter.pages_left = 100
        chapter.read = false
    }
}

internal data class LoadedNovelChapter(
    val manga: Manga,
    val chapter: Chapter,
    val document: NovelDocument,
    val position: Int,
    val total: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)
