package hayai.novel.reader

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.File

/**
 * PageLoader for downloaded novel chapters.
 * Reads chapter.html from the download directory.
 */
class NovelDownloadPageLoader(
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: Source,
    private val downloadProvider: DownloadProvider,
) : PageLoader(), NovelImageUrlResolver {

    override val isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        return listOf(ReaderPage(index = 0, url = resolveSourceUrl(source, chapter.chapter.url)).apply {
            this.chapter = this@NovelDownloadPageLoader.chapter
        })
    }

    override suspend fun loadPage(page: ReaderPage) {
        if (page.status == Page.State.Ready) return

        page.status = Page.State.LoadPage
        try {
            val chapterDir = downloadProvider.findChapterDir(
                chapter.chapter,
                manga,
                source,
            )
            val htmlFile = chapterDir?.findFile(Downloader.NOVEL_CHAPTER_FILE)?.takeIf { it.isFile }
                ?: throw Exception("Downloaded chapter file not found")

            val bytes = htmlFile.openInputStream().readBytes()
            page.text = bytes.toString(Charsets.UTF_8)
            page.stream = { ByteArrayInputStream(bytes) }
            page.status = Page.State.Ready
        } catch (e: Throwable) {
            // Cancellation is not a load failure — leaving page.status = Error after a cancelled
            // fetch causes the next bind to flash "Failed to load pages" before the relaunched
            // load updates state.
            if (e is CancellationException) throw e
            page.status = Page.State.Error(e)
            Logger.e(e) { "NovelDownloadPageLoader: Failed to load chapter ${chapter.chapter.url}" }
        }
    }

    override fun retryPage(page: ReaderPage) {
        page.status = Page.State.Queue
    }

    override fun resolveNovelImageUrl(url: String): String {
        return resolveSourceUrl(source, url)
    }
}
