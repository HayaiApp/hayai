package eu.kanade.tachiyomi.data.track

import androidx.annotation.CallSuper
import androidx.annotation.DrawableRes
import android.content.Context
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.database.models.isOneShotOrCompleted
import eu.kanade.tachiyomi.data.track.model.TrackContentType
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.collections.immutable.ImmutableList
import okhttp3.OkHttpClient
import yokai.util.koin.injectLazy
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.history.interactor.GetHistory
import yokai.domain.manga.interactor.GetManga
import yokai.domain.series.model.MetadataProviderType
import yokai.domain.series.model.SeriesMetadataField
import yokai.domain.series.model.SeriesMetadataValue
import yokai.util.lang.getString

abstract class TrackService(val id: Long) : TrackerMetadataProvider {

    val trackPreferences: TrackPreferences by injectLazy()
    val networkService: NetworkHelper by injectLazy()
    val getChapter: GetChapter by injectLazy()
    val getManga: GetManga by injectLazy()
    val getHistory: GetHistory by injectLazy()

    open fun canRemoveFromService() = false
    open val client: OkHttpClient
        get() = networkService.client

    /**
     * Content types this tracker supports. Defaults to manga-only; novel-specific trackers
     * (NovelUpdates, NovelList) override to `setOf(TrackContentType.NOVEL)`. Tracker-selection
     * UI filters by the entry's content type so manga and novel trackers don't cross-pollute.
     */
    open val supportedContentTypes: Set<TrackContentType> = setOf(TrackContentType.MANGA)

    // Name of the manga sync service to display
    abstract fun nameRes(): StringResource

    // Application and remote support for reading dates
    open val supportsReadingDates: Boolean = false

    @DrawableRes
    abstract fun getLogo(): Int

    abstract fun getTrackerColor(): Int

    abstract fun getLogoColor(): Int

    abstract fun getStatusList(): List<Int>

    abstract fun isCompletedStatus(index: Int): Boolean

    abstract fun completedStatus(): Int
    abstract fun readingStatus(): Int
    abstract fun planningStatus(): Int

    abstract fun getStatus(status: Int): String

    abstract fun getGlobalStatus(status: Int): String

    abstract fun getScoreList(): ImmutableList<String>

    open fun indexToScore(index: Int): Float {
        return index.toFloat()
    }

    open fun get10PointScore(score: Float): Float {
        return score
    }

    abstract fun displayScore(track: Track): String

    abstract suspend fun add(track: Track): Track

    abstract suspend fun update(track: Track, setToRead: Boolean = false): Track

    abstract suspend fun bind(track: Track): Track

    abstract suspend fun search(query: String): List<TrackSearch>

    abstract suspend fun refresh(track: Track): Track

    abstract suspend fun login(username: String, password: String): Boolean

    open suspend fun removeFromService(track: Track): Boolean = false

    override fun metadataValues(
        context: Context,
        mangaId: Long,
        track: Track,
        now: Long,
    ): List<SeriesMetadataValue> {
        val providerId = id.toString()
        val providerName = context.getString(nameRes())
        fun value(field: SeriesMetadataField, text: String?, confidence: Double = 0.75): SeriesMetadataValue? =
            text?.trim()?.takeIf { it.isNotBlank() }?.let {
                SeriesMetadataValue(
                    mangaId = mangaId,
                    field = field.key,
                    providerType = MetadataProviderType.TRACKER,
                    providerId = providerId,
                    providerName = providerName,
                    value = it,
                    extraJson = null,
                    confidence = confidence,
                    userLocked = false,
                    updatedAt = now,
                )
            }
        val trackerSummary = buildList {
            runCatching { getStatus(track.status) }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            track.total_chapters.takeIf { it > 0 }?.let {
                add(context.getString(yokai.i18n.MR.strings.tracker_metadata_total_chapters_format, it))
            }
        }.joinToString("\n").takeIf { it.isNotBlank() }

        return listOfNotNull(
            value(SeriesMetadataField.TITLE, track.title),
            value(SeriesMetadataField.TRACKER_SUMMARY, trackerSummary, confidence = 0.65),
        )
    }

