package dev.ahmedmohamed.hayai.network

/** Challenge signals from Mihon 3a64c8d65cf9 and TachiyomiSY 14648c7cf0aa. */
internal object CloudflareHelpDetector {
    private val markers = listOf("window._cf_chl_opt", "Ray ID is")

    fun isChallengeHtml(html: String): Boolean = markers.any(html::contains)

    fun isChallengeResponse(status: Int, headers: Map<String, List<String>>, body: String = ""): Boolean {
        val normalized = headers.mapKeys { it.key.lowercase(java.util.Locale.ROOT) }
        if (normalized["cf-mitigated"]?.any { it.equals("challenge", ignoreCase = true) } == true) return true
        val cloudflareServer = normalized["server"]?.any { it.equals("cloudflare", true) || it.equals("cloudflare-nginx", true) } == true
        return isChallengeHtml(body) || status in listOf(403, 503) && (cloudflareServer || "cf-ray" in normalized)
    }

    val javascript: String =
        """
        (() => {
          const html = document.documentElement?.outerHTML || '';
          return html.includes('window._cf_chl_opt') || html.includes('Ray ID is') || !!document.querySelector('#challenge-form');
        })()
        """.trimIndent()

    fun isChallengeResult(result: String?): Boolean = result == "true"
}
