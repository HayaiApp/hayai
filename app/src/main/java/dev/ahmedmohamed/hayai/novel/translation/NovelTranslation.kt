package dev.ahmedmohamed.hayai.novel.translation

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.parser.Parser
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

enum class NovelTranslationEngineId { GOOGLE_WEB, LIBRE_TRANSLATE, OPENAI_COMPATIBLE, DEEPL }

@Serializable
data class NovelTranslationSettings(
    val engine: NovelTranslationEngineId = NovelTranslationEngineId.GOOGLE_WEB,
    val sourceLanguage: String = "auto",
    val targetLanguage: String = "en",
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
) {
    fun validate(): NovelTranslationSettings {
        require(LANGUAGE.matches(sourceLanguage) && LANGUAGE.matches(targetLanguage))
        require(model.length in 1..128)
        if (engine != NovelTranslationEngineId.GOOGLE_WEB) {
            val uri = runCatching { URI(endpoint) }.getOrNull()
            val localHttp = uri?.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1")
            require((uri?.scheme == "https" && !uri.host.isNullOrBlank()) || localHttp) {
                "Translation endpoint must use HTTPS or local loopback HTTP"
            }
            require(uri?.userInfo == null) { "Translation endpoint must not contain credentials" }
        }
        require(endpoint.length <= 2_048 && apiKey.length <= 4_096)
        return this
    }

    companion object { private val LANGUAGE = Regex("auto|[a-z]{2,3}(?:-[A-Z]{2})?") }
}

class NovelTranslationSettingsStore(context: Context, private val json: Json = Json { ignoreUnknownKeys = true }) {
    private val preferences = context.getSharedPreferences("hayai_novel_translation", Context.MODE_PRIVATE)
    fun get(): NovelTranslationSettings = runCatching { json.decodeFromString<NovelTranslationSettings>(preferences.getString(KEY, null) ?: "") }.getOrDefault(NovelTranslationSettings())
    fun set(settings: NovelTranslationSettings) { check(preferences.edit().putString(KEY, json.encodeToString(settings.validate())).commit()) }
    private companion object { const val KEY = "settings_v1" }
}

@Serializable
data class CachedNovelTranslation(
    val key: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val engine: NovelTranslationEngineId,
    val sourceHash: String,
    val translatedText: String,
    val detectedLanguage: String?,
    val createdAt: Long,
)

class NovelTranslationCache(context: Context, private val json: Json = Json { ignoreUnknownKeys = true }) {
    private val root = File(context.cacheDir, "hayai/novel-translations").apply { mkdirs() }
    fun get(key: String, source: String, settings: NovelTranslationSettings): CachedNovelTranslation? {
        val id = cacheId(key, source, settings)
        val file = File(root, "$id.json")
        if (!file.isFile || file.length() !in 1..MAX_ENTRY_BYTES) return null
        return runCatching { json.decodeFromString<CachedNovelTranslation>(file.readText()) }.getOrNull()?.takeIf { it.sourceHash == hash(source) }
    }
    fun put(key: String, source: String, settings: NovelTranslationSettings, text: String, detected: String?): CachedNovelTranslation {
        require(text.length <= MAX_TEXT_CHARS)
        val id = cacheId(key, source, settings)
        val value = CachedNovelTranslation(id, settings.sourceLanguage, settings.targetLanguage, settings.engine, hash(source), text, detected, System.currentTimeMillis())
        val target = File(root, "$id.json")
        val temporary = File(root, ".$id.${System.nanoTime()}.tmp")
        temporary.writeText(json.encodeToString(value))
        check(temporary.renameTo(target) || run { target.delete(); temporary.renameTo(target) })
        return value
    }
    fun clear() {
        root.listFiles { file -> file.extension == "json" }.orEmpty().forEach(File::delete)
    }
    private fun cacheId(key: String, source: String, settings: NovelTranslationSettings) = hash("$key\u0000${settings.engine}\u0000${settings.sourceLanguage}\u0000${settings.targetLanguage}\u0000${settings.endpoint}\u0000${settings.model}\u0000${hash(source)}")
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private companion object { const val MAX_ENTRY_BYTES = 4L * 1024 * 1024; const val MAX_TEXT_CHARS = 2_000_000 }
}

