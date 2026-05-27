package eu.kanade.tachiyomi.data.track.novellist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Supabase Auth session payload persisted for NovelList. We need [refreshToken] so the access
 * token can be silently renewed via Supabase's `/auth/v1/token?grant_type=refresh_token`
 * endpoint instead of forcing the user back through the WebView every time the JWT lapses.
 */
@Serializable
data class NovelListSession(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("token_type")
    val tokenType: String = "bearer",
    @SerialName("expires_in")
    val expiresIn: Long = 3600,
    /** Unix seconds — populated by Supabase, falls back to created_at + expires_in. */
    @SerialName("expires_at")
    val expiresAt: Long = (System.currentTimeMillis() / 1000) + expiresIn,
) {
    /** Treat the token as expired 60 s early to absorb clock skew + the time to make the call. */
    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        nowSeconds >= (expiresAt - 60)
}
