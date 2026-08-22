package dev.ahmedmohamed.hayai.adult.eh.network

import dev.ahmedmohamed.hayai.adult.eh.domain.EhBrowseGallery
import dev.ahmedmohamed.hayai.adult.eh.domain.EhBrowsePage
import dev.ahmedmohamed.hayai.adult.eh.domain.EhDetailsPage
import dev.ahmedmohamed.hayai.adult.eh.domain.EhFailure
import dev.ahmedmohamed.hayai.adult.eh.domain.EhGalleryMetadata
import dev.ahmedmohamed.hayai.adult.eh.domain.EhGalleryPage
import dev.ahmedmohamed.hayai.adult.eh.domain.EhPageBatch
import dev.ahmedmohamed.hayai.adult.eh.domain.EhPagePreview
import dev.ahmedmohamed.hayai.adult.eh.domain.EhPreviewPage
import dev.ahmedmohamed.hayai.adult.eh.domain.EhPreviewCrop
import dev.ahmedmohamed.hayai.adult.eh.domain.EhResolvedImage
import dev.ahmedmohamed.hayai.adult.eh.domain.EhRevision
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSearchCursor
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.EhTag
import dev.ahmedmohamed.hayai.adult.eh.domain.EhTagWeight
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryId
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.pow

object EhHtmlParser {
    fun parsePreviews(
        html: String,
        location: String,
        site: EhSite,
        maxItems: Int = 40,
    ): List<EhPagePreview> = parsePreviewPage(html, location, site, maxItems).previews

    fun parsePreviewPage(
        html: String,
        location: String,
        site: EhSite,
        maxItems: Int = 100,
    ): EhPreviewPage {
        require(maxItems in 1..100)
        val document = parseDocument(html, location)
        validateLocation(location, site)
        guard(document)
        val listingPage = runCatching { URI(location).query.orEmpty().split('&').firstOrNull { it.startsWith("p=") }?.substringAfter('=')?.toInt() }.getOrNull() ?: 0
        val cells = document.select("#gdt .gdtm, #gdt .gdtl")
        if (cells.size > maxItems) throw EhFailure.BoundsExceeded("E-Hentai preview page exceeds $maxItems entries")
        val previews = cells
            .mapNotNull { cell ->
                val link = cell.selectFirst("a[href]")?.absUrl("href").orEmpty()
                if (!link.startsWith("https://")) return@mapNotNull null
                val index = previewIndex(cell, link) ?: return@mapNotNull null
                val image = cell.selectFirst("img[src], img[data-src]")
                val direct = image?.absUrl(if (image.hasAttr("data-src")) "data-src" else "src").orEmpty()
                if (direct.startsWith("https://")) {
                    return@mapNotNull runCatching { EhPagePreview(index, link, direct) }.getOrNull()
                }
                val sprite = cell.select("div[style]").firstOrNull { it.attr("style").contains("url(", ignoreCase = true) }
                    ?: return@mapNotNull null
                val style = sprite.attr("style")
                val imageUrl = STYLE_URL.find(style)?.groupValues?.get(1)?.trim('\'', '"')?.let { sprite.absUrlFromStyle(it) }
                    ?: return@mapNotNull null
                val width = STYLE_WIDTH.find(style)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val height = STYLE_HEIGHT.find(style)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val position = STYLE_POSITION.find(style)
                val x = position?.groupValues?.get(1)?.toIntOrNull()?.let { kotlin.math.abs(it) } ?: 0
                val y = position?.groupValues?.get(2)?.toIntOrNull()?.let { kotlin.math.abs(it) } ?: 0
                runCatching { EhPagePreview(index, link, imageUrl, EhPreviewCrop(x, y, width, height)) }.getOrNull()
            }
        val navigation = document.select("table.ptt td")
        val totalPages = navigation.mapNotNull { it.text().trim().toIntOrNull() }.maxOrNull()
        val hasNext = when {
            totalPages != null -> listingPage + 1 < totalPages
            navigation.isNotEmpty() -> navigation.lastOrNull()?.hasClass("ptdd") != true
            else -> false
        }
        return EhPreviewPage(listingPage, previews, hasNext, totalPages)
    }

    private fun previewIndex(cell: Element, pageUrl: String): Int? =
        cell.selectFirst("img[alt]")?.attr("alt")?.trim()?.toIntOrNull()
            ?: cell.selectFirst("[title^=Page]")?.attr("title")?.removePrefix("Page")?.trim()?.substringBefore(':')?.toIntOrNull()
            ?: PAGE_URL_INDEX.find(pageUrl)?.groupValues?.get(1)?.toIntOrNull()

