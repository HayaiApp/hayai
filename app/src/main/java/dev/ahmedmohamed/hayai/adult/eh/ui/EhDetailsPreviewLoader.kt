package dev.ahmedmohamed.hayai.adult.eh.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.EhFailure
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import dev.ahmedmohamed.hayai.adult.eh.network.EhHttpGateway
import dev.ahmedmohamed.hayai.adult.eh.settings.EhPreferences
import dev.ahmedmohamed.hayai.adult.eh.presentation.EhTextResolver
import dev.ahmedmohamed.hayai.adult.eh.presentation.localizedMessage
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import dev.ahmedmohamed.hayai.source.preview.SourceDetailsPreviewProvider
import dev.ahmedmohamed.hayai.source.preview.SourcePagePreview
import dev.ahmedmohamed.hayai.source.preview.SourcePreviewCrop
import dev.ahmedmohamed.hayai.source.preview.SourcePreviewPage
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import okhttp3.CacheControl
import java.io.ByteArrayOutputStream

class EhDetailsPreviewLoader(
    private val context: Context,
    private val gateway: EhHttpGateway,
    private val preferences: HayaiPreferences,
    private val ehPreferences: EhPreferences,
) : SourceDetailsPreviewProvider {
    private val text = EhTextResolver(context)
    override fun owns(manga: Manga): Boolean =
        preferences.hentaiFeaturesEnabled.get() &&
            ehPreferences.enhancedView.get() &&
            EhSite.entries.any { it.sourceId == manga.source }

    override suspend fun load(manga: Manga, page: Int, cacheControl: CacheControl?): SourcePreviewPage {
        require(page in 1..MAX_LISTING_PAGES)
        val result = localizedRequest { gateway.previews(site(manga), GalleryKey.parse(manga.url), page - 1) }
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
        val bytes = localizedRequest { gateway.executeImage(request) }.use { response ->
            val declared = response.body.contentLength()
            require(declared < 0 || declared <= MAX_IMAGE_BYTES) { context.getString(R.string.hayai_eh_preview_image_too_large) }
            response.body.byteStream().readBytesBounded(MAX_IMAGE_BYTES)
        }
        val crop = preview.crop ?: return bytes
        val decoder = requireNotNull(BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)) {
            context.getString(R.string.hayai_eh_preview_sprite_decode_failed)
        }
        return try {
            require(crop.x + crop.width <= decoder.width && crop.y + crop.height <= decoder.height) {
                context.getString(R.string.hayai_eh_preview_crop_outside_sprite)
            }
            requireNotNull(
                decoder.decodeRegion(Rect(crop.x, crop.y, crop.x + crop.width, crop.y + crop.height), null),
            ) { context.getString(R.string.hayai_eh_preview_region_decode_failed) }.useBytes()
        } finally {
            @Suppress("DEPRECATION")
            decoder.recycle()
        }
    }

    private fun site(manga: Manga): EhSite =
        EhSite.entries.firstOrNull { it.sourceId == manga.source }
            ?: error(context.getString(R.string.hayai_eh_source_unavailable))

    private suspend fun <T> localizedRequest(block: suspend () -> T): T = try {
        block()
    } catch (failure: EhFailure) {
        throw IllegalStateException(failure.localizedMessage(text), failure)
    }

    private fun Bitmap.useBytes(): ByteArray = try {
        ByteArrayOutputStream().use { output ->
            check(compress(Bitmap.CompressFormat.JPEG, 92, output)) { context.getString(R.string.hayai_eh_preview_crop_encode_failed) }
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
            require(total <= limit) { context.getString(R.string.hayai_eh_preview_image_too_large) }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_LISTING_PAGES = 200
        const val MAX_IMAGE_BYTES = 12L * 1024L * 1024L
    }
}
