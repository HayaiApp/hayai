package eu.kanade.tachiyomi.data.track.bangumi

import eu.kanade.tachiyomi.BuildConfig
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class BangumiInterceptor(
    val bangumi: Bangumi,
) : Interceptor {
    private val json: Json by injectLazy()

    /**
     * OAuth object used for authenticated requests.
     */
    private var oauth: OAuth? = bangumi.restoreToken()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        var currAuth = oauth ?: throw Exception("Not authenticated with Bangumi")

        if (currAuth.isExpired()) {
            val response = chain.proceed(BangumiApi.refreshTokenRequest(currAuth.refresh_token!!))
            if (response.isSuccessful) {
                currAuth = json.decodeFromString<OAuth>(response.body.string())
                newAuth(currAuth)
            } else {
                response.close()
            }
        }

        val authRequest =
            originalRequest
                .newBuilder()
                .header(
                    "User-Agent",
                    "Tachiyomi J2K/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}) " +
                        "(Android) (https://github.com/HayaiApp/hayai)",
                ).header("Authorization", "Bearer ${currAuth.access_token}")
                .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(oauth: OAuth?) {
        this.oauth =
            if (oauth == null) {
                null
            } else {
                OAuth(
                    oauth.access_token,
                    oauth.token_type,
                    System.currentTimeMillis() / 1000,
                    oauth.expires_in,
                    oauth.refresh_token,
                    this.oauth?.user_id,
                )
            }

        bangumi.saveToken(oauth)
    }
}
