package dev.ahmedmohamed.hayai.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareHelpDetectorTest {
    @Test
    fun `detects both Mihon Cloudflare challenge markers`() {
        assertTrue(CloudflareHelpDetector.isChallengeHtml("<script>window._cf_chl_opt = {}</script>"))
        assertTrue(CloudflareHelpDetector.isChallengeHtml("Cloudflare Ray ID is abc123"))
    }

    @Test
    fun `does not flag an ordinary page`() {
        assertFalse(CloudflareHelpDetector.isChallengeHtml("<html><body>Gallery</body></html>"))
        assertFalse(CloudflareHelpDetector.isChallengeResult("false"))
        assertTrue(CloudflareHelpDetector.isChallengeResult("true"))
    }
}
