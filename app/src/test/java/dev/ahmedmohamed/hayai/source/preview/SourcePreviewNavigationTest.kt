package dev.ahmedmohamed.hayai.source.preview

import eu.kanade.tachiyomi.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePreviewNavigationTest {
    @Test
    fun `first known page exposes only a bounded next page and full title`() {
        val state = SourcePreviewNavigation(page = 1, totalPages = 3, hasNextPage = true)

        assertNull(state.previousPage)
        assertEquals(2, state.nextPage)
        assertTrue(state.canChoosePage)
        assertEquals(R.string.hayai_page_previews_position, state.titleResource)
        assertArrayEquals(arrayOf(1, 3), state.titleArguments)
    }

    @Test
    fun `last known page suppresses next even if a provider claims one`() {
        val state = SourcePreviewNavigation(page = 3, totalPages = 3, hasNextPage = true)

        assertEquals(2, state.previousPage)
        assertNull(state.nextPage)
    }

    @Test
    fun `unknown single page uses the compact title and no picker`() {
        val state = SourcePreviewNavigation(page = 4)

        assertFalse(state.canChoosePage)
        assertEquals(R.string.hayai_page_previews_position_unknown, state.titleResource)
        assertArrayEquals(arrayOf(4), state.titleArguments)
    }
}
