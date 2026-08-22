package dev.ahmedmohamed.hayai.adult.eh.network

import dev.ahmedmohamed.hayai.adult.eh.domain.EhBrowsePage
import dev.ahmedmohamed.hayai.adult.eh.domain.EhDetailsPage
import dev.ahmedmohamed.hayai.adult.eh.domain.EhFailure
import dev.ahmedmohamed.hayai.adult.eh.domain.EhGalleryPage
import dev.ahmedmohamed.hayai.adult.eh.domain.EhResolvedImage
import dev.ahmedmohamed.hayai.adult.eh.domain.EhPreviewPage
import dev.ahmedmohamed.hayai.adult.eh.domain.EhRevision
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSearchCursor
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSearchSpec
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionState
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionStore
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Reader
import java.io.IOException

class EhHttpGateway(
    client: OkHttpClient,
    private val sessions: EhSessionStore,
) {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

    suspend fun browse(
        site: EhSite,
        spec: EhSearchSpec,
        cursor: EhSearchCursor? = null,
    ): EhBrowsePage {
        val url = EhRequestBuilder.search(site, spec, cursor)
        return getDocument(site, url.toString()) { html, location -> EhHtmlParser.parseBrowse(html, location, site) }
    }

    suspend fun latest(
        site: EhSite,
        cursor: EhSearchCursor.Gallery? = null,
    ): EhBrowsePage {
        val url = EhRequestBuilder.latest(site, cursor)
        return getDocument(site, url.toString()) { html, location -> EhHtmlParser.parseBrowse(html, location, site) }
    }

    suspend fun popular(site: EhSite): EhBrowsePage {
        val url = EhRequestBuilder.popular(site)
        return getDocument(site, url.toString()) { html, location -> EhHtmlParser.parseBrowse(html, location, site) }
    }

    suspend fun details(site: EhSite, key: GalleryKey): EhDetailsPage =
        getDocument(site, key.absoluteUrl(site)) { html, location -> EhHtmlParser.parseDetails(html, location, site) }

    suspend fun revisionChain(site: EhSite, start: GalleryKey): List<EhRevision> {
        val traversal = EhParentTraversal(start)
        val newestToOldest = mutableListOf<EhRevision>()
        var key: GalleryKey? = start
        while (key != null) {
            val details = details(site, key)
            newestToOldest += EhRevision(details.metadata.key, details.metadata.title, details.metadata.postedAtMillis)
            key = traversal.follow(details.metadata.parent)
        }
        return newestToOldest.asReversed()
    }

    suspend fun pages(site: EhSite, key: GalleryKey): List<EhGalleryPage> {
        val accumulator = EhPageAccumulator()
        var url: String? = key.absoluteUrl(site)
        while (url != null) {
            val batch = getDocument(site, url) { html, location -> EhHtmlParser.parsePageBatch(html, location) }
            url = accumulator.add(batch)
        }
        return accumulator.pages()
    }

    suspend fun resolveImage(site: EhSite, pageUrl: String): EhResolvedImage =
        getDocument(site, pageUrl) { html, location -> EhHtmlParser.parseImagePage(html, location) }

    suspend fun previews(
        site: EhSite,
        key: GalleryKey,
        page: Int = 0,
    ): EhPreviewPage {
        require(page in 0..199)
        val url = site.baseUrl + key.normalizedPath + "&p=$page"
        return getDocument(site, url) { html, location -> EhHtmlParser.parsePreviewPage(html, location, site) }
    }

    fun imageRequest(site: EhSite, imageUrl: String): Request = request(site, imageUrl)

    suspend fun executeImage(request: Request): Response =
        try {
            client.newCall(request).awaitSuccess()
        } catch (failure: IOException) {
            throw EhFailure.Network("E-Hentai image request failed", failure)
        }

    private suspend fun <T> getDocument(
        site: EhSite,
        url: String,
        parser: (String, String) -> T,
    ): T = withContext(Dispatchers.IO) {
        ensureAvailable(site)
        val response = try {
            client.newCall(request(site, url)).execute()
        } catch (failure: IOException) {
            throw EhFailure.Network("E-Hentai request failed", failure)
        }
        response.use {
            when (it.code) {
                401, 403 -> throw EhFailure.AuthenticationRequired()
                404 -> throw EhFailure.GalleryNotFound(runCatching { GalleryKey.parse(url) }.getOrNull())
                429 -> throw EhFailure.RateLimited(
                    "E-Hentai rate-limited this request",
                    it.header("Retry-After")?.toLongOrNull(),
                )
            }
            if (!it.isSuccessful) throw EhFailure.Network("E-Hentai returned HTTP ${it.code}")
            val declared = it.body.contentLength()
            if (declared > MAX_DOCUMENT_BYTES) throw EhFailure.BoundsExceeded("E-Hentai response is too large")
            val html = it.body.charStream().use { reader -> reader.readBounded(MAX_DOCUMENT_CHARS) }
            parser(html, it.request.url.toString())
        }
    }

    private fun request(site: EhSite, url: String): Request {
        ensureAvailable(site)
        return Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Cookie", sessions.cookieHeader(site).value)
            .header("Referer", site.baseUrl + "/")
            .build()
    }

    private fun ensureAvailable(site: EhSite) {
        if (site == EhSite.ExHentai && sessions.state.value !is EhSessionState.Verified) {
            throw EhFailure.AuthenticationRequired("Sign in and verify ExHentai access in Hayai settings")
        }
    }

    private fun Reader.readBounded(maxChars: Int): String {
        val result = StringBuilder(minOf(maxChars, 8192))
        val buffer = CharArray(8192)
        while (result.length <= maxChars) {
            val remaining = maxChars + 1 - result.length
            val count = read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            result.append(buffer, 0, count)
        }
        if (result.length > maxChars) throw EhFailure.BoundsExceeded("E-Hentai response is too large")
        return result.toString()
    }

    private companion object {
        const val MAX_DOCUMENT_CHARS = 16 * 1024 * 1024
        const val MAX_DOCUMENT_BYTES = 32L * 1024 * 1024
    }
}
