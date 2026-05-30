package yokai.domain.stats

import yokai.domain.stats.models.NovelStats

interface NovelStatsRepository {
    /** Records (or updates) the word count for a chapter the first time it is read. */
    suspend fun setWordCount(chapterId: Long, wordCount: Long)

    /**
     * Aggregates every novel statistic in a single off-main-thread pass over the SQL aggregate
     * queries, scoped to [sourceIds] (the installed novel sources). Returns an all-zero
     * [NovelStats] when [sourceIds] is empty.
     */
    suspend fun await(sourceIds: List<Long>): NovelStats
}