data class NovelTranslationResult(
    val text: String,
    val detectedLanguage: String? = null,
    val complete: Boolean = true,
    val warning: String? = null,
)

class NovelTranslationService(
    private val network: NetworkHelper,
    private val cache: NovelTranslationCache,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun translate(cacheKey: String, text: String, settings: NovelTranslationSettings): NovelTranslationResult {
        val checked = settings.validate()
        val normalized = text.trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_DOCUMENT_CHARS)
        cache.get(cacheKey, normalized, checked)?.let { return NovelTranslationResult(it.translatedText, it.detectedLanguage) }
        val chunks = split(normalized)
        val results = mutableListOf<NovelTranslationResult>()
        for ((index, chunk) in chunks.withIndex()) {
            try {
                results += translateChunk(chunk, checked)
            } catch (error: Exception) {
                if (results.isEmpty()) throw IllegalStateException("Translation failed. ${safeMessage(error)}", error)
                return NovelTranslationResult(
                    text = results.joinToString("\n\n") { it.text },
                    detectedLanguage = results.firstNotNullOfOrNull(NovelTranslationResult::detectedLanguage),
                    complete = false,
                    warning = "Translated $index of ${chunks.size} parts. ${safeMessage(error)}",
                )
            }
        }
        val joined = results.joinToString("\n\n") { it.text }
        val detected = results.firstNotNullOfOrNull(NovelTranslationResult::detectedLanguage)
        cache.put(cacheKey, normalized, checked, joined, detected)
        return NovelTranslationResult(joined, detected)
    }

    private suspend fun translateChunk(text: String, settings: NovelTranslationSettings): NovelTranslationResult = withContext(Dispatchers.IO) {
        when (settings.engine) {
            NovelTranslationEngineId.GOOGLE_WEB -> googleWeb(text, settings)
            NovelTranslationEngineId.LIBRE_TRANSLATE -> libre(text, settings)
            NovelTranslationEngineId.OPENAI_COMPATIBLE -> openAi(text, settings)
            NovelTranslationEngineId.DEEPL -> deepL(text, settings)
        }
    }

    private fun googleWeb(text: String, settings: NovelTranslationSettings): NovelTranslationResult {
        val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx").addQueryParameter("sl", settings.sourceLanguage).addQueryParameter("tl", settings.targetLanguage)
            .addQueryParameter("dt", "t").addQueryParameter("q", text).build()
        return execute(Request.Builder().url(url).get().build()) { body ->
            val root = json.parseToJsonElement(body).jsonArray
            val translated = root[0].jsonArray.joinToString("") { it.jsonArray[0].jsonPrimitive.content }
            NovelTranslationResult(Parser.unescapeEntities(translated, false), root.getOrNull(2)?.jsonPrimitive?.content)
        }
    }

    private fun libre(text: String, settings: NovelTranslationSettings): NovelTranslationResult {
        val body = JsonObject(mapOf("q" to kotlinx.serialization.json.JsonPrimitive(text), "source" to kotlinx.serialization.json.JsonPrimitive(settings.sourceLanguage), "target" to kotlinx.serialization.json.JsonPrimitive(settings.targetLanguage), "format" to kotlinx.serialization.json.JsonPrimitive("text"), "api_key" to kotlinx.serialization.json.JsonPrimitive(settings.apiKey))).toString()
        return execute(jsonRequest(settings.endpoint, body, settings.apiKey)) { response ->
            val obj = json.parseToJsonElement(response) as JsonObject
            NovelTranslationResult(obj["translatedText"]?.jsonPrimitive?.content ?: error("LibreTranslate returned no text"), obj["detectedLanguage"]?.jsonPrimitive?.content)
        }
    }

    private fun openAi(text: String, settings: NovelTranslationSettings): NovelTranslationResult {
        val prompt = "Translate from ${settings.sourceLanguage} to ${settings.targetLanguage}. Return only the translation. Preserve paragraphs and meaning."
        val body = JsonObject(mapOf("model" to kotlinx.serialization.json.JsonPrimitive(settings.model), "temperature" to kotlinx.serialization.json.JsonPrimitive(0), "messages" to JsonArray(listOf(JsonObject(mapOf("role" to kotlinx.serialization.json.JsonPrimitive("system"), "content" to kotlinx.serialization.json.JsonPrimitive(prompt))), JsonObject(mapOf("role" to kotlinx.serialization.json.JsonPrimitive("user"), "content" to kotlinx.serialization.json.JsonPrimitive(text))))))).toString()
        return execute(jsonRequest(settings.endpoint.trimEnd('/') + "/chat/completions", body, settings.apiKey)) { response ->
            val obj = json.parseToJsonElement(response) as JsonObject
            val choices = obj["choices"]?.jsonArray ?: error("Translation provider returned no choices")
            val message = choices.firstOrNull()?.let { it as JsonObject }?.get("message") as? JsonObject
            NovelTranslationResult(message?.get("content")?.jsonPrimitive?.content ?: error("Translation provider returned no text"))
        }
    }

    private fun deepL(text: String, settings: NovelTranslationSettings): NovelTranslationResult {
        val body = FormBody.Builder().add("text", text).add("target_lang", settings.targetLanguage.uppercase()).apply { if (settings.sourceLanguage != "auto") add("source_lang", settings.sourceLanguage.uppercase()) }.build()
        val request = Request.Builder().url(settings.endpoint.trimEnd('/') + "/v2/translate").header("Authorization", "DeepL-Auth-Key ${settings.apiKey}").post(body).build()
        return execute(request) { response ->
            val items = (json.parseToJsonElement(response) as JsonObject)["translations"]?.jsonArray ?: error("DeepL returned no translations")
            val item = items.firstOrNull() as? JsonObject ?: error("DeepL returned no translations")
            NovelTranslationResult(item["text"]?.jsonPrimitive?.content ?: error("DeepL returned no text"), item["detected_source_language"]?.jsonPrimitive?.content)
        }
    }

    private fun jsonRequest(url: String, body: String, key: String): Request = Request.Builder().url(url).apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }.post(body.toRequestBody("application/json".toMediaType())).build()
    private fun <T> execute(request: Request, parse: (String) -> T): T = network.client.newCall(request).execute().use { response ->
        val body = response.body.byteStream().readBounded(MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)
        check(response.isSuccessful) { "Translation provider returned HTTP ${response.code}" }
        parse(body)
    }

    internal fun split(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)
        val result = mutableListOf<String>(); var remaining = text
        while (remaining.length > MAX_CHUNK_CHARS) {
            val boundary = maxOf(remaining.lastIndexOf("\n\n", MAX_CHUNK_CHARS), remaining.lastIndexOf(". ", MAX_CHUNK_CHARS), remaining.lastIndexOf(' ', MAX_CHUNK_CHARS)).takeIf { it >= MAX_CHUNK_CHARS / 2 } ?: MAX_CHUNK_CHARS
            result += remaining.substring(0, boundary).trim(); remaining = remaining.substring(boundary).trimStart()
        }
        if (remaining.isNotEmpty()) result += remaining
        require(result.size <= MAX_CHUNKS) { "Text requires too many translation requests" }
        return result
    }

    private fun safeMessage(error: Throwable): String = (error.message ?: error.javaClass.simpleName)
        .replace(Regex("(?i)(api[_ -]?key|authorization|bearer)\\s*[:=]?\\s*\\S+"), "\$1 [redacted]")
        .take(300)

    private fun InputStream.readBounded(max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= max) { "Translation response is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object { private const val MAX_CHUNK_CHARS = 4_000; private const val MAX_CHUNKS = 16; private const val MAX_DOCUMENT_CHARS = 64_000; private const val MAX_RESPONSE_BYTES = 2_000_000 }
}
