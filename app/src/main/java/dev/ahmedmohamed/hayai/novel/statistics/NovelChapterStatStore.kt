package dev.ahmedmohamed.hayai.novel.statistics

import android.content.ContentValues
import com.pushtorefresh.storio.sqlite.queries.InsertQuery
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import com.pushtorefresh.storio.sqlite.queries.UpdateQuery
import eu.kanade.tachiyomi.data.database.DatabaseHelper

internal class NovelChapterStatStore(
    private val database: DatabaseHelper,
) {
    fun store(
        chapterId: Long,
        statistics: NovelChapterStatistics,
    ): NovelChapterStatistics {
        require(chapterId >= 0)
        synchronized(writeLock) {
            val existingWordCount = getWordCount(chapterId)
            if (existingWordCount == statistics.wordCount) return statistics
            val values = ContentValues(2).apply { put("chapter_id", chapterId); put("word_count", statistics.wordCount) }
            if (existingWordCount == null) {
                check(
                    database.lowLevel().insert(InsertQuery.builder().table(TABLE).build(), values) >= 0,
                ) { "Chapter statistics could not be saved" }
            } else {
                check(
                    database.lowLevel().update(
                        UpdateQuery.builder().table(TABLE).where("chapter_id = ?").whereArgs(chapterId).build(),
                        values,
                    ) == 1,
                ) { "Chapter statistics could not be updated" }
            }
        }
        return statistics
    }

    fun get(chapterId: Long): NovelChapterStatistics? =
        getWordCount(chapterId)?.let { NovelChapterStatistics(it.coerceAtLeast(0)) }

    private fun getWordCount(chapterId: Long): Long? =
        database
            .lowLevel()
            .rawQuery(
                RawQuery
                    .builder()
                    .query("SELECT word_count FROM $TABLE WHERE chapter_id = ?")
                    .args(chapterId)
                    .build(),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }

    private companion object {
        const val TABLE = "hayai_novel_chapter_stats"
        val writeLock = Any()
    }
}
