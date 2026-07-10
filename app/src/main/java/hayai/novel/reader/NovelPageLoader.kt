package hayai.novel.reader

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * PageLoader for online novel chapters.
 * Fetches chapter text via Source.fetchPageText() and serves it
 * as a single-page stream (UTF-8 encoded HTML).
 */
interface NovelImageUrlResolver {
    fun resolveNovelImageUrl(url: String): String
}

class NovelPageLoader(
    private val chapter: ReaderChapter,
    private val source: Source,
) : PageLoader(), NovelImageUrlResolver {

    override val isLocal: Boolean = false

    override suspend fun getPages(): List<ReaderPage> {
        return listOf(ReaderPage(index = 0, url = resolveSourceUrl(source, chapter.chapter.url)).apply {
            this.chapter = this@NovelPageLoader.chapter
        })
    }

    override suspend fun loadPage(page: ReaderPage) {
        if (page.status == Page.State.Ready) return

        page.status = Page.State.LoadPage
        try {
            val html = source.fetchPageText(Page(0, chapter.chapter.url))
            val bytes = html.toByteArray(Charsets.UTF_8)
            page.text = html
            page.stream = { ByteArrayInputStream(bytes) }
            page.status = Page.State.Ready
        } catch (e: Throwable) {
            // Cancellation is not a load failure — leaving page.status = Error after a cancelled
            // fetch causes the next bind to flash "Failed to load pages" before the relaunched
            // load updates state.
            if (e is CancellationException) throw e
            page.status = Page.State.Error(e)
            Logger.e(e) { "NovelPageLoader: Failed to load chapter ${chapter.chapter.url}" }
        }
    }

    override fun retryPage(page: ReaderPage) {
        page.status = Page.State.Queue
    }

    override fun resolveNovelImageUrl(url: String): String {
        return resolveSourceUrl(source, url)
    }
}

internal fun resolveSourceUrl(source: Source, url: String): String {
    runCatching { source.getChapterUrl(SChapter.create().apply { this.url = url }) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return runCatching { URI(source.webViewUrl.orEmpty()).resolve(url).toString() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: url
}
