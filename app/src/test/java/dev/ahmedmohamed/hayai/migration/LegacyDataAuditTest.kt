package dev.ahmedmohamed.hayai.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyDataAuditTest {
    @Test
    fun `every promoted table has one explicit disposition`() {
        val core = LegacyImportPlan.coreTables.mapTo(mutableSetOf()) { it.sourceTable }
        val typed = LegacyImportPlan.typedHayaiTables.mapTo(mutableSetOf()) { it.sourceTable }
        assertEquals(core.size + typed.size, (core + typed).size)
        assertEquals(core, LegacyDataAudit.knownTables.filterValues { it == LegacyDataDisposition.Core }.keys)
        assertEquals(typed, LegacyDataAudit.knownTables.filterValues { it == LegacyDataDisposition.TypedSidecar }.keys)
        (core + typed).forEach { table -> assertFalse(LegacyDataAudit.disposition(table) == LegacyDataDisposition.Archived) }
    }

    @Test
    fun `all known Hayai v36 schema tables are catalogued`() {
        assertEquals(KNOWN_V36_TABLES, LegacyDataAudit.knownTables.keys)
    }

    @Test
    fun `unknown future tables remain losslessly archived`() {
        assertEquals(LegacyDataDisposition.Archived, LegacyDataAudit.disposition("future_hayai_table"))
    }

    private companion object {
        val KNOWN_V36_TABLES =
            setOf(
                "categories", "chapters", "custom_manga_info", "eh_favorites", "excluded_scanlators",
                "extension_repos", "feed_saved_search", "history", "manga_sync", "mangas", "mangas_categories",
                "merged", "novel_repos", "novel_chapter_stats", "recents_hidden", "saved_search", "search_metadata",
                "search_tags", "search_titles", "series_translation_canon", "series_translation_entities",
                "series_translation_relationships", "series_translation_events", "series_translation_nudges",
                "series_metadata_values", "series_metadata_choices", "series_display_options", "series_quotes",
                "chapter_translation_cache", "chapter_translation_queue",
            )
    }
}
