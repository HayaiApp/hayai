package yokai.domain.stats.interactor

import yokai.domain.stats.NovelStatsRepository
import yokai.domain.stats.models.NovelStats

class GetNovelStats(
    private val repository: NovelStatsRepository,
) {
    suspend fun await(sourceIds: List<Long>): NovelStats = repository.await(sourceIds)
}
