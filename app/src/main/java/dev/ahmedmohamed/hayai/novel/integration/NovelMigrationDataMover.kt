package dev.ahmedmohamed.hayai.novel.integration

import android.content.ContentValues
import android.database.Cursor
import com.pushtorefresh.storio.sqlite.queries.DeleteQuery
import com.pushtorefresh.storio.sqlite.queries.InsertQuery
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import com.pushtorefresh.storio.sqlite.queries.UpdateQuery
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import java.util.UUID

class NovelMigrationDataMover(
    private val database: DatabaseHelper,
) {
    fun transfer(
        sourceManga: Manga,
        targetManga: Manga,
        replace: Boolean,
        migrateChapterState: Boolean,
    ) {
        try {
            transferChecked(sourceManga, targetManga, replace, migrateChapterState)
        } catch (error: NovelMigrationException) {
            throw error
        } catch (error: Exception) {
            throw NovelMigrationException("Novel migration data transfer failed", error)
        }
    }

    private fun transferChecked(
        sourceManga: Manga,
        targetManga: Manga,
        replace: Boolean,
        migrateChapterState: Boolean,
    ) {
        val sourceMangaId = sourceManga.id ?: throw NovelMigrationException("Source novel has no database identity")
        val targetMangaId = targetManga.id ?: throw NovelMigrationException("Target novel has no database identity")
        if (sourceMangaId == targetMangaId) return

        val sourceChapters = database.getChapters(sourceManga).executeAsBlocking()
        val targetChapters = database.getChapters(targetManga).executeAsBlocking()
        val targetChapterUrls = targetChapters.associate { chapterId(it) to it.url }
        val quotes = readQuotes(sourceMangaId)
        val statistics = readStatistics(sourceMangaId)
        val highlights = readHighlights(sourceMangaId)
        val progressChapterIds = mutableSetOf<Long>()
        val userStateChapterIds = mutableSetOf<Long>()
        if (migrateChapterState) {
            sourceChapters
                .filter { it.last_page_read != 0 || it.pages_left != 0 }
                .mapTo(progressChapterIds) { chapterId(it) }
            sourceChapters
                .filter { it.read || it.bookmark }
                .mapTo(userStateChapterIds) { chapterId(it) }
            database
                .getHistoryByMangaId(sourceMangaId)
                .executeAsBlocking()
                .mapTo(userStateChapterIds) { it.chapter_id }
        }
        userStateChapterIds.apply {
            addAll(progressChapterIds)
            addAll(statistics.map(NovelMigrationStatistic::chapterId))
            addAll(highlights.map(NovelMigrationHighlight::chapterId))
        }
        val chapterMap =
            NovelMigrationChapterMapper.map(
                requiredSourceIds = userStateChapterIds,
                sourceChapters = sourceChapters.map(::chapterReference),
                targetChapters = targetChapters.map(::chapterReference),
            )

        val statisticActions =
            NovelMigrationStatisticPlanner.plan(
                source = statistics,
                target = readStatistics(targetMangaId),
                chapterMap = chapterMap,
                replace = replace,
            )

        val copiedQuotes = if (replace) emptyList() else planCopiedQuotes(quotes, targetMangaId)
        val copiedHighlights =
            if (replace) {
                emptyList()
            } else {
                planCopiedHighlights(highlights, targetManga, chapterMap, targetChapterUrls)
            }

        if (migrateChapterState) {
            applyProgress(sourceChapters, targetChapters, chapterMap, progressChapterIds)
        }
        if (replace) {
            rebindQuotes(sourceMangaId, targetMangaId, quotes.size)
        } else {
            copiedQuotes.forEach(::insertQuote)
        }
        applyStatistics(statisticActions)
        if (replace) {
            rebindHighlights(highlights, targetManga, chapterMap, targetChapterUrls)
        } else {
            copiedHighlights.forEach(::insertHighlight)
        }
    }

    private fun applyProgress(
        sourceChapters: List<Chapter>,
        targetChapters: List<Chapter>,
        chapterMap: Map<Long, Long>,
        progressChapterIds: Set<Long>,
    ) {
        val sourceById = sourceChapters.associateBy(::chapterId)
        progressChapterIds.sorted().forEach { sourceChapterId ->
            val source = sourceById.getValue(sourceChapterId)
            val targetChapterId = chapterMap.getValue(sourceChapterId)
            if (targetChapters.none { chapterId(it) == targetChapterId }) {
                throw NovelMigrationException("Target chapter $targetChapterId is missing")
            }
            val values =
                ContentValues(2).apply {
                    put("last_page_read", source.last_page_read)
                    put("pages_left", source.pages_left)
                }
            updateExactlyOne("chapters", "_id = ?", arrayOf(targetChapterId), values)
        }
    }

    private fun rebindQuotes(
        sourceMangaId: Long,
        targetMangaId: Long,
        expected: Int,
    ) {
        if (expected == 0) return
        val values = ContentValues(1).apply { put("manga_id", targetMangaId) }
        val updated =
            database.lowLevel().update(
                UpdateQuery.builder().table("hayai_quotes").where("manga_id = ?").whereArgs(sourceMangaId).build(),
                values,
            )
        if (updated != expected) throw NovelMigrationException("Expected to move $expected quotes, moved $updated")
    }

    private fun applyStatistics(actions: List<NovelMigrationStatisticAction>) {
        actions.forEach { action ->
            when (action.operation) {
                NovelMigrationStatisticOperation.Rebind,
                NovelMigrationStatisticOperation.Insert,
                -> {
                    val values =
                        ContentValues(2).apply {
                            put("chapter_id", action.targetChapterId)
                            put("word_count", action.wordCount)
                        }
                    if (action.operation == NovelMigrationStatisticOperation.Rebind) {
                        updateExactlyOne(
                            "hayai_novel_chapter_stats",
                            "chapter_id = ?",
                            arrayOf(action.sourceChapterId),
                            values,
                        )
                    } else {
                        insertExactly("hayai_novel_chapter_stats", values)
                    }
                }
                NovelMigrationStatisticOperation.DeleteSource -> {
                    val deleted =
                        database.lowLevel().delete(
                            DeleteQuery
                                .builder()
                                .table("hayai_novel_chapter_stats")
                                .where("chapter_id = ?")
                                .whereArgs(action.sourceChapterId)
                                .build(),
                        )
                    if (deleted != 1) {
                        throw NovelMigrationException("Could not remove the source chapter statistic")
                    }
                }
            }
        }
    }

    private fun rebindHighlights(
        highlights: List<NovelMigrationHighlight>,
        targetManga: Manga,
        chapterMap: Map<Long, Long>,
        targetChapterUrls: Map<Long, String>,
    ) {
        val targetMangaId = requireNotNull(targetManga.id)
        highlights.forEach { highlight ->
            val values =
                ContentValues(5).apply {
                    put("manga_id", targetMangaId)
                    put("chapter_id", chapterMap.getValue(highlight.chapterId))
                    put("source_id", targetManga.source)
                    put("manga_url", targetManga.url)
                    put("chapter_url", highlightTargetChapterUrl(highlight, chapterMap, targetChapterUrls))
                }
            updateExactlyOne("hayai_novel_highlights", "highlight_id = ?", arrayOf(highlight.id), values)
        }
    }

    private fun planCopiedQuotes(
        quotes: List<NovelMigrationQuote>,
        targetMangaId: Long,
    ): List<NovelMigrationQuote> {
        val plannedById = mutableMapOf<String, NovelMigrationQuote>()
        return quotes.mapNotNull { source ->
            repeat(MAX_ID_ATTEMPTS) { attempt ->
                val candidate =
                    source.copy(
                        id = NovelMigrationCopyIdentity.quoteId(source, targetMangaId, attempt),
                        mangaId = targetMangaId,
                    )
                val existing = plannedById[candidate.id] ?: quoteAtId(candidate.id)
                if (existing == null) {
                    plannedById[candidate.id] = candidate
                    return@mapNotNull candidate
                }
                if (existing == candidate) return@mapNotNull null
            }
            throw NovelMigrationException("Could not allocate a destination quote identity")
        }
    }

    private fun planCopiedHighlights(
        highlights: List<NovelMigrationHighlight>,
        targetManga: Manga,
        chapterMap: Map<Long, Long>,
        targetChapterUrls: Map<Long, String>,
    ): List<NovelMigrationHighlight> {
        val plannedById = mutableMapOf<String, NovelMigrationHighlight>()
        return highlights.mapNotNull { source ->
            val target =
                source.copy(
                    mangaId = requireNotNull(targetManga.id),
                    chapterId = chapterMap.getValue(source.chapterId),
                    sourceId = targetManga.source,
                    mangaUrl = targetManga.url,
                    chapterUrl = highlightTargetChapterUrl(source, chapterMap, targetChapterUrls),
                )
            repeat(MAX_ID_ATTEMPTS) { attempt ->
                val candidate = target.copy(id = NovelMigrationCopyIdentity.highlightId(target, attempt))
                val existing = plannedById[candidate.id] ?: highlightAtId(candidate.id)
                if (existing == null) {
                    plannedById[candidate.id] = candidate
                    return@mapNotNull candidate
                }
                if (existing == candidate) return@mapNotNull null
            }
            throw NovelMigrationException("Could not allocate a destination highlight identity")
        }
    }

    private fun highlightTargetChapterUrl(
        highlight: NovelMigrationHighlight,
        chapterMap: Map<Long, Long>,
        targetChapterUrls: Map<Long, String>,
    ): String {
        val targetChapterId = chapterMap.getValue(highlight.chapterId)
        return targetChapterUrls[targetChapterId]
            ?: throw NovelMigrationException("Target chapter $targetChapterId disappeared during migration")
    }

    private fun insertQuote(quote: NovelMigrationQuote) {
        val values =
            ContentValues(9).apply {
                put("quote_id", quote.id)
                put("manga_id", quote.mangaId)
                put("novel_name", quote.novelName)
                put("chapter_name", quote.chapterName)
                put("displayed_content", quote.displayedContent)
                put("original_content", quote.originalContent)
                put("translated_content", quote.translatedContent)
                put("language", quote.language)
                put("timestamp", quote.timestamp)
            }
        insertExactly("hayai_quotes", values)
    }

    private fun insertHighlight(highlight: NovelMigrationHighlight) {
        val values =
            ContentValues(11).apply {
                put("highlight_id", highlight.id)
                put("manga_id", highlight.mangaId)
                put("chapter_id", highlight.chapterId)
                put("source_id", highlight.sourceId)
                put("manga_url", highlight.mangaUrl)
                put("chapter_url", highlight.chapterUrl)
                put("color", highlight.color)
                put("note", highlight.note)
                put("anchor_json", highlight.anchorJson)
                put("created_at", highlight.createdAt)
                put("updated_at", highlight.updatedAt)
            }
        insertExactly("hayai_novel_highlights", values)
    }

    private fun readQuotes(mangaId: Long): List<NovelMigrationQuote> =
        query(
            "SELECT quote_id,manga_id,novel_name,chapter_name,displayed_content,original_content," +
                "translated_content,language,timestamp FROM hayai_quotes WHERE manga_id = ? ORDER BY quote_id",
            mangaId,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        NovelMigrationQuote(
                            id = cursor.getString(0),
                            mangaId = cursor.getLong(1),
                            novelName = cursor.getString(2),
                            chapterName = cursor.getString(3),
                            displayedContent = cursor.getString(4),
                            originalContent = cursor.nullableString(5),
                            translatedContent = cursor.nullableString(6),
                            language = cursor.nullableString(7),
                            timestamp = cursor.getLong(8),
                        ),
                    )
                }
            }
        }

    private fun quoteAtId(id: String): NovelMigrationQuote? =
        query(
            "SELECT quote_id,manga_id,novel_name,chapter_name,displayed_content,original_content," +
                "translated_content,language,timestamp FROM hayai_quotes WHERE quote_id = ?",
            id,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            NovelMigrationQuote(
                id = cursor.getString(0),
                mangaId = cursor.getLong(1),
                novelName = cursor.getString(2),
                chapterName = cursor.getString(3),
                displayedContent = cursor.getString(4),
                originalContent = cursor.nullableString(5),
                translatedContent = cursor.nullableString(6),
                language = cursor.nullableString(7),
                timestamp = cursor.getLong(8),
            )
        }

    private fun readStatistics(mangaId: Long): List<NovelMigrationStatistic> =
        query(
            "SELECT stats.chapter_id,stats.word_count FROM hayai_novel_chapter_stats stats " +
                "JOIN chapters ON chapters._id = stats.chapter_id WHERE chapters.manga_id = ? ORDER BY stats.chapter_id",
            mangaId,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(NovelMigrationStatistic(cursor.getLong(0), cursor.getLong(1)))
            }
        }

    private fun readHighlights(mangaId: Long): List<NovelMigrationHighlight> =
        query(
            "SELECT highlight_id,manga_id,chapter_id,source_id,manga_url,chapter_url,color,note,anchor_json," +
                "created_at,updated_at FROM hayai_novel_highlights WHERE manga_id = ? ORDER BY highlight_id",
            mangaId,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toMigrationHighlight())
            }
        }

    private fun highlightAtId(id: String): NovelMigrationHighlight? =
        query(
            "SELECT highlight_id,manga_id,chapter_id,source_id,manga_url,chapter_url,color,note,anchor_json," +
                "created_at,updated_at FROM hayai_novel_highlights WHERE highlight_id = ?",
            id,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toMigrationHighlight() else null }

    private fun query(
        sql: String,
        vararg args: Any,
    ): Cursor =
        database.lowLevel().rawQuery(
            RawQuery.builder().query(sql).args(*args).build(),
        )

    private fun insertExactly(
        table: String,
        values: ContentValues,
    ) {
        if (database.lowLevel().insert(InsertQuery.builder().table(table).build(), values) < 0) {
            throw NovelMigrationException("Could not insert migrated data into $table")
        }
    }

    private fun updateExactlyOne(
        table: String,
        where: String,
        args: Array<out Any>,
        values: ContentValues,
    ) {
        val updated =
            database.lowLevel().update(
                UpdateQuery.builder().table(table).where(where).whereArgs(*args).build(),
                values,
            )
        if (updated != 1) throw NovelMigrationException("Expected one $table row, updated $updated")
    }

    private fun chapterId(chapter: Chapter): Long =
        chapter.id ?: throw NovelMigrationException("Novel chapter has no database identity")

    private fun chapterReference(chapter: Chapter) =
        NovelMigrationChapterReference(
            id = chapterId(chapter),
            chapterNumber = chapter.chapter_number,
            recognized = chapter.isRecognizedNumber,
        )

    private fun Cursor.toMigrationHighlight() =
        NovelMigrationHighlight(
            id = getString(0),
            mangaId = getLong(1),
            chapterId = getLong(2),
            sourceId = getLong(3),
            mangaUrl = getString(4),
            chapterUrl = getString(5),
            color = getInt(6),
            note = nullableString(7),
            anchorJson = getString(8),
            createdAt = getLong(9),
            updatedAt = getLong(10),
        )

    private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

    private companion object {
        const val MAX_ID_ATTEMPTS = 100
    }
}