    private fun org.jsoup.nodes.Element.absUrlFromStyle(value: String): String =
        runCatching { java.net.URI(baseUri()).resolve(value).toString() }.getOrDefault("").takeIf { it.startsWith("https://") }.orEmpty()

    private val STYLE_URL = Regex("url\\(([^)]+)\\)", RegexOption.IGNORE_CASE)
    private val STYLE_WIDTH = Regex("width\\s*:\\s*(\\d+)px", RegexOption.IGNORE_CASE)
    private val STYLE_HEIGHT = Regex("height\\s*:\\s*(\\d+)px", RegexOption.IGNORE_CASE)
    private val STYLE_POSITION = Regex("(?:background-position\\s*:\\s*|url\\([^)]+\\)\\s*)(-?\\d+)px\\s+(-?\\d+)px", RegexOption.IGNORE_CASE)
    private val PAGE_URL_INDEX = Regex("-(\\d+)(?:[/?#]|$)")

    fun parseBrowse(
        html: String,
        location: String,
        site: EhSite,
        maxItems: Int = 1_000,
    ): EhBrowsePage = parseDocument(html, location).let { document ->
        validateLocation(location, site)
        guard(document)
        val rows = document.select(".itg > tbody > tr").filter { row ->
            row.selectFirst("th") == null && row.selectFirst(".itd") == null
        }
        if (rows.size > maxItems) throw EhFailure.BoundsExceeded("E-Hentai browse result exceeds $maxItems entries")
        val galleries = rows.map { parseBrowseRow(it, site) }
        if (galleries.isEmpty()) {
            document.selectFirst(".searchwarn")?.text()?.trim()?.takeIf(String::isNotEmpty)?.let {
                throw EhFailure.RemoteWarning(it)
            }
        }
        if (galleries.map { it.metadata.key }.distinct().size != galleries.size) {
            throw EhFailure.MalformedDocument("E-Hentai browse result contains duplicate galleries")
        }
        EhBrowsePage(galleries, parseNextCursor(document, galleries))
    }

    fun parseDetails(
        html: String,
        location: String,
        site: EhSite,
        maxTags: Int = 5_000,
        maxRevisions: Int = 512,
    ): EhDetailsPage = parseDocument(html, location).let { document ->
        validateLocation(location, site)
        guard(document)
        val key = parseGalleryKey(location)
        val title = document.selectFirst("#gn")?.text()?.trim().orEmpty()
        if (title.isEmpty()) throw EhFailure.MalformedDocument("E-Hentai gallery title is missing")
        val fields = document.select("#gdd tr").associate { row ->
            row.selectFirst(".gdt1")?.text()?.removeSuffix(":")?.trim()?.lowercase().orEmpty() to row.selectFirst(".gdt2")
        }
        val tags = parseTags(document)
        if (tags.size > maxTags) throw EhFailure.BoundsExceeded("E-Hentai gallery exceeds $maxTags tags")
        val posted = fields["posted"]?.text()?.trim()?.takeIf(String::isNotEmpty)?.let(::parseTimestamp)
        val parent = fields["parent"]?.let { element ->
            element.selectFirst("a[href*=/g/]")?.attr("href")?.let(::parseGalleryKey)
        }
        val languageText = fields["language"]?.text()?.trim()?.takeIf(String::isNotEmpty)
        val metadata = EhGalleryMetadata(
            key = key,
            site = site,
            title = title,
            alternateTitle = document.selectFirst("#gj")?.text()?.trim()?.takeIf(String::isNotEmpty),
            thumbnailUrl = parseCssUrl(document.selectFirst("#gd1 div")?.attr("style"))?.let { resolveHttpsUrl(location, it) },
            category = parseCategory(document.selectFirst(".cs")),
            uploader = document.selectFirst("#gdn")?.text()?.trim()?.takeIf(String::isNotEmpty),
            postedAtMillis = posted,
            parent = parent,
            visible = fields["visible"]?.text()?.trim()?.takeIf(String::isNotEmpty),
            language = languageText?.removeSuffix("TR")?.trim()?.takeIf(String::isNotEmpty),
            translated = languageText?.endsWith("TR", ignoreCase = true),
            sizeBytes = fields["file size"]?.text()?.let(::parseByteCount),
            pageCount = fields["length"]?.text()?.let(::firstInteger),
            favoriteCount = fields["favorited"]?.text()?.let(::firstInteger),
            ratingCount = document.selectFirst("#rating_count")?.text()?.trim()?.toIntOrNull(),
            averageRating = document.selectFirst("#rating_label")?.text()?.substringAfter("Average:", "")?.trim()?.toDoubleOrNull(),
            tags = tags,
        )
        val revisions = document.select("#gnd a[href*=/g/]").map { link ->
            val siblingText = link.nextSibling()?.toString().orEmpty()
            val postedText = ADDED_TIMESTAMP.find(siblingText)?.groupValues?.get(1)
            EhRevision(
                key = parseGalleryKey(link.attr("href")),
                title = link.text().trim().ifEmpty { throw EhFailure.MalformedDocument("Gallery revision title is missing") },
                postedAtMillis = postedText?.let(::parseTimestamp),
            )
        }
        if (revisions.size > maxRevisions) throw EhFailure.BoundsExceeded("E-Hentai gallery exceeds $maxRevisions revisions")
        if (revisions.any { it.key == key } || revisions.map(EhRevision::key).distinct().size != revisions.size) {
            throw EhFailure.MalformedDocument("E-Hentai gallery contains duplicate revisions")
        }
        EhDetailsPage(metadata, revisions)
    }

