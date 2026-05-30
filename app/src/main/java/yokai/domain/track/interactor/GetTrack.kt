package yokai.domain.track.interactor

import eu.kanade.tachiyomi.data.database.models.Track
import yokai.domain.track.TrackRepository

class GetTrack(
    private val trackRepository: TrackRepository,
) {
    suspend fun awaitAllByMangaId(mangaId: Long?) = mangaId?.let { trackRepository.getAllByMangaId(it) } ?: listOf()

    /** One query for the whole library, grouped by mangaId; used to batch the per-manga N+1. */
    suspend fun awaitAllGroupedByMangaId(): Map<Long, List<Track>> = trackRepository.getAllGroupedByMangaId()
}
