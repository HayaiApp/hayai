package yokai.domain.manga.interactor

import eu.kanade.tachiyomi.domain.manga.models.Manga
import java.time.ZonedDateTime
import yokai.domain.manga.MangaRepository
import yokai.domain.manga.models.MangaUpdate

class UpdateManga (
    private val mangaRepository: MangaRepository,
    private val fetchInterval: FetchInterval,
) {
    suspend fun await(update: MangaUpdate) = mangaRepository.update(update)
    suspend fun awaitAll(updates: List<MangaUpdate>) = mangaRepository.updateAll(updates)

    // Verbatim port of mihon UpdateManga.awaitUpdateFetchInterval.
    suspend fun awaitUpdateFetchInterval(
        manga: Manga,
        dateTime: ZonedDateTime = ZonedDateTime.now(),
        window: Pair<Long, Long> = fetchInterval.getWindow(dateTime),
    ): Boolean {
        return mangaRepository.update(
            fetchInterval.toMangaUpdate(manga, dateTime, window),
        )
    }
}
