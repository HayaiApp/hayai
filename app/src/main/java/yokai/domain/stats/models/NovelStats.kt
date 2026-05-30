package yokai.domain.stats.models

/** Immutable snapshot of all novel reading statistics, computed via SQL aggregates. */
data class NovelStats(
    val libraryNovels: Long = 0,
    val startedNovels: Long = 0,
    val completedNovels: Long = 0,
    val chaptersRead: Long = 0,
    val totalChapters: Long = 0,
    val totalReadDurationMs: Long = 0,
    val totalWordsRead: Long = 0,
    /** Number of distinct calendar days on which a novel chapter was read. */
    val readingDays: Long = 0,
    /** Consecutive days ending today (or yesterday) with at least one read. */
    val currentStreakDays: Long = 0,
    /** Longest run of consecutive active reading days ever. */
    val longestStreakDays: Long = 0,
    val mostReadNovelTitle: String? = null,
    val mostReadNovelChapters: Long = 0,
    /** Read chapters per active day, averaged over the span between first and last read. */
    val averageChaptersPerDay: Double = 0.0,
)