    override fun metadataValues(
        context: Context,
        mangaId: Long,
        search: TrackSearch,
        now: Long,
    ): List<SeriesMetadataValue> {
        val providerId = id.toString()
        val providerName = context.getString(nameRes())
        fun value(field: SeriesMetadataField, text: String?, confidence: Double = 0.85): SeriesMetadataValue? =
            text?.trim()?.takeIf { it.isNotBlank() }?.let {
                SeriesMetadataValue(
                    mangaId = mangaId,
                    field = field.key,
                    providerType = MetadataProviderType.TRACKER,
                    providerId = providerId,
                    providerName = providerName,
                    value = it,
                    extraJson = null,
                    confidence = confidence,
                    userLocked = false,
                    updatedAt = now,
                )
        }
        fun safeText(read: () -> String): String? = runCatching(read).getOrNull()

        val trackerSummary = buildList {
            search.publishing_type.trim().takeIf { it.isNotBlank() }?.let {
                add(context.getString(yokai.i18n.MR.strings.tracker_metadata_type_format, it))
            }
            search.start_date.trim().takeIf { it.isNotBlank() }?.let {
                add(context.getString(yokai.i18n.MR.strings.tracker_metadata_start_date_format, it))
            }
            search.total_chapters.takeIf { it > 0 }?.let {
                add(context.getString(yokai.i18n.MR.strings.tracker_metadata_total_chapters_format, it))
            }
        }.joinToString("\n").takeIf { it.isNotBlank() }

        return listOfNotNull(
            value(SeriesMetadataField.TITLE, safeText { search.title }),
            value(SeriesMetadataField.COVER, search.cover_url),
            value(SeriesMetadataField.DESCRIPTION, search.summary),
            value(SeriesMetadataField.STATUS, search.publishing_status),
            value(SeriesMetadataField.TRACKER_SUMMARY, trackerSummary, confidence = 0.7),
        )
    }

    open fun updateTrackStatus(
        track: Track,
        setToReadStatus: Boolean,
        setToComplete: Boolean = false,
        mustReadToComplete: Boolean = false,
    ) {
        if (setToReadStatus && track.status == planningStatus() && track.last_chapter_read != 0f) {
            track.status = readingStatus()
        }
        if (setToComplete &&
            (!mustReadToComplete || track.status == readingStatus()) &&
            track.total_chapters != 0L &&
            track.last_chapter_read.toLong() == track.total_chapters
        ) {
            track.status = completedStatus()
        }
    }

    @CallSuper
    open fun logout() {
        trackPreferences.setCredentials(this, "", "")
    }

    open val isLogged: Boolean
        get() = getUsername().isNotEmpty() &&
            getPassword().isNotEmpty()

    fun getUsername() = trackPreferences.trackUsername(this).get()

    fun getPassword() = trackPreferences.trackPassword(this).get()

    fun saveCredentials(username: String, password: String) {
        trackPreferences.setCredentials(this, username, password)
    }
}

suspend fun TrackService.updateNewTrackInfo(track: Track) {
    val manga = getManga.awaitById(track.manga_id)
    val allRead = manga?.isOneShotOrCompleted() == true &&
        getChapter.awaitAll(track.manga_id, false).all { it.read }
    if (supportsReadingDates) {
        track.started_reading_date = getStartDate(track)
        track.finished_reading_date = getCompletedDate(track, allRead)
    }
    track.last_chapter_read = getLastChapterRead(track).takeUnless {
        it == 0f && allRead
    } ?: 1f
    if (track.last_chapter_read == 0f) {
        track.status = planningStatus()
    }
    if (allRead) {
        track.status = completedStatus()
    }
}

suspend fun TrackService.getStartDate(track: Track): Long {
    if (getChapter.awaitAll(track.manga_id, false).any { it.read }) {
        val chapters = getHistory.awaitAllByMangaId(track.manga_id).filter { it.last_read > 0 }
        val date = chapters.minOfOrNull { it.last_read } ?: return 0L
        return if (date <= 0L) 0L else date
    }
    return 0L
}

suspend fun TrackService.getCompletedDate(track: Track, allRead: Boolean): Long {
    if (allRead) {
        val chapters = getHistory.awaitAllByMangaId(track.manga_id)
        val date = chapters.maxOfOrNull { it.last_read } ?: return 0L
        return if (date <= 0L) 0L else date
    }
    return 0L
}

suspend fun TrackService.getLastChapterRead(track: Track): Float {
    val chapters = getChapter.awaitAll(track.manga_id, false)
    val lastChapterRead = chapters.filter { it.read }.minByOrNull { it.source_order }
    return lastChapterRead?.takeIf { it.isRecognizedNumber }?.chapter_number ?: 0f
}
