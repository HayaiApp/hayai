package dev.ahmedmohamed.hayai.novel.tracker.services

import android.content.Context
import android.graphics.Color
import androidx.annotation.StringRes
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.updateNewTrackInfo
import okhttp3.OkHttpClient

abstract class HayaiNovelTrackService internal constructor(
    protected val context: Context,
    id: Int,
    @StringRes private val trackerName: Int,
    private val trackerColor: Int,
    private val apiFactory: (OkHttpClient, () -> String) -> NovelTrackerApi,
) : TrackService(id) {
    private val api: NovelTrackerApi by lazy { apiFactory(client) { getPassword() } }

    protected abstract fun remoteKey(track: Track): String

    override fun nameRes(): Int = trackerName

    override fun getLogo(): Int = R.drawable.ic_book_open_variant_24dp

    override fun getTrackerColor(): Int = trackerColor

    override fun getLogoColor(): Int = Color.WHITE

    override val supportsReadingDates = true

    override fun canRemoveFromService() = true

    override fun getStatusList(): List<Int> =
        listOf(
            NovelReadingStatus.Reading.j2kValue,
            NovelReadingStatus.Completed.j2kValue,
            NovelReadingStatus.OnHold.j2kValue,
            NovelReadingStatus.Dropped.j2kValue,
            NovelReadingStatus.PlanToRead.j2kValue,
        )

    override fun isCompletedStatus(index: Int): Boolean =
        getStatusList().getOrNull(index) == NovelReadingStatus.Completed.j2kValue

    override fun completedStatus(): Int = NovelReadingStatus.Completed.j2kValue

    override fun readingStatus(): Int = NovelReadingStatus.Reading.j2kValue

    override fun planningStatus(): Int = NovelReadingStatus.PlanToRead.j2kValue

    override fun getStatus(status: Int): String = getGlobalStatus(status)

    override fun getGlobalStatus(status: Int): String =
        when (NovelReadingStatus.fromJ2k(status)) {
            NovelReadingStatus.Reading -> context.getString(R.string.reading)
            NovelReadingStatus.Completed -> context.getString(R.string.completed)
            NovelReadingStatus.OnHold -> context.getString(R.string.on_hold)
            NovelReadingStatus.Dropped -> context.getString(R.string.dropped)
            NovelReadingStatus.PlanToRead -> context.getString(R.string.plan_to_read)
            NovelReadingStatus.Other -> "Other"
        }

    override fun getScoreList(): List<String> = listOf("-") + (1..10).map(Int::toString)

    override fun indexToScore(index: Int): Float = index.coerceIn(0, 10).toFloat()

    override fun displayScore(track: Track): String =
        track.score.takeIf { it > 0f }?.let { score ->
            if (score % 1f == 0f) score.toInt().toString() else score.toString()
        } ?: "-"

    override suspend fun login(username: String, password: String) {
        val safeUsername = requireSafeCredential(username, "Username")
        val safeSecret = requireSafeCredential(password, "Session token")
        api.validateSession(safeUsername, safeSecret)
        saveCredentials(safeUsername, safeSecret)
    }

    override suspend fun search(query: String): List<TrackSearch> {
        val normalized = query.trim()
        require(normalized.isNotEmpty()) { "Search query is required" }
        require(normalized.length <= MAXIMUM_QUERY_LENGTH) { "Search query is too long" }
        return api.search(normalized).distinctBy(NovelTrackerSearchItem::remoteKey).take(MAXIMUM_SEARCH_RESULTS).map { item ->
            TrackSearch.create(id).apply {
                media_id = stableRemoteId(item.remoteKey)
                title = item.title
                tracking_url = item.url
                cover_url = item.coverUrl
                summary = item.summary
                publishing_status = item.publishingStatus
                total_chapters = item.totalChapters ?: 0
            }
        }
    }

    override suspend fun add(track: Track): Track {
        track.status = planningStatus()
        track.score = 0f
        updateNewTrackInfo(track)
        return applyPatch(track, api.bind(track.toNovelTrackerRecord(requireRemoteKey(track))), allowProgressRegression = false)
    }

    override suspend fun bind(track: Track): Track =
        applyPatch(track, api.bind(track.toNovelTrackerRecord(requireRemoteKey(track))), allowProgressRegression = false)

    override suspend fun update(track: Track, setToRead: Boolean): Track {
        updateTrackStatus(track, setToRead, setToComplete = true)
        normalize(track)
        return applyPatch(track, api.update(track.toNovelTrackerRecord(requireRemoteKey(track))), allowProgressRegression = false)
    }

    override suspend fun refresh(track: Track): Track =
        applyPatch(track, api.refresh(track.toNovelTrackerRecord(requireRemoteKey(track))), allowProgressRegression = false)

    override suspend fun removeFromService(track: Track): Boolean {
        api.remove(track.toNovelTrackerRecord(requireRemoteKey(track)))
        return true
    }

    private fun requireRemoteKey(track: Track): String =
        remoteKey(track).takeIf(String::isNotBlank)
            ?: throw NovelTrackerFailure.InvalidResponse("The tracker link does not contain a remote identifier")

    private fun applyPatch(track: Track, patch: NovelTrackerPatch, allowProgressRegression: Boolean): Track =
        track.apply {
            patch.title?.takeIf(String::isNotBlank)?.let { title = it }
            patch.url?.takeIf(String::isNotBlank)?.let { tracking_url = it }
            patch.status?.let { status = it.j2kValue }
            patch.chapterRead?.takeIf { it >= 0f }?.let {
                last_chapter_read = if (allowProgressRegression) it else maxOf(last_chapter_read, it)
            }
            patch.score?.takeIf { it in 0f..10f }?.let { score = it }
            patch.totalChapters?.takeIf { it >= 0 }?.let { total_chapters = it }
            patch.startedAt?.takeIf { it >= 0 }?.let { started_reading_date = it }
            patch.finishedAt?.takeIf { it >= 0 }?.let { finished_reading_date = it }
            normalize(this)
        }

    private fun normalize(track: Track) {
        track.last_chapter_read = track.last_chapter_read.coerceAtLeast(0f)
        track.score = track.score.coerceIn(0f, 10f)
        track.total_chapters = track.total_chapters.coerceAtLeast(0)
        if (track.status !in getStatusList()) track.status = readingStatus()
    }

    private companion object {
        const val MAXIMUM_QUERY_LENGTH = 512
        const val MAXIMUM_SEARCH_RESULTS = 100
    }
}
