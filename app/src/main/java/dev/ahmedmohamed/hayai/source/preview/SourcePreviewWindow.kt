package dev.ahmedmohamed.hayai.source.preview

/** A bounded, contiguous view of existing provider listings, with global image indices as anchors. */
internal class SourcePreviewWindow(private val pageLimit: Int = 5) {
    private val pages = sortedMapOf<Int, SourcePreviewPage>()
    private var totalPages: Int? = null

    init {
        require(pageLimit >= 3)
    }

    val previews: List<SourcePagePreview>
        get() = pages.values.flatMap { it.previews }.distinctBy { it.index }

    val previousPage: Int?
        get() = pages.firstKeyOrNull()?.minus(1)?.takeIf { it > 0 }

    val nextPage: Int?
        get() = pages.values.lastOrNull()?.let { navigation(it).nextPage }

    fun replace(page: SourcePreviewPage) {
        pages.clear()
        totalPages = page.totalPages
        pages[page.page] = page
    }

    fun extend(page: SourcePreviewPage) {
        require(pages.isNotEmpty())
        require(page.page == previousPage || page.page == nextPage)
        val prepending = page.page < pages.firstKey()
        pages[page.page] = page
        page.totalPages?.let { totalPages = it }
        if (pages.size > pageLimit) {
            pages.remove(if (prepending) pages.lastKey() else pages.firstKey())
        }
    }

    fun positionForPage(page: Int): Int? =
        pages[page]?.previews?.firstOrNull()?.index?.let(::positionForImage)

    fun positionForImage(index: Int): Int? = previews.indexOfFirst { it.index == index }.takeIf { it >= 0 }

    fun navigationForImage(index: Int): SourcePreviewNavigation? =
        pages.values.firstOrNull { page -> page.previews.any { it.index == index } }?.let(::navigation)

    fun navigationForPage(page: Int): SourcePreviewNavigation? = pages[page]?.let(::navigation)

    private fun navigation(page: SourcePreviewPage) = SourcePreviewNavigation(page.page, totalPages, page.hasNextPage)

    private fun java.util.SortedMap<Int, SourcePreviewPage>.firstKeyOrNull(): Int? = if (isEmpty()) null else firstKey()
}
