package dev.ahmedmohamed.hayai.adult.eh.session

import org.junit.Assert.assertTrue
import org.junit.Test

class EhSessionVerifierTest {
    @Test
    fun `Cloudflare proxy headers and passive scripts do not reject a logged in gallery`() {
        val result = EhSessionVerifier.classify(
            200,
            "https://exhentai.org/",
            mapOf("CF-Ray" to listOf("request-id"), "Server" to listOf("cloudflare")),
            "<html>Gallery List<script src='/cdn-cgi/scripts/email-decode.min.js'></script></html>",
        )
        assertTrue(result is EhVerificationResult.Verified)
    }

    @Test
    fun `successful ExHentai response verifies the session`() {
        val result = EhSessionVerifier.classify(200, "https://exhentai.org/", emptyMap(), "<html><body>Gallery List</body></html>")

        assertTrue(result is EhVerificationResult.Verified)
    }

    @Test
    fun `redirect away from ExHentai is invalid credentials`() {
        val result = EhSessionVerifier.classify(200, "https://e-hentai.org/bounce_login.php", emptyMap(), "login")

        assertTrue(result is EhVerificationResult.InvalidCredentials)
    }

    @Test
    fun `Cloudflare response is not mislabeled as invalid credentials`() {
        val result =
            EhSessionVerifier.classify(
                403,
                "https://exhentai.org/",
                mapOf("CF-Ray" to listOf("request-id")),
                "Attention required",
            )

        assertTrue(result is EhVerificationResult.Cloudflare)
    }

    @Test
    fun `login page body is invalid credentials`() {
        val result = EhSessionVerifier.classify(200, "https://exhentai.org/", emptyMap(), "<a href='/bounce_login.php'>Login</a>")

        assertTrue(result is EhVerificationResult.InvalidCredentials)
    }
}
