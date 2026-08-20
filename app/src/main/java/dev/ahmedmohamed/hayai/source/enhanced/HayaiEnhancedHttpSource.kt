package dev.ahmedmohamed.hayai.source.enhanced

import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.runAsObservable
import okhttp3.Response
import rx.Observable
import timber.log.Timber

@Suppress("DEPRECATION", "OverridingDeprecatedMember")
open class HayaiEnhancedHttpSource(
    val originalSource: HttpSource,
    val definition: EnhancedSourceDefinition,
) : HttpSource() {
    private val detailsCache = object : LinkedHashMap<String, EnhancedDetails>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EnhancedDetails>?): Boolean = size > MAX_CACHED_DETAILS
    }
    override val id: Long get() = originalSource.id
    override val name: String get() = originalSource.name
    override val lang: String get() = originalSource.lang
    override val baseUrl: String get() = originalSource.baseUrl
    override val supportsLatest: Boolean get() = originalSource.supportsLatest
    override val versionId: Int get() = originalSource.versionId
    override val client get() = originalSource.client
    override val headers get() = originalSource.headers

    override fun getHomeUrl(): String = originalSource.getHomeUrl()
    override fun getExtension(extensionManager: ExtensionManager?) = originalSource.getExtension(extensionManager)
    override fun extOnlyHasAllLanguage(extensionManager: ExtensionManager?) = originalSource.extOnlyHasAllLanguage(extensionManager)
    override fun toString(): String = originalSource.toString()

    override suspend fun getPopularManga(page: Int): MangasPage = originalSource.getPopularManga(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = originalSource.getLatestUpdates(page)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val mapped = query.takeIf { "://" in it }?.let { EnhancedSourceUrlMapper.map(definition, baseUrl, it) }
            ?: return originalSource.getSearchManga(page, query, filters)
        if (page > 1) return MangasPage(emptyList(), false)
        val manga = SManga.create().apply { url = mapped }
        return MangasPage(listOf(getMangaDetails(manga)), false)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val details = originalSource.getMangaDetails(manga)
        return enhanceDetails(details, manga.url)
    }

    private suspend fun enhanceDetails(
        details: SManga,
        fallbackUrl: String,
    ): SManga {
        details.url = details.url.ifBlank { fallbackUrl }
        val enhanced = fetchEnhancedDetails(details) ?: return details
        enhanced.title?.takeIf { details.title.isBlank() }?.let { details.title = it }
        enhanced.author?.let { details.author = it }
        enhanced.artist?.let { details.artist = it }
        val enhancedDescription = listOfNotNull(
            enhanced.alternateTitle?.takeIf { it != details.title }?.let { "**Alternative title:** $it" },
            enhanced.description,
        ).joinToString("\n").takeIf(String::isNotBlank)
        details.description = mergeDescription(enhancedDescription, details.description)
        details.genre = mergeGenres(details.genre, enhanced.genres)
        enhanced.thumbnailUrl?.takeIf { details.thumbnail_url.isNullOrBlank() }?.let { details.thumbnail_url = it }
        details.initialized = true
        return details
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val update = originalSource.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
        val updatedManga = if (fetchDetails) enhanceDetails(update.manga, manga.url) else update.manga
        return SMangaUpdate(updatedManga, update.chapters)
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = originalSource.getChapterList(manga)
    override suspend fun getPageList(chapter: SChapter): List<Page> = originalSource.getPageList(chapter)
    override suspend fun getImageUrl(page: Page): String = originalSource.getImageUrl(page)
    override suspend fun getImage(page: Page): Response = originalSource.getImage(page)
    override fun getMangaUrl(manga: SManga): String = originalSource.getMangaUrl(manga)
    override fun getChapterUrl(chapter: SChapter): String = originalSource.getChapterUrl(chapter)
    override fun getFilterList(): FilterList = originalSource.getFilterList()
    override fun mangaDetailsRequest(manga: SManga) = originalSource.mangaDetailsRequest(manga)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = originalSource.fetchPopularManga(page)
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = originalSource.fetchLatestUpdates(page)
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        if (query.contains("://")) runAsObservable { getSearchManga(page, query, filters) } else originalSource.fetchSearchManga(page, query, filters)
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = runAsObservable { getMangaDetails(manga) }
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = originalSource.fetchChapterList(manga)
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = originalSource.fetchPageList(chapter)
    override fun fetchImageUrl(page: Page): Observable<String> = originalSource.fetchImageUrl(page)

    private suspend fun fetchEnhancedDetails(manga: SManga): EnhancedDetails? {
        synchronized(detailsCache) { detailsCache[manga.url] }?.let { return it }
        val parsed = runCatching {
            val request = originalSource.mangaDetailsRequest(manga)
            originalSource.client.newCall(request).awaitSuccess().use { response ->
                val declared = response.body.contentLength()
                if (declared > MAX_DETAILS_BYTES) return@use null
                val body = response.body.charStream().readTextBounded(MAX_DETAILS_BYTES)
                EnhancedDetailsParser.parse(definition.family, body, request.url.toString())
            }
        }.onFailure { Timber.w(it, "Enhanced details failed for source %d", id) }.getOrNull()
        if (parsed != null) synchronized(detailsCache) { detailsCache[manga.url] = parsed }
        return parsed
    }

    private fun mergeDescription(enhanced: String?, original: String?): String? {
        val first = enhanced?.trim()?.takeIf(String::isNotBlank)
        val second = original?.trim()?.takeIf(String::isNotBlank)
        return when {
            first == null -> second
            second == null -> first
            first.contains(second) -> first
            second.contains(first) -> second
            else -> "$first\n\n$second"
        }
    }

    private fun mergeGenres(original: String?, enhanced: List<String>): String? {
        val values = linkedSetOf<String>()
        original.orEmpty().split(',').map(String::trim).filterTo(values, String::isNotBlank)
        enhanced.map(String::trim).filterTo(values, String::isNotBlank)
        return values.takeIf(Set<String>::isNotEmpty)?.joinToString()
    }

    private fun java.io.Reader.readTextBounded(limit: Int): String {
        val output = StringBuilder(minOf(limit, 16 * 1024))
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            require(output.length + read <= limit) { "Enhanced source details exceeded the size limit" }
            output.append(buffer, 0, read)
        }
        return output.toString()
    }

    companion object {
        private const val MAX_DETAILS_BYTES = 4 * 1024 * 1024
        private const val MAX_CACHED_DETAILS = 128
    }
}
