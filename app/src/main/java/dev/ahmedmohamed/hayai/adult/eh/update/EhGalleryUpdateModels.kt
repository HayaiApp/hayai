package dev.ahmedmohamed.hayai.adult.eh.update

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import kotlinx.serialization.Serializable

data class EhGalleryUpdatePolicy(
    val intervalHours: Int = 24,
    val wifiOnly: Boolean = false,
    val requiresCharging: Boolean = false,
) {
    init {
        require(intervalHours == 0 || intervalHours in 1..168) { "Update interval must be disabled or between 1 and 168 hours" }
    }
}

data class EhGalleryUpdateState(
    val checkedAt: Long = 0,
    val agedAt: Long? = null,
    val notFoundAt: Long? = null,
) {
    val aged: Boolean get() = agedAt != null

    fun isEligible(now: Long, forceRefresh: Boolean = false): Boolean {
        if (aged) return false
        if (forceRefresh) return true
        if (checkedAt <= 0) return true
        if (now < checkedAt) return false
        val interval = if (notFoundAt != null) NOT_FOUND_RECHECK_INTERVAL_MILLIS else MIN_CHECK_INTERVAL_MILLIS
        return now - checkedAt >= interval
    }
}

data class EhGalleryUpdateCandidate(
    val mangaId: Long,
    val sourceId: Long,
    val title: String,
    val mangaUrl: String,
    val state: EhGalleryUpdateState,
)

data class EhRemoteRevision(
    val key: GalleryKey,
    val title: String,
    val postedAt: Long,
)

data class EhLocalRevision(
    val id: Long,
    val url: String,
    val title: String,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Int,
    val pagesLeft: Int,
    val downloaded: Boolean,
    val historyLastRead: Long = 0,
    val historyTimeRead: Long = 0,
)

data class EhRevisionMutation(
    val localId: Long?,
    val remote: EhRemoteRevision,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Int,
    val pagesLeft: Int,
    val downloaded: Boolean,
    val historyLastRead: Long,
    val historyTimeRead: Long,
)

data class EhRevisionMerge(
    val mutations: List<EhRevisionMutation>,
    val newRevisionCount: Int,
)

enum class EhGalleryUpdateDisposition {
    Updated,
    Unchanged,
    Aged,
    NotFound,
    AuthenticationSkipped,
    TransientFailure,
    PermanentFailure,
}

data class EhGalleryUpdateResult(
    val disposition: EhGalleryUpdateDisposition,
    val title: String,
    val newRevisionCount: Int = 0,
    val failure: String? = null,
)

@Serializable
data class EhGalleryUpdaterStats(
    val startedAt: Long,
    val finishedAt: Long,
    val eligible: Int,
    val attempted: Int,
    val updated: Int,
    val newRevisions: Int,
    val aged: Int,
    val notFound: Int,
    val authenticationSkipped: Int,
    val transientFailures: Int,
    val permanentFailures: Int,
    val stoppedAtFailureCutoff: Boolean,
)

internal fun EhGalleryUpdateCandidate.site(): EhSite =
    EhSite.entries.firstOrNull { it.sourceId == sourceId }
        ?: error("Unsupported E-Hentai source $sourceId")

internal const val MIN_CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1_000
internal const val NOT_FOUND_RECHECK_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1_000
