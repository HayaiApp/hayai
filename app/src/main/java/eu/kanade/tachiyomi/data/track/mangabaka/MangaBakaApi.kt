package eu.kanade.tachiyomi.data.track.mangabaka

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaItem
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaItemResult
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaListResult
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaOAuth
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaSearchResult
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaUserProfile
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaUserProfileResponse
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.PkceUtil
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import java.math.RoundingMode
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale

class MangaBakaApi(
    private val trackId: Int,
    baseClient: OkHttpClient,
    interceptor: MangaBakaInterceptor,
) {
    private val json: Json by injectLazy()

    private val client =
        baseClient
            .newBuilder()
            .addInterceptor {
                it
                    .request()
                    .newBuilder()
                    .header(
                        "User-Agent",
                        "Tachiyomi J2K/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}) " +
                            "(Android) (https://github.com/HayaiApp/hayai)",
                    ).build()
                    .let(it::proceed)
            }.build()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    suspend fun addLibManga(track: Track): Track =
        withIOContext {
            val url = "$LIBRARY_API_URL/${track.media_id}"
            val body =
                buildJsonObject {
                    put("is_private", track.private)
                    put("state", track.toApiStatus())
                    if (track.last_chapter_read > 0f) {
                        put("progress_chapter", track.last_chapter_read)
                    }
                    if (track.score > 0) {
                        put("rating", track.score.toInt().coerceIn(0, 100))
                    }
                    if (track.started_reading_date > 0) {
                        put("start_date", formatIsoDate(track.started_reading_date))
                    }
                    if (track.finished_reading_date > 0) {
                        put("finish_date", formatIsoDate(track.finished_reading_date))
                    }
                }.toString()
                    .toRequestBody(jsonMime)

            authClient
                .newCall(POST(url, body = body, headers = headersOf("Content-Type", "application/json")))
                .awaitSuccess()

            // only returns 201 with the body { "status": 201, "data": true }, so no library ID for us
            track
        }

    suspend fun deleteLibManga(track: Track) {
        withIOContext {
            val url = "$LIBRARY_API_URL/${track.media_id}"

            authClient
                .newCall(DELETE(url))
                .awaitSuccess()
        }
    }

    suspend fun findLibManga(track: Track): Track? =
        withIOContext {
            with(json) {
                try {
                    val url = "$LIBRARY_API_URL/${track.media_id}"
                    val userData =
                        authClient
                            .newCall(GET(url))
                            .awaitSuccess()
                            .parseAs<MangaBakaListResult>()
                            .data

                    val additionalData =
                        authClient
                            .newCall(GET("$API_BASE_URL/v1/series/${track.media_id}"))
                            .awaitSuccess()
                            .parseAs<MangaBakaItemResult>()
                            .data

                    Track.create(TrackManager.MANGABAKA).apply {
                        media_id = track.media_id
                        title = additionalData.chooseBestTitle()
                        status = userData.getStatus()
                        score = userData.rating?.toFloat() ?: 0f
                        started_reading_date = parseIsoDate(userData.startDate)
                        finished_reading_date = parseIsoDate(userData.finishDate)
                        last_chapter_read = userData.progressChapter?.toFloat() ?: 0f
                        private = userData.isPrivate
                    }
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        null
                    } else {
                        throw e
                    }
                }
            }
        }

    suspend fun updateLibManga(track: Track): Track =
        withIOContext {
            val url = "$LIBRARY_API_URL/${track.media_id}"
            val body =
                buildJsonObject {
                    put("state", track.toApiStatus())
                    put("is_private", track.private)
                    if (track.last_chapter_read > 0f) {
                        put("progress_chapter", track.last_chapter_read)
                    } else {
                        put("progress_chapter", null)
                    }
                    if (track.score > 0) {
                        put("rating", track.score.toInt().coerceIn(0, 100))
                    } else {
                        put("rating", null)
                    }
                    if (track.started_reading_date > 0) {
                        put("start_date", formatIsoDate(track.started_reading_date))
                    } else {
                        put("start_date", null)
                    }
                    if (track.finished_reading_date > 0) {
                        put("finish_date", formatIsoDate(track.finished_reading_date))
                    } else {
                        put("finish_date", null)
                    }
                }.toString()
                    .toRequestBody(jsonMime)

            authClient
                .newCall(PUT(url, body = body, headers = headersOf("Content-Type", "application/json")))
                .awaitSuccess()

            track
        }

    suspend fun search(search: String): List<TrackSearch> =
        withIOContext {
            val url =
                "$API_BASE_URL/v1/series/search"
                    .toUri()
                    .buildUpon()
                    .appendQueryParameter("q", search)
                    .appendQueryParameter("type_not", "novel")
                    .build()
            with(json) {
                client
                    .newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<MangaBakaSearchResult>()
                    .data
                    .map { parseSearchItem(it) }
            }
        }

    private fun parseSearchItem(item: MangaBakaItem): TrackSearch =
        TrackSearch.create(trackId).apply {
            media_id = item.id
            title = item.chooseBestTitle()
            summary = item.description?.trim().orEmpty()
            score = item.rating
                ?.toBigDecimal()
                ?.setScale(2, RoundingMode.HALF_UP)
                ?.toFloat() ?: -1f
            cover_url =
                item.cover.x250.x1
                    .orEmpty()
            tracking_url = "$BASE_URL/${item.id}"
            start_date = item.published.startDate.orEmpty()
            publishing_status = item.status
            publishing_type =
                item.type.replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString()
                }
        }

    suspend fun getMangaDetails(id: Int): TrackSearch? {
        return withIOContext {
            val url =
                "$API_BASE_URL/v1/series"
                    .toUri()
                    .buildUpon()
                    .appendPath(id.toString())
                    .build()
            with(json) {
                try {
                    authClient
                        .newCall(GET(url.toString()))
                        .awaitSuccess()
                        .parseAs<MangaBakaItemResult>()
                        .data
                        .let { parseSearchItem(it) }
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        return@with null
                    }
                    throw e
                }
            }
        }
    }

    suspend fun getCurrentUser(): MangaBakaUserProfile =
        withIOContext {
            with(json) {
                authClient
                    .newCall(GET("$API_BASE_URL/v1/my/profile"))
                    .awaitSuccess()
                    .parseAs<MangaBakaUserProfileResponse>()
                    .data
            }
        }

    suspend fun getAccessToken(code: String): MangaBakaOAuth =
        withIOContext {
            val formBody =
                FormBody
                    .Builder()
                    .add("client_id", CLIENT_ID)
                    .add("code", code)
                    .add("code_verifier", codeVerifier)
                    .add("code_challenge_method", "S256")
                    .add("grant_type", "authorization_code")
                    .add("redirect_uri", REDIRECT_URI)
                    .add("scope", SCOPES)
                    .build()

            with(json) {
                client
                    .newCall(POST("${OAUTH_URL}/token", body = formBody))
                    .awaitSuccess()
                    .parseAs()
            }
        }

    fun verifyOAuthState(state: String): Boolean = state == oauthStateParam

    companion object {
        private const val CLIENT_ID = "ppVWLKHpvCXGgHnuCMBPqnbGTrVRmpqh"

        private const val BASE_URL = "https://mangabaka.org"
        private const val API_BASE_URL = "https://api.mangabaka.org"
        private const val LIBRARY_API_URL = "$API_BASE_URL/v1/my/library"
        private const val OAUTH_URL = "$BASE_URL/auth/oauth2"
        private const val SCOPES = "library.read library.write offline_access openid"

        private const val REDIRECT_URI = "tachiyomij2k://mangabaka-auth"

        private var codeVerifier: String = ""
        private var oauthStateParam: String = ""

        private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        private val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        private fun formatIsoDate(epochMillis: Long): String = dateOnlyFormat.format(epochMillis)

        private fun parseIsoDate(dateString: String?): Long {
            if (dateString.isNullOrEmpty()) return 0L
            return try {
                isoDateFormat.parse(dateString)?.time ?: 0L
            } catch (e: Exception) {
                try {
                    dateOnlyFormat.parse(dateString)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            }
        }

        fun authUrl(): Uri =
            "$OAUTH_URL/authorize"
                .toUri()
                .buildUpon()
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("code_challenge", getPkceS256ChallengeCode())
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("state", getOAuthStateParam())
                .build()

        fun refreshTokenRequest(token: String) =
            POST(
                "$OAUTH_URL/token",
                body =
                    FormBody
                        .Builder()
                        .add("grant_type", "refresh_token")
                        .add("client_id", CLIENT_ID)
                        .add("refresh_token", token)
                        .add("redirect_uri", REDIRECT_URI)
                        .build(),
            )

        private fun getOAuthStateParam(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            oauthStateParam =
                Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(bytes)

            return oauthStateParam
        }

        private fun getPkceS256ChallengeCode(): String {
            // MangaBaka requires an actually conformant PKCE process, unlike MAL
            // 1. create verifier
            // 2. create challenge from verifier (S256 hash -> base64 URL encode)
            // 3. send challenge to /authorize
            // 4. send verifier for access tokens to /token
            val codes = PkceUtil.generateS256Codes()
            codeVerifier = codes.codeVerifier
            return codes.codeChallenge
        }
    }
}
