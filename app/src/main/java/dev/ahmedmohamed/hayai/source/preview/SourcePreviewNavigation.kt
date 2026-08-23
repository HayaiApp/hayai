package dev.ahmedmohamed.hayai.source.preview

import eu.kanade.tachiyomi.R

internal data class SourcePreviewNavigation(
    val page: Int = 1,
    val totalPages: Int? = null,
    val hasNextPage: Boolean = false,
) {
    init {
        require(page > 0)
        require(totalPages == null || totalPages > 0)
    }

    val previousPage: Int?
        get() = (page - 1).takeIf { it >= 1 }

    val nextPage: Int?
        get() = (page + 1).takeIf { hasNextPage && (totalPages == null || it <= totalPages) }

    val canChoosePage: Boolean
        get() = (totalPages ?: 0) > 1

    val titleResource: Int
        get() = if (totalPages == null) R.string.hayai_page_previews_position_unknown else R.string.hayai_page_previews_position

    val titleArguments: Array<Any>
        get() = totalPages?.let { arrayOf<Any>(page, it) } ?: arrayOf<Any>(page)
}
