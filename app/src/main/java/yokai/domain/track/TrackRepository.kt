package yokai.domain.track

import eu.kanade.tachiyomi.data.database.models.Track

interface TrackRepository {
    suspend fun getAllByMangaId(mangaId: Long): List<Track>
    suspend fun getAllGroupedByMangaId(): Map<Long, List<Track>>
    suspend fun deleteForManga(mangaId: Long, syncId: Long)
    suspend fun insert(track: Track)
    suspend fun insertBulk(tracks: List<Track>)
}
