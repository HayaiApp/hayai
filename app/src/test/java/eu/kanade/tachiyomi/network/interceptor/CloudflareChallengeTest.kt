package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareChallengeTest {
    @Test
    fun `official challenge header is detected regardless of status code`() {
        assertTrue(response(200, "challenge", "cloudflare").isCloudflareChallenge())
    }

    @Test
    fun `legacy error code without official header is not treated as a challenge`() {
        assertFalse(response(503, null, "cloudflare").isCloudflareChallenge())
    }

    @Test
    fun `non Cloudflare servers are ignored`() {
        assertFalse(response(403, "challenge", "origin").isCloudflareChallenge())
    }

    private fun response(
        code: Int,
        mitigated: String?,
        server: String,
    ): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_2)
            .message("test")
            .code(code)
            .header("Server", server)
            .apply { mitigated?.let { header("cf-mitigated", it) } }
            .build()
}