    fun parsePageBatch(
        html: String,
        location: String,
        maxPages: Int = 5_000,
    ): EhPageBatch = parseDocument(html, location).let { document ->
        val validatedLocation = validateLocation(location)
        guard(document)
        val candidates = buildList {
            document.select(".gdtm a[href]").forEach { link ->
                val index = link.selectFirst("img[alt]")?.attr("alt")?.toIntOrNull()
                    ?: throw EhFailure.MalformedDocument("Thumbnail page index is missing")
                add(EhGalleryPage(index, resolveEhUrl(location, link.attr("href"))))
            }
            document.select("#gdt > a[href]").forEach { link ->
                val title = link.selectFirst("[title^=Page]")?.attr("title") ?: link.attr("title")
                val index = PAGE_TITLE.find(title)?.groupValues?.get(1)?.toIntOrNull()
                    ?: throw EhFailure.MalformedDocument("Gallery page index is missing")
                add(EhGalleryPage(index, resolveEhUrl(location, link.attr("href"))))
            }
        }
        if (candidates.size > maxPages) throw EhFailure.BoundsExceeded("E-Hentai listing exceeds $maxPages pages")
        val pages = candidates.groupBy(EhGalleryPage::index).map { (index, matches) ->
            if (matches.map(EhGalleryPage::pageUrl).distinct().size != 1) {
                throw EhFailure.MalformedDocument("E-Hentai listing has conflicting URLs for page $index")
            }
            matches.first()
        }.sortedBy(EhGalleryPage::index)
        val next = document.select("a[onclick='return false'], a[onclick='return false;']")
            .lastOrNull { it.text().trim() == ">" }
            ?.attr("href")
            ?.takeIf(String::isNotBlank)
            ?.let { resolveEhUrl(location, it) }
        EhPageBatch(validatedLocation, pages, next)
    }

    fun parseImagePage(html: String, location: String): EhResolvedImage = parseDocument(html, location).let { document ->
        validateLocation(location)
        guard(document)
        val imageUrl = document.selectFirst("#img")?.attr("src")?.trim().orEmpty()
        if (imageUrl.isEmpty()) throw EhFailure.MalformedDocument("E-Hentai image URL is missing")
        if (imageUrl == QUOTA_IMAGE) throw EhFailure.QuotaExceeded()
        val resolvedImage = resolveHttpsUrl(location, imageUrl)
        val retryToken = document.selectFirst("#loadfail")?.attr("onclick")?.let { RETRY_TOKEN.find(it)?.groupValues?.get(1) }
        val retryUrl = retryToken?.let {
            val pageUrl = location.toHttpUrlOrNull() ?: throw EhFailure.MalformedDocument("Invalid E-Hentai image page URL")
            pageUrl.newBuilder().setQueryParameter("nl", it).build().toString()
        }
        EhResolvedImage(resolvedImage, retryUrl)
    }

