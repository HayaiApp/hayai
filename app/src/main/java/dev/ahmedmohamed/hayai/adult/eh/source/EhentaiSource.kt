package dev.ahmedmohamed.hayai.adult.eh.source

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
    private val site: EhSite,
    private val gateway: EhHttpGateway,
    private val metadataStore: HayaiEhPersistenceStore,
) : HttpSource() {
    override val id: Long = site.sourceId
    override val name: String = site.displayName
    override val lang: String = "all"
    override val baseUrl: String = site.baseUrl
    override val supportsLatest: Boolean = true

    private val cursors = EhCursorStore()
    private val retryPageUrls = ConcurrentHashMap<String, String>()

    override suspend fun getPopularManga(page: Int): MangasPage {
        require(page == 1) { "E-Hentai popular only has one page" }
        return gateway.popular(site).toMangasPage()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val fingerprint = "latest"
        val cursor = cursors.cursor(fingerprint, page) as? EhSearchCursor.Gallery
        val result = gateway.latest(site, cursor)
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
            val details = gateway.details(site, key)
            return MangasPage(listOf(details.metadata.toSManga()), false)
        }
        val spec = filters.toEhSpec(query)
        val fingerprint = spec.toString()
        val result = gateway.browse(site, spec, cursors.cursor(fingerprint, page))
        cursors.record(fingerprint, page, result.nextCursor)
        return result.toMangasPage()
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val metadata = gateway.details(site, GalleryKey.parse(manga.url)).metadata
        persistMetadata(metadata)
        return metadata.toSManga().apply { initialized = true }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val chain = gateway.revisionChain(site, GalleryKey.parse(manga.url))
        return chain.mapIndexed { index, revision ->
            SChapter.create().apply {
                url = revision.key.normalizedPath
                name = "v${index + 1} · ${revision.title}"
                chapter_number = (index + 1).toFloat()
                date_upload = revision.postedAtMillis ?: 0L
            }
        }.asReversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        gateway.pages(site, GalleryKey.parse(chapter.url)).mapIndexed { index, galleryPage ->
            Page(index, galleryPage.pageUrl)
        }

    override suspend fun getImageUrl(page: Page): String {
        val resolved = gateway.resolveImage(site, retryPageUrls.remove(page.url) ?: page.url)
        resolved.retryPageUrl?.let { retryPageUrls[page.url] = it }
        return resolved.imageUrl
    }

    override suspend fun getImage(page: Page): Response {
        val firstRequest = gateway.imageRequest(site, requireNotNull(page.imageUrl) { "Image URL was not resolved" })
        return try {
            client.newCachelessCallWithProgress(firstRequest, page).awaitSuccess()
        } catch (firstFailure: Throwable) {
            if (firstFailure is CancellationException) throw firstFailure
            val retryPage = retryPageUrls.remove(page.url) ?: throw firstFailure
            val refreshed = gateway.resolveImage(site, retryPage)
            refreshed.retryPageUrl?.let { retryPageUrls[page.url] = it }
            page.imageUrl = refreshed.imageUrl
            client.newCachelessCallWithProgress(gateway.imageRequest(site, refreshed.imageUrl), page).awaitSuccess()
        }
    }

    override fun getFilterList(): FilterList = ehFilterList()

    override fun getMangaUrl(manga: SManga): String = GalleryKey.parse(manga.url).absoluteUrl(site)

    override fun getChapterUrl(chapter: SChapter): String = GalleryKey.parse(chapter.url).absoluteUrl(site)

    private fun directGallery(query: String): GalleryKey? {
        if (!query.contains("/g/")) return null
        return runCatching { GalleryKey.parse(query) }.getOrNull()
    }

    private fun dev.ahmedmohamed.hayai.adult.eh.domain.EhBrowsePage.toMangasPage(): MangasPage =
        MangasPage(galleries.map(EhBrowseGallery::metadata).map { it.toSManga() }, nextCursor != null)

    private fun EhGalleryMetadata.toSManga(): SManga = SManga.create().apply {
        url = key.normalizedPath
        title = alternateTitle?.takeIf(String::isNotBlank) ?: this@toSManga.title
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
        metadata.title.takeIf { it != metadata.alternateTitle }?.let { add("**Original title:** $it") }
        metadata.uploader?.let { add("**Uploader:** $it") }
        metadata.category?.let { add("**Category:** $it") }
        metadata.language?.let { add("**Language:** $it${if (metadata.translated == true) " (translated)" else ""}") }
        metadata.pageCount?.let { add("**Pages:** $it") }
        metadata.sizeBytes?.let { add("**Size:** ${formatBytes(it)}") }
        metadata.averageRating?.let { rating ->
            add("**Rating:** ${"%.2f".format(rating)}${metadata.ratingCount?.let { " ($it votes)" }.orEmpty()}")
        }
        metadata.favoriteCount?.let { add("**Favorites:** $it") }
        metadata.visible?.let { add("**Visibility:** $it") }
        if (metadata.tags.isNotEmpty()) {
            add("\n**Tags**\n" + metadata.tags.groupBy { it.namespace }.entries.joinToString("\n") { (namespace, tags) ->
                "- $namespace: ${tags.joinToString { it.name }}"
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

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return "%.1f %s".format(value, units[unit])
    }
}

private class EhCursorStore(
    private val maxSearches: Int = 32,
) {
    private val pages = object : LinkedHashMap<String, MutableMap<Int, EhSearchCursor?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<Int, EhSearchCursor?>>?): Boolean = size > maxSearches
    }

    @Synchronized
    fun cursor(fingerprint: String, page: Int): EhSearchCursor? {
        require(page >= 1) { "Page number must be positive" }
        if (page == 1) {
            pages[fingerprint] = mutableMapOf(1 to null)
            return null
        }
        return requireNotNull(pages[fingerprint]) { "E-Hentai pagination state expired. Refresh the search." }[page]
            ?: throw EhFailure.MalformedDocument("E-Hentai pagination cannot advance past the final page")
    }

    @Synchronized
    fun record(fingerprint: String, page: Int, next: EhSearchCursor?) {
        val state = pages.getOrPut(fingerprint) { mutableMapOf(1 to null) }
        state[page + 1] = next
    }
}
