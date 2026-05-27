package eu.kanade.tachiyomi.data.track.novellist

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.novellist.dto.NovelListSession
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Network layer for [NovelList]. Mirrors Tsundoku's network code line-by-line; uses Hayai's
 * `Track.media_id` (Long hash) for table joins while keeping the tracking URL as the canonical
 * source of the API UUID.
 */
class NovelListApi(
    private val tracker: NovelList,
    private val client: OkHttpClient,
    private val json: Json,
) {

    private val baseUrl = "https://novellist-be-960019704910.asia-east1.run.app"

    /** Serialises concurrent refresh attempts so we don't burn the refresh_token twice in a row. */
    private val refreshMutex = Mutex()

    private suspend fun currentAccessToken(): String {
        val session = tracker.loadSession()
        if (session != null && session.isExpired()) {
            refreshMutex.withLock {
                // Re-read under the lock — another coroutine may have refreshed while we waited.
                val current = tracker.loadSession()
                if (current != null && current.isExpired()) {
                    refreshAccessToken(current)
                }
            }
            return tracker.loadSession()?.accessToken
                ?: tracker.run { trackPreferences.trackPassword(this).get() }
        }
        return session?.accessToken ?: tracker.run { trackPreferences.trackPassword(this).get() }
    }

    private suspend fun authBuilder(url: String): Request.Builder {
        val token = currentAccessToken()
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "*/*")
            .addHeader("Accept-Language", "en-US,en;q=0.5")
            .addHeader("Origin", "https://www.novellist.co")
            .addHeader("Referer", "https://www.novellist.co/")
            .addHeader("Sec-Fetch-Dest", "empty")
            .addHeader("Sec-Fetch-Mode", "cors")
            .addHeader("Sec-Fetch-Site", "cross-site")
    }

    /**
     * Exchange the stored refresh_token for a new Supabase session. Mirrors what `@supabase/ssr`
     * does in the browser on near-expiry: POST `/auth/v1/token?grant_type=refresh_token` with the
     * `apikey` header set to the project anon key. On success we persist the new session so
     * subsequent requests pick up the fresh access_token + the rotated refresh_token.
     */
    private suspend fun refreshAccessToken(current: NovelListSession) {
        val url = "${eu.kanade.tachiyomi.data.track.novellist.NovelList.SUPABASE_URL}" +
            "/auth/v1/token?grant_type=refresh_token"
        val body = buildJsonObject {
            put("refresh_token", current.refreshToken)
        }.toString().toRequestBody("application/json".toMediaType())

        val anonKey = eu.kanade.tachiyomi.data.track.novellist.NovelList.SUPABASE_ANON_KEY
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer ${current.accessToken}")
            .addHeader("Content-Type", "application/json;charset=UTF-8")
            .addHeader("Accept", "*/*")
            .addHeader("Origin", "https://www.novellist.co")
            .addHeader("Referer", "https://www.novellist.co/")
            .build()

        try {
            val response = client.newCall(request).awaitSuccess()
            val refreshed = response.parseAs<NovelListSession>()
            tracker.saveSession(refreshed)
            Logger.d { "NovelList: refreshed access token (expires_at=${refreshed.expiresAt})" }
        } catch (e: HttpException) {
            if (e.code == 400 || e.code == 401) {
                // Refresh token has been revoked / rotated past us — force a re-login.
                Logger.w(e) { "NovelList: refresh token rejected (${e.code}); clearing session" }
                tracker.run { trackPreferences.trackAuthExpired(this).set(true) }
            }
            throw e
        }
    }

    /** Best-effort CORS preflight; some Cloud Run setups require it. */
    private suspend fun sendOptions(url: String, method: String) {
        try {
            val request = Request.Builder()
                .url(url)
                .method("OPTIONS", null)
                .addHeader("Accept", "*/*")
                .addHeader("Access-Control-Request-Method", method)
                .addHeader("Access-Control-Request-Headers", "authorization,content-type")
                .addHeader("Origin", "https://www.novellist.co")
                .addHeader("Referer", "https://www.novellist.co/")
                .addHeader("Sec-Fetch-Dest", "empty")
                .addHeader("Sec-Fetch-Mode", "cors")
                .addHeader("Sec-Fetch-Site", "cross-site")
                .build()
            client.newCall(request).awaitSuccess()
        } catch (e: Exception) {
            Logger.d { "NovelList: OPTIONS preflight failed (continuing): ${e.message}" }
        }
    }

    suspend fun update(track: Track) {
        val uuid = tracker.uuidFromTrack(track)
        if (uuid.isEmpty()) return
        val url = "$baseUrl/api/users/current/reading-list/$uuid"
        sendOptions(url, "PUT")
        val body = buildJsonObject {
            put("chapter_count", track.last_chapter_read.toInt())
            put("status", tracker.mapStatusToApi(track.status))
            if (track.score > 0f) put("rating", track.score.toInt())
        }.toString().toRequestBody("application/json".toMediaType())
        val request = authBuilder(url).put(body).build()
        client.newCall(request).awaitSuccess()
    }

    suspend fun bind(track: Track) {
        val uuid = tracker.uuidFromTrack(track)
        if (uuid.isEmpty()) return
        val url = "$baseUrl/api/users/current/reading-list/$uuid"

        // First try to load any existing entry from the user's reading list — this prevents
        // bind() from clobbering progress/rating/status the user already has on NovelList.
        val existing = try {
            client.newCall(authBuilder(url).get().build()).awaitSuccess().parseAs<JsonObject>()
        } catch (e: HttpException) {
            if (e.code == 404) null else throw e
        } catch (e: Exception) {
            Logger.e(e) { "NovelList: bind GET failed; falling back to PUT" }
            null
        }

        if (existing != null) {
            // Already on the user's list — adopt the server-side state instead of overwriting it.
            track.status = tracker.mapStatusFromApi(
                existing["status"]?.jsonPrimitive?.contentOrNull ?: "IN_PROGRESS",
            )
            track.last_chapter_read = existing["chapter_count"]?.jsonPrimitive?.contentOrNull
                ?.toFloatOrNull() ?: track.last_chapter_read
            track.score = existing["rating"]?.jsonPrimitive?.contentOrNull
                ?.toFloatOrNull() ?: 0f
            return
        }

        // No existing entry — create one.
        sendOptions(url, "PUT")
        val body = buildJsonObject {
            put("status", if (track.last_chapter_read > 0f) "IN_PROGRESS" else "PLANNED")
            put("chapter_count", track.last_chapter_read.toInt())
            put("rating", 0)
            put("note", "")
        }.toString().toRequestBody("application/json".toMediaType())
        client.newCall(authBuilder(url).put(body).build()).awaitSuccess()
    }

    suspend fun search(query: String): List<TrackSearch> {
        val body = buildJsonObject {
            put("page", 1)
            put("sort_order", "MOST_TRENDING")
            put("title_search_query", query)
            put("language", "UNKNOWN")
            putJsonArray("label_ids") {}
            putJsonArray("excluded_label_ids") {}
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/api/novels/filter")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("Origin", "https://www.novellist.co")
            .addHeader("Referer", "https://www.novellist.co/")
            .build()

        return try {
            val response = client.newCall(request).awaitSuccess()
            val text = response.body.string()
            val list = json.decodeFromString<List<JsonObject>>(text)
            list.map { obj ->
                val track = TrackSearch.create(tracker.id)
                val idStr = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                track.media_id = idStr.hashCode().toLong().let { if (it < 0) -it else it }
                track.title = obj["english_title"]?.jsonPrimitive?.contentOrNull
                    ?: obj["raw_title"]?.jsonPrimitive?.contentOrNull
                    ?: obj["title"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                track.cover_url = obj["cover_image_link"]?.jsonPrimitive?.contentOrNull
                    ?: obj["image_url"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                track.summary = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: idStr
                // The site routes `/novels/<slug>` (plural). The trailing `#<uuid>` fragment is
                // client-side only and is what `uuidFromTrack` reads back for API calls.
                track.tracking_url = "https://www.novellist.co/novels/$slug#$idStr"
                track.publishing_status = obj["status"]?.jsonPrimitive?.contentOrNull ?: ""
                track
            }
        } catch (e: Exception) {
            Logger.e(e) { "NovelList: search failed" }
            emptyList()
        }
    }

    suspend fun refresh(track: Track): Track? {
        val uuid = tracker.uuidFromTrack(track)
        if (uuid.isEmpty()) return null
        val url = "$baseUrl/api/users/current/reading-list/$uuid"
        return try {
            val response = client.newCall(authBuilder(url).get().build()).awaitSuccess()
            val obj = response.parseAs<JsonObject>()
            track.status = tracker.mapStatusFromApi(obj["status"]?.jsonPrimitive?.contentOrNull ?: "IN_PROGRESS")
            track.last_chapter_read = obj["chapter_count"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
            track.score = obj["rating"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
            track
        } catch (e: Exception) {
            Logger.e(e) { "NovelList: refresh failed" }
            null
        }
    }
}
