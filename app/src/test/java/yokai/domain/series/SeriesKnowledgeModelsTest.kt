package yokai.domain.series

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.series.model.SeriesDisplaySection
import yokai.domain.series.model.SeriesMetadataField

class SeriesKnowledgeModelsTest {

    @Test
    fun `metadata fields cover planned enrichment sources`() {
        val fields = SeriesMetadataField.entries.map { it.key }.toSet()

        assertTrue("title" in fields)
        assertTrue("cover" in fields)
        assertTrue("banner" in fields)
        assertTrue("images" in fields)
        assertTrue("aliases" in fields)
        assertTrue("description" in fields)
        assertTrue("genres" in fields)
        assertTrue("author" in fields)
        assertTrue("artist" in fields)
        assertTrue("status" in fields)
        assertTrue("characters" in fields)
        assertTrue("tracker_summary" in fields)
    }

    @Test
    fun `characters are visible by default with existing manga sections`() {
        assertTrue(SeriesDisplaySection.CHARACTERS.defaultVisible)
        assertTrue(SeriesDisplaySection.TITLE.defaultVisible)
        assertTrue(SeriesDisplaySection.COVER_BANNER.defaultVisible)
        assertTrue(SeriesDisplaySection.DESCRIPTION.defaultVisible)
        assertTrue(SeriesDisplaySection.GENRES.defaultVisible)
    }
}
