package dev.ahmedmohamed.hayai.backup

import android.content.ContentValues
import android.database.Cursor
import com.pushtorefresh.storio.sqlite.queries.InsertQuery
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import com.pushtorefresh.storio.sqlite.queries.UpdateQuery
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga

class HayaiBackupService(
    private val database: DatabaseHelper,
) {
    fun create(mangas: List<Manga>): HayaiBackupData {
        val identities = mangas.mapNotNull { manga -> manga.id?.let { it to MangaIdentity(manga.source, manga.url) } }.toMap()
        val quotes = mutableListOf<HayaiBackupQuote>()
        val stats = mutableListOf<HayaiBackupChapterStat>()
        identities.forEach { (mangaId, identity) ->
            query(
                "SELECT quote_id, novel_name, chapter_name, displayed_content, original_content, " +
                    "translated_content, language, timestamp FROM hayai_quotes WHERE manga_id = ? ORDER BY timestamp, quote_id",
                mangaId,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    quotes +=
                        HayaiBackupQuote(
                            id = cursor.getString(0),
                            sourceId = identity.sourceId,
                            mangaUrl = identity.mangaUrl,
                            novelName = cursor.getString(1),
                            chapterName = cursor.getString(2),
                            displayedContent = cursor.getString(3),
                            originalContent = cursor.stringOrNull(4),
                            translatedContent = cursor.stringOrNull(5),
                            language = cursor.stringOrNull(6),
                            timestamp = cursor.getLong(7),
                        )
                }
            }
            database.getChapters(mangaId).executeAsBlocking().forEach { chapter ->
                val chapterId = chapter.id ?: return@forEach
                query("SELECT word_count FROM hayai_novel_chapter_stats WHERE chapter_id = ?", chapterId).use { cursor ->
                    if (cursor.moveToFirst()) {
                        stats += HayaiBackupChapterStat(identity.sourceId, identity.mangaUrl, chapter.url, cursor.getLong(0))
                    }
                }
            }
        }
        return HayaiBackupData(
            quotes = quotes,
            novelRepositories = readNovelRepositories(),
            chapterStats = stats,
            ehFavorites = readEhFavorites(),
        )
    }

    fun restore(data: HayaiBackupData?): HayaiRestoreReport {
        if (data == null) return HayaiRestoreReport(0, 0, emptyList())
        val errors = HayaiBackupLimits.validate(data).toMutableList()
        if (errors.isNotEmpty()) return HayaiRestoreReport(0, data.itemCount(), errors)
        var restored = 0
        var skipped = 0
        val mangaCache = mutableMapOf<MangaIdentity, Manga?>()

        database.inTransaction {
            data.quotes.forEach { quote ->
                process("quote ${quote.id}", errors, { skipped++ }) {
                    val manga = findManga(quote.sourceId, quote.mangaUrl, mangaCache) ?: return@process false
                    restoreQuote(requireNotNull(manga.id), quote)
                }.also { if (it) restored++ }
            }
            data.novelRepositories.forEach { repository ->
                process("novel repository ${repository.baseUrl}", errors, { skipped++ }) {
                    upsert(
                        table = "hayai_novel_repos",
                        where = "base_url = ?",
                        whereArgs = arrayOf(repository.baseUrl),
                        values = ContentValues(2).apply { put("base_url", repository.baseUrl); put("name", repository.name) },
                    )
                }.also { if (it) restored++ }
            }
            data.chapterStats.forEach { stat ->
                process("chapter stat ${stat.chapterUrl}", errors, { skipped++ }) {
                    val manga = findManga(stat.sourceId, stat.mangaUrl, mangaCache) ?: return@process false
                    val chapter = database.getChapters(manga).executeAsBlocking().firstOrNull { it.url == stat.chapterUrl } ?: return@process false
                    val chapterId = requireNotNull(chapter.id)
                    upsert(
                        table = "hayai_novel_chapter_stats",
                        where = "chapter_id = ?",
                        whereArgs = arrayOf(chapterId),
                        values = ContentValues(2).apply { put("chapter_id", chapterId); put("word_count", stat.wordCount) },
                    )
                }.also { if (it) restored++ }
            }
            data.ehFavorites.forEach { favorite ->
                process("E-Hentai favorite ${favorite.gid}", errors, { skipped++ }) {
                    upsert(
                        table = "hayai_eh_favorites",
                        where = "gid = ? AND token = ?",
                        whereArgs = arrayOf(favorite.gid, favorite.token),
                        values =
                            ContentValues(4).apply {
                                put("gid", favorite.gid)
                                put("token", favorite.token)
                                put("title", favorite.title)
                                put("category", favorite.category)
                            },
                    )
                }.also { if (it) restored++ }
            }
        }
        return HayaiRestoreReport(restored, skipped, errors)
    }

    private fun restoreQuote(
        mangaId: Long,
        quote: HayaiBackupQuote,
    ): Boolean {
        val quoteId = resolveQuoteId(mangaId, quote) ?: return true
        val values =
            ContentValues(9).apply {
                put("quote_id", quoteId)
                put("manga_id", mangaId)
                put("novel_name", quote.novelName)
                put("chapter_name", quote.chapterName)
                put("displayed_content", quote.displayedContent)
                put("original_content", quote.originalContent)
                put("translated_content", quote.translatedContent)
                put("language", quote.language)
                put("timestamp", quote.timestamp)
            }
        check(database.lowLevel().insert(insertQuery("hayai_quotes"), values) >= 0)
        return true
    }

    /** Returns null when this exact quote is already present, otherwise a free stable ID. */
    private fun resolveQuoteId(
        mangaId: Long,
        quote: HayaiBackupQuote,
    ): String? {
        quoteAtId(quote.id)?.let { existing -> if (existing.matches(mangaId, quote)) return null } ?: return quote.id
        repeat(100) { attempt ->
            val candidate = HayaiQuoteRestoreIdentity.remappedId(quote, mangaId, attempt)
            quoteAtId(candidate)?.let { existing ->
                if (existing.matches(mangaId, quote)) return null
            } ?: return candidate
        }
        error("Unable to allocate a stable quote ID")
    }

    private fun quoteAtId(quoteId: String): StoredQuote? =
        query(
            "SELECT manga_id, novel_name, chapter_name, displayed_content, original_content, translated_content, language, timestamp " +
                "FROM hayai_quotes WHERE quote_id = ?",
            quoteId,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            StoredQuote(
                mangaId = cursor.getLong(0),
                novelName = cursor.getString(1),
                chapterName = cursor.getString(2),
                displayedContent = cursor.getString(3),
                originalContent = cursor.stringOrNull(4),
                translatedContent = cursor.stringOrNull(5),
                language = cursor.stringOrNull(6),
                timestamp = cursor.getLong(7),
            )
        }

    private fun upsert(
        table: String,
        where: String,
        whereArgs: Array<out Any>,
        values: ContentValues,
    ): Boolean {
        val exists = query("SELECT 1 FROM $table WHERE $where LIMIT 1", *whereArgs).use(Cursor::moveToFirst)
        if (exists) {
            check(database.lowLevel().update(UpdateQuery.builder().table(table).where(where).whereArgs(*whereArgs).build(), values) > 0)
        } else {
            check(database.lowLevel().insert(insertQuery(table), values) >= 0)
        }
        return true
    }

    private fun findManga(
        sourceId: Long,
        mangaUrl: String,
        cache: MutableMap<MangaIdentity, Manga?>,
    ): Manga? {
        val identity = MangaIdentity(sourceId, mangaUrl)
        return cache.getOrPut(identity) { database.getManga(mangaUrl, sourceId).executeAsBlocking() }
    }

    private fun readNovelRepositories(): List<HayaiBackupNovelRepository> =
        query("SELECT base_url, name FROM hayai_novel_repos ORDER BY base_url").use { cursor ->
            buildList { while (cursor.moveToNext()) add(HayaiBackupNovelRepository(cursor.getString(0), cursor.getString(1))) }
        }

    private fun readEhFavorites(): List<HayaiBackupEhFavorite> =
        query("SELECT gid, token, title, category FROM hayai_eh_favorites ORDER BY gid, token").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(HayaiBackupEhFavorite(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3)))
            }
        }

    private fun query(
        sql: String,
        vararg args: Any,
    ): Cursor =
        database.lowLevel().rawQuery(RawQuery.builder().query(sql).args(*args).build())

    private fun insertQuery(table: String): InsertQuery = InsertQuery.builder().table(table).build()

    private inline fun process(
        label: String,
        errors: MutableList<String>,
        skipped: () -> Unit,
        block: () -> Boolean,
    ): Boolean =
        try {
            block().also {
                if (!it) {
                    skipped()
                    errors += "$label: matching core data was not restored or the stable ID conflicts"
                }
            }
        } catch (error: Exception) {
            skipped()
            errors += "$label: ${error.message ?: error.javaClass.simpleName}"
            false
        }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun HayaiBackupData.itemCount() = quotes.size + novelRepositories.size + chapterStats.size + ehFavorites.size

    private data class MangaIdentity(
        val sourceId: Long,
        val mangaUrl: String,
    )

    private data class StoredQuote(
        val mangaId: Long,
        val novelName: String,
        val chapterName: String,
        val displayedContent: String,
        val originalContent: String?,
        val translatedContent: String?,
        val language: String?,
        val timestamp: Long,
    ) {
        fun matches(
            expectedMangaId: Long,
            quote: HayaiBackupQuote,
        ): Boolean =
            mangaId == expectedMangaId &&
                novelName == quote.novelName &&
                chapterName == quote.chapterName &&
                displayedContent == quote.displayedContent &&
                originalContent == quote.originalContent &&
                translatedContent == quote.translatedContent &&
                language == quote.language &&
                timestamp == quote.timestamp
    }
}

