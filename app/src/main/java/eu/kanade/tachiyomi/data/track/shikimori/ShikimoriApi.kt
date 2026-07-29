package eu.kanade.tachiyomi.data.track.shikimori

import android.net.Uri
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMAddMangaResponse
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMManga
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMOAuth
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUser
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUserListEntry
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.system.w
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import yokai.util.koin.injectLazy

class ShikimoriApi(
    private val trackId: Long,
    private val client: OkHttpClient,
    interceptor: ShikimoriInterceptor,
) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    suspend fun addLibManga(track: Track, userId: String): Track {
        return withIOContext {
            with(json) {
                val payload = buildJsonObject {
                    putJsonObject("user_rate") {
                        put("user_id", userId)
                        put("target_id", track.media_id)
                        put("target_type", "Manga")
                        put("chapters", track.last_chapter_read.toInt())
                        put("score", track.score.toInt())
                        put("status", track.toApiStatus())
                    }
                }
                authClient.newCall(
                    POST(
                        "$API_URL/v2/user_rates",
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                ).awaitSuccess()
                    .parseAs<SMAddMangaResponse>()
                    .let {
                        track.library_id = it.id
                    }
                track
            }
        }
    }

    suspend fun updateLibManga(track: Track, user_id: String): Track = addLibManga(track, user_id)

    suspend fun search(search: String): List<TrackSearch> {
        return withIOContext {
            val url = "$API_URL/mangas".toUri().buildUpon()
                .appendQueryParameter("order", "popularity")
                .appendQueryParameter("search", search)
                .appendQueryParameter("limit", "20")
                .build()
            authClient.newCall(GET(url.toString()))
                .awaitSuccess()
                .parseAs<List<SMManga>>()
                .map { it.toTrack(trackId) }
        }
    }

    suspend fun remove(track: Track): Boolean {
        return try {
            withIOContext {
                authClient
                    .newCall(DELETE("$API_URL/v2/user_rates/${track.media_id}"))
                    .awaitSuccess()
            }
            true
        } catch (e: Exception) {
            Logger.w(e)
            false
        }
    }

    suspend fun findLibManga(track: Track, user_id: String): Track? {
        return withIOContext {
            val urlMangas = "$API_URL/mangas".toUri().buildUpon()
                .appendPath(track.media_id.toString())
                .build()
            val manga =
                authClient.newCall(GET(urlMangas.toString()))
                    .awaitSuccess()
                    .parseAs<SMManga>()

            val url = "$API_URL/v2/user_rates".toUri().buildUpon()
                .appendQueryParameter("user_id", user_id)
                .appendQueryParameter("target_id", track.media_id.toString())
                .appendQueryParameter("target_type", "Manga")
                .build()
            authClient.newCall(GET(url.toString()))
                .execute()
                .parseAs<List<SMUserListEntry>>()
                .let { entries ->
                    if (entries.size > 1) {
                        throw Exception("Too manga manga in response")
                    }
                    entries
                        .map { it.toTrack(trackId, manga) }
                        .firstOrNull()
                }
        }
    }

    suspend fun getCurrentUser(): Int {
        return withIOContext {
            authClient.newCall(GET("$API_URL/users/whoami"))
                .awaitSuccess()
                .parseAs<SMUser>()
                .id
        }
    }

    suspend fun accessToken(code: String): SMOAuth {
        return withIOContext {
            client.newCall(accessTokenRequest(code))
                .awaitSuccess()
                .parseAs()
        }
    }

    private fun accessTokenRequest(code: String) = POST(
        OAUTH_URL,
        body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("code", code)
            .add("redirect_uri", REDIRECT_URL)
            .build(),
    )

    companion object {
        private const val CLIENT_ID = "zU0wHfXbpx2GwVBK7jILx6druyPdmp0J8bLUSH9NBFc"
        private const val CLIENT_SECRET = "t-I_sBzlWAbJPjkO9EYnqBpXYdPhAxjxRuoTSZgiJPg"

        const val BASE_URL = "https://shikimori.io"
        private const val API_URL = "$BASE_URL/api"
        private const val OAUTH_URL = "$BASE_URL/oauth/token"
        private const val LOGIN_URL = "$BASE_URL/oauth/authorize"

        private const val REDIRECT_URL = "hayai://shikimori-auth"
        private const val BASE_MANGA_URL = "$API_URL/mangas"

        fun mangaUrl(remoteId: Int): String {
            return "$BASE_MANGA_URL/$remoteId"
        }

        fun authUrl(): Uri =
            LOGIN_URL.toUri().buildUpon()
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", REDIRECT_URL)
                .appendQueryParameter("response_type", "code")
                .build()

        fun refreshTokenRequest(token: String) = POST(
            OAUTH_URL,
            body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("refresh_token", token)
                .build(),
        )
    }
}
