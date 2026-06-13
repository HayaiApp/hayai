package eu.kanade.tachiyomi.data.track.anilist.dto

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.util.lang.htmlDecode
import java.text.SimpleDateFormat
import java.util.Locale

data class ALManga(
    val remoteId: Long,
    val title: String,
    val imageUrl: String,
    val description: String?,
    val format: String,
    val publishingStatus: String,
    val startDateFuzzy: Long,
    val totalChapters: Long,
    val averageScore: Int,
    val bannerImage: String?,
    val genres: List<String>,
    val tags: List<String>,
    val siteUrl: String?,
    val externalLinks: List<String>,
) {
    fun toTrack() = TrackSearch.create(TrackManager.ANILIST).apply {
        media_id = remoteId
        title = this@ALManga.title
        total_chapters = totalChapters
        cover_url = imageUrl
        summary = description?.htmlDecode() ?: ""
        score = averageScore.toFloat()  // TODO: Turn to Double
        tracking_url = siteUrl ?: AnilistApi.mangaUrl(media_id)
        publishing_status = publishingStatus
        publishing_type = format
        if (startDateFuzzy != 0L) {
            start_date = try {
                val outputDf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                outputDf.format(startDateFuzzy)
            } catch (e: IllegalArgumentException) {
                ""
            }
        }
    }
}

data class ALUserManga(
    val libraryId: Long,
    val listStatus: String,
    val scoreRaw: Int,
    val chaptersRead: Int,
    val startDateFuzzy: Long,
    val completedDateFuzzy: Long,
    val manga: ALManga,
) {
    fun toTrack() = Track.create(TrackManager.ANILIST).apply {
        media_id = manga.remoteId
        title = manga.title
        status = toTrackStatus()
        score = scoreRaw.toFloat()  // TODO: Turn to Double
        started_reading_date = startDateFuzzy
        finished_reading_date = completedDateFuzzy
        last_chapter_read = chaptersRead.toFloat()  // TODO: Turn to Double
        library_id = libraryId
        total_chapters = manga.totalChapters
    }

    private fun toTrackStatus() = when (listStatus) {
        "CURRENT" -> Anilist.READING
        "COMPLETED" -> Anilist.COMPLETED
        "PAUSED" -> Anilist.ON_HOLD
        "DROPPED" -> Anilist.DROPPED
        "PLANNING" -> Anilist.PLAN_TO_READ
        "REPEATING" -> Anilist.REREADING
        else -> throw NotImplementedError("Unknown status: $listStatus")
    }
}
