package dev.ahmedmohamed.hayai.novel.reader

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.ahmedmohamed.hayai.novel.source.NovelAssetProvider
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.net.URLConnection

internal class NovelAssetWebViewClient(
    private val assetProvider: NovelAssetProvider,
    private val chapterUrl: () -> String,
    private val offline: () -> Boolean,
    private val chapterUrlForId: (Long) -> String? = { null },
    private val offlineForId: (Long) -> Boolean? = { null },
    private val blockMedia: () -> Boolean,
    private val fontStore: NovelFontStore,
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
        if (uri.scheme == NovelFontStore.WEB_SCHEME) {
            val id = uri.encodedAuthority.orEmpty()
            val item = fontStore.fonts().firstOrNull { it.id == id } ?: return notFoundResponse()
            val stream = fontStore.open(id) ?: return notFoundResponse()
            return WebResourceResponse(fontMimeType(item.extension), null, stream).apply {
                responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
            }
        }
        if (blockMedia() && isMedia(uri)) return emptyResponse()
        if (offline() && uri.scheme in REMOTE_SCHEMES) return notFoundResponse()
        if (uri.scheme !in ASSET_SCHEMES) return null
        val path = decodeAssetPath(uri) ?: return notFoundResponse()
        val blockId = uri.getQueryParameter(CHAPTER_ID_QUERY)?.toLongOrNull()
        val resolvedChapterUrl = blockId?.let(chapterUrlForId) ?: chapterUrl()
        val resolvedOffline = blockId?.let(offlineForId) ?: offline()
        if (resolvedOffline && uri.scheme in REMOTE_SCHEMES) return notFoundResponse()
        val stream = runBlocking { assetProvider.getChapterAsset(resolvedChapterUrl, path) } ?: return notFoundResponse()
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

    private fun fontMimeType(extension: String): String =
        when (extension) {
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }

    private fun emptyResponse() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private fun notFoundResponse() =
        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private companion object {
        val ASSET_SCHEMES = setOf("hayai-novel-image", "novel-image")
        val REMOTE_SCHEMES = setOf("http", "https")
        const val CHAPTER_ID_QUERY = "hayaiChapterId"
        val MEDIA_EXTENSIONS =
            setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "avif", "bmp", "mp3", "m4a", "aac", "ogg", "wav", "mp4", "webm")
    }
}
