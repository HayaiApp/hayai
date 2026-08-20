package dev.ahmedmohamed.hayai.migration

import androidx.sqlite.db.SupportSQLiteDatabase

object HayaiSchema {
    const val VERSION = 1

    fun ensure(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS search_metadata(
                manga_id INTEGER NOT NULL PRIMARY KEY,
                uploader TEXT,
                extra TEXT NOT NULL,
                indexed_extra TEXT,
                extra_version INTEGER NOT NULL,
                FOREIGN KEY(manga_id) REFERENCES mangas(_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS search_metadata_uploader_index ON search_metadata(uploader)")
        db.execSQL("CREATE INDEX IF NOT EXISTS search_metadata_indexed_extra_index ON search_metadata(indexed_extra)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_migration_state(
                plan_id TEXT NOT NULL PRIMARY KEY,
                schema_version INTEGER NOT NULL,
                legacy_user_version INTEGER,
                status TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                report_json TEXT,
                error TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_quotes(
                quote_id TEXT NOT NULL PRIMARY KEY,
                manga_id INTEGER NOT NULL,
                novel_name TEXT NOT NULL,
                chapter_name TEXT NOT NULL,
                displayed_content TEXT NOT NULL,
                original_content TEXT,
                translated_content TEXT,
                language TEXT,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY(manga_id) REFERENCES mangas(_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS hayai_quotes_manga_id_index ON hayai_quotes(manga_id)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_novel_repos(
                base_url TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_novel_chapter_stats(
                chapter_id INTEGER NOT NULL PRIMARY KEY,
                word_count INTEGER NOT NULL,
                FOREIGN KEY(chapter_id) REFERENCES chapters(_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_eh_favorites(
                gid TEXT NOT NULL,
                token TEXT NOT NULL,
                title TEXT NOT NULL,
                category INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(gid, token)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_legacy_rows(
                table_name TEXT NOT NULL,
                legacy_key TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                PRIMARY KEY(table_name, legacy_key)
            )
            """.trimIndent(),
        )
    }
}
