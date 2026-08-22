package dev.ahmedmohamed.hayai.adult.eh.source

import android.content.Context
import android.text.format.Formatter
import dev.ahmedmohamed.hayai.adult.eh.domain.EhBrowseGallery
import dev.ahmedmohamed.hayai.adult.eh.domain.EhFailure
import dev.ahmedmohamed.hayai.adult.eh.domain.EhGalleryMetadata
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSearchCursor
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSearchSpec
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import dev.ahmedmohamed.hayai.adult.eh.network.EhHttpGateway
import dev.ahmedmohamed.hayai.adult.eh.persistence.HayaiEhPersistenceStore
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMangaIdentity
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMetadata
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMetadataTag
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMetadataTitle
import dev.ahmedmohamed.hayai.adult.eh.presentation.EhTextResolver
import dev.ahmedmohamed.hayai.adult.eh.presentation.localizedMessage
import dev.ahmedmohamed.hayai.adult.eh.settings.EhPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

class EhentaiSource(
    private val context: Context,
    private val site: EhSite,
    private val gateway: EhHttpGateway,
    private val metadataStore: HayaiEhPersistenceStore,
    private val preferences: EhPreferences,
) : HttpSource() {
    override val id: Long = site.sourceId
    override val name: String = site.displayName
    override val lang: String = "all"
    override val baseUrl: String = site.baseUrl
    override val supportsLatest: Boolean = true

    private val cursors = EhCursorStore(context)
    private val text = EhTextResolver(context)
    private val retryPageUrls = ConcurrentHashMap<String, String>()

    override suspend fun getPopularManga(page: Int): MangasPage {
        require(page == 1) { context.getString(R.string.hayai_eh_popular_single_page) }
        return request { gateway.popular(site).toMangasPage() }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val fingerprint = "latest"
        val cursor = cursors.cursor(fingerprint, page) as? EhSearchCursor.Gallery
        val result = request { gateway.latest(site, cursor) }
        cursors.record(fingerprint, page, result.nextCursor)
        return result.toMangasPage()
    }

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        directGallery(query)?.let { key ->
            if (page > 1) return MangasPage(emptyList(), false)
            val details = request { gateway.details(site, key) }
            return MangasPage(listOf(details.metadata.toSManga()), false)
        }
        val spec = filters.toEhSpec(query, context)
        val fingerprint = spec.toString()
        val result = request { gateway.browse(site, spec, cursors.cursor(fingerprint, page)) }
        cursors.record(fingerprint, page, result.nextCursor)
        return result.toMangasPage()
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val metadata = request { gateway.details(site, GalleryKey.parse(manga.url)).metadata }
        persistMetadata(metadata)
        return metadata.toSManga().apply { initialized = true }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val chain = request { gateway.revisionChain(site, GalleryKey.parse(manga.url)) }
        return chain.mapIndexed { index, revision ->
            SChapter.create().apply {
                url = revision.key.normalizedPath
                name = context.getString(R.string.hayai_eh_revision_name, index + 1, revision.title)
                chapter_number = (index + 1).toFloat()
                date_upload = revision.postedAtMillis ?: 0L
            }
        }.asReversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        request { gateway.pages(site, GalleryKey.parse(chapter.url)) }.mapIndexed { index, galleryPage ->
            Page(index, galleryPage.pageUrl)
        }

    override suspend fun getImageUrl(page: Page): String {
        val resolved = request { gateway.resolveImage(site, retryPageUrls.remove(page.url) ?: page.url) }
        resolved.retryPageUrl?.let { retryPageUrls[page.url] = it }
        return resolved.imageUrl
    }

    override suspend fun getImage(page: Page): Response {
        val firstRequest = gateway.imageRequest(site, requireNotNull(page.imageUrl) { context.getString(R.string.hayai_eh_image_url_unresolved) })
        return try {
            client.newCachelessCallWithProgress(firstRequest, page).awaitSuccess()
        } catch (firstFailure: Throwable) {
            if (firstFailure is CancellationException) throw firstFailure
            val retryPage = retryPageUrls.remove(page.url) ?: throw firstFailure
            val refreshed = request { gateway.resolveImage(site, retryPage) }
            refreshed.retryPageUrl?.let { retryPageUrls[page.url] = it }
            page.imageUrl = refreshed.imageUrl
            client.newCachelessCallWithProgress(gateway.imageRequest(site, refreshed.imageUrl), page).awaitSuccess()
        }
    }

    override fun getFilterList(): FilterList = ehFilterList(context, preferences)

    override fun getMangaUrl(manga: SManga): String = GalleryKey.parse(manga.url).absoluteUrl(site)

    override fun getChapterUrl(chapter: SChapter): String = GalleryKey.parse(chapter.url).absoluteUrl(site)

    private suspend fun <T> request(block: suspend () -> T): T = try {
        block()
    } catch (failure: EhFailure) {
        throw IllegalStateException(failure.localizedMessage(text), failure)
    }

    private fun directGallery(query: String): GalleryKey? {
        if (!query.contains("/g/")) return null
        return runCatching { GalleryKey.parse(query) }.getOrNull()
    }

    private fun dev.ahmedmohamed.hayai.adult.eh.domain.EhBrowsePage.toMangasPage(): MangasPage =
        MangasPage(galleries.map(EhBrowseGallery::metadata).map { it.toSManga() }, nextCursor != null)

    private fun EhGalleryMetadata.toSManga(): SManga = SManga.create().apply {
        url = key.normalizedPath
        title = if (preferences.useJapaneseTitle.get()) {
            alternateTitle?.takeIf(String::isNotBlank) ?: this@toSManga.title
        } else {
            this@toSManga.title
        }
        val artists = tags.filter { it.namespace == "artist" }.map { it.name }
        val groups = tags.filter { it.namespace == "group" }.map { it.name }
        artist = artists.takeIf(List<String>::isNotEmpty)?.joinToString()
        author = (artists + groups).distinct().takeIf(List<String>::isNotEmpty)?.joinToString()
        description = buildDescription(this@toSManga)
        genre = buildList {
            category?.let(::add)
            language?.let(::add)
            addAll(tags.map { "${it.namespace}: ${it.name}" })
        }.distinct().joinToString().takeIf(String::isNotBlank)
        status = SManga.UNKNOWN
        thumbnail_url = this@toSManga.thumbnailUrl
        initialized = true
    }

    private fun buildDescription(metadata: EhGalleryMetadata): String = buildList {
        metadata.title.takeIf { it != metadata.alternateTitle }?.let { add(context.getString(R.string.hayai_eh_description_original_title, it)) }
        metadata.uploader?.let { add(context.getString(R.string.hayai_eh_description_uploader, it)) }
        metadata.category?.let { add(context.getString(R.string.hayai_eh_description_category, it)) }
        metadata.language?.let {
            add(
                context.getString(
                    R.string.hayai_eh_description_language,
                    it,
                    if (metadata.translated == true) context.getString(R.string.hayai_eh_description_translated) else "",
                ),
            )
        }
        metadata.pageCount?.let { add(context.getString(R.string.hayai_eh_description_pages, it)) }
        metadata.sizeBytes?.let { add(context.getString(R.string.hayai_eh_description_size, Formatter.formatShortFileSize(context, it))) }
        metadata.averageRating?.let { rating ->
            val votes = metadata.ratingCount?.let { context.resources.getQuantityString(R.plurals.hayai_eh_description_votes, it, it) }.orEmpty()
            add(context.getString(R.string.hayai_eh_description_rating, "%.2f".format(rating), votes))
        }
        metadata.favoriteCount?.let { add(context.getString(R.string.hayai_eh_description_favorites, it)) }
        metadata.visible?.let { add(context.getString(R.string.hayai_eh_description_visibility, it)) }
        if (metadata.tags.isNotEmpty()) {
            add("\n${context.getString(R.string.hayai_eh_description_tags)}\n" + metadata.tags.groupBy { it.namespace }.entries.joinToString("\n") { (namespace, tags) ->
                context.getString(R.string.hayai_eh_description_tag_group, namespace, tags.joinToString { it.name })
            })
        }
    }.joinToString("\n")

    private fun persistMetadata(metadata: EhGalleryMetadata) {
        val payload = buildJsonObject {
            put("gid", metadata.key.id.value)
            put("token", metadata.key.token.value)
            metadata.thumbnailUrl?.let { put("thumbnail", it) }
            metadata.category?.let { put("category", it) }
            metadata.postedAtMillis?.let { put("postedAt", it) }
            metadata.parent?.let { put("parent", it.normalizedPath) }
            metadata.visible?.let { put("visible", it) }
            metadata.language?.let { put("language", it) }
            metadata.translated?.let { put("translated", it) }
            metadata.sizeBytes?.let { put("sizeBytes", it) }
            metadata.pageCount?.let { put("pageCount", it) }
            metadata.favoriteCount?.let { put("favoriteCount", it) }
            metadata.ratingCount?.let { put("ratingCount", it) }
            metadata.averageRating?.let { put("averageRating", it) }
        }.toString()
        runCatching {
            metadataStore.replaceMetadata(
                SourceMetadata(
                    identity = SourceMangaIdentity(id, metadata.key.normalizedPath),
                    uploader = metadata.uploader,
                    extra = payload,
                    indexedExtra = metadata.key.id.value,
                    extraVersion = 1,
                    tags = metadata.tags.map { SourceMetadataTag(it.namespace, it.name, it.weight.ordinal) },
                    titles = buildList {
                        add(SourceMetadataTitle(metadata.title, 0))
                        metadata.alternateTitle?.takeIf(String::isNotBlank)?.let { add(SourceMetadataTitle(it, 1)) }
                    },
                ),
            )
        }.onFailure { Timber.w(it, "Unable to persist E-Hentai metadata for source %d", id) }
    }

}

private class EhCursorStore(
    private val context: Context,
    private val maxSearches: Int = 32,
) {
    private val pages = object : LinkedHashMap<String, MutableMap<Int, EhSearchCursor?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<Int, EhSearchCursor?>>?): Boolean = size > maxSearches
    }

    @Synchronized
    fun cursor(fingerprint: String, page: Int): EhSearchCursor? {
        require(page >= 1) { context.getString(R.string.hayai_eh_page_number_positive) }
        if (page == 1) {
            pages[fingerprint] = mutableMapOf(1 to null)
            return null
        }
        return requireNotNull(pages[fingerprint]) { context.getString(R.string.hayai_eh_pagination_expired) }[page]
            ?: throw EhFailure.MalformedDocument(context.getString(R.string.hayai_eh_pagination_finished))
    }

    @Synchronized
    fun record(fingerprint: String, page: Int, next: EhSearchCursor?) {
        val state = pages.getOrPut(fingerprint) { mutableMapOf(1 to null) }
        state[page + 1] = next
    }
}
