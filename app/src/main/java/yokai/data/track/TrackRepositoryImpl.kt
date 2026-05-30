package yokai.data.track

import eu.kanade.tachiyomi.data.database.models.Track
import yokai.data.DatabaseHandler
import yokai.domain.track.TrackRepository

class TrackRepositoryImpl(private val handler: DatabaseHandler) : TrackRepository {
    override suspend fun getAllByMangaId(mangaId: Long): List<Track> =
        handler.awaitList { manga_syncQueries.getAllByMangaId(mangaId, Track::mapper) }

    // Single query for every track row, grouped client-side — replaces the per-manga N+1
    // the library build pass otherwise issues for filtering/grouping by tracking state.
    override suspend fun getAllGroupedByMangaId(): Map<Long, List<Track>> =
        handler.awaitList { manga_syncQueries.getAll(Track::mapper) }.groupBy { it.manga_id }

    override suspend fun deleteForManga(mangaId: Long, syncId: Long) {
        handler.await { manga_syncQueries.deleteForManga(mangaId, syncId) }
    }

    override suspend fun insert(track: Track) {
        handler.await {
            manga_syncQueries.insert(
                mangaId = track.manga_id,
                syncId = track.sync_id,
                remoteId = track.media_id,
                libraryId = track.library_id,
                title = track.title,
                lastChapterRead = track.last_chapter_read.toDouble(),
                totalChapters = track.total_chapters,
                status = track.status.toLong(),
                score = track.score.toDouble(),
                remoteUrl = track.tracking_url,
                startDate = track.started_reading_date,
                finishDate = track.finished_reading_date,
            )
        }
    }

    override suspend fun insertBulk(tracks: List<Track>) {
        handler.await(inTransaction = true) {
            tracks.forEach { track ->
                manga_syncQueries.insert(
                    mangaId = track.manga_id,
                    syncId = track.sync_id,
                    remoteId = track.media_id,
                    libraryId = track.library_id,
                    title = track.title,
                    lastChapterRead = track.last_chapter_read.toDouble(),
                    totalChapters = track.total_chapters,
                    status = track.status.toLong(),
                    score = track.score.toDouble(),
                    remoteUrl = track.tracking_url,
                    startDate = track.started_reading_date,
                    finishDate = track.finished_reading_date,
                )
            }
        }
    }
}
