package dev.ahmedmohamed.hayai.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

class LegacyArchiveStore(
    private val database: SupportSQLiteDatabase,
) {
    fun tables(): List<LegacyArchivedTable> =
        database.query(
            "SELECT table_name, count(*) FROM hayai_legacy_rows GROUP BY table_name ORDER BY table_name",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(LegacyArchivedTable(cursor.getString(0), cursor.getInt(1)))
            }
        }

    fun rows(table: String): List<LegacyArchivedRow> =
        database.query(
            "SELECT legacy_key, payload_json FROM hayai_legacy_rows WHERE table_name = ? ORDER BY legacy_key",
            arrayOf(table),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(LegacyArchivedRow(table, cursor.getString(0), JSONObject(cursor.getString(1))))
                }
            }
        }

    fun find(
        table: String,
        legacyKey: String,
    ): LegacyArchivedRow? =
        database.query(
            "SELECT payload_json FROM hayai_legacy_rows WHERE table_name = ? AND legacy_key = ?",
            arrayOf(table, legacyKey),
        ).use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.let {
                LegacyArchivedRow(table, legacyKey, JSONObject(it.getString(0)))
            }
        }
}

data class LegacyArchivedTable(
    val name: String,
    val rowCount: Int,
)

data class LegacyArchivedRow(
    val table: String,
    val legacyKey: String,
    val payload: JSONObject,
)
