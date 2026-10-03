package dev.ahmedmohamed.hayai.novel.search

import android.content.Context
import dev.ahmedmohamed.hayai.novel.download.NovelDownloadStore
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelSource
import dev.ahmedmohamed.hayai.novel.statistics.NovelTextStatistics
import dev.ahmedmohamed.hayai.novel.statistics.NovelWordDensity
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.chapter.ChapterSort
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

internal sealed interface NovelStoredChapterText {
    data class Available(val document: NovelDocument) : NovelStoredChapterText
    data object Missing : NovelStoredChapterText
    data object Unreadable : NovelStoredChapterText
}

internal data class NovelChapterTextResult(val chapter: Chapter, val matches: NovelTextMatches)
internal data class NovelEntryTextSearchResult(val matches: List<NovelChapterTextResult>, val searched: Int, val missing: Int, val unreadable: Int)

internal class NovelChapterTextLibrary(
    private val chapters: List<Chapter>,
    private val read: suspend (Chapter) -> NovelStoredChapterText,
) {
    constructor(context: Context, manga: Manga, database: DatabaseHelper, sourceManager: SourceManager) : this(
        ChapterSort(manga).getChaptersSorted(database.getChapters(manga).executeAsBlocking(), andFiltered = false),
        NovelDownloadStore(File(context.filesDir, "hayai/novel-downloads")),
        manga.source,
        (sourceManager.get(manga.source) as? LocalNovelSource)?.let { local -> { chapter: Chapter -> local.getChapterDocument(chapter) } },
    )

    constructor(chapters: List<Chapter>, store: NovelDownloadStore, sourceId: Long, localDocument: (suspend (Chapter) -> NovelDocument)? = null) : this(chapters, reader(store, sourceId, localDocument))

    suspend fun search(pattern: Pattern): NovelEntryTextSearchResult {
        val found = mutableListOf<NovelChapterTextResult>()
        var searched = 0
        var missing = 0
        var unreadable = 0
        val coroutine = currentCoroutineContext()
        chapters.forEach { chapter ->
            coroutine.ensureActive()
            when (val content = read(chapter)) {
                is NovelStoredChapterText.Available -> {
                    val text = NovelTextStatistics.readableText(content.document)
                    val matches = NovelChapterTextSearch.find(text, pattern) { coroutine.isActive }
                    searched++
                    if (matches.count > 0) found += NovelChapterTextResult(chapter, matches)
                }
                NovelStoredChapterText.Missing -> missing++
                NovelStoredChapterText.Unreadable -> unreadable++
            }
        }
        return NovelEntryTextSearchResult(found, searched, missing, unreadable)
    }

    suspend fun statistics(): NovelWordDensity {
        var words = 0L
        var counted = 0
        var unreadable = 0
        chapters.forEach { chapter ->
            currentCoroutineContext().ensureActive()
            when (val content = read(chapter)) {
                is NovelStoredChapterText.Available -> {
                    words += NovelTextStatistics.analyze(content.document).wordCount
                    counted++
                }
                NovelStoredChapterText.Missing -> Unit
                NovelStoredChapterText.Unreadable -> unreadable++
            }
        }
        return NovelWordDensity(words, counted, chapters.size, unreadable)
    }

    companion object {
        private fun reader(store: NovelDownloadStore, sourceId: Long, localDocument: (suspend (Chapter) -> NovelDocument)?): suspend (Chapter) -> NovelStoredChapterText {
            return { chapter ->
                try {
                    val stored = store.loadDocument(sourceId, chapter.url)
                    when {
                        stored != null -> NovelStoredChapterText.Available(stored)
                        store.hasStoredChapter(sourceId, chapter.url) -> NovelStoredChapterText.Unreadable
                        localDocument != null -> NovelStoredChapterText.Available(localDocument(chapter))
                        else -> NovelStoredChapterText.Missing
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    NovelStoredChapterText.Unreadable
                }
            }
        }
    }
}
