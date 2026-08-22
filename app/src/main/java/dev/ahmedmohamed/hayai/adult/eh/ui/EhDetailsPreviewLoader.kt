package dev.ahmedmohamed.hayai.adult.eh.ui

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import dev.ahmedmohamed.hayai.adult.eh.network.EhHttpGateway
import dev.ahmedmohamed.hayai.adult.eh.settings.EhPreferences
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import dev.ahmedmohamed.hayai.source.preview.SourceDetailsPreviewProvider
import dev.ahmedmohamed.hayai.source.preview.SourcePagePreview
import dev.ahmedmohamed.hayai.source.preview.SourcePreviewCrop
import dev.ahmedmohamed.hayai.source.preview.SourcePreviewPage
import eu.kanade.tachiyomi.data.database.models.Manga
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

    override suspend fun load(manga: Manga, page: Int, cacheControl: CacheControl?): SourcePreviewPage {
        require(page in 1..MAX_LISTING_PAGES)
        val result = gateway.previews(site(manga), GalleryKey.parse(manga.url), page - 1)
        return SourcePreviewPage(
            page = page,
            previews = result.previews.map { preview ->
                SourcePagePreview(
                    index = preview.index,
                    imageUrl = preview.imageUrl,
                    pageUrl = preview.pageUrl,
                    crop = preview.crop?.let { SourcePreviewCrop(it.x, it.y, it.width, it.height) },
                )
            },
            hasNextPage = result.hasNextPage,
            totalPages = result.totalPages,
        )
    }

    override suspend fun loadImage(
        manga: Manga,
        preview: SourcePagePreview,
        cacheControl: CacheControl?,
    ): ByteArray {
        val request = gateway.imageRequest(site(manga), preview.imageUrl).newBuilder().apply {
            cacheControl?.let(::cacheControl)
        }.build()
        val bytes = gateway.executeImage(request).use { response ->
            val declared = response.body.contentLength()
            require(declared < 0 || declared <= MAX_IMAGE_BYTES) { "Preview image exceeded the size limit" }
            response.body.byteStream().readBytesBounded(MAX_IMAGE_BYTES)
        }
        val crop = preview.crop ?: return bytes
        val decoder = requireNotNull(BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)) {
            "Preview sprite could not be decoded"
        }
        return try {
            require(crop.x + crop.width <= decoder.width && crop.y + crop.height <= decoder.height) {
                "Preview crop is outside the sprite"
            }
            requireNotNull(
                decoder.decodeRegion(Rect(crop.x, crop.y, crop.x + crop.width, crop.y + crop.height), null),
            ) { "Preview sprite region could not be decoded" }.useBytes()
        } finally {
            @Suppress("DEPRECATION")
            decoder.recycle()
        }
    }

    private fun site(manga: Manga): EhSite =
        EhSite.entries.firstOrNull { it.sourceId == manga.source }
            ?: error("The E-Hentai source is unavailable")

    private fun Bitmap.useBytes(): ByteArray = try {
        ByteArrayOutputStream().use { output ->
            check(compress(Bitmap.CompressFormat.JPEG, 92, output)) { "Preview crop could not be encoded" }
            output.toByteArray()
        }
    } finally {
        recycle()
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

    private companion object {
        const val MAX_LISTING_PAGES = 200
        const val MAX_IMAGE_BYTES = 12L * 1024L * 1024L
    }
}
