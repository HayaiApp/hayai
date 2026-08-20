package dev.ahmedmohamed.hayai.novel.source.local

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.Assert.assertEquals
import org.junit.Test

class ComicInfoMetadataTest {
    @Test
    fun `maps namespaced ComicInfo metadata without depending on prefix spelling`() {
        val xml =
            """
            <ComicInfo xmlns:ty="urn:tachiyomi">
              <Title>Chapter title</Title><Series>Series title</Series><Number>12.5</Number>
              <Writer>A. Writer</Writer><Penciller>Artist One</Penciller><Inker>Artist Two</Inker>
              <Summary>A summary</Summary><Genre>Fantasy, Action</Genre><Tags>Action, Magic</Tags>
              <Translator>Translator</Translator><ty:PublishingStatusTachiyomi>On hiatus</ty:PublishingStatusTachiyomi>
            </ComicInfo>
            """.trimIndent()
        val metadata = xml.byteInputStream().use(ComicInfoMetadata::parse)
        val manga = SManga.create().apply { title = "fallback" }
        val chapter = SChapter.create().apply { name = "fallback" }

        metadata.applyTo(manga)
        metadata.applyTo(chapter)

        assertEquals("Series title", manga.title)
        assertEquals("A. Writer", manga.author)
        assertEquals("Artist One, Artist Two", manga.artist)
        assertEquals("Fantasy, Action, Magic", manga.genre)
        assertEquals(SManga.ON_HIATUS, manga.status)
        assertEquals("Chapter title", chapter.name)
        assertEquals(12.5f, chapter.chapter_number)
        assertEquals("Translator", chapter.scanlator)
    }
}
