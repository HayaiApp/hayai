package dev.ahmedmohamed.hayai.adult.eh.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import dev.ahmedmohamed.hayai.adult.eh.domain.EhPagePreview
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import dev.ahmedmohamed.hayai.adult.eh.network.EhHttpGateway
import dev.ahmedmohamed.hayai.adult.eh.settings.EhPreferences
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import dev.ahmedmohamed.hayai.source.preview.SourceDetailsPreviewProvider
import dev.ahmedmohamed.hayai.source.preview.SourceRenderedPreview
import dev.ahmedmohamed.hayai.source.preview.SourceRenderedPreviewPage
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlinx.coroutines.CancellationException
import okhttp3.CacheControl
import java.io.ByteArrayOutputStream

class EhDetailsPreviewLoader(
    private val gateway: EhHttpGateway,
    private val preferences: HayaiPreferences,
    private val ehPreferences: EhPreferences,
) : SourceDetailsPreviewProvider {
    override fun owns(manga: Manga): Boolean =
        preferences.hentaiFeaturesEnabled.get() &&
            ehPreferences.enhancedView.get() &&
            EhSite.entries.any { it.sourceId == manga.source }

    override suspend fun load(
        manga: Manga,
        page: Int,
        cacheControl: CacheControl?,
    ): SourceRenderedPreviewPage {
        require(page in 1..MAX_LISTING_PAGES)
        val site = EhSite.entries.firstOrNull { it.sourceId == manga.source }
            ?: error("The E-Hentai source is unavailable")
        val previews = gateway.previews(site, GalleryKey.parse(manga.url), page - 1).take(PREVIEWS_PER_PAGE)
        val spriteBytes = mutableMapOf<String, ByteArray>()
        var spriteByteCount = 0L
        var decodedByteCount = 0L
        val rendered = mutableListOf<SourceRenderedPreview>()
        try {
            previews.forEach { preview ->
                try {
                    val bytes =
                        if (preview.crop != null) {
                            spriteBytes[preview.imageUrl] ?: download(site, preview.imageUrl, cacheControl).also {
                                spriteByteCount += it.size
                                require(spriteByteCount <= MAX_ENCODED_SPRITE_BYTES) { "Preview sprites exceeded the memory budget" }
                                spriteBytes[preview.imageUrl] = it
                            }
                        } else {
                            download(site, preview.imageUrl, cacheControl)
                        }
                    val bitmap = decode(bytes, preview)
                    decodedByteCount += bitmap.byteCount
                    if (decodedByteCount > MAX_DECODED_BYTES_PER_PAGE) {
                        bitmap.recycle()
                        error("Preview page exceeded the decoded bitmap budget")
                    }
                    rendered += SourceRenderedPreview(preview.index, preview.pageUrl, bitmap)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // One malformed or unavailable thumbnail must not hide the usable previews.
                }
            }
        } catch (failure: Throwable) {
            rendered.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            throw failure
        }
        val hasNext = previews.size == PREVIEWS_PER_PAGE && page < MAX_LISTING_PAGES
        return SourceRenderedPreviewPage(page, rendered, hasNext, if (hasNext) page + 1 else page)
    }

    private suspend fun download(
        site: EhSite,
        url: String,
        cacheControl: CacheControl?,
    ): ByteArray {
        val request = gateway.imageRequest(site, url).newBuilder().apply {
            cacheControl?.let { cacheControl(it) }
        }.build()
        return gateway.executeImage(request).use { response ->
            val declared = response.body.contentLength()
            require(declared < 0 || declared <= MAX_IMAGE_BYTES) { "Preview image exceeded the size limit" }
            response.body.byteStream().readBytesBounded(MAX_IMAGE_BYTES)
        }
    }

    @Suppress("DEPRECATION")
    private fun decode(
        bytes: ByteArray,
        preview: EhPagePreview,
    ): Bitmap {
        val crop = preview.crop
        if (crop != null) {
            val decoder = requireNotNull(BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)) {
                "Preview sprite could not be decoded"
            }
            return decoder.useCompat {
                require(it.width in 1..MAX_SOURCE_DIMENSION && it.height in 1..MAX_SOURCE_DIMENSION)
                require(crop.x + crop.width <= it.width && crop.y + crop.height <= it.height) {
                    "Preview crop is outside the sprite"
                }
                requireNotNull(
                    it.decodeRegion(
                        Rect(crop.x, crop.y, crop.x + crop.width, crop.y + crop.height),
                        decodeOptions(sampleSize(crop.width, crop.height)),
                    ),
                ) { "Preview sprite region could not be decoded" }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..MAX_SOURCE_DIMENSION && bounds.outHeight in 1..MAX_SOURCE_DIMENSION) {
            "Preview image dimensions are invalid"
        }
        return requireNotNull(
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions(sampleSize(bounds.outWidth, bounds.outHeight))),
        ) { "Preview image could not be decoded" }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_RENDER_DIMENSION || height / sample > MAX_RENDER_DIMENSION) sample *= 2
        return sample
    }

    private fun decodeOptions(sample: Int) = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }

    private fun java.io.InputStream.readBytesBounded(limit: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024L).toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Preview image exceeded the size limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    @Suppress("DEPRECATION")
    private inline fun <T> BitmapRegionDecoder.useCompat(block: (BitmapRegionDecoder) -> T): T =
        try {
            block(this)
        } finally {
            recycle()
        }

    private companion object {
        const val PREVIEWS_PER_PAGE = 12
        const val MAX_LISTING_PAGES = 200
        const val MAX_IMAGE_BYTES = 4L * 1024L * 1024L
        const val MAX_ENCODED_SPRITE_BYTES = 12L * 1024L * 1024L
        const val MAX_DECODED_BYTES_PER_PAGE = 12L * 1024L * 1024L
        const val MAX_SOURCE_DIMENSION = 16_384
        const val MAX_RENDER_DIMENSION = 512
    }
}
