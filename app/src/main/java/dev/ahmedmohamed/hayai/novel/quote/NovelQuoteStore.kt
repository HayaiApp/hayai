package dev.ahmedmohamed.hayai.novel.quote

import android.content.ContentValues
import android.database.Cursor
import com.pushtorefresh.storio.sqlite.queries.DeleteQuery
import com.pushtorefresh.storio.sqlite.queries.InsertQuery
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import com.pushtorefresh.storio.sqlite.queries.UpdateQuery
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import java.util.UUID

class NovelQuoteStore(
    private val database: DatabaseHelper,
) {
    fun add(
        mangaId: Long,
        novelName: String,
        chapterName: String,
        selectedText: String,
        language: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): QuoteAddResult = synchronized(ADD_LOCK) {
        val content = NovelQuoteText.normalize(selectedText)
        database.inTransactionReturn {
            findDuplicate(mangaId, chapterName, content)?.let { return@inTransactionReturn QuoteAddResult.Existing(it) }
            val quote =
                NovelQuote(
                    id = UUID.randomUUID().toString(),
                    mangaId = mangaId,
                    novelName = novelName.trim(),
                    chapterName = chapterName.trim(),
                    displayedContent = content,
                    originalContent = content,
                    translatedContent = null,
                    language = language?.trim()?.takeIf(String::isNotEmpty),
                    timestamp = timestamp,
                )
            val inserted = database.lowLevel().insert(INSERT_QUERY, quote.toContentValues())
            check(inserted >= 0) { "The quote could not be saved." }
            QuoteAddResult.Created(quote)
        }
    }

    fun forManga(mangaId: Long): List<NovelQuote> =
        database
            .lowLevel()
            .rawQuery(
                RawQuery
                    .builder()
                    .query("SELECT $COLUMNS FROM hayai_quotes WHERE manga_id = ? ORDER BY timestamp DESC, quote_id")
                    .args(mangaId)
                    .observesTables("hayai_quotes")
                    .build(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.toQuote())
                    }
                }
            }

    fun delete(quoteId: String): Boolean =
        database.lowLevel().delete(
            DeleteQuery.builder().table("hayai_quotes").where("quote_id = ?").whereArgs(quoteId).build(),
        ) > 0

    fun update(
        quoteId: String,
        displayedContent: String,
        chapterName: String,
        language: String?,
    ): NovelQuote? = synchronized(ADD_LOCK) {
        val existing = get(quoteId) ?: return@synchronized null
        val normalized = NovelQuoteText.normalize(displayedContent)
        findDuplicate(existing.mangaId, chapterName, normalized)?.takeIf { it.id != quoteId }?.let {
            error("This quote is already saved in that chapter.")
        }
        val updated =
            existing.copy(
                displayedContent = normalized,
                chapterName = chapterName.trim().ifBlank { existing.chapterName },
                language = language?.trim()?.takeIf(String::isNotEmpty),
            )
        database.lowLevel().update(
            UpdateQuery.builder().table("hayai_quotes").where("quote_id = ?").whereArgs(quoteId).build(),
            updated.toContentValues(),
        )
        get(quoteId)
    }

    fun move(
        mangaId: Long,
        quoteId: String,
        direction: Int,
    ): Boolean = synchronized(ADD_LOCK) {
        val quotes = forManga(mangaId)
        val index = quotes.indexOfFirst { it.id == quoteId }
        val target = (index + direction.coerceIn(-1, 1)).takeIf { it in quotes.indices } ?: return@synchronized false
        val reordered = quotes.toMutableList().apply { add(target, removeAt(index)) }
        val newestTimestamp = maxOf(System.currentTimeMillis(), quotes.maxOfOrNull(NovelQuote::timestamp) ?: 0L)
        database.inTransactionReturn {
            reordered.forEachIndexed { position, quote ->
                database.lowLevel().update(
                    UpdateQuery.builder().table("hayai_quotes").where("quote_id = ?").whereArgs(quote.id).build(),
                    ContentValues(1).apply { put("timestamp", newestTimestamp - position) },
                )
            }
        }
        true
    }

    fun get(quoteId: String): NovelQuote? =
        database.lowLevel().rawQuery(
            RawQuery.builder().query("SELECT $COLUMNS FROM hayai_quotes WHERE quote_id = ? LIMIT 1").args(quoteId).observesTables("hayai_quotes").build(),
        ).use { cursor -> cursor.takeIf(Cursor::moveToFirst)?.toQuote() }

    private fun findDuplicate(
        mangaId: Long,
        chapterName: String,
        content: String,
    ): NovelQuote? =
        database
            .lowLevel()
            .rawQuery(
                RawQuery
                    .builder()
                    .query(
                        "SELECT $COLUMNS FROM hayai_quotes " +
                            "WHERE manga_id = ? AND chapter_name = ? AND displayed_content = ? LIMIT 1",
                    ).args(mangaId, chapterName.trim(), content)
                    .observesTables("hayai_quotes")
                    .build(),
            ).use { cursor -> cursor.takeIf(Cursor::moveToFirst)?.toQuote() }

    private fun Cursor.toQuote() =
        NovelQuote(
            id = getString(0),
            mangaId = getLong(1),
            novelName = getString(2),
            chapterName = getString(3),
            displayedContent = getString(4),
            originalContent = takeUnless { it.isNull(5) }?.getString(5),
            translatedContent = takeUnless { it.isNull(6) }?.getString(6),
            language = takeUnless { it.isNull(7) }?.getString(7),
            timestamp = getLong(8),
        )

    private fun NovelQuote.toContentValues() =
        ContentValues(9).apply {
            put("quote_id", id)
            put("manga_id", mangaId)
            put("novel_name", novelName)
            put("chapter_name", chapterName)
            put("displayed_content", displayedContent)
            put("original_content", originalContent)
            put("translated_content", translatedContent)
            put("language", language)
            put("timestamp", timestamp)
        }

    private companion object {
        const val COLUMNS =
            "quote_id, manga_id, novel_name, chapter_name, displayed_content, original_content, translated_content, language, timestamp"
        val INSERT_QUERY = InsertQuery.builder().table("hayai_quotes").build()
        val ADD_LOCK = Any()
    }
}

data class NovelQuote(
    val id: String,
    val mangaId: Long,
    val novelName: String,
    val chapterName: String,
    val displayedContent: String,
    val originalContent: String?,
    val translatedContent: String?,
    val language: String?,
    val timestamp: Long,
)

sealed interface QuoteAddResult {
    val quote: NovelQuote

    data class Created(
        override val quote: NovelQuote,
    ) : QuoteAddResult

    data class Existing(
        override val quote: NovelQuote,
    ) : QuoteAddResult
}

internal object NovelQuoteText {
    fun normalize(value: String): String {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim()
        require(normalized.isNotEmpty()) { "Select text before saving a quote." }
        require(normalized.length <= MAX_LENGTH) { "The selected quote is too long." }
        return normalized
    }

    const val MAX_LENGTH = 20_000
}
