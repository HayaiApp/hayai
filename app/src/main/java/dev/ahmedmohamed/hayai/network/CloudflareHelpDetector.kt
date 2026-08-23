package dev.ahmedmohamed.hayai.network

/** Mirrors Mihon's WebView Cloudflare challenge detection. */
internal object CloudflareHelpDetector {
    private val markers = listOf("window._cf_chl_opt", "Ray ID is")

    fun isChallengeHtml(html: String): Boolean = markers.any(html::contains)

    val javascript: String =
        """
        (() => {
          const html = document.documentElement?.outerHTML || '';
          return html.includes('window._cf_chl_opt') || html.includes('Ray ID is');
        })()
        """.trimIndent()

    fun isChallengeResult(result: String?): Boolean = result == "true"
}
