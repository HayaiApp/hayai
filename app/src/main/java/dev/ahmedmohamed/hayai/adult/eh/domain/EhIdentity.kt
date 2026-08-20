package dev.ahmedmohamed.hayai.adult.eh.domain

import java.net.URI

enum class EhSite(
    val sourceId: Long,
    val displayName: String,
    val baseUrl: String,
    val matchingHosts: Set<String>,
) {
    EHentai(
        sourceId = 6901L,
        displayName = "E-Hentai",
        baseUrl = "https://e-hentai.org",
        matchingHosts = setOf("e-hentai.org", "g.e-hentai.org"),
    ),
    ExHentai(
        sourceId = 6902L,
        displayName = "ExHentai",
        baseUrl = "https://exhentai.org",
        matchingHosts = setOf("exhentai.org"),
    ),
    ;

    companion object {
        fun fromHost(host: String?): EhSite? {
            val normalized = host?.trim()?.lowercase()?.removeSuffix(".") ?: return null
            return entries.firstOrNull { normalized in it.matchingHosts }
        }
    }
}

@JvmInline
value class GalleryId private constructor(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "Invalid E-Hentai gallery ID" }
        require(value.toLongOrNull() != null) { "E-Hentai gallery ID is outside the supported range" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[1-9][0-9]{0,19}")

        fun parse(value: String): GalleryId = GalleryId(value.trim())
    }
}

@JvmInline
value class GalleryToken private constructor(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "Invalid E-Hentai gallery token" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[A-Za-z0-9_-]{1,128}")

        fun parse(value: String): GalleryToken = GalleryToken(value.trim())
    }
}

data class GalleryKey(
    val id: GalleryId,
    val token: GalleryToken,
) {
    val normalizedPath: String
        get() = "/g/$id/$token/?nw=always"

    fun absoluteUrl(site: EhSite): String = site.baseUrl + normalizedPath

    companion object {
        fun parse(url: String): GalleryKey {
            val path = extractPath(url)
            val segments = path.split('/').filter(String::isNotBlank)
            require(segments.size >= 3 && segments[0].equals("g", ignoreCase = true)) {
                "Not an E-Hentai gallery URL"
            }
            return GalleryKey(GalleryId.parse(segments[1]), GalleryToken.parse(segments[2]))
        }

        private fun extractPath(value: String): String {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "Gallery URL is empty" }
            return if (trimmed.contains("://")) {
                val uri = runCatching { URI(trimmed) }.getOrElse { throw IllegalArgumentException("Invalid gallery URL", it) }
                require(uri.scheme.equals("https", ignoreCase = true)) { "Gallery URL must use HTTPS" }
                require(EhSite.fromHost(uri.host) != null) { "Unsupported E-Hentai gallery host" }
                uri.path.orEmpty()
            } else {
                trimmed.substringBefore('?').substringBefore('#')
            }
        }
    }
}

data class EhImagePageRef(
    val galleryId: GalleryId,
    val pageToken: GalleryToken,
    val page: Int,
) {
    init {
        require(page in 1..MAX_PAGE_NUMBER) { "Invalid E-Hentai page number" }
    }

    companion object {
        private const val MAX_PAGE_NUMBER = 100_000

        fun parse(url: String): EhImagePageRef {
            val uri = runCatching { URI(url.trim()) }.getOrElse { throw IllegalArgumentException("Invalid image page URL", it) }
            require(uri.scheme.equals("https", ignoreCase = true)) { "Image page URL must use HTTPS" }
            require(EhSite.fromHost(uri.host) != null) { "Unsupported E-Hentai image page host" }
            val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
            require(segments.size == 3 && segments[0].equals("s", ignoreCase = true)) {
                "Not an E-Hentai image page URL"
            }
            val galleryAndPage = segments[2].split('-')
            require(galleryAndPage.size == 2) { "Malformed E-Hentai image page path" }
            return EhImagePageRef(
                galleryId = GalleryId.parse(galleryAndPage[0]),
                pageToken = GalleryToken.parse(segments[1]),
                page = galleryAndPage[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid image page number"),
            )
        }
    }
}
