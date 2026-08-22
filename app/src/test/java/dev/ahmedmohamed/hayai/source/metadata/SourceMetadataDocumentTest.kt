package dev.ahmedmohamed.hayai.source.metadata

import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMangaIdentity
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMetadata
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMetadataTag
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMetadataTitle
import dev.ahmedmohamed.hayai.source.SourceFamily
import dev.ahmedmohamed.hayai.source.enhanced.EnhancedDescriptionLabel
import dev.ahmedmohamed.hayai.source.enhanced.EnhancedDescriptionRow
import dev.ahmedmohamed.hayai.source.enhanced.EnhancedDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMetadataDocumentTest {
    @Test
    fun `raises persisted E-Hentai metadata into typed fields and namespaced tags`() {
        val stored = SourceMetadata(
            identity = SourceMangaIdentity(6901, "/g/123/token/"),
            uploader = "alice",
            extra = """{"category":"manga","language":"english","translated":true,"pageCount":12,"averageRating":4.5}""",
            indexedExtra = "123",
            extraVersion = 1,
            tags = listOf(SourceMetadataTag("artist", "someone", 0)),
            titles = listOf(SourceMetadataTitle("English", 0), SourceMetadataTitle("日本語", 1)),
        )

        val document = SourceMetadataDocuments.fromEh(stored)

        assertEquals(SourceFamily.EHentai, document.family)
        assertEquals("alice", document.value(SourceMetadataKey.Uploader))
        assertEquals("manga", document.value(SourceMetadataKey.Category))
        assertEquals("12", document.value(SourceMetadataKey.Pages))
        assertEquals(listOf(SourceMetadataTagValue("artist", "someone")), document.tags)
        assertEquals(listOf("English", "日本語"), document.titles)
    }

    @Test
    fun `raises enhanced metadata without flattening source-specific rows`() {
        val details = EnhancedDetails(
            title = "Gallery",
            alternateTitle = "Alternate",
            descriptionRows = listOf(
                EnhancedDescriptionRow(EnhancedDescriptionLabel.Pages, "23"),
                EnhancedDescriptionRow(EnhancedDescriptionLabel.Rating, "4.8"),
            ),
            genres = listOf("artist: Alice", "language: English"),
        )

        val document = SourceMetadataDocuments.fromEnhanced(SourceFamily.NHentai, details)

        assertEquals("23", document.value(SourceMetadataKey.Pages))
        assertEquals("4.8", document.value(SourceMetadataKey.Rating))
        assertTrue(SourceMetadataTagValue("artist", "Alice") in document.tags)
        assertEquals(listOf("Gallery", "Alternate"), document.titles)
    }

    @Test
    fun `persists every enhanced field and tag for offline metadata display`() {
        val identity = SourceMangaIdentity(123, "/gallery/42")
        val details = EnhancedDetails(
            title = "Gallery",
            alternateTitle = "Alternate",
            descriptionRows = listOf(
                EnhancedDescriptionRow(EnhancedDescriptionLabel.Id, "42"),
                EnhancedDescriptionRow(EnhancedDescriptionLabel.Path, "/gallery/42"),
                EnhancedDescriptionRow(EnhancedDescriptionLabel.ThumbnailUrl, "https://example.org/cover.jpg"),
            ),
            genres = listOf("artist: Alice", "category: Doujinshi"),
        )

        val stored = SourceMetadataDocuments.toStoredEnhanced(identity, SourceFamily.EightMuses, details)
        val restored = requireNotNull(SourceMetadataDocuments.fromStoredEnhanced(stored))

        assertEquals(SourceFamily.EightMuses, restored.family)
        assertEquals("42", restored.value(SourceMetadataKey.GalleryId))
        assertEquals("/gallery/42", restored.value(SourceMetadataKey.Path))
        assertEquals("https://example.org/cover.jpg", restored.value(SourceMetadataKey.ThumbnailUrl))
        assertEquals(SourceMetadataTagValue("category", "Doujinshi"), restored.tags.last())
    }
}
