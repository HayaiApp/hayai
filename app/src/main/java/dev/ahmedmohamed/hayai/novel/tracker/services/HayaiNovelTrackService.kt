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
            NovelReadingStatus.Other -> context.getString(R.string.other)
        }

    override fun getScoreList(): List<String> = listOf("-") + (1..10).map(Int::toString)

    override fun indexToScore(index: Int): Float = index.coerceIn(0, 10).toFloat()

    override fun displayScore(track: Track): String =
        track.score.takeIf { it > 0f }?.let { score ->
            if (score % 1f == 0f) score.toInt().toString() else score.toString()
        } ?: "-"

    override suspend fun login(username: String, password: String) {
        localizedApiCall {
            val safeUsername = requireSafeCredential(username, NovelTrackerCredential.Username)
            val safeSecret = requireSafeCredential(password, NovelTrackerCredential.SessionToken)
            api.validateSession(safeUsername, safeSecret)
            saveCredentials(safeUsername, safeSecret)
        }
    }

    override suspend fun search(query: String): List<TrackSearch> {
        val normalized = query.trim()
        require(normalized.isNotEmpty()) { context.getString(R.string.hayai_tracker_search_query_required) }
        require(normalized.length <= MAXIMUM_QUERY_LENGTH) {
            context.getString(R.string.hayai_tracker_search_query_too_long, MAXIMUM_QUERY_LENGTH)
        }
        return localizedApiCall { api.search(normalized) }.distinctBy(NovelTrackerSearchItem::remoteKey).take(MAXIMUM_SEARCH_RESULTS).map { item ->
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
        return localizedApiCall {
            applyPatch(track, api.bind(track.toNovelTrackerRecord(requireRemoteKey(track))), allowProgressRegression = false)
        }
    }

    override suspend fun bind(track: Track): Track =
        localizedApiCall {
            applyPatch(track, api.bind(track.toNovelTrackerRecord(requireRemoteKey(track))), allowProgressRegression = false)
        }

    override suspend fun update(track: Track, setToRead: Boolean): Track {
        updateTrackStatus(track, setToRead, setToComplete = true)
        normalize(track)
        return localizedApiCall {
            applyPatch(track, api.update(track.toNovelTrackerRecord(requireRemoteKey(track))), allowProgressRegression = false)
        }
    }

    override suspend fun refresh(track: Track): Track =
        localizedApiCall {
            applyPatch(track, api.refresh(track.toNovelTrackerRecord(requireRemoteKey(track))), allowProgressRegression = false)
        }

    override suspend fun removeFromService(track: Track): Boolean {
        localizedApiCall { api.remove(track.toNovelTrackerRecord(requireRemoteKey(track))) }
        return true
    }

    private fun requireRemoteKey(track: Track): String =
        remoteKey(track).takeIf(String::isNotBlank)
            ?: throw NovelTrackerFailure.InvalidResponse(NovelTrackerResponseIssue.TrackerLinkMissingRemoteId)

    private suspend fun <T> localizedApiCall(block: suspend () -> T): T =
        try {
            block()
        } catch (failure: NovelTrackerFailure) {
            throw IllegalStateException(failure.displayMessage(context), failure)
        }

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

private fun NovelTrackerFailure.displayMessage(context: Context): String = when (this) {
    is NovelTrackerFailure.InvalidCredentials -> when (issue) {
        NovelTrackerCredentialIssue.Generic -> context.getString(R.string.hayai_tracker_credentials_invalid)
        NovelTrackerCredentialIssue.Required -> context.getString(
            R.string.hayai_tracker_credential_required,
            context.getString(requireNotNull(credential).labelRes()),
        )
        NovelTrackerCredentialIssue.InvalidCharacters -> context.getString(
            R.string.hayai_tracker_credential_invalid_characters,
            context.getString(requireNotNull(credential).labelRes()),
        )
        NovelTrackerCredentialIssue.NovelListTokenRequired -> context.getString(R.string.hayai_tracker_novellist_token_required)
        NovelTrackerCredentialIssue.NovelListTokenMalformed -> context.getString(R.string.hayai_tracker_novellist_token_malformed)
        NovelTrackerCredentialIssue.NovelListTokenExpired -> context.getString(R.string.hayai_tracker_novellist_token_expired)
        NovelTrackerCredentialIssue.RanobeDbAuthCookieRequired -> context.getString(R.string.hayai_tracker_ranobedb_cookie_required)
        NovelTrackerCredentialIssue.NovelUpdatesCookiesExpired -> context.getString(R.string.hayai_tracker_novelupdates_cookies_expired)
        NovelTrackerCredentialIssue.NovelUpdatesCookieHeaderRequired -> context.getString(R.string.hayai_tracker_novelupdates_cookie_header_required)
    }
    is NovelTrackerFailure.SessionExpired -> context.getString(R.string.hayai_tracker_session_expired)
    is NovelTrackerFailure.RateLimited -> retryAfterSeconds?.let { seconds ->
        context.resources.getQuantityString(
            R.plurals.hayai_tracker_rate_limited_seconds,
            seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            seconds,
        )
    } ?: context.getString(R.string.hayai_tracker_rate_limited)
    is NovelTrackerFailure.ResponseTooLarge -> context.getString(R.string.hayai_tracker_response_too_large, maximumBytes)
    is NovelTrackerFailure.InvalidResponse -> context.getString(issue.messageRes())
    is NovelTrackerFailure.Remote -> context.getString(R.string.hayai_tracker_http_error, statusCode)
    is NovelTrackerFailure.Network -> context.getString(R.string.hayai_tracker_network_error)
}

private fun NovelTrackerCredential.labelRes(): Int = when (this) {
    NovelTrackerCredential.Username -> R.string.username
    NovelTrackerCredential.SessionToken -> R.string.hayai_tracker_session_token
    NovelTrackerCredential.NovelListAccessToken -> R.string.hayai_tracker_novellist_access_token
    NovelTrackerCredential.RanobeDbSessionCookie -> R.string.hayai_tracker_ranobedb_session_cookie
    NovelTrackerCredential.NovelUpdatesSessionCookies -> R.string.hayai_tracker_novelupdates_session_cookies
}

private fun NovelTrackerResponseIssue.messageRes(): Int = when (this) {
    NovelTrackerResponseIssue.TrackerLinkMissingRemoteId -> R.string.hayai_tracker_link_missing_remote_id
    NovelTrackerResponseIssue.NovelListAuthenticatedUserInvalid -> R.string.hayai_tracker_novellist_user_invalid
    NovelTrackerResponseIssue.NovelListInvalidJson -> R.string.hayai_tracker_novellist_invalid_json
    NovelTrackerResponseIssue.NovelListExpectedObject -> R.string.hayai_tracker_novellist_expected_object
    NovelTrackerResponseIssue.NovelListEntryInvalidRemoteId -> R.string.hayai_tracker_novellist_invalid_remote_id
    NovelTrackerResponseIssue.NovelListUnknownReadingStatus -> R.string.hayai_tracker_novellist_unknown_status
    NovelTrackerResponseIssue.RanobeDbMissingSeriesId -> R.string.hayai_tracker_ranobedb_missing_series_id
    NovelTrackerResponseIssue.RanobeDbInvalidJson -> R.string.hayai_tracker_ranobedb_invalid_json
    NovelTrackerResponseIssue.RanobeDbExpectedObject -> R.string.hayai_tracker_ranobedb_expected_object
    NovelTrackerResponseIssue.RanobeDbEntryInvalidRemoteId -> R.string.hayai_tracker_ranobedb_invalid_remote_id
    NovelTrackerResponseIssue.NovelUpdatesMissingRemoveAction -> R.string.hayai_tracker_novelupdates_missing_remove_action
    NovelTrackerResponseIssue.NovelUpdatesEntryInvalidRemoteId -> R.string.hayai_tracker_novelupdates_invalid_remote_id
    NovelTrackerResponseIssue.NovelUpdatesMissingNovelId -> R.string.hayai_tracker_novelupdates_missing_novel_id
    NovelTrackerResponseIssue.NovelUpdatesUnknownReadingListState -> R.string.hayai_tracker_novelupdates_unknown_list_state
    NovelTrackerResponseIssue.NovelUpdatesUnknownReadingListId -> R.string.hayai_tracker_novelupdates_unknown_list_id
    NovelTrackerResponseIssue.NovelUpdatesNotesExpectedObject -> R.string.hayai_tracker_novelupdates_notes_expected_object
    NovelTrackerResponseIssue.NovelUpdatesNotesInvalidJson -> R.string.hayai_tracker_novelupdates_notes_invalid_json
}
