package dev.ahmedmohamed.hayai.adult.eh.session

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.network.withEhBrowserCookies
import dev.ahmedmohamed.hayai.network.CloudflareHelpDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.Locale

sealed interface EhVerificationResult {
    data object Verified : EhVerificationResult

    data object Cloudflare : EhVerificationResult

    data object InvalidCredentials : EhVerificationResult

    data class NetworkFailure(
        val diagnostic: String? = null,
    ) : EhVerificationResult
}

class EhSessionVerifier(
    client: OkHttpClient,
) {
    private val client = client.withEhBrowserCookies()

    suspend fun verify(sessionStore: EhSessionStore): EhVerificationResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url(VERIFY_URL)
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .header("Cookie", sessionStore.cookieHeader(EhSite.ExHentai).value)
                        .build()
                client.newCall(request).execute().use { response ->
                    classify(
                        statusCode = response.code,
                        finalUrl = response.request.url.toString(),
                        headers = response.headers.toMultimap(),
                        body = response.body.charStream().use { it.readBounded(MAX_BODY_CHARS) },
                    )
                }
            }.getOrElse { EhVerificationResult.NetworkFailure(it.message) }
        }

    companion object {
        const val VERIFY_URL = "https://exhentai.org/"
        private const val MAX_BODY_CHARS = 512 * 1024

        fun classify(
            statusCode: Int,
            finalUrl: String,
            headers: Map<String, List<String>>,
            body: String,
        ): EhVerificationResult {
            val normalizedBody = body.lowercase(Locale.ROOT)
            if (CloudflareHelpDetector.isChallengeResponse(statusCode, headers, body)) return EhVerificationResult.Cloudflare

            val host = runCatching { URI(finalUrl).host.orEmpty() }.getOrDefault("")
            val loginPage =
                "bounce_login.php" in normalizedBody ||
                    "act=login" in normalizedBody ||
                    "sad panda" in normalizedBody
            if (statusCode !in 200..299 || !host.equals("exhentai.org", ignoreCase = true) || loginPage) {
                return EhVerificationResult.InvalidCredentials
            }
            return EhVerificationResult.Verified
        }
    }
}

private fun java.io.Reader.readBounded(maxChars: Int): String {
    val output = StringBuilder(minOf(maxChars, 8192))
    val buffer = CharArray(8192)
    while (output.length < maxChars) {
        val read = read(buffer, 0, minOf(buffer.size, maxChars - output.length))
        if (read < 0) break
        output.append(buffer, 0, read)
    }
    return output.toString()
}
