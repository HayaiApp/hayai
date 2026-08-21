package dev.ahmedmohamed.hayai.novel.highlight

import android.content.ContentValues
import com.pushtorefresh.storio.sqlite.queries.InsertQuery
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import com.pushtorefresh.storio.sqlite.queries.UpdateQuery
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class NovelHighlight(
    val id: String,
    val mangaId: Long,
    val chapterId: Long,
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
    val color: Int,
    val note: String?,
    val anchor: NovelHighlightAnchor,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class NovelHighlightBackup(
    val id: String,
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
    val color: Int,
    val note: String?,
    val anchor: NovelHighlightAnchor,
    val createdAt: Long,
    val updatedAt: Long,
)

data class RestoredHighlightIdentity(val mangaId: Long, val chapterId: Long)
data class UnresolvedHighlightIdentity(
    val highlightId: String,
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
)
data class NovelHighlightRestoreResult(
    val restored: Int,
    val unresolved: List<UnresolvedHighlightIdentity>,
)

class NovelHighlightStore(private val database: DatabaseHelper, private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun create(mangaId: Long, chapterId: Long, sourceId: Long, mangaUrl: String, chapterUrl: String, color: Int, note: String?, anchor: NovelHighlightAnchor): NovelHighlight {
        require(mangaId > 0 && chapterId > 0)
        require(mangaUrl.isNotBlank() && mangaUrl.length <= 8_192 && chapterUrl.isNotBlank() && chapterUrl.length <= 8_192)
        require(note == null || note.length <= MAX_NOTE)
        val now = System.currentTimeMillis()
        val highlight = NovelHighlight(UUID.randomUUID().toString(), mangaId, chapterId, sourceId, mangaUrl, chapterUrl, color, note?.trim()?.takeIf(String::isNotEmpty), anchor, now, now)
        val values = ContentValues(11).apply {
            put("highlight_id", highlight.id); put("manga_id", mangaId); put("chapter_id", chapterId); put("color", color)
            put("source_id", sourceId); put("manga_url", mangaUrl); put("chapter_url", chapterUrl)
            put("note", highlight.note); put("anchor_json", json.encodeToString(anchor)); put("created_at", now); put("updated_at", now)
        }
        check(database.lowLevel().insert(InsertQuery.builder().table("hayai_novel_highlights").build(), values) >= 0)
        return highlight
    }

    fun forChapter(chapterId: Long): List<NovelHighlight> = database.lowLevel().rawQuery(
        RawQuery.builder().query("SELECT highlight_id,manga_id,chapter_id,source_id,manga_url,chapter_url,color,note,anchor_json,created_at,updated_at FROM hayai_novel_highlights WHERE chapter_id=? ORDER BY created_at").args(chapterId).build(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) runCatching {
                add(cursor.toHighlight())
            }
        }
    }

    fun forStableChapter(sourceId: Long, mangaUrl: String, chapterUrl: String): List<NovelHighlight> = database.lowLevel().rawQuery(
        RawQuery.builder().query("SELECT highlight_id,manga_id,chapter_id,source_id,manga_url,chapter_url,color,note,anchor_json,created_at,updated_at FROM hayai_novel_highlights WHERE source_id=? AND manga_url=? AND chapter_url=? ORDER BY created_at").args(sourceId, mangaUrl, chapterUrl).build(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                runCatching {
                    add(cursor.toHighlight())
                }
            }
        }
    }

    fun exportAll(): List<NovelHighlightBackup> = database.lowLevel().rawQuery(
        RawQuery.builder()
            .query("SELECT highlight_id,source_id,manga_url,chapter_url,color,note,anchor_json,created_at,updated_at FROM hayai_novel_highlights ORDER BY created_at,highlight_id")
            .build(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                runCatching {
                    add(
                        NovelHighlightBackup(
                            id = cursor.getString(0),
                            sourceId = cursor.getLong(1),
                            mangaUrl = cursor.getString(2),
                            chapterUrl = cursor.getString(3),
                            color = cursor.getInt(4),
                            note = cursor.getString(5),
                            anchor = json.decodeFromString(cursor.getString(6)),
                            createdAt = cursor.getLong(7),
                            updatedAt = cursor.getLong(8),
                        ),
                    )
                }
            }
        }
    }

    fun restore(
        entries: List<NovelHighlightBackup>,
        resolve: (Long, String, String) -> RestoredHighlightIdentity?,
    ): NovelHighlightRestoreResult {
        require(entries.size <= 100_000)
        var restored = 0
        val unresolved = mutableListOf<UnresolvedHighlightIdentity>()
        entries.forEach { entry ->
            require(entry.note == null || entry.note.length <= MAX_NOTE)
            val anchorJson = json.encodeToString(entry.anchor)
            val existing = database.lowLevel().rawQuery(
                RawQuery.builder()
                    .query("SELECT source_id,manga_url,chapter_url,anchor_json FROM hayai_novel_highlights WHERE highlight_id=?")
                    .args(entry.id)
                    .build(),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    listOf(cursor.getLong(0).toString(), cursor.getString(1), cursor.getString(2), cursor.getString(3))
                } else {
                    null
                }
            }
            if (existing != null) {
                require(existing == listOf(entry.sourceId.toString(), entry.mangaUrl, entry.chapterUrl, anchorJson)) {
                    "Highlight identity conflict for ${entry.id}"
                }
                val values = ContentValues(4).apply {
                    put("color", entry.color)
                    put("note", entry.note)
                    put("created_at", entry.createdAt)
                    put("updated_at", entry.updatedAt)
                }
                database.lowLevel().update(
                    UpdateQuery.builder().table("hayai_novel_highlights").where("highlight_id=?").whereArgs(entry.id).build(),
                    values,
                )
                restored++
                return@forEach
            }
            val identity = resolve(entry.sourceId, entry.mangaUrl, entry.chapterUrl)
            if (identity == null) {
                unresolved += UnresolvedHighlightIdentity(
                    highlightId = entry.id,
                    sourceId = entry.sourceId,
                    mangaUrl = entry.mangaUrl,
                    chapterUrl = entry.chapterUrl,
                )
                return@forEach
            }
            val values = ContentValues(11).apply {
                put("highlight_id", entry.id)
                put("manga_id", identity.mangaId)
                put("chapter_id", identity.chapterId)
                put("source_id", entry.sourceId)
                put("manga_url", entry.mangaUrl)
                put("chapter_url", entry.chapterUrl)
                put("color", entry.color)
                put("note", entry.note)
                put("anchor_json", anchorJson)
                put("created_at", entry.createdAt)
                put("updated_at", entry.updatedAt)
            }
            check(database.lowLevel().insert(InsertQuery.builder().table("hayai_novel_highlights").build(), values) >= 0)
            restored++
        }
        return NovelHighlightRestoreResult(restored, unresolved)
    }

    fun update(id: String, color: Int, note: String?): Boolean {
        require(note == null || note.length <= MAX_NOTE)
        val values = ContentValues(3).apply { put("color", color); put("note", note?.trim()?.takeIf(String::isNotEmpty)); put("updated_at", System.currentTimeMillis()) }
        return database.lowLevel().update(UpdateQuery.builder().table("hayai_novel_highlights").where("highlight_id=?").whereArgs(id).build(), values) > 0
    }

    fun delete(id: String): Boolean {
        val exists = database.lowLevel().rawQuery(RawQuery.builder().query("SELECT 1 FROM hayai_novel_highlights WHERE highlight_id=?").args(id).build()).use { it.moveToFirst() }
        if (exists) database.lowLevel().executeSQL(RawQuery.builder().query("DELETE FROM hayai_novel_highlights WHERE highlight_id=?").args(id).build())
        return exists
    }

    private fun android.database.Cursor.toHighlight() = NovelHighlight(
        id = getString(0),
        mangaId = getLong(1),
        chapterId = getLong(2),
        sourceId = getLong(3),
        mangaUrl = getString(4),
        chapterUrl = getString(5),
        color = getInt(6),
        note = getString(7),
        anchor = json.decodeFromString(getString(8)),
        createdAt = getLong(9),
        updatedAt = getLong(10),
    )

    companion object {
        private const val MAX_NOTE = 16_384
    }
}
