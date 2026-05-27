package exh.metadata.metadata

import android.content.Context
import androidx.core.net.toUri
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.copy
import exh.metadata.MetadataUtil
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@Serializable
class EHentaiSearchMetadata : RaisedSearchMetadata() {
    var gId: String?
        get() = indexedExtra
        set(value) {
            indexedExtra = value
        }

    var gToken: String? = null
    var exh: Boolean? = null
    var thumbnailUrl: String? = null

    var title by titleDelegate(TITLE_TYPE_TITLE)
    var altTitle by titleDelegate(TITLE_TYPE_ALT_TITLE)

    var genre: String? = null

    var datePosted: Long? = null
    var parent: String? = null

    var visible: String? = null // Not a boolean
    var language: String? = null
    var translated: Boolean? = null
    var size: Long? = null
    var length: Int? = null
    var favorites: Int? = null
    var ratingCount: Int? = null
    var averageRating: Double? = null

    var aged: Boolean = false
    var lastUpdateCheck: Long = 0

    override fun createMangaInfo(manga: SManga): SManga {
        val key = gId?.let { gId ->
            gToken?.let { gToken ->
                idAndTokenToUrl(gId, gToken)
            }
        }
        val cover = thumbnailUrl

        // No title bug?
        val delegateSourcePreferences = org.koin.core.context.GlobalContext.get().get<exh.pref.DelegateSourcePreferences>()
        val title = altTitle?.takeIf { delegateSourcePreferences.useJapaneseTitle.get() } ?: title

        // Set artist (if we can find one)
        val artist = tags.ofNamespace(EH_ARTIST_NAMESPACE)
            .ifEmpty { null }
            ?.joinToString { it.name }

        // Set group (if we can find one)
        val group = tags.ofNamespace(EH_GROUP_NAMESPACE)
            .ifEmpty { null }
            ?.joinToString { it.name }

        // Copy tags -> genres
        val genres = tagsToGenreString()

        // Try to automatically identify if it is ongoing, we try not to be too lenient here to avoid making mistakes
        // We default to completed
        var status = SManga.COMPLETED
        title?.let { t ->
            MetadataUtil.ONGOING_SUFFIX.find {
                t.endsWith(it, ignoreCase = true)
            }?.let {
                status = SManga.ONGOING
            }
        }

        return manga.copy(
            url = key ?: manga.url,
            title = title ?: manga.title,
            artist = group ?: manga.artist,
            author = artist ?: manga.artist,
            description = null,
            genre = genres,
            status = status,
            thumbnail_url = cover ?: manga.thumbnail_url,
        )
    }

    override fun getExtraInfoPairs(context: Context): List<Pair<String, String>> {
        return listOfNotNull(
            getItem(gId) { "ID" },
            getItem(gToken) { "Token" },
            getItem(exh) { "Is ExHentai gallery" },
            getItem(thumbnailUrl) { "Thumbnail URL" },
            getItem(title) { "Title" },
            getItem(altTitle) { "Alt title" },
            getItem(genre) { "Genre" },
            getItem(
                datePosted,
                {
                    MetadataUtil.EX_DATE_FORMAT
                        .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()))
                },
            ) {
                "Date posted"
            },
            getItem(parent) { "Parent" },
            getItem(visible) { "Visible" },
            getItem(language) { "Language" },
            getItem(translated) { "Translated" },
            getItem(size, { MetadataUtil.humanReadableByteCount(it, true) }) {
                "Gallery size"
            },
            getItem(length) { "Page count" },
            getItem(favorites) { "Total favorites" },
            getItem(ratingCount) { "Total ratings" },
            getItem(averageRating) { "Average rating" },
            getItem(aged) { "Aged" },
            getItem(
                lastUpdateCheck,
                {
                    MetadataUtil.EX_DATE_FORMAT
                        .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()))
                },
            ) { "Last update check" },
        )
    }

    companion object {
        private const val TITLE_TYPE_TITLE = 0
        private const val TITLE_TYPE_ALT_TITLE = 1

        const val TAG_TYPE_NORMAL = 0
        const val TAG_TYPE_LIGHT = 1
        const val TAG_TYPE_WEAK = 2

        const val EH_GENRE_NAMESPACE = "genre"
        private const val EH_ARTIST_NAMESPACE = "artist"
        private const val EH_GROUP_NAMESPACE = "group"
        const val EH_LANGUAGE_NAMESPACE = "language"
        const val EH_META_NAMESPACE = "meta"
        const val EH_UPLOADER_NAMESPACE = "uploader"
        const val EH_VISIBILITY_NAMESPACE = "visibility"

        private fun splitGalleryUrl(url: String) =
            url.let {
                // Only parse URL if is full URL
                val pathSegments = if (it.startsWith("http")) {
                    it.toUri().pathSegments
                } else {
                    it.split('/')
                }
                pathSegments.filterNot(String::isNullOrBlank)
            }

        fun galleryId(url: String): String = splitGalleryUrl(url)[1]

        fun galleryToken(url: String): String =
            splitGalleryUrl(url)[2]

        fun normalizeUrl(url: String) =
            idAndTokenToUrl(galleryId(url), galleryToken(url))

        fun idAndTokenToUrl(id: String, token: String) =
            "/g/$id/$token/?nw=always"
    }
}
