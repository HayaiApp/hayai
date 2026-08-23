package dev.ahmedmohamed.hayai.adult.eh.presentation

import dev.ahmedmohamed.hayai.adult.eh.domain.EhGalleryMetadata
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.EhTag
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import dev.ahmedmohamed.hayai.source.presentation.SourceBrowseLayout
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
        assertEquals(SourceBrowseLayout.Default, result.layout)
    }

    @Test
    fun `enhanced browse preference selects the SY detailed list`() {
        val metadata = EhGalleryMetadata(
            key = GalleryKey.parse("/g/123/token"),
            site = EhSite.EHentai,
            title = "Gallery",
        )

        assertEquals(SourceBrowseLayout.DetailedList, EhBrowsePresentation.from(metadata, enhancedList = true).layout)
    }

    @Test
    fun `uses browse row language tag when the details field is unavailable`() {
        val metadata = EhGalleryMetadata(
            key = GalleryKey.parse("/g/123/token"),
            site = EhSite.EHentai,
            title = "Gallery",
            tags = listOf(EhTag("language", "chinese")),
        )

        assertEquals("zh", EhBrowsePresentation.from(metadata).language)
    }

    @Test
    fun `conservatively infers CJK script when browse metadata omits language`() {
        val japanese = EhGalleryMetadata(
            key = GalleryKey.parse("/g/123/token"),
            site = EhSite.EHentai,
            title = "くまみこ 雨宿まち",
        )
        val chinese = japanese.copy(title = "星界之王")
        val ambiguousLatin = japanese.copy(title = "Romanized doujin title")

        assertEquals("ja", EhBrowsePresentation.from(japanese).language)
        assertEquals("zh", EhBrowsePresentation.from(chinese).language)
        assertEquals(null, EhBrowsePresentation.from(ambiguousLatin).language)
    }
}