internal object HayaiBackupLimits {
    fun validate(data: HayaiBackupData): List<String> =
        buildList {
            if (data.version != HayaiBackupData.CURRENT_VERSION) add("Unsupported Hayai backup version ${data.version}")
            if (data.quotes.size > 100_000) add("Too many Hayai quotes")
            if (data.novelRepositories.size > 1_000) add("Too many novel repositories")
            if (data.chapterStats.size > 1_000_000) add("Too many novel chapter statistics")
            if (data.ehFavorites.size > 100_000) add("Too many E-Hentai favorites")
            data.quotes.firstOrNull {
                it.id.length !in 1..128 ||
                    it.mangaUrl.length !in 1..8_192 ||
                    it.novelName.length !in 1..8_192 ||
                    it.chapterName.length !in 1..8_192 ||
                    it.displayedContent.length !in 1..20_000 ||
                    (it.originalContent?.length ?: 0) > 20_000 ||
                    (it.translatedContent?.length ?: 0) > 20_000 ||
                    (it.language?.length ?: 0) > 128
            }
                ?.let { add("Invalid Hayai quote ${it.id.take(32)}") }
            data.novelRepositories.firstOrNull { it.baseUrl.length !in 1..8_192 || it.name.length !in 1..1_024 }
                ?.let { add("Invalid novel repository") }
            data.chapterStats.firstOrNull { it.mangaUrl.length !in 1..8_192 || it.chapterUrl.length !in 1..8_192 || it.wordCount < 0 }
                ?.let { add("Invalid novel chapter statistic") }
            data.ehFavorites.firstOrNull {
                it.gid.length !in 1..128 ||
                    it.token.length !in 1..512 ||
                    it.title.length !in 1..8_192 ||
                    it.category !in 0..9
            }
                ?.let { add("Invalid E-Hentai favorite") }
        }
}