class NovelMigrationException internal constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal data class NovelMigrationChapterReference(
    val id: Long,
    val chapterNumber: Float,
    val recognized: Boolean,
)

internal object NovelMigrationChapterMapper {
    fun map(
        requiredSourceIds: Set<Long>,
        sourceChapters: List<NovelMigrationChapterReference>,
        targetChapters: List<NovelMigrationChapterReference>,
    ): Map<Long, Long> {
        if (requiredSourceIds.isEmpty()) return emptyMap()
        val sourceById = sourceChapters.associateBy(NovelMigrationChapterReference::id)
        val targetsByNumber =
            targetChapters
                .filter(NovelMigrationChapterReference::recognized)
                .groupBy(NovelMigrationChapterReference::chapterNumber)
        val usedTargetIds = mutableSetOf<Long>()
        return buildMap {
            requiredSourceIds.sorted().forEach { sourceId ->
                val source = sourceById[sourceId]
                    ?: throw NovelMigrationException("Source chapter $sourceId is missing")
                if (!source.recognized) {
                    throw NovelMigrationException("Source chapter $sourceId has no stable chapter number")
                }
                val targets = targetsByNumber[source.chapterNumber].orEmpty()
                if (targets.size != 1) {
                    throw NovelMigrationException(
                        "Chapter ${source.chapterNumber} has ${targets.size} possible migration targets",
                    )
                }
                val targetId = targets.single().id
                if (!usedTargetIds.add(targetId)) {
                    throw NovelMigrationException("Multiple source chapters map to target chapter $targetId")
                }
                put(sourceId, targetId)
            }
        }
    }
}

