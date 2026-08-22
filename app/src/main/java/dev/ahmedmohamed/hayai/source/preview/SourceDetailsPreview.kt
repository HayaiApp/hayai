package dev.ahmedmohamed.hayai.source.preview

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlinx.serialization.Serializable
import okhttp3.CacheControl

@Serializable
data class SourcePreviewCrop(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

@Serializable
data class SourcePagePreview(
    val index: Int,
    val imageUrl: String,
    val pageUrl: String? = null,
    val crop: SourcePreviewCrop? = null,
) {
    init {
        require(index > 0)
        require(imageUrl.startsWith("https://") || imageUrl.startsWith("http://"))
    }

    fun cacheIdentity(sourceId: Long): String = "$sourceId:$imageUrl:${crop ?: "full"}"
}

@Serializable
data class SourcePreviewPage(
    val page: Int,
    val previews: List<SourcePagePreview>,
    val hasNextPage: Boolean,
    val totalPages: Int? = null,
) {
    init {
        require(page > 0)
        require(totalPages == null || totalPages > 0)
    }
}

interface SourceDetailsPreviewProvider {
    fun owns(manga: Manga): Boolean

    suspend fun load(
        manga: Manga,
        page: Int = 1,
        cacheControl: CacheControl? = null,
    ): SourcePreviewPage

    suspend fun loadImage(
        manga: Manga,
        preview: SourcePagePreview,
        cacheControl: CacheControl? = null,
    ): ByteArray
}

class SourceDetailsPreviewRegistry(
    private val providers: List<SourceDetailsPreviewProvider>,
    private val database: DatabaseHelper,
    private val cache: SourcePreviewCache,
) : SourceDetailsPreviewProvider {
    override fun owns(manga: Manga): Boolean = providers.any { it.owns(manga) }

    override suspend fun load(
        manga: Manga,
        page: Int,
        cacheControl: CacheControl?,
    ): SourcePreviewPage {
        val chapterIds = database.getChapters(manga).executeAsBlocking().mapNotNull { it.id }
        val key = cache.listingKey(manga, chapterIds, page)
        if (cacheControl != CacheControl.FORCE_NETWORK) cache.readListing(key)?.let { return it }
        return provider(manga).load(manga, page, cacheControl).also { cache.writeListing(key, it) }
    }

    override suspend fun loadImage(
        manga: Manga,
        preview: SourcePagePreview,
        cacheControl: CacheControl?,
    ): ByteArray {
        val key = preview.cacheIdentity(manga.source)
        if (cacheControl != CacheControl.FORCE_NETWORK) cache.readImage(key)?.let { return it }
        return provider(manga).loadImage(manga, preview, cacheControl).also { cache.writeImage(key, it) }
    }

    fun clearCache(): Int = cache.clear()

    val readableCacheSize: String
        get() = cache.readableSize

    private fun provider(manga: Manga): SourceDetailsPreviewProvider =
        providers.singleOrNull { it.owns(manga) }
            ?: error("No unique page-preview provider is registered for source ${manga.source}")
}
