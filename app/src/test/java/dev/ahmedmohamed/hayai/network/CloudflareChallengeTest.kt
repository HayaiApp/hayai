package dev.ahmedmohamed.hayai.network

import eu.kanade.tachiyomi.network.interceptor.isCloudflareChallenge
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
    fun `SY legacy challenge responses trigger the existing solver`() {
        assertTrue(response(503, null, "cloudflare").isCloudflareChallenge())
        assertTrue(response(403, null, "Cloudflare-nginx").isCloudflareChallenge())
        assertFalse(response(200, null, "cloudflare").isCloudflareChallenge())
    }

    @Test
    fun `ordinary origin errors are ignored but explicit challenges remain authoritative`() {
        assertFalse(response(403, null, "origin").isCloudflareChallenge())
        assertTrue(response(403, "challenge", "origin").isCloudflareChallenge())
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
