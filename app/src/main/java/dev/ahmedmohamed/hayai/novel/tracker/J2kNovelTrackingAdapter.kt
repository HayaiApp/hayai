package dev.ahmedmohamed.hayai.novel.tracker

import android.content.Context
import dev.ahmedmohamed.hayai.novel.tracker.services.HayaiNovelTrackService
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackSearch

class J2kNovelTrackingGateway(
    private val context: Context,
    private val database: DatabaseHelper,
    private val manager: TrackManager,
) : J2kNovelTrackingAdapter {

    override fun services(): List<NovelTrackerDescriptor> =
        manager.services.filter(TrackService::isLogged).map { service ->
            NovelTrackerDescriptor(
                serviceId = service.id.toLong(),
                name = context.getString(service.nameRes()),
                novelNative = service is HayaiNovelTrackService || service.id in NOVEL_COMPATIBLE_SERVICES,
            )
        }

    override suspend fun search(serviceId: Long, query: String): List<NovelTrackSearchResult> {
        val service = requireService(serviceId)
        return service.search(query).map { result ->
            NovelTrackSearchResult(result.media_id, result.title, result.tracking_url, result.total_chapters.takeIf { it > 0 })
        }
    }

    override suspend fun bind(mangaId: Long, serviceId: Long, result: NovelTrackSearchResult): NovelTrackState {
        val service = requireService(serviceId)
        val search = TrackSearch.create(service.id).apply {
            media_id = result.remoteId
            title = result.title
            tracking_url = result.url
            total_chapters = result.totalChapters ?: 0
        }
        search.manga_id = mangaId
        val bound = service.bind(search).apply { manga_id = mangaId }
        val inserted = database.insertTrack(bound).executeAsBlocking()
        if (bound.id == null) bound.id = inserted.insertedId()
        return bound.toNovelState()
    }

    override suspend fun update(mangaId: Long, state: NovelTrackState, setToRead: Boolean): NovelTrackState {
        val service = requireService(state.serviceId)
        val existing = database.getTracks(mangaId).executeAsBlocking().firstOrNull {
            it.sync_id == service.id && it.media_id == state.remoteId
        } ?: error("Tracking record not found")
        existing.last_chapter_read = maxOf(existing.last_chapter_read, state.chapterRead)
        existing.status = state.status
        existing.score = state.score
        existing.started_reading_date = state.startedAt ?: 0L
        existing.finished_reading_date = state.finishedAt ?: 0L
        val updated = service.update(existing, setToRead).apply { manga_id = mangaId }
        database.insertTrack(updated).executeAsBlocking()
        return updated.toNovelState()
    }

    override suspend fun remove(mangaId: Long, serviceId: Long) {
        val service = requireService(serviceId)
        val manga = requireNotNull(database.getManga(mangaId).executeAsBlocking())
        val track = database.getTracks(mangaId).executeAsBlocking().firstOrNull { it.sync_id == service.id }
        if (track != null && service.canRemoveFromService()) {
            check(service.removeFromService(track)) { "Tracker refused removal" }
        }
        database.deleteTrackForManga(manga, service).executeAsBlocking()
    }

    private fun requireService(id: Long): TrackService =
        requireNotNull(manager.getService(id.toInt())) { "Tracker is unavailable" }
            .also { require(it.isLogged) { "Tracker is not logged in" } }

    private fun Track.toNovelState() =
        NovelTrackState(
            sync_id.toLong(),
            media_id,
            last_chapter_read,
            total_chapters,
            status,
            score,
            started_reading_date.takeIf { it > 0 },
            finished_reading_date.takeIf { it > 0 },
        )

    private companion object {
        val NOVEL_COMPATIBLE_SERVICES =
            setOf(TrackManager.MYANIMELIST, TrackManager.ANILIST, TrackManager.KITSU, TrackManager.MANGA_UPDATES)
    }
}
