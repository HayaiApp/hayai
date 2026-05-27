package eu.kanade.tachiyomi.data.track.novellist

import android.content.Context
import android.graphics.Color
import android.util.Base64
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackContentType
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.novellist.dto.NovelListSession
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * NovelList tracker — https://www.novellist.co (LNReader-compatible API).
 *
 * Logic ported line-by-line from Tsundoku; class structure adapted to Hayai's [TrackService].
 * The user logs in via WebView; the resulting JWT access token is stored as the tracker
 * password and used as a `Bearer` header by [NovelListApi].
 */
class NovelList(private val context: Context, id: Long) : TrackService(id) {

    companion object {
        const val READING = 1
        const val COMPLETED = 2
        const val ON_HOLD = 3
        const val DROPPED = 4
        const val PLAN_TO_READ = 5

        const val LOGIN_URL = "https://www.novellist.co/login"

        // Supabase project that backs NovelList. URL + anon key are baked into the site's
        // public JS bundle (yionrsulvvpfmzmbmami.supabase.co), so they're safe to embed here.
        const val SUPABASE_URL = "https://yionrsulvvpfmzmbmami.supabase.co"
        const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inlpb25yc3VsdnZwZm16bWJtYW1pIiwicm9sZSI6ImFub24iL" +
            "CJpYXQiOjE3MTk5ODg0MTMsImV4cCI6MjAzNTU2NDQxM30.EW3C9NhuFu6TvZuYJ8B29HqeW35hH3BujR0sGRa9Ej0"
    }

    override val supportedContentTypes: Set<TrackContentType> = setOf(TrackContentType.NOVEL)

    private val json by lazy { Json { ignoreUnknownKeys = true } }
    internal val api by lazy { NovelListApi(this, client, json) }

    override fun nameRes() = MR.strings.novellist

    override fun getLogo() = R.drawable.ic_tracker_novellist

    override fun getTrackerColor() = Color.rgb(83, 109, 254)

    override fun getLogoColor() = Color.rgb(83, 109, 254)

    override fun getStatusList(): List<Int> =
        listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ)

    override fun isCompletedStatus(index: Int) = getStatusList()[index] == COMPLETED

    override fun completedStatus() = COMPLETED
    override fun readingStatus() = READING
    override fun planningStatus() = PLAN_TO_READ

    override fun getStatus(status: Int): String = with(context) {
        when (status) {
            READING -> getString(MR.strings.reading)
            COMPLETED -> getString(MR.strings.completed)
            ON_HOLD -> getString(MR.strings.on_hold)
            DROPPED -> getString(MR.strings.dropped)
            PLAN_TO_READ -> getString(MR.strings.plan_to_read)
            else -> ""
        }
    }

    override fun getGlobalStatus(status: Int): String = getStatus(status)

    override fun getScoreList(): ImmutableList<String> = persistentListOf(
        "10", "9", "8", "7", "6", "5", "4", "3", "2", "1", "",
    )

    override fun indexToScore(index: Int): Float =
        if (index == 10) 0f else (10 - index).toFloat()

    override fun displayScore(track: Track): String =
        if (track.score == 0f) "-" else track.score.toInt().toString()

    internal fun mapStatusToApi(status: Int): String = when (status) {
        READING -> "IN_PROGRESS"
        COMPLETED -> "COMPLETED"
        ON_HOLD -> "PLANNED"
        DROPPED -> "DROPPED"
        PLAN_TO_READ -> "PLANNED"
        else -> "IN_PROGRESS"
    }

    internal fun mapStatusFromApi(status: String): Int = when (status) {
        "IN_PROGRESS" -> READING
        "COMPLETED" -> COMPLETED
        "PLANNED" -> PLAN_TO_READ
        "DROPPED" -> DROPPED
        else -> READING
    }

    /** `tracking_url` is `https://www.novellist.co/novels/<slug>#<uuid>`; the fragment is the API UUID. */
    internal fun uuidFromTrack(track: Track): String =
        track.tracking_url.substringAfter("#", "")

    override suspend fun add(track: Track): Track {
        return bind(track)
    }

    override suspend fun update(track: Track, setToRead: Boolean): Track {
        updateTrackStatus(track, setToRead)
        api.update(track)
        return track
    }

    override suspend fun bind(track: Track): Track {
        if (track.status == 0) track.status = if (track.last_chapter_read > 0f) READING else PLAN_TO_READ
        api.bind(track)
        return track
    }

    override suspend fun search(query: String): List<TrackSearch> = api.search(query)

    override suspend fun refresh(track: Track): Track = api.refresh(track) ?: track

    /**
     * Decode a NovelList Supabase session payload. Pass the full cookie header (the
     * `Cookie:` value) — the helper picks out `novellist` / `novellist.<n>` chunks, sorts
     * them by index, joins them, strips the `base64-` prefix and decodes. Also accepts a
     * pre-joined `base64-…` value (legacy single-cookie form) for back-compat.
     */
    fun extractTokenFromCookie(cookieValue: String): String? = try {
        val chunkRegex = Regex("(?:^|;\\s*)novellist(?:\\.(\\d+))?=([^;]+)")
        val joined = chunkRegex.findAll(cookieValue)
            .map { (it.groupValues[1].toIntOrNull() ?: -1) to it.groupValues[2] }
            .sortedBy { it.first }
            .joinToString(separator = "") { it.second }
            .ifBlank {
                // Caller passed a single cookie value rather than a `Cookie:` header.
                cookieValue
            }
        if (!joined.startsWith("base64-")) {
            null
        } else {
            val payload = joined.removePrefix("base64-")
            val decoded = String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
            val obj = json.decodeFromString<JsonObject>(decoded)
            obj["access_token"]?.jsonPrimitive?.contentOrNull
        }
    } catch (e: Exception) {
        Logger.e(e) { "NovelList: failed to extract token from cookie" }
        null
    }

    /**
     * `password` carries either:
     *   - the full Supabase session JSON (preferred — emitted by [TrackerWebViewLoginActivity]
     *     after decoding the `novellist[.N]` cookie chunks), so we can persist `refresh_token`,
     *   - or a bare JWT access token (legacy callers / manual entry), in which case there is no
     *     refresh path and the user will have to sign in again when the JWT lapses.
     */
    override suspend fun login(username: String, password: String): Boolean {
        if (password.isBlank()) return false
        val session = parseSession(password)
        val accessToken = session?.accessToken ?: password
        saveCredentials(username.ifBlank { "NovelList User" }, accessToken)
        saveSession(session)
        return true
    }

    private fun parseSession(payload: String): NovelListSession? = try {
        if (payload.trimStart().startsWith("{")) json.decodeFromString<NovelListSession>(payload) else null
    } catch (e: Exception) {
        Logger.d(e) { "NovelList: payload is not a session JSON; treating as bare access token" }
        null
    }

    internal fun saveSession(session: NovelListSession?) {
        if (session == null) {
            trackPreferences.trackToken(this).delete()
        } else {
            trackPreferences.trackToken(this).set(json.encodeToString(session))
            // Keep the legacy password slot in sync so `isLogged` + any old code path still works.
            trackPreferences.trackPassword(this).set(session.accessToken)
        }
    }

    internal fun loadSession(): NovelListSession? {
        val stored = trackPreferences.trackToken(this).get()
        if (stored.isBlank()) return null
        return try {
            json.decodeFromString<NovelListSession>(stored)
        } catch (e: Exception) {
            Logger.w(e) { "NovelList: stored session JSON is unreadable" }
            null
        }
    }

    override fun logout() {
        super.logout()
        trackPreferences.trackToken(this).delete()
    }
}
