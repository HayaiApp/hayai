package hayai.novel.js

import yokai.util.koin.get
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.Charset

/**
 * Kotlin implementation of the JS bridge.
 * Bridges fetch calls to OkHttp and storage calls to SharedPreferences.
 */
class NovelJsBridgeImpl(
    private val context: Context,
) : NovelJsBridge {

    private val networkHelper: NetworkHelper = get()
    private val client get() = networkHelper.client
    private val json = Json { ignoreUnknownKeys = true }

    private fun getPrefs(pluginId: String): SharedPreferences {
        return context.getSharedPreferences("novel_plugin_$pluginId", Context.MODE_PRIVATE)
    }

    @Serializable
    private data class FetchInit(
        val method: String? = null,
        val headers: Map<String, String>? = null,
        val body: String? = null,
        val bodyBase64: String? = null,
    )

    override suspend fun fetch(url: String, initJson: String): String = withContext(Dispatchers.IO) {
        try {
            val init = json.decodeFromString<FetchInit>(initJson)
            val request = buildRequest(url, init)
            val response = client.newCall(request).execute()

            val bodyBytes = response.body.bytes()
            val bodyText = String(bodyBytes, Charsets.UTF_8)
            val bodyBase64 = Base64.encodeToString(bodyBytes, Base64.NO_WRAP)

            val headersMap = mutableMapOf<String, String>()
            response.headers.forEach { (name, value) ->
                headersMap[name.lowercase()] = value
            }

            buildJsonResponse(response.code, response.message, bodyText, bodyBase64, headersMap)
        } catch (e: Exception) {
            Logger.e(e) { "NovelJsBridge.fetch failed: $url" }
            buildJsonResponse(0, e.message ?: "Error", "", "", emptyMap())
        }
    }

    override suspend fun fetchText(url: String, initJson: String, encoding: String): String = withContext(Dispatchers.IO) {
        try {
            val init = json.decodeFromString<FetchInit>(initJson)
            val request = buildRequest(url, init)
            val response = client.newCall(request).execute()

            val bodyBytes = response.body.bytes()
            val charset = try {
                Charset.forName(encoding)
            } catch (_: Exception) {
                Charsets.UTF_8
            }
            String(bodyBytes, charset)
        } catch (e: Exception) {
            Logger.e(e) { "NovelJsBridge.fetchText failed: $url" }
            ""
        }
    }

    override suspend fun fetchFile(url: String, initJson: String): String = withContext(Dispatchers.IO) {
        try {
            val init = json.decodeFromString<FetchInit>(initJson)
            val request = buildRequest(url, init)
            val response = client.newCall(request).execute()

            val bodyBytes = response.body.bytes()
            Base64.encodeToString(bodyBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.e(e) { "NovelJsBridge.fetchFile failed: $url" }
            ""
        }
    }

    // --- Storage ---

    override fun storageGet(pluginId: String, key: String): String? {
        return getPrefs(pluginId).getString(key, null)
    }

    override fun storageSet(pluginId: String, key: String, valueJson: String) {
        getPrefs(pluginId).edit().putString(key, valueJson).apply()
    }

    override fun storageDelete(pluginId: String, key: String) {
        getPrefs(pluginId).edit().remove(key).apply()
    }

    override fun storageGetAllKeys(pluginId: String): String {
        val keys = getPrefs(pluginId).all.keys.toList()
        return "[" + keys.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
    }

    override fun storageClearAll(pluginId: String) {
        getPrefs(pluginId).edit().clear().apply()
    }

    override suspend fun sleep(durationMs: Int) {
        if (durationMs > 0) {
            // delay() suspends the coroutine without occupying the JS worker thread —
            // Thread.sleep() previously stalled it for the full duration, blocking every
            // other JS operation on this plugin and (pre per-source dispatcher) every plugin.
            delay(durationMs.toLong())
        }
    }

    // --- Helpers ---

    private fun buildRequest(url: String, init: FetchInit): Request {
        val builder = Request.Builder().url(url)

        init.headers?.forEach { (key, value) ->
            // Accept-Encoding handling: OkHttp transparently negotiates and decompresses
            // gzip/deflate ONLY when we don't set the header ourselves. The JS shim layer adds
            // `gzip, deflate` by default to mirror LNReader's surface, but if we forwarded that
            // verbatim OkHttp would skip auto-decompression and the JS layer would receive raw
            // compressed bytes. So we strip those auto-managed values — but pass through any
            // explicit override (`identity`, `br`, `zstd`, etc.) so plugins can still opt out.
            if (key.equals("accept-encoding", ignoreCase = true) && isAutoManagedEncoding(value)) {
                return@forEach
            }
            builder.addHeader(key, value)
        }

        val method = (init.method ?: "GET").uppercase()
        val contentType = init.headers?.entries
            ?.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value ?: "application/x-www-form-urlencoded"
        val mediaType = contentType.toMediaTypeOrNull()

        val requestBody = when {
            init.bodyBase64 != null -> {
                // btoa produces standard base64 (+/) — NO_WRAP matches it.
                Base64.decode(init.bodyBase64, Base64.NO_WRAP).toRequestBody(mediaType)
            }
            init.body != null -> init.body.toRequestBody(mediaType)
            else -> null
        }

        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> builder.post(requestBody ?: "".toRequestBody(mediaType))
            "PUT" -> builder.put(requestBody ?: "".toRequestBody(mediaType))
            "DELETE" -> {
                if (requestBody != null) builder.delete(requestBody)
                else builder.delete()
            }
            "PATCH" -> builder.patch(requestBody ?: "".toRequestBody(mediaType))
            else -> builder.method(method, requestBody)
        }

        return builder.build()
    }

    /**
     * `Accept-Encoding` values that OkHttp will negotiate + decompress automatically.
     * Anything else (e.g. `identity` to disable compression) is honored as-is.
     */
    private fun isAutoManagedEncoding(value: String): Boolean {
        val tokens = value.split(',').map { it.substringBefore(';').trim().lowercase() }.toSet()
        if (tokens.isEmpty()) return false
        return tokens.all { it == "gzip" || it == "deflate" || it == "*" || it.isEmpty() }
    }

    private fun buildJsonResponse(
        status: Int,
        statusText: String,
        body: String,
        bodyBase64: String,
        headers: Map<String, String>,
    ): String {
        @Serializable
        data class FetchResponse(
            val status: Int,
            val statusText: String,
            val body: String,
            val bodyBase64: String,
            val headers: Map<String, String>,
        )
        return json.encodeToString(FetchResponse.serializer(), FetchResponse(status, statusText, body, bodyBase64, headers))
    }
}
