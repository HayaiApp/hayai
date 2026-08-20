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
}
