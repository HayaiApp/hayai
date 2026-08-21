package dev.ahmedmohamed.hayai.source.enhanced

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.ahmedmohamed.hayai.source.enhanced.EnhancedSourceFeature.PagePreview
import dev.ahmedmohamed.hayai.source.preview.SourceDetailsPreviewProvider
import dev.ahmedmohamed.hayai.source.preview.SourceRenderedPreview
import dev.ahmedmohamed.hayai.source.preview.SourceRenderedPreviewPage
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.supervisorScope
import okhttp3.CacheControl
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

class EnhancedDetailsPreviewLoader(
    private val sourceManager: SourceManager,
    private val database: DatabaseHelper,
) : SourceDetailsPreviewProvider {
    override fun owns(manga: Manga): Boolean {
        val source = sourceManager.get(manga.source) as? HayaiEnhancedHttpSource ?: return false
        return EnhancedSourceFeatureRegistry.features(source)?.contains(PagePreview) == true
    }

    override suspend fun load(
        manga: Manga,
        page: Int,
        cacheControl: CacheControl?,
    ): SourceRenderedPreviewPage {
        require(page > 0)
        val source = sourceManager.get(manga.source) as? HayaiEnhancedHttpSource
            ?: error("The enhanced source is no longer installed")
        check(EnhancedSourceFeatureRegistry.features(source)?.contains(PagePreview) == true) {
            "${source.name} does not expose page previews"
        }
        val chapters = database.getChapters(manga).executeAsBlocking()
        val previews = source.getPagePreviews(manga, chapters, page)
        val semaphore = Semaphore(MAX_PARALLEL_IMAGES)
        val decodedBytes = AtomicLong(0)
        val allocated = Collections.synchronizedList(mutableListOf<SourceRenderedPreview>())
        val rendered = try {
            supervisorScope {
                previews.previews.map { preview ->
                    async {
                        semaphore.withPermit {
                            try {
                                source.fetchPreviewImage(preview, cacheControl).use { response ->
                                    val bitmap = response.body.readPreviewBitmap()
                                    val total = decodedBytes.addAndGet(bitmap.byteCount.toLong())
                                    if (total > MAX_DECODED_BYTES_PER_PAGE) {
                                        decodedBytes.addAndGet(-bitmap.byteCount.toLong())
                                        bitmap.recycle()
                                        error("Preview page exceeded the decoded bitmap budget")
                                    }
                                    SourceRenderedPreview(preview.index, preview.pageUrl, bitmap).also(allocated::add)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (error: Throwable) {
            allocated.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            throw error
        }
        check(rendered.isNotEmpty() || previews.previews.isEmpty()) { "No page preview images could be loaded" }
        return SourceRenderedPreviewPage(previews.page, rendered, previews.hasNextPage, previews.totalPages)
    }

    private fun okhttp3.ResponseBody.readPreviewBitmap(): Bitmap {
        val declared = contentLength()
        require(declared < 0 || declared <= MAX_IMAGE_BYTES) { "Preview image exceeded the size limit" }
        val bytes = byteStream().use { input ->
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..MAX_SOURCE_DIMENSION && bounds.outHeight in 1..MAX_SOURCE_DIMENSION) {
            "Preview image dimensions are invalid"
        }
        var sample = 1
        while (bounds.outWidth / sample > MAX_RENDER_DIMENSION || bounds.outHeight / sample > MAX_RENDER_DIMENSION) sample *= 2
        return requireNotNull(
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            ),
        ) { "Preview image could not be decoded" }
    }

    companion object {
        private const val MAX_PARALLEL_IMAGES = 3
        private const val MAX_IMAGE_BYTES = 4L * 1024L * 1024L
        private const val MAX_SOURCE_DIMENSION = 16_384
        private const val MAX_RENDER_DIMENSION = 512
        private const val MAX_DECODED_BYTES_PER_PAGE = 12L * 1024L * 1024L
    }
}
