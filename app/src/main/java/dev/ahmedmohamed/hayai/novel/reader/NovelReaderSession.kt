package dev.ahmedmohamed.hayai.novel.reader

import dev.ahmedmohamed.hayai.novel.download.NovelAssetReferences
import dev.ahmedmohamed.hayai.novel.download.NovelDownloadResult
import dev.ahmedmohamed.hayai.novel.download.NovelDownloadStore
import dev.ahmedmohamed.hayai.novel.source.NovelAssetProvider
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import dev.ahmedmohamed.hayai.novel.source.NovelDocumentLoader
import dev.ahmedmohamed.hayai.novel.source.NovelSource
import dev.ahmedmohamed.hayai.novel.statistics.NovelChapterStatStore
import dev.ahmedmohamed.hayai.novel.statistics.NovelChapterStatistics
import dev.ahmedmohamed.hayai.novel.statistics.NovelStatisticsResolver
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.ChapterSort
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

internal class NovelReaderSession(
    private val database: DatabaseHelper,
    private val sourceManager: SourceManager,
    private val downloadStore: NovelDownloadStore,
    private val network: NetworkHelper,
) : NovelAssetProvider {
    private val chapterStatStore = NovelChapterStatStore(database)
    lateinit var manga: Manga
        private set
    lateinit var source: Source
        private set
    private lateinit var chapters: List<Chapter>
    private var chapterIndex = -1
    private var offlineChapterUrl: String? = null
    private var recordHistory = true
    private val prefetchedDocuments =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, NovelDocument>(8, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NovelDocument>?): Boolean = size > MAX_PREFETCHED_DOCUMENTS
            },
        )

    val currentChapter: Chapter
        get() = chapters[chapterIndex]
    val hasPrevious: Boolean
        get() = chapterIndex > 0
    val hasNext: Boolean
        get() = chapterIndex in 0 until chapters.lastIndex

    suspend fun initialize(
        mangaId: Long,
        chapterId: Long,
        recordHistory: Boolean = true,
    ): LoadedNovelChapter {
        this.recordHistory = recordHistory
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
        if (recordHistory) {
            database
                .upsertHistoryLastRead(
                    History.create(chapter).apply { last_read = System.currentTimeMillis() },
                ).executeAsBlocking()
        }
    }

    suspend fun saveOffline(chapter: LoadedNovelChapter): NovelDownloadResult =
        downloadStore.save(source.id, chapter.chapter.url, chapter.document) { reference ->
            openDownloadAsset(chapter, reference)
        }

    fun removeOffline(chapter: LoadedNovelChapter) {
        check(downloadStore.remove(source.id, chapter.chapter.url)) { "The offline copy no longer exists." }
    }

    suspend fun reload(): LoadedNovelChapter = loadCurrent()

    suspend fun prefetchAdjacent(radius: Int) {
        val bounded = radius.coerceIn(0, MAX_PREFETCHED_DOCUMENTS / 2)
        if (bounded == 0 || chapterIndex !in chapters.indices) return
        val indices = (1..bounded).flatMap { distance -> listOf(chapterIndex - distance, chapterIndex + distance) }
        for (index in indices) {
            val chapter = chapters.getOrNull(index) ?: continue
            if (prefetchedDocuments.containsKey(chapter.url) || downloadStore.contains(source.id, chapter.url)) continue
            val document =
                try {
                    (source as? NovelSource)?.getChapterDocument(chapter) ?: NovelDocumentLoader.load(source, chapter)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    continue
                }
            prefetchedDocuments[chapter.url] = document
        }
    }

    override suspend fun getChapterAsset(
        chapterUrl: String,
        assetPath: String,
    ): InputStream? =
        downloadStore.openAsset(source.id, chapterUrl, assetPath)
            ?: if (chapterUrl == offlineChapterUrl) null else (source as? NovelAssetProvider)?.getChapterAsset(chapterUrl, assetPath)

    fun isOffline(chapterUrl: String): Boolean = chapterUrl == offlineChapterUrl

    private suspend fun loadCurrent(): LoadedNovelChapter {
        val chapter = currentChapter
        val offlineDocument = downloadStore.loadDocument(source.id, chapter.url)
        offlineChapterUrl = chapter.url.takeIf { offlineDocument != null }
        val document =
            offlineDocument
                ?: prefetchedDocuments.remove(chapter.url)
                ?: (source as? NovelSource)?.getChapterDocument(chapter)
                ?: NovelDocumentLoader.load(source, chapter)
        val chapterId = requireNotNull(chapter.id)
        // Derived analytics must never make otherwise readable content unavailable.
        val statistics =
            NovelStatisticsResolver.resolve(document, persisted = { chapterStatStore.get(chapterId) })
        runCatching { chapterStatStore.store(chapterId, statistics) }
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
            isDownloaded = offlineDocument != null,
            statistics = statistics,
        )
    }

    private suspend fun openDownloadAsset(
        chapter: LoadedNovelChapter,
        reference: String,
    ): InputStream? {
        val provider = source as? NovelAssetProvider
        val providerPath = NovelAssetReferences.providerPath(reference) ?: return null
        try {
            provider?.getChapterAsset(chapter.chapter.url, providerPath)?.let { return it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Remote references can still use the source's authenticated HTTP path below.
        }
        if (NovelAssetReferences.isSourceAsset(reference)) return null

        val url =
            reference.toHttpUrlOrNull()
                ?: chapter.document.baseUrl?.toHttpUrlOrNull()?.resolve(reference)
                ?: return null
        (source as? HttpSource)?.let { httpSource ->
            val response = httpSource.getImage(Page(index = 0, url = url.toString(), imageUrl = url.toString()))
            return response.body.byteStream()
        }
        val response = network.client.newCall(Request.Builder().url(url).get().build()).execute()
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        return response.body.byteStream()
    }

    private companion object {
        const val MAX_PREFETCHED_DOCUMENTS = 10
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
    val isDownloaded: Boolean,
    val statistics: NovelChapterStatistics,
)
