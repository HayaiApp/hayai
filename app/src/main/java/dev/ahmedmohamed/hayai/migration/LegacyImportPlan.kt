package dev.ahmedmohamed.hayai.migration

data class TableCopyPlan(
    val sourceTable: String,
    val targetTable: String = sourceTable,
    val columns: List<ColumnCopy>,
)

data class ColumnCopy(
    val source: String,
    val target: String = source,
)

internal object LegacyImportPlan {
    val coreTables =
        listOf(
            plan("categories", "_id", "name", "sort", "flags", "manga_order"),
            TableCopyPlan(
                sourceTable = "mangas",
                columns =
                    columns(
                        "_id",
                        "source",
                        "url",
                        "artist",
                        "author",
                        "description",
                        "genre",
                        "title",
                        "status",
                        "thumbnail_url",
                        "favorite",
                        "last_update",
                        "initialized",
                        "viewer",
                        "chapter_flags",
                        "date_added",
                        "filtered_scanlators",
                        "update_strategy",
                        "memo",
                    ) + ColumnCopy("hide_title", "hideTitle"),
            ),
            plan(
                "chapters",
                "_id",
                "manga_id",
                "url",
                "name",
                "scanlator",
                "read",
                "bookmark",
                "last_page_read",
                "pages_left",
                "chapter_number",
                "source_order",
                "date_fetch",
                "date_upload",
                "memo",
            ),
            plan("mangas_categories", "_id", "manga_id", "category_id"),
            plan("history", "history_id", "history_chapter_id", "history_last_read", "history_time_read"),
            plan(
                "manga_sync",
                "_id",
                "manga_id",
                "sync_id",
                "remote_id",
                "library_id",
                "title",
                "last_chapter_read",
                "total_chapters",
                "status",
                "score",
                "remote_url",
                "start_date",
                "finish_date",
            ),
            plan("search_metadata", "manga_id", "uploader", "extra", "indexed_extra", "extra_version"),
        )

    val typedHayaiTables =
        listOf(
            TableCopyPlan(
                "series_quotes",
                "hayai_quotes",
                columns(
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
            ),
            TableCopyPlan("novel_repos", "hayai_novel_repos", columns("base_url", "name")),
            TableCopyPlan("novel_chapter_stats", "hayai_novel_chapter_stats", columns("chapter_id", "word_count")),
            TableCopyPlan("eh_favorites", "hayai_eh_favorites", columns("gid", "token", "title", "category")),
        )

    val promotedTables: Set<String> =
        (coreTables + typedHayaiTables).mapTo(mutableSetOf()) { it.sourceTable }

    private fun plan(
        table: String,
        vararg names: String,
    ) = TableCopyPlan(sourceTable = table, columns = columns(*names))

    private fun columns(vararg names: String) = names.map(::ColumnCopy)
}