    private fun parseBrowseRow(row: Element, site: EhSite): EhBrowseGallery {
        val link = row.selectFirst(".gl3c > a[href*=/g/], .gl2e > div > a[href*=/g/], a[href*=/g/]")
            ?: throw EhFailure.MalformedDocument("E-Hentai browse entry has no gallery link")
        val thumbnail = row.selectFirst(".gl1e img, .gl2c .glthumb img, .gl1c img, img")
        val title = thumbnail?.attr("title")?.trim()?.takeIf(String::isNotEmpty)
            ?: link.selectFirst(".glink")?.text()?.trim()?.takeIf(String::isNotEmpty)
            ?: link.text().trim().takeIf(String::isNotEmpty)
            ?: throw EhFailure.MalformedDocument("E-Hentai browse entry has no title")
        val key = parseGalleryKey(link.attr("href"))
        val info = row.selectFirst(".gl3e")?.select("div")
        val compactInfo = row.selectFirst(".gl2c")?.select("div div")
        val extra = row.selectFirst(".gl4c")?.select("div")
        val genre = if (info != null) parseCategory(info.getOrNull(1)) else parseCategory(row.selectFirst(".gl1c div"))
        val posted = (info?.getOrNull(2) ?: compactInfo?.getOrNull(8))?.text()?.trim()?.takeIf(DATE_TIME::matches)?.let(::parseTimestamp)
        val uploader = (info?.getOrNull(4) ?: extra?.lastOrNull { it.selectFirst("a") != null })
            ?.selectFirst("a")?.text()?.trim()?.takeIf(String::isNotEmpty)
        val pageCount = (info?.getOrNull(5) ?: extra?.lastOrNull())?.text()?.let(::firstInteger)
        val rating = parseRating(info?.getOrNull(3) ?: compactInfo?.getOrNull(9))
        val metadata = EhGalleryMetadata(
            key = key,
            site = site,
            title = title,
            thumbnailUrl = thumbnail?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.takeIf(String::isNotBlank)
                ?.let { resolveHttpsUrl(row.baseUri(), it) },
            category = genre,
            uploader = uploader,
            postedAtMillis = posted,
            pageCount = pageCount,
            averageRating = rating,
            tags = parseTags(row),
        )
        val favoriteCategory = row.select("[style*=border-color]").firstNotNullOfOrNull { element ->
            BORDER_COLOR.find(element.attr("style"))?.groupValues?.get(1)?.lowercase()?.let(::favoriteCategory)
        }
        return EhBrowseGallery(metadata, favoriteCategory)
    }

    private fun parseNextCursor(document: Document, galleries: List<EhBrowseGallery>): EhSearchCursor? {
        val location = document.location().toHttpUrlOrNull()
        if (location?.encodedPath?.endsWith("/toplist.php") == true) {
            val current = location.queryParameter("p")?.toIntOrNull() ?: 0
            val next = current + 2
            return if (next <= 200 && galleries.isNotEmpty()) EhSearchCursor.ToplistPage(next) else null
        }
        val reverse = location?.queryParameterNames?.contains(EhRequestBuilder.REVERSE_PARAMETER) == true
        val linkText = if (reverse) "prev" else "next"
        val hasNext = document.select(".searchnav a[href]").any { it.text().contains(linkText, ignoreCase = true) }
        if (!hasNext || galleries.isEmpty()) return null
        val gallery = if (reverse) galleries.first() else galleries.last()
        val cursor = EhSearchCursor.Gallery(gallery.metadata.key.id)
        val requestCursor = location?.queryParameter(if (reverse) "prev" else "next")
        if (requestCursor == cursor.id.value) throw EhFailure.MalformedDocument("E-Hentai browse cursor did not advance")
        return cursor
    }

    private fun parseTags(element: Element): List<EhTag> = element.select("#taglist tr, tr").flatMap { row ->
        val rowNamespace = row.selectFirst(".tc")?.text()?.removeSuffix(":")?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        row.select(".gt, .gtl, .gtw").mapNotNull { tag ->
            val titled = tag.attr("title").trim()
            val namespace = rowNamespace ?: titled.substringBefore(':', "").trim().lowercase().takeIf(String::isNotEmpty)
            val name = tag.text().trim().takeIf(String::isNotEmpty)
                ?: titled.substringAfter(':', "").trim().takeIf(String::isNotEmpty)
            if (namespace == null || name == null) return@mapNotNull null
            EhTag(
                namespace,
                name,
                when {
                    tag.hasClass("gtl") -> EhTagWeight.Light
                    tag.hasClass("gtw") -> EhTagWeight.Weak
                    else -> EhTagWeight.Normal
                },
            )
        }
    }.distinct()

