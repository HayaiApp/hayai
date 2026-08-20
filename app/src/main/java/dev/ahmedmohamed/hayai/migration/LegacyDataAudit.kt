package dev.ahmedmohamed.hayai.migration

enum class LegacyDataDisposition {
    Core,
    TypedSidecar,
    Archived,
}

/** The known Hayai v36 tables and the migration boundary that owns each one. */
object LegacyDataAudit {
    private val CORE_TABLES =
        setOf(
            "categories",
            "chapters",
            "history",
            "manga_sync",
            "mangas",
            "mangas_categories",
            "search_metadata",
        )

    private val TYPED_SIDECAR_TABLES =
        setOf(
            "series_quotes",
            "novel_repos",
            "novel_chapter_stats",
            "eh_favorites",
        )

    private val ARCHIVED_TABLES =
        setOf(
            "custom_manga_info",
            "excluded_scanlators",
            "extension_repos",
            "feed_saved_search",
            "merged",
            "recents_hidden",
            "saved_search",
            "search_tags",
            "search_titles",
            "series_translation_canon",
            "series_translation_entities",
            "series_translation_relationships",
            "series_translation_events",
            "series_translation_nudges",
            "series_metadata_values",
            "series_metadata_choices",
            "series_display_options",
            "chapter_translation_cache",
            "chapter_translation_queue",
        )

    val knownTables: Map<String, LegacyDataDisposition> =
        buildMap {
            CORE_TABLES.forEach { put(it, LegacyDataDisposition.Core) }
            TYPED_SIDECAR_TABLES.forEach { put(it, LegacyDataDisposition.TypedSidecar) }
            ARCHIVED_TABLES.forEach { put(it, LegacyDataDisposition.Archived) }
        }

    fun disposition(table: String): LegacyDataDisposition = knownTables[table] ?: LegacyDataDisposition.Archived
}
