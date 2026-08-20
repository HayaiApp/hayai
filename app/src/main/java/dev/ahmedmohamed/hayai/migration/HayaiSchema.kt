package dev.ahmedmohamed.hayai.migration

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

object HayaiSchema {
    const val VERSION = 2

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
        ensureColumn(db, "hayai_novel_repos", "enabled", "INTEGER NOT NULL DEFAULT 1")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_novel_plugin_sources(
                source_id INTEGER NOT NULL PRIMARY KEY,
                plugin_id TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                lang TEXT NOT NULL,
                last_seen INTEGER NOT NULL
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
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_schema_migrations(
                version INTEGER NOT NULL PRIMARY KEY,
                applied_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT OR IGNORE INTO hayai_schema_migrations(version, applied_at) VALUES (1, ?)",
            arrayOf(System.currentTimeMillis()),
        )
        migrateToVersion2(db)
    }

    private fun migrateToVersion2(db: SupportSQLiteDatabase) {
        if (hasMigration(db, 2)) return
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            createEhPersistenceTables(db)
            createEhPersistenceTriggers(db)
            backfillStableMetadata(db)
            backfillArchivedMetadata(db)
            db.execSQL(
                "INSERT INTO hayai_schema_migrations(version, applied_at) VALUES (2, ?)",
                arrayOf(System.currentTimeMillis()),
            )
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    private fun createEhPersistenceTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_source_metadata(
                source_id INTEGER NOT NULL,
                manga_url TEXT NOT NULL,
                uploader TEXT,
                extra TEXT NOT NULL,
                indexed_extra TEXT,
                extra_version INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(source_id, manga_url)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS hayai_source_metadata_indexed_extra ON hayai_source_metadata(indexed_extra)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_source_metadata_tags(
                source_id INTEGER NOT NULL,
                manga_url TEXT NOT NULL,
                namespace TEXT NOT NULL DEFAULT '',
                name TEXT NOT NULL,
                type INTEGER NOT NULL,
                PRIMARY KEY(source_id, manga_url, namespace, name, type),
                FOREIGN KEY(source_id, manga_url) REFERENCES hayai_source_metadata(source_id, manga_url) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_source_metadata_titles(
                source_id INTEGER NOT NULL,
                manga_url TEXT NOT NULL,
                title TEXT NOT NULL,
                type INTEGER NOT NULL,
                PRIMARY KEY(source_id, manga_url, title, type),
                FOREIGN KEY(source_id, manga_url) REFERENCES hayai_source_metadata(source_id, manga_url) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_eh_gallery_aliases(
                canonical_gid TEXT NOT NULL,
                canonical_token TEXT NOT NULL,
                alternate_gid TEXT NOT NULL,
                alternate_token TEXT NOT NULL,
                PRIMARY KEY(canonical_gid, canonical_token, alternate_gid, alternate_token),
                UNIQUE(alternate_gid, alternate_token),
                CHECK(canonical_gid != alternate_gid OR canonical_token != alternate_token)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS hayai_eh_gallery_aliases_alternate ON hayai_eh_gallery_aliases(alternate_gid, alternate_token)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_eh_sync_checkpoint(
                singleton INTEGER NOT NULL PRIMARY KEY CHECK(singleton = 1),
                generation INTEGER NOT NULL DEFAULT 0,
                completed_at INTEGER,
                remote_fingerprint TEXT,
                requires_full_reconcile INTEGER NOT NULL DEFAULT 1 CHECK(requires_full_reconcile IN (0, 1))
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT OR IGNORE INTO hayai_eh_sync_checkpoint(singleton, generation, requires_full_reconcile) VALUES (1, 0, 1)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_eh_sync_runs(
                run_id TEXT NOT NULL PRIMARY KEY,
                mode TEXT NOT NULL CHECK(mode IN ('bidirectional', 'remote_only')),
                status TEXT NOT NULL CHECK(status IN ('running', 'complete', 'failed')),
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                error TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hayai_eh_sync_journal(
                operation_id TEXT NOT NULL PRIMARY KEY,
                run_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                operation_kind TEXT NOT NULL,
                gid TEXT,
                token TEXT,
                payload_json TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('pending', 'applied', 'failed')),
                attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
                last_error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(run_id, sequence),
                FOREIGN KEY(run_id) REFERENCES hayai_eh_sync_runs(run_id) ON DELETE CASCADE,
                CHECK((gid IS NULL AND token IS NULL) OR (gid IS NOT NULL AND token IS NOT NULL))
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS hayai_eh_sync_journal_run_status ON hayai_eh_sync_journal(run_id, status, sequence)")
    }

    private fun createEhPersistenceTriggers(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS hayai_search_metadata_insert
            AFTER INSERT ON search_metadata
            BEGIN
                INSERT OR IGNORE INTO hayai_source_metadata(source_id, manga_url, uploader, extra, indexed_extra, extra_version, updated_at)
                SELECT source, url, NEW.uploader, NEW.extra, NEW.indexed_extra, NEW.extra_version, CAST(strftime('%s','now') AS INTEGER) * 1000
                FROM mangas WHERE _id = NEW.manga_id;
                UPDATE hayai_source_metadata SET
                    uploader = NEW.uploader,
                    extra = NEW.extra,
                    indexed_extra = NEW.indexed_extra,
                    extra_version = NEW.extra_version,
                    updated_at = CAST(strftime('%s','now') AS INTEGER) * 1000
                WHERE source_id = (SELECT source FROM mangas WHERE _id = NEW.manga_id)
                    AND manga_url = (SELECT url FROM mangas WHERE _id = NEW.manga_id);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS hayai_search_metadata_update
            AFTER UPDATE ON search_metadata
            BEGIN
                INSERT OR IGNORE INTO hayai_source_metadata(source_id, manga_url, uploader, extra, indexed_extra, extra_version, updated_at)
                SELECT source, url, NEW.uploader, NEW.extra, NEW.indexed_extra, NEW.extra_version, CAST(strftime('%s','now') AS INTEGER) * 1000
                FROM mangas WHERE _id = NEW.manga_id;
                UPDATE hayai_source_metadata SET
                    uploader = NEW.uploader,
                    extra = NEW.extra,
                    indexed_extra = NEW.indexed_extra,
                    extra_version = NEW.extra_version,
                    updated_at = CAST(strftime('%s','now') AS INTEGER) * 1000
                WHERE source_id = (SELECT source FROM mangas WHERE _id = NEW.manga_id)
                    AND manga_url = (SELECT url FROM mangas WHERE _id = NEW.manga_id);
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS hayai_manga_delete_source_metadata
            BEFORE DELETE ON mangas
            BEGIN
                DELETE FROM hayai_source_metadata WHERE source_id = OLD.source AND manga_url = OLD.url;
            END
            """.trimIndent(),
        )
    }

    private fun backfillStableMetadata(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO hayai_source_metadata(source_id, manga_url, uploader, extra, indexed_extra, extra_version, updated_at)
            SELECT m.source, m.url, sm.uploader, sm.extra, sm.indexed_extra, sm.extra_version, ?
            FROM search_metadata sm JOIN mangas m ON m._id = sm.manga_id
            """.trimIndent(),
            arrayOf(System.currentTimeMillis()),
        )
    }

    fun backfillArchivedMetadata(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT table_name, payload_json FROM hayai_legacy_rows WHERE table_name IN ('search_tags', 'search_titles') ORDER BY table_name, legacy_key",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val table = cursor.getString(0)
                val payload = JSONObject(cursor.getString(1))
                val mangaId = payload.getLong("manga_id")
                val identity = mangaIdentity(db, mangaId) ?: continue
                if (!hasStableMetadata(db, identity.first, identity.second)) continue
                when (table) {
                    "search_tags" -> insertArchivedTag(db, identity.first, identity.second, payload)
                    "search_titles" -> insertArchivedTitle(db, identity.first, identity.second, payload)
                }
            }
        }
    }

    private fun insertArchivedTag(
        db: SupportSQLiteDatabase,
        sourceId: Long,
        mangaUrl: String,
        payload: JSONObject,
    ) {
        val name = payload.getString("name")
        val values =
            ContentValues(5).apply {
                put("source_id", sourceId)
                put("manga_url", mangaUrl)
                put("namespace", if (payload.isNull("namespace")) "" else payload.getString("namespace"))
                put("name", name)
                put("type", payload.getInt("type"))
            }
        db.insert("hayai_source_metadata_tags", SQLiteDatabase.CONFLICT_IGNORE, values)
    }

    private fun insertArchivedTitle(
        db: SupportSQLiteDatabase,
        sourceId: Long,
        mangaUrl: String,
        payload: JSONObject,
    ) {
        val values =
            ContentValues(4).apply {
                put("source_id", sourceId)
                put("manga_url", mangaUrl)
                put("title", payload.getString("title"))
                put("type", payload.getInt("type"))
            }
        db.insert("hayai_source_metadata_titles", SQLiteDatabase.CONFLICT_IGNORE, values)
    }

    private fun mangaIdentity(
        db: SupportSQLiteDatabase,
        mangaId: Long,
    ): Pair<Long, String>? =
        db.query("SELECT source, url FROM mangas WHERE _id = ?", arrayOf(mangaId)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
        }

    private fun hasStableMetadata(
        db: SupportSQLiteDatabase,
        sourceId: Long,
        mangaUrl: String,
    ): Boolean =
        db.query(
            "SELECT 1 FROM hayai_source_metadata WHERE source_id = ? AND manga_url = ?",
            arrayOf<Any>(sourceId, mangaUrl),
        ).use { cursor -> cursor.moveToFirst() }

    private fun hasMigration(
        db: SupportSQLiteDatabase,
        version: Int,
    ): Boolean =
        db.query("SELECT 1 FROM hayai_schema_migrations WHERE version = ?", arrayOf(version)).use { cursor ->
            cursor.moveToFirst()
        }

    private fun ensureColumn(
        db: SupportSQLiteDatabase,
        table: String,
        column: String,
        declaration: String,
    ) {
        val exists =
            db.query("PRAGMA table_info($table)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.any { it == column }
            }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $declaration")
    }
}
