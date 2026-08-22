package dev.ahmedmohamed.hayai.adult.eh.presentation

import dev.ahmedmohamed.hayai.adult.eh.domain.EhGalleryMetadata
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import org.junit.Assert.assertEquals
import org.junit.Test

class EhBrowsePresentationTest {
    @Test
    fun `maps every field used by the SY browse card`() {
        val metadata = EhGalleryMetadata(
            key = GalleryKey.parse("/g/123/token"),
            site = EhSite.EHentai,
            title = "Gallery",
            category = "doujinshi",
            uploader = "uploader",
            postedAtMillis = 1_700_000_000_000,
            language = "english",
            pageCount = 42,
            averageRating = 4.75,
        )

        val result = EhBrowsePresentation.from(metadata)

        assertEquals("doujinshi", result.type?.value)
        assertEquals("uploader", result.uploader)
        assertEquals("en", result.language)
        assertEquals(42, result.pageCount)
        assertEquals(4.75, result.rating ?: 0.0, 0.0)
        assertEquals(1_700_000_000_000, result.postedAtMillis)
    }
}