    private fun guard(document: Document) {
        val title = document.title().lowercase()
        val bodyText = document.body()?.text()?.lowercase().orEmpty()
        when {
            document.selectFirst("form[action*=Login] input[type=password], form[action*=login] input[type=password]") != null ->
                throw EhFailure.AuthenticationRequired()
            document.selectFirst("#challenge-form, script[src*=/cdn-cgi/]") != null || title.contains("just a moment") ->
                throw EhFailure.AccessDenied("E-Hentai access challenge blocked the request")
            bodyText.contains("temporarily banned") || bodyText.contains("excessive pageloads") ->
                throw EhFailure.RateLimited("E-Hentai temporarily rate-limited this client")
            title.contains("gallery not available") || bodyText.contains("this gallery is unavailable") ->
                throw EhFailure.GalleryNotFound()
            title.contains("sad panda") || document.selectFirst("img[src*=sadpanda]") != null ->
                throw EhFailure.AccessDenied("E-Hentai denied access to this gallery")
        }
    }

    private fun parseDocument(html: String, location: String): Document {
        if (html.length > MAX_DOCUMENT_CHARS) throw EhFailure.BoundsExceeded("E-Hentai document is too large")
        return try {
            Jsoup.parse(html, location)
        } catch (failure: Exception) {
            throw EhFailure.MalformedDocument("Malformed E-Hentai HTML", failure)
        }
    }

    private fun parseGalleryKey(url: String): GalleryKey = try {
        GalleryKey.parse(url)
    } catch (failure: IllegalArgumentException) {
        throw EhFailure.MalformedDocument("Malformed E-Hentai gallery URL", failure)
    }

    private fun parseCategory(element: Element?): String? {
        val onclick = element?.attr("onclick")?.trim().orEmpty()
        return onclick.substringAfterLast('/', "").removeSuffix("'").trim().takeIf(String::isNotEmpty)
            ?: element?.text()?.trim()?.lowercase()?.replace(" ", "")?.takeIf(String::isNotEmpty)
    }

    private fun parseCssUrl(style: String?): String? = style?.let { CSS_URL.find(it)?.groupValues?.get(2)?.trim() }