internal data class NovelMigrationQuote(
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

internal data class NovelMigrationStatistic(
    val chapterId: Long,
    val wordCount: Long,
)

internal enum class NovelMigrationStatisticOperation {
    Rebind,
    Insert,
    DeleteSource,
}

internal data class NovelMigrationStatisticAction(
    val operation: NovelMigrationStatisticOperation,
    val sourceChapterId: Long,
    val targetChapterId: Long,
    val wordCount: Long,
)

internal object NovelMigrationStatisticPlanner {
    fun plan(
        source: List<NovelMigrationStatistic>,
        target: List<NovelMigrationStatistic>,
        chapterMap: Map<Long, Long>,
        replace: Boolean,
    ): List<NovelMigrationStatisticAction> {
        val targetByChapter = target.associateBy(NovelMigrationStatistic::chapterId)
        return source.sortedBy(NovelMigrationStatistic::chapterId).mapNotNull { statistic ->
            val targetChapterId = chapterMap.getValue(statistic.chapterId)
            val existing = targetByChapter[targetChapterId]
            if (existing != null && existing.wordCount != statistic.wordCount) {
                throw NovelMigrationException("Chapter statistic conflicts with target chapter $targetChapterId")
            }
            val operation =
                when {
                    existing == null && replace -> NovelMigrationStatisticOperation.Rebind
                    existing == null -> NovelMigrationStatisticOperation.Insert
                    replace -> NovelMigrationStatisticOperation.DeleteSource
                    else -> return@mapNotNull null
                }
            NovelMigrationStatisticAction(
                operation = operation,
                sourceChapterId = statistic.chapterId,
                targetChapterId = targetChapterId,
                wordCount = statistic.wordCount,
            )
        }
    }
}

internal data class NovelMigrationHighlight(
    val id: String,
    val mangaId: Long,
    val chapterId: Long,
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
    val color: Int,
    val note: String?,
    val anchorJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

internal object NovelMigrationCopyIdentity {
    fun quoteId(
        quote: NovelMigrationQuote,
        targetMangaId: Long,
        attempt: Int,
    ): String =
        stableId(
            "hayai-migration-quote-v1",
            listOf(
                quote.id,
                targetMangaId.toString(),
                quote.novelName,
                quote.chapterName,
                quote.displayedContent,
                quote.originalContent,
                quote.translatedContent,
                quote.language,
                quote.timestamp.toString(),
                attempt.toString(),
            ),
        )

    fun highlightId(
        highlight: NovelMigrationHighlight,
        attempt: Int,
    ): String =
        stableId(
            "hayai-migration-highlight-v1",
            listOf(
                highlight.id,
                highlight.mangaId.toString(),
                highlight.chapterId.toString(),
                highlight.sourceId.toString(),
                highlight.mangaUrl,
                highlight.chapterUrl,
                highlight.color.toString(),
                highlight.note,
                highlight.anchorJson,
                highlight.createdAt.toString(),
                highlight.updatedAt.toString(),
                attempt.toString(),
            ),
        )

    private fun stableId(
        namespace: String,
        values: List<String?>,
    ): String {
        val seed =
            buildString {
                append(namespace.length).append(':').append(namespace)
                values.forEach { value ->
                    append('|')
                    if (value == null) {
                        append(-1).append(':')
                    } else {
                        append(value.length).append(':').append(value)
                    }
                }
            }
        return UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
    }
}
