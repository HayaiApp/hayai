package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import dev.ahmedmohamed.hayai.novel.download.NovelAssetReferences
import dev.ahmedmohamed.hayai.novel.download.NovelDownloadStore
import dev.ahmedmohamed.hayai.novel.source.NovelAssetProvider
import dev.ahmedmohamed.hayai.novel.source.NovelContentType
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import dev.ahmedmohamed.hayai.novel.source.NovelDocumentLoader
import dev.ahmedmohamed.hayai.novel.source.NovelSource
import dev.ahmedmohamed.hayai.novel.statistics.NovelChapterStatStore
import dev.ahmedmohamed.hayai.novel.statistics.NovelChapterStatistics
import dev.ahmedmohamed.hayai.novel.statistics.NovelStatisticsResolver
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationLocator
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationSettingsStore
import dev.ahmedmohamed.hayai.novel.translation.SqliteNovelTranslationStore
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.io.InputStream

internal object NovelReaderIdentity {
    fun isNovel(source: Source): Boolean = source.isNovelSource()
}

internal data class NovelChapterContent(
    val manga: Manga,
    val chapter: Chapter,
    val source: Source,
    val document: NovelDocument,
    val isDownloaded: Boolean,
    val statistics: NovelChapterStatistics,
    val assets: NovelAssetProvider,
    val markReadThreshold: Int,
    val translatedOfflineLanguage: String? = null,
)

internal interface NovelOfflineAwareAssetProvider : NovelAssetProvider {
    fun setChapterOffline(chapterUrl: String, offline: Boolean)
}

internal class NovelProgressPage(
    progressPercent: Int,
    val content: NovelChapterContent,
) : ReaderPage(progressPercent.coerceIn(MIN_PROGRESS, MAX_PROGRESS)) {
    var presentation = NovelPagePresentation(progressPercent.coerceIn(MIN_PROGRESS, MAX_PROGRESS))

    init {
        status = Page.State.READY
    }

    companion object {
        const val MIN_PROGRESS = 0
        const val MAX_PROGRESS = 100
    }
}

internal class NovelChapterPageLoader(
    context: Context,
    manga: Manga,
    source: Source,
    chapter: ReaderChapter,
    private val repository: NovelDocumentRepository =
        NovelDocumentRepository(
            context = context,
            manga = manga,
            source = source,
            database = Injekt.get(),
            network = Injekt.get(),
            preferences = HayaiPreferences(Injekt.get<PreferenceStore>()),
        ),
) : PageLoader() {
    private val chapter = chapter.chapter
    private var pages: List<NovelProgressPage>? = null

    override suspend fun getPages(): List<ReaderPage> =
        pages ?: repository.load(chapter).let { content ->
            (NovelProgressPage.MIN_PROGRESS..NovelProgressPage.MAX_PROGRESS)
                .map { progress -> NovelProgressPage(progress, content) }
                .also { pages = it }
        }

    override suspend fun loadPage(page: ReaderPage) {
        require(page is NovelProgressPage) { "Novel loader received a non-novel page" }
        page.status = Page.State.READY
    }

    override fun recycle() {
        pages = null
        super.recycle()
    }
}

internal class NovelDocumentRepository(
    private val context: Context,
    private val manga: Manga,
    private val source: Source,
    private val database: DatabaseHelper,
    private val network: NetworkHelper,
    private val preferences: HayaiPreferences,
    private val downloadStore: NovelDownloadStore = NovelDownloadStore(File(context.filesDir, "hayai/novel-downloads")),
) : NovelOfflineAwareAssetProvider {
    private val statistics = NovelChapterStatStore(database)
    private val offlineChapterUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun load(chapter: Chapter): NovelChapterContent {
        check(NovelReaderIdentity.isNovel(source)) { "Source ${source.id} is not a novel source" }
        val offline = downloadStore.loadDocument(source.id, chapter.url)
        if (offline != null) offlineChapterUrls += chapter.url else offlineChapterUrls -= chapter.url
        val chapterId = requireNotNull(chapter.id) { "Novel chapter is missing its durable database identity" }
        val locator = NovelTranslationLocator(chapterId, source.id, manga.url, chapter.url)
        var translatedOfflineLanguage: String? = null
        val document =
            offline ?: try {
                (source as? NovelSource)?.getChapterDocument(chapter) ?: NovelDocumentLoader.load(source, chapter)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!error.allowsOfflineTranslationFallback()) throw error
                val targetLanguage = NovelTranslationSettingsStore(context).get().targetLanguage
                val translated =
                    SqliteNovelTranslationStore(database)
                        .findLatestCompletedForOfflineFallback(locator, targetLanguage)
                        ?: throw error
                translatedOfflineLanguage = translated.targetLanguage
                NovelDocument(translated.translatedContent, NovelContentType.PlainText)
            }
        val resolvedStatistics = NovelStatisticsResolver.resolve(document, persisted = { statistics.get(chapterId) })
        if (translatedOfflineLanguage == null) {
            runCatching { statistics.store(chapterId, resolvedStatistics) }
        }
        return NovelChapterContent(
            manga = manga,
            chapter = chapter,
            source = source,
            document = document,
            isDownloaded = offline != null,
            statistics = resolvedStatistics,
            assets = this,
            markReadThreshold = preferences.novelMarkAsReadThreshold.get().coerceIn(1, 100),
            translatedOfflineLanguage = translatedOfflineLanguage,
        )
    }

    override suspend fun getChapterAsset(
        chapterUrl: String,
        assetPath: String,
    ): InputStream? =
        downloadStore.openAsset(source.id, chapterUrl, assetPath)
            ?: if (chapterUrl in offlineChapterUrls) null else (source as? NovelAssetProvider)?.getChapterAsset(chapterUrl, assetPath)

    override fun setChapterOffline(chapterUrl: String, offline: Boolean) {
        if (offline) offlineChapterUrls += chapterUrl else offlineChapterUrls -= chapterUrl
    }

    suspend fun openDownloadAsset(
        content: NovelChapterContent,
        reference: String,
    ): InputStream? {
        val providerPath = NovelAssetReferences.providerPath(reference) ?: return null
        try {
            (source as? NovelAssetProvider)?.getChapterAsset(content.chapter.url, providerPath)?.let { return it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Authenticated HTTP loading below remains available for remote references.
        }
        if (NovelAssetReferences.isSourceAsset(reference)) return null
        val url =
            reference.toHttpUrlOrNull()
                ?: content.document.baseUrl?.toHttpUrlOrNull()?.resolve(reference)
                ?: return null
        (source as? HttpSource)?.let { httpSource ->
            return httpSource.getImage(Page(0, url.toString(), url.toString())).body.byteStream()
        }
        val response = network.client.newCall(Request.Builder().url(url).get().build()).execute()
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        return response.body.byteStream()
    }
}

internal fun Throwable.allowsOfflineTranslationFallback(): Boolean =
    this is IOException || this is HttpException && (code == 408 || code == 429 || code in 500..599)