    private fun parseTimestamp(value: String): Long = try {
        LocalDateTime.parse(value.trim(), DATE_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (failure: DateTimeParseException) {
        throw EhFailure.MalformedDocument("Malformed E-Hentai timestamp", failure)
    }

    private fun parseByteCount(value: String): Long? {
        val match = BYTE_COUNT.find(value.trim()) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val exponent = when (match.groupValues[2].uppercase()) {
            "B", "" -> 0
            "KB" -> 1
            "MB" -> 2
            "GB" -> 3
            "TB" -> 4
            else -> return null
        }
        val result = amount * 1024.0.pow(exponent)
        return result.takeIf { it.isFinite() && it <= Long.MAX_VALUE }?.toLong()
    }

    private fun firstInteger(value: String): Int? = INTEGER.find(value)?.value?.toIntOrNull()

    private fun parseRating(element: Element?): Double? {
        val explicit = element?.attr("title")?.let { RATING_TEXT.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        if (explicit != null) return explicit
        val positions = element?.attr("style")?.let { PIXELS.findAll(it).mapNotNull { match -> match.groupValues[1].toIntOrNull() }.toList() }.orEmpty()
        if (positions.size < 2) return null
        var rating = 5 - positions[0] / 16.0
        if (positions[1] == 21) rating -= 0.5
        return rating.coerceIn(0.0, 5.0)
    }

    private fun favoriteCategory(color: String): Int? {
        val shorthand = if (color.length == 6 && color[0] == color[1] && color[2] == color[3] && color[4] == color[5]) {
            "${color[0]}${color[2]}${color[4]}"
        } else {
            color
        }
        return FAVORITE_COLORS.indexOf(shorthand).takeIf { it >= 0 }
    }

    private fun resolveEhUrl(base: String, value: String): String {
        val resolved = resolveHttpsUrl(base, value)
        val uri = URI(resolved)
        if (EhSite.fromHost(uri.host) == null) throw EhFailure.MalformedDocument("E-Hentai page URL uses an unsupported host")
        return resolved
    }

    private fun validateLocation(location: String, expectedSite: EhSite? = null): String {
        val resolved = resolveEhUrl(location, location)
        val site = EhSite.fromHost(URI(resolved).host)
        if (expectedSite != null && site != expectedSite) {
            throw EhFailure.MalformedDocument("E-Hentai response came from the wrong site")
        }
        return resolved
    }

    private fun resolveHttpsUrl(base: String, value: String): String {
        val resolved = runCatching { URI(base).resolve(value.trim()) }.getOrElse {
            throw EhFailure.MalformedDocument("Malformed E-Hentai URL", it)
        }
        if (!resolved.scheme.equals("https", ignoreCase = true) || resolved.userInfo != null || resolved.host.isNullOrBlank()) {
            throw EhFailure.MalformedDocument("E-Hentai URL is not a safe HTTPS URL")
        }
        return resolved.toString()
    }

    private const val MAX_DOCUMENT_CHARS = 16 * 1024 * 1024
    private const val QUOTA_IMAGE = "https://ehgt.org/g/509.gif"
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DATE_TIME = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}")
    private val ADDED_TIMESTAMP = Regex("added\\s+([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2})", RegexOption.IGNORE_CASE)
    private val CSS_URL = Regex("url\\((['\"]?)(.*?)\\1\\)")
    private val BYTE_COUNT = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(B|KB|MB|GB|TB)?", RegexOption.IGNORE_CASE)
    private val INTEGER = Regex("[0-9]+")
    private val RATING_TEXT = Regex("(?:rating[: ]+)?([0-5](?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
    private val PIXELS = Regex("([0-9]+)px")
    private val BORDER_COLOR = Regex("border-color\\s*:\\s*#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})")
    private val PAGE_TITLE = Regex("Page\\s+([0-9]+)", RegexOption.IGNORE_CASE)
    private val RETRY_TOKEN = Regex("['\"]([A-Za-z0-9_-]{1,128})['\"]")
    private val FAVORITE_COLORS = listOf("000", "f00", "fa0", "dd0", "080", "9f4", "4bf", "00f", "508", "e8e")
}

class EhParentTraversal(
    start: GalleryKey,
    private val maxHops: Int = 64,
) {
    private val visited = linkedSetOf(start)

    init {
        require(maxHops in 1..1_024) { "Parent traversal bound is invalid" }
    }

    fun follow(parent: GalleryKey?): GalleryKey? {
        parent ?: return null
        if (parent in visited) throw EhFailure.MalformedDocument("E-Hentai parent chain contains a cycle")
        if (visited.size >= maxHops) throw EhFailure.BoundsExceeded("E-Hentai parent chain exceeds $maxHops galleries")
        visited.add(parent)
        return parent
    }

    fun path(): List<GalleryKey> = visited.toList()
}

class EhPageAccumulator(
    private val maxListingPages: Int = 200,
    private val maxGalleryPages: Int = 20_000,
) {
    private val listingUrls = linkedSetOf<String>()
    private val pages = sortedMapOf<Int, EhGalleryPage>()

    init {
        require(maxListingPages in 1..1_000) { "Listing page bound is invalid" }
        require(maxGalleryPages in 1..100_000) { "Gallery page bound is invalid" }
    }

    fun add(batch: EhPageBatch): String? {
        validateChainUrl(batch.listingUrl)
        if (batch.listingUrl in listingUrls) throw EhFailure.MalformedDocument("E-Hentai listing chain contains a cycle")
        if (listingUrls.size >= maxListingPages) throw EhFailure.BoundsExceeded("E-Hentai listing chain exceeds $maxListingPages pages")
        listingUrls.add(batch.listingUrl)
        batch.pages.forEach { page ->
            val previous = pages.putIfAbsent(page.index, page)
            if (previous != null && previous.pageUrl != page.pageUrl) {
                throw EhFailure.MalformedDocument("E-Hentai listing has conflicting URLs for page ${page.index}")
            }
        }
        if (pages.size > maxGalleryPages) throw EhFailure.BoundsExceeded("E-Hentai gallery exceeds $maxGalleryPages pages")
        batch.nextListingUrl?.let {
            validateChainUrl(it)
            if (it in listingUrls) throw EhFailure.MalformedDocument("E-Hentai listing chain contains a cycle")
        }
        return batch.nextListingUrl
    }

    fun pages(): List<EhGalleryPage> = pages.values.toList()

    private fun validateChainUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrElse { throw EhFailure.MalformedDocument("Malformed E-Hentai listing URL", it) }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || EhSite.fromHost(uri.host) == null) {
            throw EhFailure.MalformedDocument("E-Hentai listing URL is not a supported HTTPS URL")
        }
    }
}
