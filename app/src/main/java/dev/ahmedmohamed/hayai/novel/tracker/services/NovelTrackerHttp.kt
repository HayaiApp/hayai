package dev.ahmedmohamed.hayai.novel.tracker.services

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal data class NovelTrackerHttpResponse(
    val body: String,
    val finalUrl: String,
)

internal class NovelTrackerHttp(
    private val client: OkHttpClient,
    private val maximumResponseBytes: Long = DEFAULT_MAXIMUM_RESPONSE_BYTES,
) {
    suspend fun execute(request: Request): NovelTrackerHttpResponse {
        val response =
            try {
                client.newCall(request).await()
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                throw NovelTrackerFailure.Network(error)
            }
        response.use {
            when (it.code) {
                401, 403 -> throw NovelTrackerFailure.SessionExpired()
                429 -> throw NovelTrackerFailure.RateLimited(it.header("Retry-After")?.toLongOrNull())
            }
            val body =
                try {
                    val source = it.body.source()
                    source.request(maximumResponseBytes + 1)
                    if (source.buffer.size > maximumResponseBytes) {
                        throw NovelTrackerFailure.ResponseTooLarge(maximumResponseBytes)
                    }
                    source.readUtf8()
                } catch (error: NovelTrackerFailure) {
                    throw error
                } catch (error: IOException) {
                    throw NovelTrackerFailure.Network(error)
                }
            if (!it.isSuccessful) {
                val safeMessage = body.lineSequence().firstOrNull()?.take(160).orEmpty()
                throw NovelTrackerFailure.Remote(it.code, safeMessage.ifBlank { "The tracker returned HTTP ${it.code}" })
            }
            return NovelTrackerHttpResponse(body, it.request.url.toString())
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES = 1_048_576L
    }
}

internal fun requireSafeCredential(value: String, label: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) throw NovelTrackerFailure.InvalidCredentials("$label is required")
    if (trimmed.length > 8_192 || trimmed.any { it == '\r' || it == '\n' || it == '\u0000' }) {
        throw NovelTrackerFailure.InvalidCredentials("$label contains invalid characters")
    }
    return trimmed
}
