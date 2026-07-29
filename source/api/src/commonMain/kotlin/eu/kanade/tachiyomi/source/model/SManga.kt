package eu.kanade.tachiyomi.source.model

import java.io.Serializable
import kotlinx.serialization.json.JsonObject

interface SManga : Serializable {

    var url: String

    var title: String

    var thumbnail_url: String?

    var artist: String?

    var author: String?

    var status: Int

    var description: String?

    var genre: String?

    var update_strategy: UpdateStrategy

    var initialized: Boolean

    /**
     * Extra metadata associated with the manga.
     *
     * The JSON object is not visible to users and intended for internal or source-specific
     * purposes. Apps may define their own namespaced keys (e.g., `"mihon.*"`) for sources to populate.
     *
     * This allows apps to attach and ask for custom information without affecting the visible
     * manga data.
     *
     * @since tachiyomix 1.6
     */
    var memo: JsonObject

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        // Split on comma only, then trim — sources are inconsistent: some emit "A, B, C", others
        // "A,B,C" (notably most LNReader-style novel plugins use .join(',')). Splitting strictly
        // on ", " would lump the latter into a single chip. The trim handles either form.
        return genre?.split(",")?.map { it.trim() }?.filterNot { it.isBlank() }?.distinct()
    }

    fun copy() = create().also {
        it.url = url
        it.title = title
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre
        it.status = status
        it.thumbnail_url = thumbnail_url
        it.update_strategy = update_strategy
        it.initialized = initialized
        it.memo = memo
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SManga {
            return SMangaImpl()
        }
    }
}

// EXH -->
fun SManga.copy(
    url: String = this.url,
    title: String = this.title,
    artist: String? = this.artist,
    author: String? = this.author,
    description: String? = this.description,
    genre: String? = this.genre,
    status: Int = this.status,
    thumbnail_url: String? = this.thumbnail_url,
    update_strategy: UpdateStrategy = this.update_strategy,
    initialized: Boolean = this.initialized,
    memo: JsonObject = this.memo,
) = SManga.create().also {
    it.url = url
    it.title = title
    it.artist = artist
    it.author = author
    it.description = description
    it.genre = genre
    it.status = status
    it.thumbnail_url = thumbnail_url
    it.update_strategy = update_strategy
    it.initialized = initialized
    it.memo = memo
}
// EXH <--
