package dev.ahmedmohamed.hayai.novel.reader

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.ahmedmohamed.hayai.novel.source.NovelAssetProvider
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.net.URLConnection

internal class NovelAssetWebViewClient(
    private val source: Source,
    private val chapterUrl: () -> String,
    private val blockMedia: () -> Boolean,
) : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest,
    ): WebResourceResponse? = intercept(request.url)

    @Deprecated("Deprecated by Android")
    override fun shouldInterceptRequest(
        view: WebView?,
        url: String?,
    ): WebResourceResponse? = url?.let(Uri::parse)?.let(::intercept)

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest,
    ): Boolean = request.url.scheme != "about"

    @Deprecated("Deprecated by Android")
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        url: String?,
    ): Boolean = Uri.parse(url.orEmpty()).scheme != "about"

    private fun intercept(uri: Uri): WebResourceResponse? {
        if (blockMedia() && isMedia(uri)) return emptyResponse()
        if (uri.scheme !in ASSET_SCHEMES) return null
        val provider = source as? NovelAssetProvider ?: return notFoundResponse()
        val path = decodeAssetPath(uri) ?: return notFoundResponse()
        val stream = runBlocking { provider.getChapterAsset(chapterUrl(), path) } ?: return notFoundResponse()
        return WebResourceResponse(mimeType(path), null, stream)
    }

    private fun decodeAssetPath(uri: Uri): String? {
        val encoded =
            buildString {
                uri.encodedAuthority?.takeIf { it.isNotBlank() }?.let(::append)
                uri.encodedPath?.let(::append)
            }.trimStart('/')
        if (encoded.isBlank()) return null
        return Uri.decode(encoded).replace('\\', '/').takeUnless { path ->
            path.startsWith('/') || path.split('/').any { it == ".." }
        }
    }

    private fun isMedia(uri: Uri): Boolean =
        uri.scheme in ASSET_SCHEMES ||
            uri.path
                .orEmpty()
                .substringAfterLast('.', "")
                .lowercase() in MEDIA_EXTENSIONS

    private fun mimeType(path: String): String =
        URLConnection.guessContentTypeFromName(path) ?: when (path.substringAfterLast('.', "").lowercase()) {
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            else -> "application/octet-stream"
        }

    private fun emptyResponse() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private fun notFoundResponse() =
        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private companion object {
        val ASSET_SCHEMES = setOf("hayai-novel-image", "novel-image")
        val MEDIA_EXTENSIONS =
            setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "avif", "bmp", "mp3", "m4a", "aac", "ogg", "wav", "mp4", "webm")
    }
}
