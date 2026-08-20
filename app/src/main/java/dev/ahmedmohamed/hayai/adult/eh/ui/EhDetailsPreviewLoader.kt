package dev.ahmedmohamed.hayai.adult.eh.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.ahmedmohamed.hayai.adult.eh.domain.EhPagePreview
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import dev.ahmedmohamed.hayai.adult.eh.network.EhHttpGateway
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlinx.coroutines.CancellationException

class EhDetailsPreviewLoader(
    private val gateway: EhHttpGateway,
    private val preferences: HayaiPreferences,
) {
    fun owns(manga: Manga): Boolean = preferences.hentaiFeaturesEnabled.get() && EhSite.entries.any { it.sourceId == manga.source }

    suspend fun load(manga: Manga): List<EhRenderedPreview> {
        val site = EhSite.entries.firstOrNull { it.sourceId == manga.source } ?: return emptyList()
        val previews = gateway.previews(site, GalleryKey.parse(manga.url)).take(MAX_PREVIEWS)
        val sprites = mutableMapOf<String, Bitmap>()
        return previews.mapNotNull { preview ->
            try {
                val source = sprites[preview.imageUrl] ?: downloadBitmap(site, preview.imageUrl).also { bitmap ->
                    if (preview.crop != null) sprites[preview.imageUrl] = bitmap
                }
                val bitmap =
                    preview.crop?.let { crop ->
                        if (crop.x + crop.width > source.width || crop.y + crop.height > source.height) return@mapNotNull null
                        Bitmap.createBitmap(source, crop.x, crop.y, crop.width, crop.height)
                    } ?: source
                EhRenderedPreview(preview, bitmap)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun downloadBitmap(
        site: EhSite,
        url: String,
    ): Bitmap {
        val response = gateway.executeImage(gateway.imageRequest(site, url))
        val bytes = response.use {
            val declared = it.body.contentLength()
            require(declared in -1..MAX_IMAGE_BYTES.toLong()) { "Preview image is too large" }
            it.body.byteStream().readBytesBounded(MAX_IMAGE_BYTES)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..MAX_DIMENSION && bounds.outHeight in 1..MAX_DIMENSION) { "Invalid preview dimensions" }
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Preview could not be decoded" }
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Preview image is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_PREVIEWS = 20
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val MAX_DIMENSION = 12_000
    }
}

data class EhRenderedPreview(
    val preview: EhPagePreview,
    val bitmap: Bitmap,
)
