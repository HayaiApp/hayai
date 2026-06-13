package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.MetadataMangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.PagePreviewPage
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import eu.kanade.tachiyomi.util.asJsoup
import exh.debug.DebugToggles
import exh.eh.EHTags
import exh.metadata.MetadataUtil
import exh.eh.EHentaiUpdateHelper
import exh.eh.EHentaiUpdateWorkerConstants
import exh.eh.GalleryEntry
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.EH_GENRE_NAMESPACE
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.EH_META_NAMESPACE
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.EH_UPLOADER_NAMESPACE
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.EH_VISIBILITY_NAMESPACE
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.TAG_TYPE_LIGHT
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.TAG_TYPE_NORMAL
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.TAG_TYPE_WEAK
import exh.metadata.metadata.RaisedSearchMetadata.Companion.TAG_TYPE_VIRTUAL
import exh.metadata.metadata.RaisedSearchMetadata.Companion.toGenreString
import exh.metadata.metadata.base.RaisedTag
import exh.source.ExhPreferences
import exh.ui.login.EhLoginActivity
import exh.util.UriFilter
import exh.util.UriGroup
import exh.util.dropBlank
import exh.util.ignore
import exh.util.nullIfBlank
import exh.util.trimAll
import exh.util.trimOrNull
import exh.util.urlImportFetchSearchManga
import exh.util.urlImportFetchSearchMangaSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import rx.Observable
import yokai.util.koin.injectLazy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

