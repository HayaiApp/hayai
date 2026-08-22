package dev.ahmedmohamed.hayai.source.enhanced

import dev.ahmedmohamed.hayai.source.enhanced.EnhancedSourceFeature.PagePreview
import dev.ahmedmohamed.hayai.source.preview.SourceDetailsPreviewProvider
import dev.ahmedmohamed.hayai.source.preview.SourcePagePreview
import dev.ahmedmohamed.hayai.source.preview.SourcePreviewPage
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import okhttp3.CacheControl
import java.io.ByteArrayOutputStream

class EnhancedDetailsPreviewLoader(
    private val sourceManager: SourceManager,
    private val database: DatabaseHelper,
) : SourceDetailsPreviewProvider {
    override fun owns(manga: Manga): Boolean {
        val source = sourceManager.get(manga.source) as? HayaiEnhancedHttpSource ?: return false
        return EnhancedSourceFeatureRegistry.features(source)?.contains(PagePreview) == true
    }

    override suspend fun load(manga: Manga, page: Int, cacheControl: CacheControl?): SourcePreviewPage {
        require(page > 0)
        val source = source(manga)
        val chapters = database.getChapters(manga).executeAsBlocking()
        val result = source.getPagePreviews(manga, chapters, page)
        return SourcePreviewPage(
            page = result.page,
            previews = result.previews.map { SourcePagePreview(it.index, it.imageUrl, it.pageUrl) },
            hasNextPage = result.hasNextPage,
            totalPages = result.totalPages,
        )
    }

    override suspend fun loadImage(
        manga: Manga,
        preview: SourcePagePreview,
        cacheControl: CacheControl?,
    ): ByteArray {
        val source = source(manga)
        val enhanced = EnhancedPagePreview(preview.index, preview.imageUrl, preview.pageUrl)
        return source.fetchPreviewImage(enhanced, cacheControl).use { response ->
            val declared = response.body.contentLength()
            require(declared < 0 || declared <= MAX_IMAGE_BYTES) { "Preview image exceeded the size limit" }
            response.body.byteStream().use { input ->
                val output = ByteArrayOutputStream(minOf(MAX_IMAGE_BYTES, maxOf(16 * 1024L, declared)).toInt())
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_IMAGE_BYTES) { "Preview image exceeded the size limit" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private fun source(manga: Manga): HayaiEnhancedHttpSource {
        val source = sourceManager.get(manga.source) as? HayaiEnhancedHttpSource
            ?: error("The enhanced source is no longer installed")
        check(EnhancedSourceFeatureRegistry.features(source)?.contains(PagePreview) == true) {
            "${source.name} does not expose page previews"
        }
        return source
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 4L * 1024L * 1024L
    }
}
