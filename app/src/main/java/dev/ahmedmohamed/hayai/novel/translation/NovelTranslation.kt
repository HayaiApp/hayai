package dev.ahmedmohamed.hayai.novel.translation

import android.content.Context
import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.novel.error.NovelFailureCarrier
import dev.ahmedmohamed.hayai.novel.error.NovelValidationFailure
import dev.ahmedmohamed.hayai.novel.error.novelFailure
import dev.ahmedmohamed.hayai.novel.error.novelRequire
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

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
            if (!((uri?.scheme == "https" && !uri.host.isNullOrBlank()) || localHttp)) {
                throw NovelValidationFailure(NovelFailure.Code.TranslationEndpointHttps)
            }
            if (uri?.userInfo != null) {
                throw NovelValidationFailure(NovelFailure.Code.TranslationEndpointCredentials)
            }
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

data class NovelTranslationResult(
    val text: String,
    val detectedLanguage: String? = null,
    val complete: Boolean = true,
    val warning: NovelTranslationWarning? = null,
)

data class NovelTranslationWarning(
    val completedParts: Int,
    val totalParts: Int,
    val error: Throwable,
)

class NovelTranslationService(
    private val network: NetworkHelper,
    private val store: NovelTranslationStore? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun translate(
        locator: NovelTranslationLocator,
        text: String,
        settings: NovelTranslationSettings,
    ): NovelTranslationResult {
        val checked = settings.validate()
        val normalized = text.trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_DOCUMENT_CHARS)
        val sourceHash = NovelTranslationHash.sha256(normalized)
        store?.findCompleted(locator, checked.targetLanguage, sourceHash)?.let {
            return NovelTranslationResult(it.translatedContent, it.detectedLanguage)
        }
        val result = translateNormalized(normalized, checked)
        if (result.complete) {
            val now = System.currentTimeMillis()
            store?.saveCompleted(
                StoredNovelTranslation(
                    locator = locator,
                    sourceLanguage = checked.sourceLanguage,
                    targetLanguage = checked.targetLanguage,
                    sourceHash = sourceHash,
                    translatedContent = result.text,
                    engineId = checked.engine.name,
                    detectedLanguage = result.detectedLanguage,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        return result
    }

    suspend fun translateText(
        text: String,
        settings: NovelTranslationSettings,
    ): NovelTranslationResult {
        val checked = settings.validate()
        val normalized = text.trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_DOCUMENT_CHARS)
        return translateNormalized(normalized, checked)
    }

    private suspend fun translateNormalized(
        normalized: String,
        checked: NovelTranslationSettings,
    ): NovelTranslationResult {
        val chunks = split(normalized)
        val results = mutableListOf<NovelTranslationResult>()
        for ((index, chunk) in chunks.withIndex()) {
            try {
                results += translateChunk(chunk, checked)
            } catch (error: Exception) {
                val surfaced = error.takeIf { it is NovelFailureCarrier }
                    ?: NovelFailure(NovelFailure.Code.TranslationFailed, cause = error)
                if (results.isEmpty()) {
                    throw surfaced
                }
                return NovelTranslationResult(
                    text = results.joinToString("\n\n") { it.text },
                    detectedLanguage = results.firstNotNullOfOrNull(NovelTranslationResult::detectedLanguage),
                    complete = false,
                    warning = NovelTranslationWarning(index, chunks.size, surfaced),
                )
            }
        }
        val joined = results.joinToString("\n\n") { it.text }
        val detected = results.firstNotNullOfOrNull(NovelTranslationResult::detectedLanguage)
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
            NovelTranslationResult(
                obj["translatedText"]?.jsonPrimitive?.content
                    ?: novelFailure(NovelFailure.Code.TranslationProviderNoText),
                obj["detectedLanguage"]?.jsonPrimitive?.content,
            )
        }
    }

    private fun openAi(text: String, settings: NovelTranslationSettings): NovelTranslationResult {
        val prompt = "Translate from ${settings.sourceLanguage} to ${settings.targetLanguage}. Return only the translation. Preserve paragraphs and meaning."
        val body = JsonObject(mapOf("model" to kotlinx.serialization.json.JsonPrimitive(settings.model), "temperature" to kotlinx.serialization.json.JsonPrimitive(0), "messages" to JsonArray(listOf(JsonObject(mapOf("role" to kotlinx.serialization.json.JsonPrimitive("system"), "content" to kotlinx.serialization.json.JsonPrimitive(prompt))), JsonObject(mapOf("role" to kotlinx.serialization.json.JsonPrimitive("user"), "content" to kotlinx.serialization.json.JsonPrimitive(text))))))).toString()
        return execute(jsonRequest(settings.endpoint.trimEnd('/') + "/chat/completions", body, settings.apiKey)) { response ->
            val obj = json.parseToJsonElement(response) as JsonObject
            val choices = obj["choices"]?.jsonArray
                ?: novelFailure(NovelFailure.Code.TranslationProviderNoChoices)
            val message = choices.firstOrNull()?.let { it as JsonObject }?.get("message") as? JsonObject
            NovelTranslationResult(
                message?.get("content")?.jsonPrimitive?.content
                    ?: novelFailure(NovelFailure.Code.TranslationProviderNoText),
            )
        }
    }

    private fun deepL(text: String, settings: NovelTranslationSettings): NovelTranslationResult {
        val body = FormBody.Builder().add("text", text).add("target_lang", settings.targetLanguage.uppercase()).apply { if (settings.sourceLanguage != "auto") add("source_lang", settings.sourceLanguage.uppercase()) }.build()
        val request = Request.Builder().url(settings.endpoint.trimEnd('/') + "/v2/translate").header("Authorization", "DeepL-Auth-Key ${settings.apiKey}").post(body).build()
        return execute(request) { response ->
            val items = (json.parseToJsonElement(response) as JsonObject)["translations"]?.jsonArray
                ?: novelFailure(NovelFailure.Code.TranslationProviderNoTranslations)
            val item = items.firstOrNull() as? JsonObject
                ?: novelFailure(NovelFailure.Code.TranslationProviderNoTranslations)
            NovelTranslationResult(
                item["text"]?.jsonPrimitive?.content
                    ?: novelFailure(NovelFailure.Code.TranslationProviderNoText),
                item["detected_source_language"]?.jsonPrimitive?.content,
            )
        }
    }

    private fun jsonRequest(url: String, body: String, key: String): Request = Request.Builder().url(url).apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }.post(body.toRequestBody("application/json".toMediaType())).build()
    private fun <T> execute(request: Request, parse: (String) -> T): T = network.client.newCall(request).execute().use { response ->
        val body = response.body.byteStream().readBounded(MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)
        novelRequire(response.isSuccessful, NovelFailure.Code.TranslationHttp, response.code)
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
        novelRequire(result.size <= MAX_CHUNKS, NovelFailure.Code.TranslationTooManyRequests)
        return result
    }

    private fun InputStream.readBounded(max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            novelRequire(total <= max, NovelFailure.Code.TranslationResponseTooLarge)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object { private const val MAX_CHUNK_CHARS = 4_000; private const val MAX_CHUNKS = 16; private const val MAX_DOCUMENT_CHARS = 64_000; private const val MAX_RESPONSE_BYTES = 2_000_000 }
}
