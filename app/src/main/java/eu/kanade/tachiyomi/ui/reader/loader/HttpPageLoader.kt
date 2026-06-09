package eu.kanade.tachiyomi.ui.reader.loader

import yokai.util.koin.get
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.withIOContext
import exh.source.ExhPreferences
import exh.util.DataSaver
import exh.util.DataSaver.Companion.getImage
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Loader used to load chapters from an online source.
 */
class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
    private val chapterCache: ChapterCache = get(),
    private val preferences: PreferencesHelper = get(),
    private val exhPreferences: ExhPreferences = get(),
) : PageLoader() {

    override val isLocal: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A queue used to manage requests one by one while allowing priorities.
     */
    private val queue = PriorityBlockingQueue<PriorityPage>()

    private val preloadSize get() = preferences.preloadSize().get()

    // EH / ExH benefit from parallel page loading because every page requires an extra HTTP
    // round-trip to resolve its image URL (per-page HTML scrape or showpage API). Other
    // sources keep the single-worker behaviour because most of them ship their own
    // RateLimitInterceptor and would just serialise at the OkHttp layer anyway.
    private val workerCount: Int = if (source is EHentai) {
        exhPreferences.ehReaderThreads.get().coerceIn(1, 8)
    } else {
        1
    }

    init {
        // Spawn N worker coroutines, each draining the shared PriorityBlockingQueue.
        // The queue is thread-safe and the per-page `Page.State.Queue` check prevents
        // double-processing of the same page (a page enqueued twice via preloadNextPages
        // is filtered out by the second worker because the first worker mutates state to
        // LoadPage before releasing).
        repeat(workerCount) {
            scope.launchIO {
                flow {
                    while (true) {
                        emit(runInterruptible { queue.take() }.page)
                    }
                }
                    .filter { it.status is Page.State.Queue }
                    .collect {
                        _loadPage(it)
                    }
            }
        }
    }

    /**
     * Recycles this loader and the active subscriptions and queue.
     */
    override fun recycle() {
        super.recycle()
        scope.cancel()
        queue.clear()

        // Cache current page list progress for online chapters to allow a faster reopen
        // Novel text lives on Page.text, which is transient. Reusing the image page-list
        // cache would reopen novel chapters with text=null, so novel sources always fetch fresh.
        if (source.isNovelSource()) return
        chapter.pages?.let { pages ->
            launchIO {
                try {
                    // Convert to pages without reader information
                    val pagesToSave = pages.map { Page(it.index, it.url, it.imageUrl) }
                    chapterCache.putPageListToCache(chapter.chapter, pagesToSave)
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Returns the page list for a chapter. It tries to return the page list from the local cache,
     * otherwise fallbacks to network.
     */
    override suspend fun getPages(): List<ReaderPage> {
        val pages = if (source.isNovelSource()) {
            source.getPageList(chapter.chapter)
        } else {
            try {
                chapterCache.getPageListFromCache(chapter.chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                source.getPageList(chapter.chapter)
            }
        }
        return pages.mapIndexed { index, page ->
            // Don't trust sources and use our own indexing
            ReaderPage(index, page.url, page.imageUrl, page.text)
        }
    }

    /**
     * Loads a page through the queue. Handles re-enqueueing pages if they were evicted from the cache.
     */
    override suspend fun loadPage(page: ReaderPage) = withIOContext {
        val imageUrl = page.imageUrl

        // Check if the image has been deleted
        if (page.status is Page.State.Ready && imageUrl != null && !chapterCache.isImageInCache(imageUrl)) {
            page.status = Page.State.Queue
        }

        // Automatically retry failed pages when subscribed to this page
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }

        val queuedPages = mutableListOf<PriorityPage>()
        if (page.status is Page.State.Queue) {
            queuedPages += PriorityPage(page, 1).also { queue.offer(it) }
        }
        queuedPages += preloadNextPages(page, preloadSize)

        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                queuedPages.forEach {
                    if (it.page.status is Page.State.Queue) {
                        queue.remove(it)
                    }
                }
            }
        }
    }

    /**
     * Preloads the given [amount] of pages after the [currentPage] with a lower priority.
     * @return a list of [PriorityPage] that were added to the [queue]
     */
    private fun preloadNextPages(currentPage: ReaderPage, amount: Int): List<PriorityPage> {
        val pageIndex = currentPage.index
        val pages = currentPage.chapter.pages ?: return emptyList()
        if (pageIndex == pages.lastIndex) return emptyList()

        return pages
            .subList(pageIndex + 1, min(pageIndex + 1 + amount, pages.size))
            .mapNotNull {
                if (it.status is Page.State.Queue) {
                    PriorityPage(it, 0).apply { queue.offer(this) }
                } else {
                    null
                }
            }
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     * Clears imageUrl so getImageUrl() runs again — this is critical for EHentai where
     * the page URL is modified with an &nl= parameter to switch to a different image server.
     */
    override fun retryPage(page: ReaderPage) {
        if (page.status is Page.State.Error) {
            // Clear imageUrl so _loadPage re-fetches the page HTML and gets the new server URL
            page.imageUrl = null
            page.status = Page.State.Queue
        }
        queue.offer(PriorityPage(page, 2))
    }

    /**
     * Data class used to keep ordering of pages in order to maintain priority.
     */
    private class PriorityPage(
        val page: ReaderPage,
        val priority: Int,
    ) : Comparable<PriorityPage> {
        companion object {
            private val idGenerator = AtomicInteger()
        }

        private val identifier = idGenerator.incrementAndGet()

        override fun compareTo(other: PriorityPage): Int {
            val p = other.priority.compareTo(priority)
            return if (p != 0) p else identifier.compareTo(other.identifier)
        }
    }

    /**
     * Loads the page, retrieving the image URL and downloading the image if necessary.
     * Downloaded images are stored in the chapter cache.
     *
     * @param page the page whose source image has to be downloaded.
     */
    private suspend fun _loadPage(page: ReaderPage) {
        try {
            if (source.isNovelSource() && page.imageUrl.isNullOrEmpty()) {
                page.status = Page.State.LoadPage
                page.text = page.text ?: source.fetchPageText(page)
                page.status = Page.State.Ready
                return
            }

            if (page.imageUrl.isNullOrEmpty()) {
                page.status = Page.State.LoadPage
                page.imageUrl = source.getImageUrl(page)
            }
            val imageUrl = page.imageUrl!!

            if (!chapterCache.isImageInCache(imageUrl)) {
                page.status = Page.State.DownloadImage
                val dataSaver = DataSaver.fromPreferences(exhPreferences)
                val imageResponse = source.getImage(page, dataSaver)
                chapterCache.putImageToCache(imageUrl, imageResponse)
            }

            page.stream = { chapterCache.getImageFile(imageUrl).inputStream() }
            page.status = Page.State.Ready
        } catch (e: Throwable) {
            page.status = Page.State.Error(e)
            if (e is CancellationException) {
                throw e
            }
        }
    }
}