class EHentai(
    override val id: Long,
    val exh: Boolean,
    val context: Context,
) : HttpSource(),
    MetadataSource<EHentaiSearchMetadata, Document>,
    UrlImportableSource,
    NamespaceSource,
    PagePreviewSource {

    override val metaClass = EHentaiSearchMetadata::class

    override fun newMetaInstance() = EHentaiSearchMetadata()

    private val logger = Logger.withTag(if (exh) "ExHentai" else "EHentai")

    private val domain: String
        get() = if (exh) {
            "exhentai.org"
        } else {
            "e-hentai.org"
        }

    override val baseUrl: String
        get() = "https://$domain"

    // Per-site /api.php host. EhViewer (the most actively-maintained Android reference) uses
    // a separate `s.exhentai.org` subdomain for ExH because showkey is site-scoped and the
    // returned image URLs route through that site's H@H pool. Using api.e-hentai.org for ExH
    // (the old SY behaviour) silently fails for ExH-restricted galleries that don't exist
    // on the public EH index.
    private val apiUrl: String
        get() = if (exh) "https://s.exhentai.org/api.php" else "https://api.e-hentai.org/api.php"

    override val lang = "all"
    override val supportsLatest = true

    private val exhPreferences: ExhPreferences by injectLazy()
    private val updateHelper: EHentaiUpdateHelper by injectLazy()

    /**
     * Per-gallery session state shared between getPageList (which discovers MPV link and
     * gallery id/token) and getImageUrl (which needs them to call /api.php). Keyed by
     * gallery id because page URLs (`/s/{imgkey}/{gid}-{page}`) carry the gid but not the
     * chapter URL. ConcurrentHashMap handles concurrent reads/writes from N worker
     * coroutines safely.
     */
    private val gallerySessions = ConcurrentHashMap<Long, GallerySession>()

    /**
     * Image-URL resolution state for a single gallery. Promotes itself:
     *   - Starts as Mode.None or Mode.MPV at gallery load time.
     *   - If None and the first HTML scrape yields a `showkey`, promotes itself to
     *     Mode.ShowKey so all subsequent images use the showpage API.
     */
    private class GallerySession(
        val gid: Long,
        val token: String,
        val mpvKey: String? = null,
        val mpvImageKeys: Map<Int, String>? = null,
    ) {
        // Volatile so the worker that extracts the showkey publishes it safely to peers.
        @Volatile var showKey: String? = null
        val hasMpv: Boolean get() = mpvKey != null && mpvImageKeys != null
    }

    class StaleIgneousException : Exception(
        "Invalid igneous cookie, try re-logging or finding a correct one to input in the login menu",
    )

    class SadPandaException : Exception(
        "ExHentai returned an empty response. Your IP may be temporarily blocked or your session cookies have expired — try re-logging.",
    )

    /**
     * Pre-flight check before firing any batch of requests. Mirrors EhViewer's
     * `EhCookieStore` stale-igneous detection: if the user's igneous cookie is the literal
     * sentinel `mystery` (which EH returns when the cookie is wrong but the session is
     * otherwise valid), every subsequent request will return empty HTML. Catching this
     * upfront prevents N parallel API calls from all failing in lockstep.
     */
    private fun checkExhSession() {
        if (exh && exhPreferences.igneousVal.get().equals("mystery", true)) {
            throw StaleIgneousException()
        }
    }

    /**
     * Gallery list entry
     */
    data class ParsedManga(val fav: Int, val manga: SManga, val metadata: EHentaiSearchMetadata)

    private fun extendedGenericMangaParse(doc: Document) = with(doc) {
        // Parse mangas (supports compact + extended layout)
        val parsedMangas = select(".itg > tbody > tr").filter { element ->
            // Do not parse header and ads
            element.selectFirst("th") == null && element.selectFirst(".itd") == null
        }.map { body ->
            val thumbnailElement = body.selectFirst(".gl1e img, .gl2c .glthumb img")!!
            val column2 = body.selectFirst(".gl3e, .gl2c")!!
            val linkElement = body.selectFirst(".gl3c > a, .gl2e > div > a")!!
            val infoElement = body.selectFirst(".gl3e")

            // why is column2 null
            val favElement = column2.children().find { it.attr("style").startsWith("border-color") }
            // Use direct children of .gl3e and skip the optional favorite-color border div
            // (only present on ExHentai when the gallery is in your favorites). Without this
            // filter, positional indexing shifts by one for non-favorited entries and the
            // category badge / date / rating / uploader / length all read the wrong source.
            val infoChildren = infoElement?.children()
                ?.filter { it.tagName() == "div" && !it.attr("style").startsWith("border-color") }
            val parsedTags = mutableListOf<RaisedTag>()
            val categoryToken = if (infoElement != null) {
                // Prefer class-based selection over positional indexing: category badges
                // carry .cn (compact) or .cs (extended) regardless of layout variant.
                getGenre(infoElement.selectFirst(".cn, .cs") ?: infoChildren?.getOrNull(0))
            } else {
                getGenre(body.selectFirst(".gl1c div"))
            }

            ParsedManga(
                fav = FAVORITES_BORDER_HEX_COLORS.indexOf(
                    favElement?.attr("style")?.substring(14, 17),
                ),
                manga = SManga.create().apply {
                    // Get title
                    title = thumbnailElement.attr("title")
                    url = EHentaiSearchMetadata.normalizeUrl(linkElement.attr("href"))
                    // Get image
                    thumbnail_url = thumbnailElement.attr("src")

                    if (infoElement != null) {
                        linkElement.select("div div").getOrNull(1)?.select("tr")?.forEach { row ->
                            val namespace = row.select(".tc").text().removeSuffix(":")
                            parsedTags.addAll(
                                row.select("div").map { element ->
                                    RaisedTag(
                                        namespace,
                                        element.text().trim(),
                                        when {
                                            element.hasClass("gtl") -> TAG_TYPE_LIGHT
                                            element.hasClass("gtw") -> TAG_TYPE_WEAK
                                            else -> TAG_TYPE_NORMAL
                                        },
                                    )
                                },
                            )
                        }
                    } else {
                        val tagElement = body.selectFirst(".gl3c > a")!!
                        val tagElements = tagElement.select("div")
                        tagElements.forEach { element ->
                            if (element.className() == "gt") {
                                val namespace = element.attr("title").substringBefore(":").trimOrNull() ?: "misc"
                                parsedTags += RaisedTag(
                                    namespace,
                                    element.attr("title").substringAfter(":").trim(),
                                    TAG_TYPE_NORMAL,
                                )
                            }
                        }
                    }

                    // Prepend the EH category token (e.g. "doujinshi", "manga", "imageset") so the
                    // browse list/grid holders can render a colored category badge. The token is a
                    // single lowercased word, distinct from namespaced tags like "language:english".
                    val tagsString = parsedTags.toGenreString()
                    genre = listOfNotNull(categoryToken, tagsString.ifBlank { null })
                        .joinToString(", ")
                },
                metadata = EHentaiSearchMetadata().apply {
                    tags += parsedTags

                    if (infoElement != null) {
                        genre = categoryToken

                        // After filtering out the favorite-border div the order is
                        // [category, date, rating, uploader, length]. Pick the rating by
                        // class to stay robust even if EH adds new metadata divs later.
                        datePosted = getDateTag(infoChildren?.getOrNull(1))

                        averageRating = getRating(infoElement.selectFirst(".ir") ?: infoChildren?.getOrNull(2))

                        uploader = getUploader(infoChildren?.getOrNull(3))

                        length = getPageCount(infoChildren?.getOrNull(4))
                    } else {
                        genre = categoryToken

                        val info = body.selectFirst(".gl2c")!!
                        val extraInfo = body.selectFirst(".gl4c")!!

                        val infoList = info.select("div div")

                        datePosted = getDateTag(infoList.getOrNull(8))

                        averageRating = getRating(infoList.getOrNull(9))

                        val extraInfoList = extraInfo.select("div")

                        if (extraInfoList.getOrNull(2) == null) {
                            uploader = getUploader(extraInfoList.getOrNull(0))

                            length = getPageCount(extraInfoList.getOrNull(1))
                        } else {
                            uploader = getUploader(extraInfoList.getOrNull(1))

                            length = getPageCount(extraInfoList.getOrNull(2))
                        }
                    }
                },
            )
        }.ifEmpty {
            selectFirst(".searchwarn")?.let { throw Exception(it.text()) }
            emptyList()
        }

        val parsedLocation = doc.location().toHttpUrlOrNull()
        val isReversed = parsedLocation != null && parsedLocation.queryParameterNames.contains(REVERSE_PARAM)

        // Add to page if required
        val hasNextPage = if (isReversed) {
            select(".searchnav >div > a")
                .any { "prev" in it.attr("href") }
        } else {
            select(".searchnav >div > a")
                .any { "next" in it.attr("href") }
        }
        val nextPage = if (parsedLocation?.pathSegments?.contains("toplist.php") == true) {
            ((parsedLocation.queryParameter("p")?.toLong() ?: 0) + 2).takeIf { it <= 200 }
        } else if (hasNextPage) {
            parsedMangas.let { if (isReversed) it.first() else it.last() }
                .manga
                .url
                .let { EHentaiSearchMetadata.galleryId(it).toLong() }
        } else {
            null
        }

        parsedMangas.let { if (isReversed) it.reversed() else it } to nextPage
    }

    private fun getGenre(element: Element?): String? {
        // onclick is typically "document.location='https://e-hentai.org/manga/'"; the
        // trailing slash means a single substringAfterLast('/') yields just "'" → empty
        // after removeSuffix. Strip the trailing quote+slash first, then take the last
        // path segment. ifBlank{null} ensures we fall back to text instead of returning
        // an empty string when the chain produces nothing usable.
        val fromOnclick = element?.attr("onclick")
            ?.nullIfBlank()
            ?.removeSuffix("'")
            ?.trimEnd('/')
            ?.substringAfterLast('/')
            ?.trim()
            ?.ifBlank { null }

        return fromOnclick ?: element?.text()
            ?.nullIfBlank()
            ?.lowercase()
            ?.replace(" ", "")
            ?.trim()
    }

    private fun getDateTag(element: Element?): Long? {
        val text = element?.text()?.nullIfBlank()
        return if (text != null) {
            val date = ZonedDateTime.parse(text, MetadataUtil.EX_DATE_FORMAT.withZone(ZoneOffset.UTC))
            date?.toInstant()?.toEpochMilli()
        } else {
            null
        }
    }

    private fun getRating(element: Element?): Double? {
        val ratingStyle = element?.attr("style")?.nullIfBlank()
        return if (ratingStyle != null) {
            val matches = RATING_REGEX.findAll(ratingStyle)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .toList()
            if (matches.size == 2) {
                var rate = 5 - matches[0] / 16
                if (matches[1] == 21) {
                    rate--
                    rate + 0.5
                } else {
                    rate.toDouble()
                }
            } else {
                null
            }
        } else {
            null
        }
    }

    private fun getUploader(element: Element?): String? {
        return element?.select("a")?.text()?.trimOrNull()
    }

    private fun getPageCount(element: Element?): Int? {
        val pageCount = element?.text()?.trimOrNull()
        return if (pageCount != null) {
            PAGE_COUNT_REGEX.find(pageCount)?.value?.toIntOrNull()
        } else {
            null
        }
    }

    /**
     * Parse a list of galleries
     */
    private fun genericMangaParse(
        response: Response,
    ) = extendedGenericMangaParse(response.asJsoup()).let { (parsedManga, nextPage) ->
        MetadataMangasPage(
            parsedManga.map { it.manga },
            nextPage != null,
            parsedManga.map { it.metadata },
            nextPage,
        )
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = getChapterList(manga) {}

    suspend fun getChapterList(manga: SManga, throttleFunc: suspend () -> Unit): List<SChapter> {
        // Pull all the way to the root gallery
        var url = manga.url
        var doc: Document

        while (true) {
            val gid = EHentaiSearchMetadata.galleryId(url).toInt()
            val cachedParent = updateHelper.parentLookupTable.get(
                gid,
            )
            if (cachedParent == null) {
                throttleFunc()
                doc = client.newCall(exGet(baseUrl + url)).awaitSuccess().asJsoup()

                val parentLink = doc.select("#gdd .gdt1").find { el ->
                    el.text().lowercase() == "parent:"
                }?.nextElementSibling()?.selectFirst("a")?.attr("href")

                if (parentLink != null) {
                    updateHelper.parentLookupTable.put(
                        gid,
                        GalleryEntry(
                            EHentaiSearchMetadata.galleryId(parentLink),
                            EHentaiSearchMetadata.galleryToken(parentLink),
                        ),
                    )
                    url = EHentaiSearchMetadata.normalizeUrl(parentLink)
                } else {
                    break
                }
            } else {
                logger.d { "Parent cache hit: $gid!" }
                url = EHentaiSearchMetadata.idAndTokenToUrl(
                    cachedParent.gId,
                    cachedParent.gToken,
                )
            }
        }
        val newDisplay = doc.select("#gnd a")
        // Build chapter for root gallery
        val location = doc.location()
        val self = SChapter.create().apply {
            this.url = EHentaiSearchMetadata.normalizeUrl(location)
            name = "v1: " + (doc.selectFirst("#gn")?.text() ?: "")
            chapter_number = 1f
            date_upload = doc.select("#gdd .gdt1").find { el ->
                el.text().lowercase() == "posted:"
            }?.nextElementSibling()?.text()?.let { postedText ->
                runCatching {
                    ZonedDateTime.parse(postedText, MetadataUtil.EX_DATE_FORMAT.withZone(ZoneOffset.UTC))
                        .toInstant().toEpochMilli()
                }.getOrNull()
            } ?: 0L
            scanlator = EHentaiSearchMetadata.galleryId(location)
        }
        // Build and append the rest of the galleries
        return if (DebugToggles.INCLUDE_ONLY_ROOT_WHEN_LOADING_EXH_VERSIONS.enabled) {
            listOf(self)
        } else {
            newDisplay.mapIndexed { index, newGallery ->
                val link = newGallery.attr("href")
                val chapterName = newGallery.text()
                val posted = (newGallery.nextSibling() as? TextNode)?.text()?.removePrefix(", added ") ?: ""
                SChapter.create().apply {
                    this.url = EHentaiSearchMetadata.normalizeUrl(link)
                    name = "v${index + 2}: $chapterName"
                    chapter_number = index + 2f
                    date_upload = runCatching {
                        ZonedDateTime.parse(posted, MetadataUtil.EX_DATE_FORMAT.withZone(ZoneOffset.UTC))
                            .toInstant().toEpochMilli()
                    }.getOrDefault(0L)
                    scanlator = EHentaiSearchMetadata.galleryId(link)
                }
            }.reversed() + self
        }
    }

    @Deprecated("Use the 1.x API instead", replaceWith = ReplaceWith("getChapterList"))
    @Suppress("DEPRECATION")
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = fetchChapterList(manga) {}

    @Deprecated("Use the 1.x API instead", replaceWith = ReplaceWith("getChapterList"))
    fun fetchChapterList(manga: SManga, throttleFunc: suspend () -> Unit): Observable<List<SChapter>> {
        // Source API compat: Observable wrapper for suspend function
        return Observable.empty()
    }

    // Walks thumb-index pages via the explicit next-link; synthesizing `?p=N` URLs and
    // fanning out in parallel caused the first batch to repeat throughout the chapter.
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        checkExhSession()

        val firstUrl = baseUrl + chapter.url
        val gid = EHentaiSearchMetadata.galleryId(chapter.url).toLongOrNull()
        val token = EHentaiSearchMetadata.galleryToken(chapter.url)

        val firstDoc = client.newCall(exGet(firstUrl)).awaitSuccess().asJsoup()
        val mpvHref = firstDoc.selectFirst("#gmid a[href*=/mpv/]")?.attr("href")

        val allPageUrls = ArrayList<String>()
        allPageUrls += parseChapterPage(firstDoc)
        var nextUrl = nextPageUrl(firstDoc)
        while (nextUrl != null) {
            val doc = client.newCall(exGet(url = nextUrl, additionalHeaders = headers)).awaitSuccess().asJsoup()
            allPageUrls += parseChapterPage(doc)
            nextUrl = nextPageUrl(doc)
        }

        if (gid != null) {
            val session = if (mpvHref != null) {
                loadMpvSession(mpvHref, gid, token) ?: GallerySession(gid = gid, token = token)
            } else {
                GallerySession(gid = gid, token = token)
            }
            gallerySessions[gid] = session
        }

        return allPageUrls.mapIndexed { i, url -> Page(i, url) }
    }

    @Deprecated("Use the 1.x API instead", replaceWith = ReplaceWith("getPageList"))
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.fromCallable {
            kotlinx.coroutines.runBlocking { getPageList(chapter) }
        }
    }

    private fun parseChapterPage(response: Element) = with(response) {
        select(".gdtm a").mapNotNull {
            val pageNum = it.child(0).attr("alt").toIntOrNull() ?: return@mapNotNull null
            Pair(pageNum, it.attr("href"))
        }.plus(
            select("#gdt a").mapNotNull {
                val pageNum = it.child(0).attr("title").removePrefix("Page ").substringBefore(":").toIntOrNull()
                    ?: return@mapNotNull null
                Pair(pageNum, it.attr("href"))
            },
        ).sortedBy(Pair<Int, String>::first).map { it.second }
    }

    private fun nextPageUrl(element: Element): String? =
        element.select("a[onclick=return false]").last()?.let {
            if (it.text() == ">") it.attr("href") else null
        }

    /**
     * Fetches the /mpv/ page and parses the JS-embedded `mpvkey` + `imagelist`. Returns
     * null if the page wasn't parseable (which can happen if the user lost the perk
     * mid-session or EH's template changes); caller falls back to non-MPV mode.
     */
    private suspend fun loadMpvSession(mpvHref: String, gid: Long, token: String): GallerySession? {
        return try {
            val absHref = if (mpvHref.startsWith("http")) mpvHref else baseUrl + mpvHref
            val doc = client.newCall(exGet(absHref)).awaitSuccess().asJsoup()
            val scriptText = doc.select("script").joinToString("\n") { it.data() }
            val mpvKey = MPV_KEY_REGEX.find(scriptText)?.groupValues?.getOrNull(1)
            val imageListJson = MPV_IMAGELIST_REGEX.find(scriptText)?.groupValues?.getOrNull(1)
            if (mpvKey == null || imageListJson == null) {
                logger.w { "MPV link present for gid=$gid but parser failed; falling back to showpage" }
                return null
            }
            val imageList = Json.parseToJsonElement(imageListJson).jsonArray
            val keys = buildMap<Int, String> {
                imageList.forEachIndexed { idx, el ->
                    el.jsonObject["k"]?.jsonPrimitive?.contentOrNull?.let { put(idx + 1, it) }
                }
            }
            if (keys.isEmpty()) {
                logger.w { "MPV imagelist parsed empty for gid=$gid" }
                return null
            }
            GallerySession(
                gid = gid,
                token = token,
                mpvKey = mpvKey,
                mpvImageKeys = keys,
            )
        } catch (e: Exception) {
            logger.w(e) { "MPV session load failed for gid=$gid; falling back to showpage" }
            null
        }
    }

    override fun popularMangaRequest(page: Int): Request {
        // /popular has no pagination, use main page with cursor-based ?next= instead
        return exGet(baseUrl, page)
    }

    private fun <T : MangasPage> Observable<T>.checkValid(): Observable<MangasPage> = map {
        it.checkValid()
    }

    private fun <T : MangasPage> T.checkValid(): MangasPage {
        // Only stale-igneous is reliably detectable here without false-positiving on
        // legitimately-empty search results. The generic Sad Panda case (empty response
        // with otherwise-valid cookies) is detected upstream in the cookie interceptor by
        // inspecting raw body size, which doesn't confuse "no search matches" with "banned".
        if (exh && mangas.isEmpty() && exhPreferences.igneousVal.get().equals("mystery", true)) {
            throw StaleIgneousException()
        }
        return this
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        @Suppress("DEPRECATION")
        return super<HttpSource>.fetchLatestUpdates(page).checkValid()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return super<HttpSource>.getLatestUpdates(page).checkValid()
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPopularManga"))
    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        @Suppress("DEPRECATION")
        return super<HttpSource>.fetchPopularManga(page).checkValid()
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        return super<HttpSource>.getPopularManga(page).checkValid()
    }

    // Support direct URL importing
    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getSearchManga"))
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        urlImportFetchSearchManga(context, query) {
            @Suppress("DEPRECATION")
            super<HttpSource>.fetchSearchManga(page, query, filters).checkValid()
        }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        return urlImportFetchSearchMangaSuspend(context, query) {
            super<HttpSource>.getSearchManga(page, query, filters).checkValid()
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val toplist = ToplistOption.entries[filters.firstNotNullOfOrNull { (it as? ToplistOptions)?.state } ?: 0]
        if (toplist != ToplistOption.NONE) {
            // Use the source's own baseUrl so ExH users land on exhentai.org/toplist.php
            // with their igneous cookie, rather than being redirected to e-hentai.org which
            // won't show ExH-restricted entries.
            val uri = baseUrl.toUri().buildUpon()
            uri.appendPath("toplist.php")
            uri.appendQueryParameter("tl", toplist.index.toString())
            uri.appendQueryParameter("p", (page - 1).toString())

            return exGet(url = uri.toString())
        }

        val uri = baseUrl.toUri().buildUpon()
        val isReverseFilterEnabled = filters.any { it is ReverseFilter && it.state }
        val jumpSeekValue = filters.firstNotNullOfOrNull { (it as? JumpSeekFilter)?.state?.nullIfBlank() }

        uri.appendQueryParameter("f_apply", "Apply+Filter")
        uri.appendQueryParameter("f_search", (query + " " + combineQuery(filters)).trim())
        filters.forEach {
            if (it is UriFilter) it.addToUri(uri)
        }
        // Reverse search results on filter
        if (isReverseFilterEnabled) {
            uri.appendQueryParameter(REVERSE_PARAM, "on")
        }
        if (jumpSeekValue != null && page == 1) {
            if (
                MATCH_SEEK_REGEX.matches(jumpSeekValue) ||
                (
                    MATCH_YEAR_REGEX.matches(jumpSeekValue) &&
                        jumpSeekValue.toIntOrNull()?.let {
                            it in 2007..2099
                        } == true
                    )
            ) {
                uri.appendQueryParameter("seek", jumpSeekValue)
            } else if (MATCH_JUMP_REGEX.matches(jumpSeekValue)) {
                uri.appendQueryParameter("jump", jumpSeekValue)
            }
        }

        return exGet(
            url = uri.toString(),
            next = if (!isReverseFilterEnabled) page else null,
            prev = if (isReverseFilterEnabled) page else null,
        )
    }

    override fun latestUpdatesRequest(page: Int) = exGet(baseUrl, page)

    override fun popularMangaParse(response: Response) = genericMangaParse(response)
    override fun searchMangaParse(response: Response) = genericMangaParse(response)
    override fun latestUpdatesParse(response: Response) = genericMangaParse(response)

    // region PagePreviewSource

    override suspend fun getPagePreviewList(
        manga: SManga,
        chapters: List<SChapter>,
        page: Int,
    ): PagePreviewPage {
        // Use manga.url (the gallery the user actually added/reads), not
        // chapters.lastOrNull() which after sortedByDescending(source_order) always
        // resolves to the newest version (vN). For multi-version EH galleries the
        // newest revision often has a different page count than the root, which made
        // previews show a different gallery than the reader (Komikku/SY share the bug).
        val galleryUrl = (baseUrl + manga.url)
            .toHttpUrl()
            .newBuilder()
            .removeAllQueryParameters("nw")
            .addQueryParameter("p", (page - 1).toString())
            .build()
            .toString()

        val doc = client.newCall(exGet(galleryUrl)).awaitSuccess().asJsoup()
        val body = doc.body()

        val previews = body.select("#gdt > div > div")
            .plus(body.select("#gdt > a"))
            .mapNotNull { parseNormalPreview(it) }
            .map { PagePreviewInfo(it.index, imageUrl = it.toUrl()) }
            .ifEmpty {
                body.select("#gdt div a img").mapNotNull {
                    val pageNum = it.attr("alt").toIntOrNull() ?: return@mapNotNull null
                    PagePreviewInfo(pageNum, imageUrl = it.attr("src"))
                }
            }

        return PagePreviewPage(
            page = page,
            pagePreviews = previews,
            hasNextPage = doc.select("table.ptt tbody tr td")
                .lastOrNull()
                ?.hasClass("ptdd")
                ?.not() ?: false,
            pagePreviewPages = doc.select("table.ptt tbody tr td a").asReversed()
                .firstNotNullOfOrNull { it.text().toIntOrNull() },
        )
    }

    override suspend fun fetchPreviewImage(page: PagePreviewInfo, cacheControl: CacheControl?): Response {
        return client.newCachelessCallWithProgress(
            exGet(page.imageUrl, cacheControl = cacheControl),
            page,
        ).awaitSuccess()
    }

    private fun parseNormalPreview(element: Element): EHentaiThumbnailPreview? {
        val imgElement = element.selectFirst("img")
        val index = imgElement?.attr("alt")?.toIntOrNull()
            ?: element.child(0).attr("title").removePrefix("Page ").substringBefore(":").toIntOrNull()
            ?: return null
        val styleElement = if (imgElement != null) element else element.child(0)
        val styles = styleElement.attr("style").split(";").mapNotNull { it.trimOrNull() }

        val width = styles.firstOrNull { it.startsWith("width:") }
            ?.removePrefix("width:")?.removeSuffix("px")?.toIntOrNull() ?: return null
        val height = styles.firstOrNull { it.startsWith("height:") }
            ?.removePrefix("height:")?.removeSuffix("px")?.toIntOrNull() ?: return null
        val background = styles.firstOrNull { it.startsWith("background:") }
            ?.removePrefix("background:")?.split(" ") ?: return null
        val url = background.firstOrNull { it.startsWith("url(") }
            ?.removePrefix("url(")?.removeSuffix(")") ?: return null
        val widthOffset = background.firstOrNull { it.startsWith("-") }
            ?.removePrefix("-")?.removeSuffix("px")?.toIntOrNull() ?: 0

        return EHentaiThumbnailPreview(url, width, height, widthOffset, index)
    }

    // endregion

    private fun exGet(
        url: String,
        next: Int? = null,
        prev: Int? = null,
        additionalHeaders: Headers? = null,
        cacheControl: CacheControl? = null,
    ): Request {
        return GET(
            when {
                next != null && next > 1 -> addParam(url, "next", next.toString())
                prev != null && prev > 0 -> addParam(url, "prev", prev.toString())
                else -> url
            },
            if (additionalHeaders != null) {
                val headers = headers.newBuilder()
                additionalHeaders.toMultimap().forEach { (t, u) ->
                    u.forEach {
                        headers.add(t, it)
                    }
                }
                headers.build()
            } else {
                headers
            },
        ).let {
            if (cacheControl == null) {
                it
            } else {
                it.newBuilder().cacheControl(cacheControl).build()
            }
        }
    }

    /**
     * Returns an observable with the updated details for a manga.
     */
    // Source API compat: Observable wrapper for suspend getMangaDetails
    @Suppress("DEPRECATION")
    @Deprecated("Use the 1.x API instead", replaceWith = ReplaceWith("getMangaDetails"))
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.fromCallable {
            kotlinx.coroutines.runBlocking { getMangaDetails(manga) }
        }
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val exception = Exception("Async stacktrace")
        val response = client.newCall(mangaDetailsRequest(manga)).await()
        if (response.isSuccessful) {
            val doc = response.asJsoup()
            val newerGallery = doc.select("#gnd a").lastOrNull()
            val pre = if (
                newerGallery != null && DebugToggles.PULL_TO_ROOT_WHEN_LOADING_EXH_MANGA_DETAILS.enabled
            ) {
                val sManga = SManga.create().apply {
                    url = EHentaiSearchMetadata.normalizeUrl(newerGallery.attr("href"))
                    title = manga.title
                }
                client.newCall(mangaDetailsRequest(sManga)).awaitSuccess().asJsoup()
            } else {
                doc
            }
            return parseGalleryPage(pre, manga)
        } else {
            response.close()

            if (response.code == 404) {
                throw GalleryNotFoundException(exception)
            } else {
                throw Exception("HTTP error ${response.code}", exception)
            }
        }
    }

    override suspend fun parseIntoMetadata(metadata: EHentaiSearchMetadata, input: Document) {
        with(metadata) {
            with(input) {
                val url = location()
                gId = EHentaiSearchMetadata.galleryId(url)
                gToken = EHentaiSearchMetadata.galleryToken(url)

                metadata.exh = this@EHentai.exh
                title = select("#gn").text().trimOrNull()

                altTitle = select("#gj").text().trimOrNull()

                thumbnailUrl = select("#gd1 div").attr("style").nullIfBlank()?.let {
                    it.substring(it.indexOf('(') + 1 until it.lastIndexOf(')'))
                }
                genre = select(".cs")
                    .attr("onclick")
                    .trimOrNull()
                    ?.substringAfterLast('/')
                    ?.removeSuffix("'")

                uploader = select("#gdn").text().trimOrNull()

                select("#gdd tr").forEach {
                    val left = it.select(".gdt1").text().trimOrNull()
                    val rightElement = it.selectFirst(".gdt2") ?: return@forEach
                    val right = rightElement.text().trimOrNull()
                    if (left != null && right != null) {
                        ignore {
                            when (left.removeSuffix(":").lowercase()) {
                                "posted" -> datePosted = ZonedDateTime.parse(
                                    right,
                                    MetadataUtil.EX_DATE_FORMAT.withZone(ZoneOffset.UTC),
                                ).toInstant().toEpochMilli()
                                "parent" -> parent = if (!right.equals("None", true)) {
                                    rightElement.child(0).attr("href")
                                } else {
                                    null
                                }
                                "visible" -> visible = right.nullIfBlank()
                                "language" -> {
                                    language = right.removeSuffix(TR_SUFFIX).trimOrNull()
                                    translated = right.endsWith(TR_SUFFIX, true)
                                }
                                "file size" -> size = MetadataUtil.parseHumanReadableByteCount(right)?.toLong()
                                "length" -> length = right.removeSuffix("pages").trimOrNull()?.toInt()
                                "favorited" -> favorites = right.removeSuffix("times").trimOrNull()?.toInt()
                            }
                        }
                    }
                }

                lastUpdateCheck = System.currentTimeMillis()
                if (datePosted != null &&
                    lastUpdateCheck - datePosted!! > EHentaiUpdateWorkerConstants.GALLERY_AGE_TIME
                ) {
                    aged = true
                    logger.d { "aged $title - too old" }
                }

                ignore {
                    averageRating = select("#rating_label")
                        .text()
                        .removePrefix("Average:")
                        .trimOrNull()
                        ?.toDouble()
                    ratingCount = select("#rating_count")
                        .text()
                        .trimOrNull()
                        ?.toInt()
                }

                tags.clear()
                select("#taglist tr").forEach {
                    val namespace = it.select(".tc").text().removeSuffix(":")
                    tags += it.select("div").map { element ->
                        RaisedTag(
                            namespace,
                            element.text().trim(),
                            when {
                                element.hasClass("gtl") -> TAG_TYPE_LIGHT
                                element.hasClass("gtw") -> TAG_TYPE_WEAK
                                else -> TAG_TYPE_NORMAL
                            },
                        )
                    }
                }

                genre?.let {
                    tags += RaisedTag(EH_GENRE_NAMESPACE, it, TAG_TYPE_VIRTUAL)
                }
                if (aged) {
                    tags += RaisedTag(EH_META_NAMESPACE, "aged", TAG_TYPE_VIRTUAL)
                }
                uploader?.let {
                    tags += RaisedTag(EH_UPLOADER_NAMESPACE, it, TAG_TYPE_VIRTUAL)
                }
                visible?.let {
                    tags += RaisedTag(
                        EH_VISIBILITY_NAMESPACE,
                        it.substringAfter('(').substringBeforeLast(')'),
                        TAG_TYPE_VIRTUAL,
                    )
                }
            }
        }
    }

    /**
     * Parse gallery page via MetadataSource — automatically persists metadata to DB.
     */
    private suspend fun parseGalleryPage(document: Document, manga: SManga): SManga {
        return parseToManga(manga, document)
    }

    /**
     * Parse gallery page to metadata model
     */
    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()

    override fun chapterListParse(response: Response) =
        throw UnsupportedOperationException("Unused method was called somehow!")

    override fun chapterPageParse(response: Response): SChapter =
        throw UnsupportedOperationException("Unused method was called somehow!")

    override fun pageListParse(response: Response) =
        throw UnsupportedOperationException("Unused method was called somehow!")

    /**
     * Resolves a page to its image URL using the cheapest available method:
     *   1. MPV `imagedispatch` API — if the user has the Multi-Page Viewer perk and we
     *      detected the /mpv/ link at gallery-load time. Smallest response payload.
     *   2. `showpage` API — once we've cached a showkey from an earlier HTML scrape in
     *      this gallery. Works for any logged-in user without paid perks.
     *   3. Per-page HTML scrape (legacy) — falls through when neither API key is known
     *      yet. The scrape extracts the showkey from JS so subsequent calls upgrade.
     *
     * `nl` rotation (retry-on-failed-image) is preserved across all three modes: the
     * response's new `s` field is encoded back into `page.url` as `?nl=...`, mirroring
     * the original HTML-scrape behaviour so HttpPageLoader.retryPage works unchanged.
     */
    override suspend fun getImageUrl(page: Page): String {
        val pageUri = page.url.toHttpUrl()
        val segments = pageUri.pathSegments
        // Page URL format: /s/{imgkey}/{gid}-{pageIndex}
        val imgKey = segments.getOrNull(1)
        val gidPagePart = segments.lastOrNull()?.split('-')
        val gid = gidPagePart?.getOrNull(0)?.toLongOrNull()
        val pageIdx = gidPagePart?.getOrNull(1)?.toIntOrNull()
        val nlParam = pageUri.queryParameter("nl")

        val session = gid?.let { gallerySessions[it] }

        // Mode 1: MPV imagedispatch. session != null implies gid != null (session is
        // gid-keyed) — the compiler smart-casts gid accordingly inside the block.
        if (session != null && session.hasMpv && pageIdx != null) {
            val mpvKey = session.mpvKey!!
            val mpvImgKey = session.mpvImageKeys?.get(pageIdx)
            if (mpvImgKey != null) {
                return resolveImageDispatch(page, gid, pageIdx, mpvImgKey, mpvKey, nlParam)
            }
            // Fall through if we somehow don't have a key for this index (shouldn't happen
            // for well-formed MPV responses but defensive).
        }

        // Mode 2: showpage API (cached showkey).
        if (session != null && pageIdx != null && imgKey != null) {
            val showKey = session.showKey
            if (showKey != null) {
                return resolveShowpage(page, gid, pageIdx, imgKey, showKey, nlParam)
            }
        }

        // Mode 3: legacy HTML scrape. Extracts showkey if present and promotes the
        // session so subsequent images skip the HTML round-trip.
        val response = client.newCall(imageUrlRequest(page)).awaitSuccess()
        return realImageUrlParse(response, page, gid)
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getImageUrl"))
    override fun fetchImageUrl(page: Page): Observable<String> {
        return Observable.fromCallable {
            kotlinx.coroutines.runBlocking { getImageUrl(page) }
        }
    }

    /**
     * Legacy HTML-scrape path. Extracts the image URL the slow way (one HTML fetch per
     * image) AND grabs the `showkey` JS var if present, promoting the gallery session to
     * Mode.ShowKey so the next image in the same gallery uses the cheap `showpage` API.
     */
    private fun realImageUrlParse(response: Response, page: Page, gid: Long?): String {
        with(response.asJsoup()) {
            val currentImage = getElementById("img")!!.attr("src")
            // Each press of the retry button will choose another server
            select("#loadfail").attr("onclick").nullIfBlank()?.let {
                page.url = addParam(page.url, "nl", it.substring(it.indexOf('\'') + 1 until it.lastIndexOf('\'')))
            }
            // E-Hentai and ExHentai use different 509 quota images
            if (currentImage == "https://ehgt.org/g/509.gif" || currentImage == "https://exhentai.org/img/509.gif") {
                throw Exception("Exceeded page quota")
            }
            // Opportunistically extract showkey from any script tag so future image
            // resolutions in this gallery can skip the HTML scrape. The var is rendered
            // server-side as: var showkey="abc123def...";
            //
            // If no session exists yet (app restart resumed the reader without re-running
            // getPageList), create a fresh non-MPV session keyed by gid so the showkey we
            // extract here is available to subsequent images in this gallery.
            if (gid != null) {
                val scriptText = select("script").joinToString("\n") { it.data() }
                val extractedKey = SHOWKEY_REGEX.find(scriptText)?.groupValues?.getOrNull(1)
                if (extractedKey != null) {
                    gallerySessions.compute(gid) { _, existing ->
                        when {
                            existing == null -> GallerySession(gid = gid, token = "").also {
                                it.showKey = extractedKey
                            }
                            existing.showKey == null && !existing.hasMpv -> existing.also {
                                it.showKey = extractedKey
                            }
                            else -> existing
                        }
                    }
                    logger.d { "Extracted showkey for gid=$gid; subsequent images will use showpage API" }
                }
            }
            return currentImage
        }
    }

    /**
     * POSTs `method=showpage` to /api.php and returns the resolved image URL.
     * Response shape (per EHentai API behavior / EhViewer EhEngine.kt):
     *   { "i3": "<img src='...' />", "i6": "...nl token wrapper...", "s": "nlToken",
     *     "k": "newImgKey", "n": ... }
     * The actual image URL is parsed out of the i3 HTML fragment. The new `nl` token is
     * encoded back into page.url for retry support.
     */
    private suspend fun resolveShowpage(
        page: Page,
        gid: Long,
        pageIdx: Int,
        imgKey: String,
        showKey: String,
        nl: String?,
    ): String {
        val payload = buildJsonObject {
            put("method", "showpage")
            put("gid", gid)
            put("page", pageIdx)
            put("imgkey", imgKey)
            put("showkey", showKey)
            if (nl != null) put("nl", nl)
        }
        val response = postApi(payload)
        val imgHtml = response["i3"]?.jsonPrimitive?.contentOrNull
            ?: throw Exception("Malformed showpage response (no i3 field): $response")
        val imageUrl = SHOWPAGE_IMG_SRC_REGEX.find(imgHtml)?.groupValues?.getOrNull(1)
            ?: throw Exception("Could not parse image URL from showpage i3: $imgHtml")
        if (imageUrl == "https://ehgt.org/g/509.gif" || imageUrl == "https://exhentai.org/img/509.gif") {
            throw Exception("Exceeded page quota")
        }
        // Rotate nl for the next retry attempt.
        response["s"]?.jsonPrimitive?.contentOrNull?.let { newNl ->
            page.url = addParam(stripParam(page.url, "nl"), "nl", newNl)
        }
        return imageUrl
    }

    /**
     * POSTs `method=imagedispatch` (MPV variant of showpage) and returns the image URL.
     * Response is pure JSON instead of HTML-wrapped: `{ "i": "image_url", "s": "nl", ... }`.
     */
    private suspend fun resolveImageDispatch(
        page: Page,
        gid: Long,
        pageIdx: Int,
        imgKey: String,
        mpvKey: String,
        nl: String?,
    ): String {
        val payload = buildJsonObject {
            put("method", "imagedispatch")
            put("gid", gid)
            put("page", pageIdx)
            put("imgkey", imgKey)
            put("mpvkey", mpvKey)
            if (nl != null) put("nl", nl)
        }
        val response = postApi(payload)
        val imageUrl = response["i"]?.jsonPrimitive?.contentOrNull
            ?: throw Exception("Malformed imagedispatch response (no i field): $response")
        // Defensive: imagedispatch sometimes returns a scheme-relative path; force https.
        val finalImageUrl = when {
            imageUrl.startsWith("http") -> imageUrl
            imageUrl.startsWith("//") -> "https:$imageUrl"
            else -> imageUrl
        }
        if (finalImageUrl == "https://ehgt.org/g/509.gif" || finalImageUrl == "https://exhentai.org/img/509.gif") {
            throw Exception("Exceeded page quota")
        }
        response["s"]?.jsonPrimitive?.contentOrNull?.let { newNl ->
            page.url = addParam(stripParam(page.url, "nl"), "nl", newNl)
        }
        return finalImageUrl
    }

    private fun stripParam(url: String, param: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return url
        return httpUrl.newBuilder().removeAllQueryParameters(param).build().toString()
    }

    /**
     * Parsed gdata response entry. Mirrors the fields EH returns in `gmetadata[]`.
     * Field names match the wire format so JSON deserialisation could be added later
     * without breaking callers. `tags` is the wire `tags[]` array (namespace:value
     * strings). The full set of wire fields includes `archiver_key`, `parent_gid`, and
     * others that we don't currently consume; add to this class as needed.
     */
    data class GdataEntry(
        val gid: Long,
        val token: String,
        val title: String,
        val titleJpn: String?,
        val category: String,
        val thumb: String,
        val uploader: String?,
        val posted: Long,
        val filecount: Int,
        val filesize: Long,
        val expunged: Boolean,
        val rating: Double?,
        val torrentcount: Int,
        val tags: List<String>,
    )

    /**
     * Batched metadata fetch via `method=gdata`. Replaces N HTML detail-page fetches
     * with `ceil(N/25)` POSTs — a 25× request-count reduction for library refresh on
     * EH-heavy libraries.
     *
     * EH's /api.php accepts max 25 `[gid, token]` pairs per call. Larger inputs are
     * chunked. Calls run sequentially with no inter-batch delay because the per-call
     * size limit already implies natural pacing; FooIbar/EhViewer add a 5-second gap
     * for long sweeps which we mirror at the caller's level if doing many batches.
     *
     * Public for future wiring into LibraryUpdateJob. Throws StaleIgneousException
     * up-front for ExH to avoid wasted batches on a known-bad session.
     */
    suspend fun fetchGdataMetadata(galleries: List<Pair<Long, String>>): List<GdataEntry> {
        if (galleries.isEmpty()) return emptyList()
        checkExhSession()

        return galleries.chunked(GDATA_MAX_BATCH).flatMap { batch ->
            val payload = buildJsonObject {
                put("method", "gdata")
                put(
                    "gidlist",
                    buildJsonArray {
                        batch.forEach { (gid, token) ->
                            add(
                                buildJsonArray {
                                    add(gid)
                                    add(token)
                                },
                            )
                        }
                    },
                )
                // `namespace=1` returns tags as namespaced strings (e.g. "language:english")
                // rather than the legacy flat list. Matches what Hayai's parser expects.
                put("namespace", 1)
            }
            val response = postApi(payload)
            val metadata = response["gmetadata"]?.jsonArray.orEmpty()
            metadata.mapNotNull { entry ->
                val obj = entry.jsonObject
                val gid = obj["gid"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: return@mapNotNull null
                val token = obj["token"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                GdataEntry(
                    gid = gid,
                    token = token,
                    title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    titleJpn = obj["title_jpn"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    category = obj["category"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    thumb = obj["thumb"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    uploader = obj["uploader"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    posted = obj["posted"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { it * 1000L } ?: 0L,
                    filecount = obj["filecount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    filesize = obj["filesize"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    expunged = obj["expunged"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                    rating = obj["rating"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                    torrentcount = obj["torrentcount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    tags = obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                )
            }
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Unused method was called somehow!")
    }

    suspend fun fetchFavorites(): Pair<List<ParsedManga>, List<String>> {
        val favoriteUrl = "$baseUrl/favorites.php"
        val result = mutableListOf<ParsedManga>()
        var page = 1

        var favNames: List<String>? = null

        do {
            val response2 = withContext(Dispatchers.IO) {
                client.newCall(
                    exGet(
                        favoriteUrl,
                        next = page,
                        cacheControl = CacheControl.FORCE_NETWORK,
                    ),
                ).await()
            }
            val doc = response2.asJsoup()

            // Parse favorites
            val parsed = extendedGenericMangaParse(doc)
            result += parsed.first

            // Parse fav names
            if (favNames == null) {
                favNames = doc.select(".fp:not(.fps)").mapNotNull {
                    it.child(2).text()
                }
            }
            // Next page

            page = parsed.first.lastOrNull()?.manga?.url?.let { EHentaiSearchMetadata.galleryId(it) }?.toInt() ?: 0
        } while (parsed.second != null)

        return Pair(result.toList(), favNames.orEmpty())
    }

    fun spPref() = if (exh) {
        exhPreferences.exhSettingsProfile
    } else {
        exhPreferences.ehSettingsProfile
    }

    private fun rawCookies(sp: Int): Map<String, String> {
        val cookies: MutableMap<String, String> = mutableMapOf()
        if (exhPreferences.enableExhentai.get()) {
            cookies[EhLoginActivity.MEMBER_ID_COOKIE] = exhPreferences.memberIdVal.get()
            cookies[EhLoginActivity.PASS_HASH_COOKIE] = exhPreferences.passHashVal.get()
            cookies[EhLoginActivity.IGNEOUS_COOKIE] = exhPreferences.igneousVal.get()
            cookies["sp"] = sp.toString()

            val sessionKey = exhPreferences.exhSettingsKey.get()
            if (sessionKey.isNotBlank()) {
                cookies["sk"] = sessionKey
            }

            val sessionCookie = exhPreferences.exhSessionCookie.get()
            if (sessionCookie.isNotBlank()) {
                cookies["s"] = sessionCookie
            }

            val hathPerksCookie = exhPreferences.exhHathPerksCookies.get()
            if (hathPerksCookie.isNotBlank()) {
                cookies["hath_perks"] = hathPerksCookie
            }
        }

        // Session-less extended display mode (for users without ExHentai)
        cookies["sl"] = "dm_2"

        // Ignore all content warnings
        cookies["nw"] = "1"

        return cookies
    }

    fun cookiesHeader(sp: Int = spPref().get()) = buildCookies(rawCookies(sp))

    // Headers. EH/ExH's H@H image nodes (the residential IPs that serve some thumbnails
    // and full-size images) hot-link-protect by checking Referer; without it some thumbs
    // 403 while CDN-served thumbs (ehgt.org) succeed, which manifests as "some covers
    // load and some don't" in browse/library lists. Matching EhViewer's behaviour we send
    // a static site-root Referer on every request — well-behaved clients always do.
    override fun headersBuilder() = super.headersBuilder()
        .add("Cookie", cookiesHeader())
        .add("Referer", "$baseUrl/")

    private fun addParam(url: String, param: String, value: String) = url.toUri()
        .buildUpon()
        .appendQueryParameter(param, value)
        .toString()

    override val client = network.client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .addInterceptor { chain ->
            val newReq = chain
                .request()
                .newBuilder()
                .removeHeader("Cookie")
                .addHeader("Cookie", cookiesHeader())
                .build()

            val response = chain.proceed(newReq)

            // Sad-Panda / IP-ban detection on ExH: an HTML response with effectively no
            // body is the sentinel. Restricted to text/html so we don't mis-flag image
            // 200s. Uses peekBody so the response remains consumable by the actual caller.
            // Skipped for /api.php because those legitimately return small JSON.
            if (exh && response.isSuccessful) {
                val urlPath = response.request.url.encodedPath
                val ct = response.body.contentType()?.toString().orEmpty()
                val looksHtml = ct.startsWith("text/html", ignoreCase = true) ||
                    urlPath.endsWith(".php") || urlPath == "/" || urlPath.startsWith("/g/")
                val isApi = urlPath.endsWith("/api.php")
                if (looksHtml && !isApi) {
                    val peek = response.peekBody(SAD_PANDA_PEEK_BYTES).string()
                    val trimmed = peek.trim()
                    // EhViewer's heuristic: empty body OR a body so short it can't be a real
                    // gallery / search page. Real EH responses always contain an <html> tag
                    // within the first kilobyte.
                    if (trimmed.isEmpty() || (trimmed.length < SAD_PANDA_MIN_HTML_BYTES && !trimmed.contains("<html", ignoreCase = true))) {
                        response.close()
                        throw SadPandaException()
                    }
                }
            }

            response
        }
        .addInterceptor(ThumbnailPreviewInterceptor())
        .build()

    // Filters
    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("Note: Will ignore other parameters!"),
            ToplistOptions(),
            Filter.Separator(),
            AutoCompleteTags(),
            Watched(isEnabled = exhPreferences.exhWatchedListDefaultState.get()),
            GenreGroup(),
            AdvancedGroup(),
            ReverseFilter(),
            JumpSeekFilter(),
        )
    }

    class Watched(val isEnabled: Boolean) : Filter.CheckBox("Watched List", isEnabled), UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state) {
                builder.appendPath("watched")
            }
        }
    }

    enum class ToplistOption(val humanName: String, val index: Int) {
        NONE("None", 0),
        ALL_TIME("All time", 11),
        PAST_YEAR("Past year", 12),
        PAST_MONTH("Past month", 13),
        YESTERDAY("Yesterday", 15),
        ;

        override fun toString(): String {
            return humanName
        }
    }

    class ToplistOptions : Filter.Select<ToplistOption>(
        "Toplists",
        ToplistOption.entries.toTypedArray(),
    )

    class GenreOption(name: String, val genreId: Int) : Filter.CheckBox(name, false)
    class GenreGroup :
        Filter.Group<GenreOption>(
            "Genres",
            listOf(
                GenreOption("Dōjinshi", 2),
                GenreOption("Manga", 4),
                GenreOption("Artist CG", 8),
                GenreOption("Game CG", 16),
                GenreOption("Western", 512),
                GenreOption("Non-H", 256),
                GenreOption("Image Set", 32),
                GenreOption("Cosplay", 64),
                GenreOption("Asian Porn", 128),
                GenreOption("Misc", 1),
            ),
        ),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            val bits = state.fold(0) { acc, genre ->
                if (!genre.state) acc + genre.genreId else acc
            }
            builder.appendQueryParameter("f_cats", bits.toString())
        }
    }

    class AdvancedOption(
        name: String,
        val param: String,
        defValue: Boolean = false,
    ) : Filter.CheckBox(name, defValue), UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state) {
                builder.appendQueryParameter(param, "on")
            }
        }
    }

    open class PageOption(name: String, private val queryKey: String) : Filter.Text(name), UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state.isNotBlank()) {
                if (builder.build().getQueryParameters("f_sp").isEmpty()) {
                    builder.appendQueryParameter("f_sp", "on")
                }

                builder.appendQueryParameter(queryKey, state.trim())
            }
        }
    }

    private fun combineQuery(filters: FilterList): String {
        val stringBuilder = StringBuilder()
        val advSearch = filters.filterIsInstance<Filter.AutoComplete>().flatMap { filter ->
            filter.state.trimAll().dropBlank().mapNotNull { tag ->
                val split = tag.split(":").filterNot { it.isBlank() }
                if (split.size > 1) {
                    val namespace = split[0].removePrefix("-").removePrefix("~")
                    val exclude = split[0].startsWith("-")
                    val or = split[0].startsWith("~")

                    AdvSearchEntry(namespace to split[1], exclude, or)
                } else if (split.size == 1) {
                    val item = split.first()
                    val exclude = item.startsWith("-")
                    val or = item.startsWith("~")
                    AdvSearchEntry(null to item, exclude, or)
                } else {
                    null
                }
            }
        }

        advSearch.forEach { entry ->
            if (entry.exclude) stringBuilder.append("-")
            if (entry.or) stringBuilder.append("~")
            val namespace = entry.search.first?.let { "$it:" }.orEmpty()
            if (entry.search.second.contains(" ")) {
                stringBuilder.append(("""$namespace"${entry.search.second}$""""))
            } else {
                stringBuilder.append("$namespace${entry.search.second}$")
            }
            stringBuilder.append(" ")
        }

        return stringBuilder.toString().trim().also { logger.d { it } }
    }

    data class AdvSearchEntry(val search: Pair<String?, String>, val exclude: Boolean, val or: Boolean)

    inner class AutoCompleteTags :
        Filter.AutoComplete(
            name = "Tags",
            hint = "Search tags here (limit of 8)",
            values = EHTags.getNamespaces().map { "$it:" } + EHTags.getAllTags(context),
            skipAutoFillTags = EHTags.getNamespaces().map { "$it:" },
            validPrefixes = listOf("-", "~"),
            state = emptyList(),
        )

    class MinPagesOption : PageOption("Minimum Pages", "f_spf")
    class MaxPagesOption : PageOption("Maximum Pages", "f_spt")

    class RatingOption :
        Filter.Select<String>(
            "Minimum Rating",
            arrayOf(
                "Any",
                "2 stars",
                "3 stars",
                "4 stars",
                "5 stars",
            ),
        ),
        UriFilter {
        override fun addToUri(builder: Uri.Builder) {
            if (state > 0) {
                builder.appendQueryParameter("f_srdd", (state + 1).toString())
                builder.appendQueryParameter("f_sr", "on")
            }
        }
    }

    class AdvancedGroup : UriGroup<Filter<*>>(
        "Advanced Options",
        listOf(
            AdvancedOption("Browse Expunged Galleries", "f_sh"),
            AdvancedOption("Require Gallery Torrent", "f_sto"),
            RatingOption(),
            MinPagesOption(),
            MaxPagesOption(),
            AdvancedOption("Disable custom Language filters", "f_sfl"),
            AdvancedOption("Disable custom Uploader filters", "f_sfu"),
            AdvancedOption("Disable custom Tag filters", "f_sft"),
        ),
    )

    class ReverseFilter : Filter.CheckBox("Reverse search results")

    class JumpSeekFilter : Filter.Text("Jump/Seek")

    override val name = if (exh) {
        "ExHentai"
    } else {
        "E-Hentai"
    }

    class GalleryNotFoundException(cause: Throwable) : RuntimeException("Gallery not found!", cause)

    // === URL IMPORT STUFF

    override val matchingHosts: List<String> = if (exh) {
        listOf(
            "exhentai.org",
        )
    } else {
        listOf(
            "g.e-hentai.org",
            "e-hentai.org",
        )
    }

    override suspend fun mapUrlToMangaUrl(uri: Uri): String? {
        return when (uri.pathSegments.firstOrNull()) {
            "g" -> {
                // Is already gallery page, do nothing
                uri.toString()
            }
            "s" -> {
                // Is page, fetch gallery token and use that
                getGalleryUrlFromPage(uri)
            }
            else -> null
        }
    }

    override fun cleanMangaUrl(url: String): String {
        return EHentaiSearchMetadata.normalizeUrl(super.cleanMangaUrl(url))
    }

    /**
     * Resolves a `/s/{ptoken}/{gid}-{page}` URL to its parent gallery URL via /api.php.
     *
     * Route the call through `apiUrl` (per-instance host) and `client` (carries igneous +
     * member cookies). The previous implementation hard-coded `api.e-hentai.org` and built
     * its own Request, which silently failed for ExH-restricted galleries because that
     * host has no view onto restricted content. EhViewer's pattern: same /api.php method
     * on the matching site's host with the matching cookie jar.
     */
    private suspend fun getGalleryUrlFromPage(uri: Uri): String {
        val lastSplit = uri.pathSegments.last().split("-")
        val pageNum = lastSplit.last()
        val gallery = lastSplit.first()
        val pageToken = uri.pathSegments.elementAt(1)

        val json = buildJsonObject {
            put("method", "gtoken")
            put(
                "pagelist",
                buildJsonArray {
                    add(
                        buildJsonArray {
                            add(gallery.toInt())
                            add(pageToken)
                            add(pageNum.toInt())
                        },
                    )
                },
            )
        }

        val outJson = postApi(json)

        val obj = outJson["tokenlist"]!!.jsonArray.first().jsonObject
        return "${uri.scheme}://${uri.host}/g/${obj["gid"]!!.jsonPrimitive.int}/${
            obj["token"]!!.jsonPrimitive.content
        }/"
    }

    /**
     * Common /api.php POST helper. Uses the per-instance `client` so cookies/igneous are
     * attached and the response goes through the Sad-Panda interceptor (which won't trip
     * for /api.php because it returns JSON, not HTML).
     */
    private suspend fun postApi(payload: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(apiUrl)
            .post(payload.toString().toRequestBody(JSON))
            .headers(headers)
            .build()
        val body = client.newCall(request).awaitSuccess().body.string()
        return Json.decodeFromString(body)
    }

    data class EHentaiThumbnailPreview(
        val imageUrl: String,
        val width: Int,
        val height: Int,
        val widthOffset: Int,
        val index: Int,
    ) {
        fun toUrl(): String {
            return BLANK_PREVIEW_THUMB.toHttpUrl().newBuilder()
                .addQueryParameter("imageUrl", imageUrl)
                .addQueryParameter("width", width.toString())
                .addQueryParameter("height", height.toString())
                .addQueryParameter("widthOffset", widthOffset.toString())
                .build()
                .toString()
        }

        companion object {
            fun parseFromUrl(url: HttpUrl) = EHentaiThumbnailPreview(
                imageUrl = url.queryParameter("imageUrl")!!,
                width = url.queryParameter("width")!!.toInt(),
                height = url.queryParameter("height")!!.toInt(),
                widthOffset = url.queryParameter("widthOffset")!!.toInt(),
                index = -1,
            )
        }
    }

    private class ThumbnailPreviewInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()

            if (request.url.host == THUMB_DOMAIN && request.url.pathSegments.contains(BLANK_THUMB)) {
                val thumbnailPreview = EHentaiThumbnailPreview.parseFromUrl(request.url)
                val response = chain.proceed(request.newBuilder().url(thumbnailPreview.imageUrl).build())
                if (response.isSuccessful) {
                    val body = ByteArrayOutputStream()
                        .use {
                            val bitmap = BitmapFactory.decodeStream(response.body.byteStream())
                                ?: throw IOException("Null bitmap($thumbnailPreview)")
                            Bitmap.createBitmap(
                                bitmap,
                                thumbnailPreview.widthOffset,
                                0,
                                thumbnailPreview.width.coerceAtMost(bitmap.width - thumbnailPreview.widthOffset),
                                thumbnailPreview.height.coerceAtMost(bitmap.height),
                            // Quality 85 is visually equivalent to 100 for these small sprite
                            // crops but produces ~3× smaller payloads to Coil. EhViewer/Hentoid
                            // both ship lossy re-encodes in the same range. WEBP_LOSSY would be
                            // smaller still but requires API 30+; minSdk=23 rules it out.
                            ).compress(Bitmap.CompressFormat.JPEG, 85, it)
                            it.toByteArray()
                        }
                        .toResponseBody("image/jpeg".toMediaType())

                    return response.newBuilder().body(body).build()
                } else {
                    return response
                }
            }

            return chain.proceed(request)
        }
    }

    companion object {
        private const val TR_SUFFIX = "TR"
        private const val REVERSE_PARAM = "TEH_REVERSE"
        private val PAGE_COUNT_REGEX = "[0-9]*".toRegex()
        private val RATING_REGEX = "([0-9]*)px".toRegex()
        private const val THUMB_DOMAIN = "ehgt.org"
        private const val BLANK_THUMB = "blank.gif"
        private const val BLANK_PREVIEW_THUMB = "https://$THUMB_DOMAIN/g/$BLANK_THUMB"

        private val MATCH_YEAR_REGEX = "^\\d{4}\$".toRegex()
        private val MATCH_SEEK_REGEX = "^\\d{2,4}-\\d{1,2}(-\\d{1,2})?".toRegex()
        private val MATCH_JUMP_REGEX = "^\\d+(\$|d\$|w\$|m\$|y\$|-\$)".toRegex()

        private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()!!

        // Sad-Panda detection thresholds. EhViewer/Hentoid agree that a healthy EH/ExH
        // HTML response is multi-kilobyte and always contains an <html> tag in the first
        // kilobyte. An empty-ish body without one is the sentinel for an IP-ban page or
        // a malformed-cookie 200.
        private const val SAD_PANDA_PEEK_BYTES = 1024L
        private const val SAD_PANDA_MIN_HTML_BYTES = 100

        // Per-image-page JS holds `var showkey="abc...";` — accept single or double quotes
        // and arbitrary whitespace around the `=` per EH's varying server-side templates.
        private val SHOWKEY_REGEX = Regex("""var\s+showkey\s*=\s*["']([^"']+)["']""")

        // MPV page JS holds five top-level vars; we need mpvkey and imagelist. imagelist
        // is a JSON array literal that may span many lines, so the regex captures
        // greedily to the next `;` on its own and we parse the captured JSON afterwards.
        private val MPV_KEY_REGEX = Regex("""var\s+mpvkey\s*=\s*["']([^"']+)["']""")
        private val MPV_IMAGELIST_REGEX = Regex("""var\s+imagelist\s*=\s*(\[[\s\S]*?\])\s*;""")

        // Showpage response's i3 field is HTML like `<a ...><img id="img" src="URL" /></a>`.
        private val SHOWPAGE_IMG_SRC_REGEX = Regex("""<img[^>]+id=["']img["'][^>]+src=["']([^"']+)["']""")

        // EH's /api.php caps `gdata` requests at 25 (gid, token) pairs per call.
        // Empirically enforced server-side; larger arrays return an error.
        private const val GDATA_MAX_BATCH = 25

        private val FAVORITES_BORDER_HEX_COLORS = listOf(
            "000",
            "f00",
            "fa0",
            "dd0",
            "080",
            "9f4",
            "4bf",
            "00f",
            "508",
            "e8e",
        )

        fun buildCookies(cookies: Map<String, String>) = cookies.entries.joinToString(separator = "; ") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
    }
}
