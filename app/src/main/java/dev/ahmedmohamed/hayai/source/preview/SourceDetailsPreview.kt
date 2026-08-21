package dev.ahmedmohamed.hayai.source.preview

import android.graphics.Bitmap
import eu.kanade.tachiyomi.data.database.models.Manga
import okhttp3.CacheControl
import java.io.Closeable

data class SourceRenderedPreview(
    val index: Int,
    val pageUrl: String?,
    val bitmap: Bitmap,
)

data class SourceRenderedPreviewPage(
    val page: Int,
    val previews: List<SourceRenderedPreview>,
    val hasNextPage: Boolean,
    val totalPages: Int,
) : Closeable {
    override fun close() {
        previews.forEach { rendered ->
            if (!rendered.bitmap.isRecycled) rendered.bitmap.recycle()
        }
    }
}

interface SourceDetailsPreviewProvider {
    fun owns(manga: Manga): Boolean

    suspend fun load(
        manga: Manga,
        page: Int = 1,
        cacheControl: CacheControl? = null,
    ): SourceRenderedPreviewPage
}

class SourceDetailsPreviewRegistry(
    private val providers: List<SourceDetailsPreviewProvider>,
) : SourceDetailsPreviewProvider {
    override fun owns(manga: Manga): Boolean = providers.any { it.owns(manga) }

    override suspend fun load(
        manga: Manga,
        page: Int,
        cacheControl: CacheControl?,
    ): SourceRenderedPreviewPage {
        val provider = providers.singleOrNull { it.owns(manga) }
            ?: error("No unique page-preview provider is registered for source ${manga.source}")
        return provider.load(manga, page, cacheControl)
    }
}
