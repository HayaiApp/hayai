package dev.ahmedmohamed.hayai.novel.tracker.services

import eu.kanade.tachiyomi.data.database.models.Track
import java.nio.ByteBuffer
import java.security.MessageDigest

internal enum class NovelReadingStatus(val j2kValue: Int) {
    Reading(1),
    Completed(2),
    OnHold(3),
    Dropped(4),
    PlanToRead(5),
    Other(6),
    ;

    companion object {
        fun fromJ2k(value: Int): NovelReadingStatus = values().firstOrNull { it.j2kValue == value } ?: Reading
    }
}

internal data class NovelTrackerSearchItem(
    val remoteKey: String,
    val title: String,
    val url: String,
    val coverUrl: String = "",
    val summary: String = "",
    val publishingStatus: String = "",
    val totalChapters: Int? = null,
)

internal data class NovelTrackerRecord(
    val remoteKey: String,
    val title: String,
    val url: String,
    val status: NovelReadingStatus,
    val chapterRead: Float,
    val score: Float,
    val totalChapters: Int,
    val startedAt: Long,
    val finishedAt: Long,
)

internal data class NovelTrackerPatch(
    val title: String? = null,
    val url: String? = null,
    val status: NovelReadingStatus? = null,
    val chapterRead: Float? = null,
    val score: Float? = null,
    val totalChapters: Int? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

internal enum class NovelTrackerCredential {
    Username,
    SessionToken,
    NovelListAccessToken,
    RanobeDbSessionCookie,
    NovelUpdatesSessionCookies,
}

internal enum class NovelTrackerCredentialIssue {
    Generic,
    Required,
    InvalidCharacters,
    NovelListTokenRequired,
    NovelListTokenMalformed,
    NovelListTokenExpired,
    RanobeDbAuthCookieRequired,
    NovelUpdatesCookiesExpired,
    NovelUpdatesCookieHeaderRequired,
}

internal enum class NovelTrackerResponseIssue {
    TrackerLinkMissingRemoteId,
    NovelListAuthenticatedUserInvalid,
    NovelListInvalidJson,
    NovelListExpectedObject,
    NovelListEntryInvalidRemoteId,
    NovelListUnknownReadingStatus,
    RanobeDbMissingSeriesId,
    RanobeDbInvalidJson,
    RanobeDbExpectedObject,
    RanobeDbEntryInvalidRemoteId,
    NovelUpdatesMissingRemoveAction,
    NovelUpdatesEntryInvalidRemoteId,
    NovelUpdatesMissingNovelId,
    NovelUpdatesUnknownReadingListState,
    NovelUpdatesUnknownReadingListId,
    NovelUpdatesNotesExpectedObject,
    NovelUpdatesNotesInvalidJson,
}

internal sealed class NovelTrackerFailure(cause: Throwable? = null) : Exception(null, cause) {
    class InvalidCredentials(
        val issue: NovelTrackerCredentialIssue = NovelTrackerCredentialIssue.Generic,
        val credential: NovelTrackerCredential? = null,
    ) : NovelTrackerFailure()
    class SessionExpired : NovelTrackerFailure()
    class RateLimited(val retryAfterSeconds: Long?) : NovelTrackerFailure()
    class ResponseTooLarge(val maximumBytes: Long) : NovelTrackerFailure()
    class InvalidResponse(val issue: NovelTrackerResponseIssue) : NovelTrackerFailure()
    class Remote(val statusCode: Int) : NovelTrackerFailure()
    class Network(cause: Throwable) : NovelTrackerFailure(cause)
}

internal interface NovelTrackerApi {
    suspend fun validateSession(username: String, secret: String)
    suspend fun search(query: String): List<NovelTrackerSearchItem>
    suspend fun bind(record: NovelTrackerRecord): NovelTrackerPatch
    suspend fun update(record: NovelTrackerRecord): NovelTrackerPatch
    suspend fun refresh(record: NovelTrackerRecord): NovelTrackerPatch
    suspend fun remove(record: NovelTrackerRecord)
}

internal fun Track.toNovelTrackerRecord(remoteKey: String): NovelTrackerRecord =
    NovelTrackerRecord(
        remoteKey = remoteKey,
        title = title,
        url = tracking_url,
        status = NovelReadingStatus.fromJ2k(status),
        chapterRead = last_chapter_read.coerceAtLeast(0f),
        score = score.coerceIn(0f, 10f),
        totalChapters = total_chapters.coerceAtLeast(0),
        startedAt = started_reading_date.coerceAtLeast(0),
        finishedAt = finished_reading_date.coerceAtLeast(0),
    )

internal fun stableRemoteId(remoteKey: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(remoteKey.toByteArray(Charsets.UTF_8))
    return (ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long and Long.MAX_VALUE).takeIf { it != 0L } ?: 1L
}
