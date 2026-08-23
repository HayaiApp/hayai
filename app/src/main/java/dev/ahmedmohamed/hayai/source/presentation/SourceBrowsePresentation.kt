package dev.ahmedmohamed.hayai.source.presentation

import eu.kanade.tachiyomi.data.database.models.Manga

@JvmInline
value class SourceContentType(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

data class SourceBrowsePresentation(
    val type: SourceContentType? = null,
    val uploader: String? = null,
    val rating: Double? = null,
    val language: String? = null,
    val pageCount: Int? = null,
    val postedAtMillis: Long? = null,
    val layout: SourceBrowseLayout = SourceBrowseLayout.Default,
) {
    init {
        require(rating == null || rating in 0.0..5.0)
        require(pageCount == null || pageCount > 0)
        require(postedAtMillis == null || postedAtMillis >= 0)
    }
}

enum class SourceBrowseLayout {
    Default,
    DetailedList,
}

interface SourceBrowsePresentationProvider {
    fun browsePresentation(manga: Manga): SourceBrowsePresentation?
}

class BoundedSourceBrowsePresentationStore(
    private val maximumEntries: Int = 512,
) {
    private val values = object : LinkedHashMap<String, SourceBrowsePresentation>(maximumEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SourceBrowsePresentation>?): Boolean =
            size > maximumEntries
    }

    init {
        require(maximumEntries > 0)
    }

    @Synchronized
    fun put(mangaUrl: String, presentation: SourceBrowsePresentation) {
        values[mangaUrl] = presentation
    }

    @Synchronized
    fun get(mangaUrl: String): SourceBrowsePresentation? = values[mangaUrl]
}
