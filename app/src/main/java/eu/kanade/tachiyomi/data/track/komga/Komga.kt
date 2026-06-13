package eu.kanade.tachiyomi.data.track.komga

import android.content.Context
import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.updateNewTrackInfo
import eu.kanade.tachiyomi.domain.manga.models.Manga
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import okhttp3.Dns
import okhttp3.OkHttpClient
import yokai.i18n.MR
import yokai.util.lang.getString

class Komga(private val context: Context, id: Long) : TrackService(id), EnhancedTrackService {

    companion object {
        const val UNREAD = 1
        const val READING = 2
        const val COMPLETED = 3
    }

    override val client: OkHttpClient =
        networkService.client.newBuilder()
            .dns(Dns.SYSTEM) // don't use DNS over HTTPS as it breaks IP addressing
            .build()

    val api by lazy { KomgaApi(client) }

    override fun nameRes() = MR.strings.komga

    override fun getLogo() = R.drawable.ic_tracker_komga

    override fun getTrackerColor() = Color.rgb(0, 94, 211)

    override fun getLogoColor() = Color.argb(0, 51, 37, 50)

    override fun getStatusList() = listOf(UNREAD, READING, COMPLETED)

    override fun isCompletedStatus(index: Int): Boolean = getStatusList()[index] == COMPLETED

    override fun getStatus(status: Int): String = with(context) {
        when (status) {
            UNREAD -> getString(MR.strings.unread)
            READING -> getString(MR.strings.currently_reading)
            COMPLETED -> getString(MR.strings.completed)
            else -> ""
        }
    }

    override fun getGlobalStatus(status: Int): String = with(context) {
        when (status) {
            UNREAD -> getString(MR.strings.plan_to_read)
            READING -> getString(MR.strings.reading)
            COMPLETED -> getString(MR.strings.completed)
            else -> ""
        }
    }

    override fun completedStatus(): Int = COMPLETED
    override fun readingStatus() = READING
    override fun planningStatus() = UNREAD

    override fun getScoreList(): ImmutableList<String> = persistentListOf()

    override fun displayScore(track: Track): String = ""
    override suspend fun add(track: Track): Track {
        track.status = READING
        updateNewTrackInfo(track)
        return api.updateProgress(track)
    }

    override suspend fun update(track: Track, setToRead: Boolean): Track {
        updateTrackStatus(track, setToRead)
        return api.updateProgress(track)
    }

    override suspend fun bind(track: Track): Track {
        return track
    }

    override suspend fun search(query: String): List<TrackSearch> {
        return emptyList()
    }

    override suspend fun refresh(track: Track): Track {
        val remoteTrack = api.getTrackSearch(track.tracking_url)
        track.copyPersonalFrom(remoteTrack)
        track.total_chapters = remoteTrack.total_chapters
        return track
    }

    override suspend fun login(username: String, password: String): Boolean {
        saveCredentials("user", "pass")
        return true
    }

    // TrackService.isLogged works by checking that credentials are saved.
    // By saving dummy, unused credentials, we can activate the tracker simply by login/logout
    override fun loginNoop() {
        saveCredentials("user", "pass")
    }

    override fun getAcceptedSources() = listOf("eu.kanade.tachiyomi.extension.all.komga.Komga")

    override suspend fun match(manga: Manga): TrackSearch? =
        try {
            api.getTrackSearch(manga.url)
        } catch (e: Exception) {
            null
        }
}
