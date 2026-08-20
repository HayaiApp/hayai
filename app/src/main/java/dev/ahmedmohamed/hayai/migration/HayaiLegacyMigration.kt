package dev.ahmedmohamed.hayai.migration

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.getBlobOrNull
import androidx.core.database.getDoubleOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import timber.log.Timber

class HayaiLegacyMigration(
    context: Context,
) {
    private val legacyFile = context.getDatabasePath(LEGACY_DATABASE_NAME)

    fun runIfNeeded(target: SupportSQLiteDatabase) {
        HayaiSchema.ensure(target)
        if (!legacyFile.isFile || migrationState(target) != null) return

        val startedAt = System.currentTimeMillis()
        val legacy =
            try {
                SQLiteDatabase.openDatabase(legacyFile.path, null, SQLiteDatabase.OPEN_READONLY)
            } catch (error: Exception) {
                recordFailure(target, startedAt, null, error)
                return
            }

        legacy.use { source ->
            val legacyVersion = source.version
            if (legacyVersion < MINIMUM_SUPPORTED_LEGACY_VERSION || !hasColumn(source, "mangas", "hide_title")) {
                recordFailure(
                    target,
                    startedAt,
                    legacyVersion,
                    IllegalStateException("Unsupported legacy Hayai schema $legacyVersion"),
                )
                return
            }
            if (!targetIsPristine(target)) {
                recordFailure(
                    target,
                    startedAt,
                    legacyVersion,
                    IllegalStateException("Legacy import requires a pristine hayai-j2k.db"),
                )
                return
            }

            val counts = linkedMapOf<String, Int>()
            try {
                target.beginTransaction()
                LegacyImportPlan.coreTables.forEach { counts[it.targetTable] = copyTable(source, target, it) }
                LegacyImportPlan.typedHayaiTables.forEach { counts[it.targetTable] = copyTable(source, target, it) }
                counts["hayai_legacy_rows"] = archiveAllTables(source, target)
                val foreignKeyErrors = foreignKeyErrors(target)
                check(foreignKeyErrors == 0) { "Imported database has $foreignKeyErrors foreign-key violations" }
                recordSuccess(target, startedAt, legacyVersion, counts)
                target.setTransactionSuccessful()
            } catch (error: Exception) {
                Timber.e(error, "Hayai legacy data import failed")
                recordFailureAfterRollback = Triple(startedAt, legacyVersion, error)
            } finally {
                target.endTransaction()
            }
        }

        recordFailureAfterRollback?.let { (startedAt, version, error) ->
            recordFailure(target, startedAt, version, error)
            recordFailureAfterRollback = null
        }
    }

    private var recordFailureAfterRollback: Triple<Long, Int, Exception>? = null

    private fun copyTable(
        source: SQLiteDatabase,
        target: SupportSQLiteDatabase,
        plan: TableCopyPlan,
    ): Int {
        if (!tableExists(source, plan.sourceTable)) return 0
        val select = plan.columns.joinToString(",") { quoteIdentifier(it.source) }
        var copied = 0
        source.rawQuery("SELECT $select FROM ${quoteIdentifier(plan.sourceTable)}", null).use { cursor ->
            while (cursor.moveToNext()) {
                val values = ContentValues(plan.columns.size)
                plan.columns.forEachIndexed { index, column -> putCursorValue(values, column.target, cursor, index) }
                target.insert(plan.targetTable, SQLiteDatabase.CONFLICT_ABORT, values)
                copied++
            }
        }
        return copied
    }

    private fun archiveAllTables(
        source: SQLiteDatabase,
        target: SupportSQLiteDatabase,
    ): Int {
        var archived = 0
        val tables = mutableListOf<String>()
        source.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", null).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        tables.sorted().forEach { table ->
            var row = 0L
            source.rawQuery("SELECT * FROM ${quoteIdentifier(table)}", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val payload = JSONObject()
                    repeat(cursor.columnCount) { index ->
                        payload.put(cursor.getColumnName(index), cursorValue(cursor, index) ?: JSONObject.NULL)
                    }
                    val stableKey = firstStableKey(cursor) ?: "row"
                    val key = "$stableKey#row=$row"
                    val values =
                        ContentValues(3).apply {
                            put("table_name", table)
                            put("legacy_key", key)
                            put("payload_json", payload.toString())
                        }
                    target.insert("hayai_legacy_rows", SQLiteDatabase.CONFLICT_REPLACE, values)
                    row++
                    archived++
                }
            }
        }
        return archived
    }

    private fun firstStableKey(cursor: Cursor): String? {
        for (name in listOf("_id", "id", "quote_id", "manga_id", "chapter_id", "base_url")) {
            val index = cursor.getColumnIndex(name)
            if (index >= 0 && !cursor.isNull(index)) return "$name=${cursor.getString(index)}"
        }
        return null
    }

    private fun cursorValue(
        cursor: Cursor,
        index: Int,
    ): Any? =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLongOrNull(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDoubleOrNull(index)
            Cursor.FIELD_TYPE_BLOB ->
                cursor
                    .getBlobOrNull(
                        index,
                    )?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
            else -> cursor.getStringOrNull(index)
        }

    private fun putCursorValue(
        values: ContentValues,
        name: String,
        cursor: Cursor,
        index: Int,
    ) {
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> values.putNull(name)
            Cursor.FIELD_TYPE_INTEGER -> values.put(name, cursor.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> values.put(name, cursor.getDouble(index))
            Cursor.FIELD_TYPE_BLOB -> values.put(name, cursor.getBlob(index))
            else -> values.put(name, cursor.getString(index))
        }
    }

    private fun migrationState(db: SupportSQLiteDatabase): String? =
        db.query("SELECT status FROM hayai_migration_state WHERE plan_id = ?", arrayOf(PLAN_ID)).use { cursor ->
            cursor.takeIf(Cursor::moveToFirst)?.getString(0)
        }

    private fun recordSuccess(
        db: SupportSQLiteDatabase,
        startedAt: Long,
        legacyVersion: Int,
        counts: Map<String, Int>,
    ) {
        val report = JSONObject(counts).toString()
        db.execSQL(
            "INSERT OR REPLACE INTO hayai_migration_state VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
            arrayOf<Any?>(PLAN_ID, HayaiSchema.VERSION, legacyVersion, "complete", startedAt, System.currentTimeMillis(), report),
        )
    }

    private fun recordFailure(
        db: SupportSQLiteDatabase,
        startedAt: Long,
        legacyVersion: Int?,
        error: Exception,
    ) {
        db.execSQL(
            "INSERT OR REPLACE INTO hayai_migration_state VALUES (?, ?, ?, ?, ?, NULL, NULL, ?)",
            arrayOf<Any?>(PLAN_ID, HayaiSchema.VERSION, legacyVersion, "failed", startedAt, error.message ?: error.javaClass.simpleName),
        )
    }

    private fun foreignKeyErrors(db: SupportSQLiteDatabase): Int =
        db.query("PRAGMA foreign_key_check").use { cursor ->
            var count = 0
            while (cursor.moveToNext()) count++
            count
        }

    private fun targetIsPristine(db: SupportSQLiteDatabase): Boolean =
        (LegacyImportPlan.coreTables + LegacyImportPlan.typedHayaiTables)
            .map(TableCopyPlan::targetTable)
            .plus("hayai_legacy_rows")
            .distinct()
            .all { table ->
                db.query("SELECT 1 FROM ${quoteIdentifier(table)} LIMIT 1").use { cursor -> !cursor.moveToFirst() }
            }

    private fun tableExists(
        db: SQLiteDatabase,
        table: String,
    ): Boolean = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use(Cursor::moveToFirst)

    private fun hasColumn(
        db: SQLiteDatabase,
        table: String,
        column: String,
    ): Boolean =
        db.rawQuery("PRAGMA table_info(${quoteIdentifier(table)})", null).use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == column) return true
            false
        }

    private fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    companion object {
        const val ACTIVE_DATABASE_NAME = "hayai-j2k.db"
        const val LEGACY_DATABASE_NAME = "tachiyomi.db"
        const val PLAN_ID = "hayai-v36-to-j2k-v20"
        const val MINIMUM_SUPPORTED_LEGACY_VERSION = 36
    }
}
