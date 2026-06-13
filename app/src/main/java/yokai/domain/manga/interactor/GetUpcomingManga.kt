package yokai.domain.manga.interactor

import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.Flow
import yokai.domain.manga.MangaRepository

class GetUpcomingManga(
    private val mangaRepository: MangaRepository,
) {
    private val includedStatuses = setOf(
        SManga.ONGOING.toLong(),
        SManga.PUBLISHING_FINISHED.toLong(),
    )

    fun subscribe(now: Long = System.currentTimeMillis()): Flow<List<Manga>> {
        return mangaRepository.getUpcoming(includedStatuses, now)
    }
}
