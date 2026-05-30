package yokai.domain.stats.interactor

import yokai.domain.stats.NovelStatsRepository

class SetChapterWordCount(
    private val repository: NovelStatsRepository,
) {
    suspend fun await(chapterId: Long, wordCount: Long) = repository.setWordCount(chapterId, wordCount)
}
