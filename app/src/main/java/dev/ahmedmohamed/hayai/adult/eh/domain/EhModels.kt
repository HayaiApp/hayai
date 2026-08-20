package dev.ahmedmohamed.hayai.adult.eh.domain

sealed class EhFailure(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class AuthenticationRequired(message: String = "E-Hentai authentication is required") : EhFailure(message)

    class AccessDenied(message: String) : EhFailure(message)

    class GalleryNotFound(val key: GalleryKey? = null) : EhFailure("E-Hentai gallery was not found")

    class QuotaExceeded : EhFailure("E-Hentai image quota was exceeded")

    class RateLimited(
        message: String,
        val retryAfterSeconds: Long? = null,
    ) : EhFailure(message)

    class RemoteWarning(message: String) : EhFailure(message)

    class MalformedDocument(message: String, cause: Throwable? = null) : EhFailure(message, cause)

    class BoundsExceeded(message: String) : EhFailure(message)

    class Network(message: String, cause: Throwable? = null) : EhFailure(message, cause)
}

enum class EhCategory(val exclusionBit: Int) {
    Doujinshi(2),
    Manga(4),
    ArtistCg(8),
    GameCg(16),
    Western(512),
    NonH(256),
    ImageSet(32),
    Cosplay(64),
    AsianPorn(128),
    Misc(1),
}

enum class EhToplist(val requestValue: Int?) {
    None(null),
    AllTime(11),
    PastYear(12),
    PastMonth(13),
    Yesterday(15),
}

sealed interface EhSearchCursor {
    data class Gallery(val id: GalleryId) : EhSearchCursor

    data class ToplistPage(val page: Int) : EhSearchCursor {
        init {
            require(page in 1..200) { "Toplist page is outside the supported range" }
        }
    }
}

sealed interface EhJumpTarget {
    val value: String

    data class Date(override val value: String) : EhJumpTarget

    data class Relative(override val value: String) : EhJumpTarget

    companion object {
        private val DATE = Regex("(?:[0-9]{2}|[0-9]{4})-[0-9]{1,2}(?:-[0-9]{1,2})?")
        private val YEAR = Regex("20(?:0[7-9]|[1-9][0-9])")
        private val RELATIVE = Regex("[0-9]+(?:d|w|m|y|-)?")

        fun parse(value: String): EhJumpTarget {
            val normalized = value.trim()
            return when {
                DATE.matches(normalized) || YEAR.matches(normalized) -> Date(normalized)
                RELATIVE.matches(normalized) -> Relative(normalized)
                else -> throw IllegalArgumentException("Invalid E-Hentai jump or seek value")
            }
        }
    }
}

enum class EhTagMode {
    Include,
    Exclude,
    Any,
}

data class EhTagTerm(
    val namespace: String?,
    val value: String,
    val mode: EhTagMode,
)

data class EhSearchSpec(
    val query: String = "",
    val tags: List<EhTagTerm> = emptyList(),
    val toplist: EhToplist = EhToplist.None,
    val watched: Boolean = false,
    val excludedCategories: Set<EhCategory> = emptySet(),
    val browseExpunged: Boolean = false,
    val requireTorrent: Boolean = false,
    val minimumRating: Int? = null,
    val minimumPages: Int? = null,
    val maximumPages: Int? = null,
    val disableLanguageFilter: Boolean = false,
    val disableUploaderFilter: Boolean = false,
    val disableTagFilter: Boolean = false,
    val reverse: Boolean = false,
    val jumpTarget: EhJumpTarget? = null,
) {
    init {
        require(query.length <= 1_024) { "E-Hentai search query is too long" }
        require(query.none(Char::isISOControl)) { "E-Hentai search query contains a control character" }
        require(tags.size <= 8) { "E-Hentai supports at most eight tag terms" }
        require(minimumRating == null || minimumRating in 2..5) { "Minimum rating must be from 2 through 5" }
        require(minimumPages == null || minimumPages in 1..100_000) { "Minimum page count is invalid" }
        require(maximumPages == null || maximumPages in 1..100_000) { "Maximum page count is invalid" }
        require(minimumPages == null || maximumPages == null || minimumPages <= maximumPages) {
            "Minimum page count exceeds maximum page count"
        }
    }
}

enum class EhTagWeight {
    Normal,
    Light,
    Weak,
    Virtual,
}

data class EhTag(
    val namespace: String,
    val name: String,
    val weight: EhTagWeight = EhTagWeight.Normal,
)

data class EhGalleryMetadata(
    val key: GalleryKey,
    val site: EhSite,
    val title: String,
    val alternateTitle: String? = null,
    val thumbnailUrl: String? = null,
    val category: String? = null,
    val uploader: String? = null,
    val postedAtMillis: Long? = null,
    val parent: GalleryKey? = null,
    val visible: String? = null,
    val language: String? = null,
    val translated: Boolean? = null,
    val sizeBytes: Long? = null,
    val pageCount: Int? = null,
    val favoriteCount: Int? = null,
    val ratingCount: Int? = null,
    val averageRating: Double? = null,
    val tags: List<EhTag> = emptyList(),
)

data class EhBrowseGallery(
    val metadata: EhGalleryMetadata,
    val favoriteCategory: Int? = null,
)

data class EhBrowsePage(
    val galleries: List<EhBrowseGallery>,
    val nextCursor: EhSearchCursor?,
)

data class EhRevision(
    val key: GalleryKey,
    val title: String,
    val postedAtMillis: Long?,
)

data class EhDetailsPage(
    val metadata: EhGalleryMetadata,
    val newerRevisions: List<EhRevision>,
)

data class EhGalleryPage(
    val index: Int,
    val pageUrl: String,
) {
    init {
        require(index in 1..100_000) { "E-Hentai gallery page index is invalid" }
    }
}

data class EhPageBatch(
    val listingUrl: String,
    val pages: List<EhGalleryPage>,
    val nextListingUrl: String?,
)

data class EhResolvedImage(
    val imageUrl: String,
    val retryPageUrl: String?,
)

data class EhPagePreview(
    val index: Int,
    val pageUrl: String,
    val imageUrl: String,
    val crop: EhPreviewCrop? = null,
) {
    init {
        require(index in 1..100_000)
        require(pageUrl.startsWith("https://") && imageUrl.startsWith("https://"))
    }
}

data class EhPreviewCrop(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(x >= 0 && y >= 0 && width in 1..2_000 && height in 1..2_000)
    }
}
