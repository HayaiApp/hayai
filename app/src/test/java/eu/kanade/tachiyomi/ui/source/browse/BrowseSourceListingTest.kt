package eu.kanade.tachiyomi.ui.source.browse

import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BrowseSourceListingTest {

    @Test
    fun `source thumbnail repairs a stale non-library row`() {
        val local = Manga.create("/series", "Series", 1L).apply {
            id = 42L
            thumbnail_url = null
        }
        val source = SManga.create().apply {
            url = "/series"
            title = "Series"
            thumbnail_url = "https://example.test/cover.jpg"
        }

        val update = mergeSourceListing(local, source)

        assertEquals(source.thumbnail_url, local.thumbnail_url)
        assertEquals(source.thumbnail_url, update?.thumbnailUrl)
    }

    @Test
    fun `source listing does not replace a library cover`() {
        val local = mockk<Manga>(relaxed = true) {
            every { id } returns 42L
            every { title } returns "Series"
            every { favorite } returns true
            every { thumbnail_url } returns "https://example.test/library-cover.jpg"
        }
        val source = SManga.create().apply {
            url = "/series"
            title = "Series"
            thumbnail_url = "https://example.test/listing-cover.jpg"
        }

        val update = mergeSourceListing(local, source)

        assertEquals("https://example.test/library-cover.jpg", local.thumbnail_url)
        assertNull(update)
        verify(exactly = 0) { local.thumbnail_url = any() }
    }
}
