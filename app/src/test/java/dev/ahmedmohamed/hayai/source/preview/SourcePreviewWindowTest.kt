package dev.ahmedmohamed.hayai.source.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SourcePreviewWindowTest {
    @Test
    fun `seeking into a gallery permits scrolling into both adjacent listings`() {
        val window = SourcePreviewWindow()
        window.replace(page(3))
        window.extend(page(2))
        window.extend(page(4))

        assertEquals((3..8).toList(), window.previews.map { it.index })
        assertEquals(2, window.positionForImage(5))
        assertEquals(1, window.previousPage)
        assertEquals(5, window.nextPage)
        assertEquals(3, window.navigationForImage(5)?.page)
        assertEquals(6, window.navigationForImage(5)?.totalPages)
    }

    @Test
    fun `bounded forward and backward scrolling retain global anchors`() {
        val window = SourcePreviewWindow(pageLimit = 3)
        window.replace(page(1))
        window.extend(page(2))
        window.extend(page(3))
        window.extend(page(4))

        assertNull(window.positionForPage(1))
        assertEquals(2, window.positionForImage(5))
        assertEquals(1, window.previousPage)

        window.extend(page(1))

        assertNull(window.positionForPage(4))
        assertEquals(4, window.positionForImage(5))
        assertEquals(4, window.nextPage)
    }

    @Test
    fun `an arbitrary seek replaces the window and cannot retain stale listings`() {
        val window = SourcePreviewWindow()
        window.replace(page(1))
        window.extend(page(2))
        window.replace(page(6))

        assertEquals(listOf(11, 12), window.previews.map { it.index })
        assertEquals(0, window.positionForPage(6))
        assertEquals(5, window.previousPage)
        assertNull(window.nextPage)
        assertNull(window.navigationForImage(1))
    }

    @Test
    fun `unknown totals follow provider pagination and stop at an empty terminal page`() {
        val window = SourcePreviewWindow()
        window.replace(page(2).copy(totalPages = null))
        window.extend(SourcePreviewPage(3, emptyList(), hasNextPage = false))

        assertEquals(1, window.previousPage)
        assertNull(window.nextPage)
        assertNull(window.navigationForImage(3)?.totalPages)
        assertEquals(listOf(3, 4), window.previews.map { it.index })
    }

    @Test
    fun `repeated or nonadjacent responses cannot corrupt the mounted window`() {
        val window = SourcePreviewWindow()
        window.replace(page(3))

        assertThrows(IllegalArgumentException::class.java) { window.extend(page(3)) }
        assertThrows(IllegalArgumentException::class.java) { window.extend(page(6)) }
        assertEquals(listOf(5, 6), window.previews.map { it.index })
    }

    private fun page(number: Int) = SourcePreviewPage(
        number,
        ((number - 1) * 2 + 1..number * 2).map { SourcePagePreview(it, "https://example.com/$it.jpg") },
        hasNextPage = number < 6,
        totalPages = 6,
    )
}
