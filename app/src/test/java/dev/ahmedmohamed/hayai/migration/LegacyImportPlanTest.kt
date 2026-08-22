package dev.ahmedmohamed.hayai.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyImportPlanTest {
    @Test
    fun `maps legacy manga hide column to J2K spelling`() {
        val mangaPlan = LegacyImportPlan.coreTables.single { it.sourceTable == "mangas" }
        assertEquals("hideTitle", mangaPlan.columns.single { it.source == "hide_title" }.target)
    }

    @Test
    fun `promotes user-authored and source-specific data into typed tables`() {
        val targets = LegacyImportPlan.typedHayaiTables.associate { it.sourceTable to it.targetTable }
        assertEquals("hayai_quotes", targets["series_quotes"])
        assertEquals("hayai_novel_repos", targets["novel_repos"])
        assertEquals("hayai_novel_chapter_stats", targets["novel_chapter_stats"])
        assertEquals("hayai_eh_favorites", targets["eh_favorites"])
        assertTrue(LegacyImportPlan.promotedTables.contains("search_metadata"))
    }

    @Test
    fun `quote migration preserves identity attribution translations and ordering timestamp`() {
        val quotePlan = LegacyImportPlan.typedHayaiTables.single { it.sourceTable == "series_quotes" }
        assertEquals(
            listOf(
                "quote_id",
                "manga_id",
                "novel_name",
                "chapter_name",
                "displayed_content",
                "original_content",
                "translated_content",
                "language",
                "timestamp",
            ),
            quotePlan.columns.map(ColumnCopy::source),
        )
        assertTrue(quotePlan.columns.all { it.source == it.target })
    }

    @Test
    fun `source metadata keeps its typed row and archives titles and tags for backfill`() {
        val metadataPlan = LegacyImportPlan.coreTables.single { it.sourceTable == "search_metadata" }
        assertEquals(
            listOf("manga_id", "uploader", "extra", "indexed_extra", "extra_version"),
            metadataPlan.columns.map(ColumnCopy::source),
        )
        assertEquals(LegacyDataDisposition.Archived, LegacyDataAudit.disposition("search_titles"))
        assertEquals(LegacyDataDisposition.Archived, LegacyDataAudit.disposition("search_tags"))
    }
}
