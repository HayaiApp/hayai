package yokai.data.stats

import yokai.data.DatabaseHandler
import yokai.domain.stats.NovelStatsRepository
import yokai.domain.stats.models.NovelStats

class NovelStatsRepositoryImpl(
    private val handler: DatabaseHandler,
) : NovelStatsRepository {

    private data class DayCount(val day: Long, val readCount: Long)
    private data class MostRead(val title: String, val readCount: Long)

    override suspend fun setWordCount(chapterId: Long, wordCount: Long) {
        handler.await { novel_statsQueries.upsertWordCount(chapterId, wordCount) }
    }

    override suspend fun await(sourceIds: List<Long>): NovelStats {
        // No installed novel sources -> nothing to aggregate; skip the round-trips entirely.
        if (sourceIds.isEmpty()) return NovelStats()

        // Each call is a single SQL aggregate (COUNT/SUM/GROUP BY) executed on the DB thread by the
        // handler; nothing materializes whole tables in memory. Single-column aggregates return their
        // scalar directly; the two coalesce(sum(...)) queries are REAL, so coerce to Long. The two
        // list queries return one row per active day at most, so the streak/average derivation is
        // O(active days).
        val libraryNovels = handler.awaitOne { novel_statsQueries.libraryNovelCount(sourceIds) }
        val startedNovels = handler.awaitOne { novel_statsQueries.startedNovelCount(sourceIds) }
        val completedNovels = handler.awaitOne { novel_statsQueries.completedNovelCount(sourceIds) }
        val chaptersRead = handler.awaitOne { novel_statsQueries.chaptersReadCount(sourceIds) }
        val totalChapters = handler.awaitOne { novel_statsQueries.totalChaptersCount(sourceIds) }
        val totalReadDurationMs = handler.awaitOne { novel_statsQueries.totalReadDuration(sourceIds) }.toLong()
        val totalWordsRead = handler.awaitOne { novel_statsQueries.totalWordsRead(sourceIds) }.toLong()
        val mostRead = handler.awaitOneOrNull {
            novel_statsQueries.mostReadNovel(sourceIds) { title, readCount -> MostRead(title, readCount) }
        }

        // `day` is typed nullable because `history_last_read` is a nullable column, but the queries'
        // `history_last_read > 0` guard means every returned row is non-null at runtime.
        val readDays = handler.awaitList { novel_statsQueries.readDays(sourceIds) { it ?: 0L } }
        val readsPerDay = handler.awaitList {
            novel_statsQueries.readsPerDay(sourceIds) { day, readCount -> DayCount(day ?: 0L, readCount) }
        }

        val (currentStreak, longestStreak) = computeStreaks(readDays)
        val averagePerDay = computeAveragePerDay(readsPerDay)

        return NovelStats(
            libraryNovels = libraryNovels,
            startedNovels = startedNovels,
            completedNovels = completedNovels,
            chaptersRead = chaptersRead,
            totalChapters = totalChapters,
            totalReadDurationMs = totalReadDurationMs,
            totalWordsRead = totalWordsRead,
            readingDays = readDays.size.toLong(),
            currentStreakDays = currentStreak,
            longestStreakDays = longestStreak,
            mostReadNovelTitle = mostRead?.title,
            mostReadNovelChapters = mostRead?.readCount ?: 0,
            averageChaptersPerDay = averagePerDay,
        )
    }

    /**
     * Derives the current and longest consecutive-day streaks from the distinct active-day
     * buckets (`history_last_read / 86400000`), which arrive newest-first. The current streak is
     * anchored to today or yesterday so a missed day breaks it but reading "today" still counts
     * before midnight.
     */
    private fun computeStreaks(daysDesc: List<Long>): Pair<Long, Long> {
        if (daysDesc.isEmpty()) return 0L to 0L

        val today = System.currentTimeMillis() / DAY_MS

        var longest = 1L
        var run = 1L
        for (i in 1 until daysDesc.size) {
            if (daysDesc[i] == daysDesc[i - 1] - 1) {
                run++
            } else {
                run = 1L
            }
            if (run > longest) longest = run
        }

        var current = 0L
        val mostRecent = daysDesc.first()
        if (mostRecent == today || mostRecent == today - 1) {
            current = 1L
            for (i in 1 until daysDesc.size) {
                if (daysDesc[i] == daysDesc[i - 1] - 1) current++ else break
            }
        }
        return current to longest
    }

    /**
     * Average read chapters per day over the active span (first..last read day inclusive). Using
     * the span rather than just active days reflects real cadence including idle days.
     */
    private fun computeAveragePerDay(perDayAsc: List<DayCount>): Double {
        if (perDayAsc.isEmpty()) return 0.0
        val totalReads = perDayAsc.sumOf { it.readCount }
        val span = (perDayAsc.last().day - perDayAsc.first().day + 1).coerceAtLeast(1)
        return totalReads.toDouble() / span.toDouble()
    }

    companion object {
        private const val DAY_MS = 86_400_000L
    }
}
