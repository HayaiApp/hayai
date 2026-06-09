package eu.kanade.tachiyomi.data.translation.engine

import eu.kanade.tachiyomi.data.translation.TranslationEngineManager
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.domain.translation.model.LanguageCodes
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.service.TranslationPreferences
import yokai.util.koin.get
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val JSON = "application/json; charset=utf-8".toMediaType()

abstract class BaseEngine(
    private val network: NetworkHelper = get(),
    protected val preferences: TranslationPreferences = get(),
) : TranslationEngine {
    override val supportedLanguages: List<Pair<String, String>> = LanguageCodes.common

    protected val client: OkHttpClient
        get() = network.client.newBuilder()
            .callTimeout(preferences.translationTimeoutMs().get(), TimeUnit.MILLISECONDS)
            .readTimeout(preferences.translationTimeoutMs().get(), TimeUnit.MILLISECONDS)
            .build()

    protected suspend fun executeJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw TranslationHttpException(response.code, body.ifBlank { response.message })
            }
            JSONObject(body)
        }
    }

    protected fun jsonPost(url: String, headers: Headers = Headers.Builder().build(), body: JSONObject): Request {
        return Request.Builder()
            .url(url)
            .headers(headers)
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    protected fun error(e: Throwable): TranslationResult.Error {
        if (e is TranslationHttpException) {
            val code = when (e.statusCode) {
                401, 403 -> TranslationResult.ErrorCode.API_KEY_INVALID
                402, 456 -> TranslationResult.ErrorCode.QUOTA_EXCEEDED
                429 -> TranslationResult.ErrorCode.RATE_LIMITED
                500, 502, 503, 504 -> TranslationResult.ErrorCode.SERVICE_UNAVAILABLE
                else -> TranslationResult.ErrorCode.UNKNOWN
            }
            return TranslationResult.Error(e.message ?: "HTTP ${e.statusCode}", code)
        }
        return TranslationResult.Error(e.message ?: "Translation failed", TranslationResult.ErrorCode.UNKNOWN)
    }
}

private class TranslationHttpException(
    val statusCode: Int,
    override val message: String,
) : RuntimeException(message)

class LibreTranslateEngine : BaseEngine() {
    override val id = TranslationEngineManager.ENGINE_LIBRE_TRANSLATE
    override val name = "LibreTranslate"

    override fun isConfigured(): Boolean = preferences.libreTranslateUrl().get().isNotBlank()

    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): TranslationResult {
        return try {
            val body = JSONObject()
                .put("q", JSONArray(texts))
                .put("source", if (sourceLanguage == "auto") "auto" else sourceLanguage)
                .put("target", targetLanguage)
                .put("format", "text")
            preferences.libreTranslateApiKey().get().takeIf { it.isNotBlank() }?.let { body.put("api_key", it) }
            val obj = executeJson(jsonPost(preferences.libreTranslateUrl().get(), body = body))
            val arr = obj.optJSONArray("translatedText")
            val translated = when {
                arr != null -> List(arr.length()) { arr.getString(it) }
                obj.has("translatedText") -> listOf(obj.getString("translatedText"))
                else -> emptyList()
            }
            TranslationResult.Success(translated.ifEmpty { texts })
        } catch (e: Throwable) {
            error(e)
        }
    }
}

abstract class OpenAiCompatibleEngine : BaseEngine() {
    protected abstract val url: String
    protected abstract val apiKey: String
    protected abstract val model: String

    override fun isConfigured(): Boolean = url.isNotBlank() && model.isNotBlank() && (apiKey.isNotBlank() || this is OllamaTranslateEngine)

    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): TranslationResult {
        if (!isConfigured()) {
            return TranslationResult.Error("API key or model is missing", TranslationResult.ErrorCode.API_KEY_MISSING)
        }
        return try {
            val targetName = LanguageCodes.displayName(targetLanguage)
            val sourceName = if (sourceLanguage == "auto") "the detected language" else LanguageCodes.displayName(sourceLanguage)
            val prompt = texts.joinToString("\n\n---\n\n")
            val system = preferences.openAiSystemPrompt().get().ifBlank {
                "You are a professional fiction translator. Translate from $sourceName to $targetName. Preserve paragraph order, punctuation, names, HTML placeholders like [IMG_PLACEHOLDER_0], and output only the translation."
            }
            val user = preferences.openAiUserPrompt().get().ifBlank {
                "Translate the following text from {SOURCE_LANG} to {TARGET_LANG}. Keep sections separated by ---.\n\n{TEXT}"
            }
                .replace("{SOURCE_LANG}", sourceName)
                .replace("{TARGET_LANG}", targetName)
                .replace("{TEXT}", prompt)

            val body = JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)),
                )
                .put("temperature", 0.2)
            val headers = Headers.Builder()
                .add("Content-Type", "application/json")
                .apply { if (apiKey.isNotBlank()) add("Authorization", "Bearer $apiKey") }
                .build()
            val obj = executeJson(jsonPost(url, headers, body))
            val content = obj.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            val translated = content.split(Regex("\\n\\s*---\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
            TranslationResult.Success(if (translated.size == texts.size) translated else listOf(content))
        } catch (e: Throwable) {
            error(e)
        }
    }
}

class OpenAITranslateEngine : OpenAiCompatibleEngine() {
    override val id = TranslationEngineManager.ENGINE_OPENAI
    override val name = "OpenAI"
    override val url: String get() = preferences.openAiBaseUrl().get()
    override val apiKey: String get() = preferences.openAiApiKey().get()
    override val model: String get() = preferences.openAiModel().get()
}

class DeepSeekTranslateEngine : OpenAiCompatibleEngine() {
    override val id = TranslationEngineManager.ENGINE_DEEPSEEK
    override val name = "DeepSeek"
    override val url = "https://api.deepseek.com/chat/completions"
    override val apiKey: String get() = preferences.deepSeekApiKey().get()
    override val model = "deepseek-chat"
}

class NvidiaNimTranslateEngine : OpenAiCompatibleEngine() {
    override val id = TranslationEngineManager.ENGINE_NVIDIA_NIM
    override val name = "NVIDIA NIM"
    override val url: String
        get() = preferences.nvidiaNimBaseUrl().get().trimEnd('/') + "/v1/chat/completions"
    override val apiKey: String get() = preferences.nvidiaNimApiKey().get()
    override val model: String get() = preferences.nvidiaNimModel().get()
}

class OllamaTranslateEngine : OpenAiCompatibleEngine() {
    override val id = TranslationEngineManager.ENGINE_OLLAMA
    override val name = "Ollama"
    override val isOffline = true
    override val isRateLimited = false
    override val url: String
        get() = preferences.ollamaUrl().get().trimEnd('/') + "/v1/chat/completions"
    override val apiKey = ""
    override val model: String get() = preferences.ollamaModel().get()
}

class GeminiTranslateEngine : BaseEngine() {
    override val id = TranslationEngineManager.ENGINE_GEMINI
    override val name = "Gemini"

    override fun isConfigured(): Boolean = preferences.geminiApiKey().get().isNotBlank()

    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): TranslationResult {
        val key = preferences.geminiApiKey().get()
        if (key.isBlank()) return TranslationResult.Error("Gemini API key missing", TranslationResult.ErrorCode.API_KEY_MISSING)
        return try {
            val targetName = LanguageCodes.displayName(targetLanguage)
            val prompt = "Translate to $targetName. Preserve [IMG_PLACEHOLDER_N] tokens and separate items with ---.\n\n" +
                texts.joinToString("\n\n---\n\n")
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt)),
                        ),
                    ),
                )
            val url = "https://generativelanguage.googleapis.com/v1beta/models/${preferences.geminiModel().get()}:generateContent?key=$key"
            val obj = executeJson(jsonPost(url, body = body))
            val content = obj.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
            val parts = content.split(Regex("\\n\\s*---\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
            TranslationResult.Success(if (parts.size == texts.size) parts else listOf(content.trim()))
        } catch (e: Throwable) {
            error(e)
        }
    }
}

class DeepLTranslateEngine : BaseEngine() {
    override val id = TranslationEngineManager.ENGINE_DEEPL
    override val name = "DeepL"

    override fun isConfigured(): Boolean = preferences.deepLApiKey().get().isNotBlank()

    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): TranslationResult {
        val key = preferences.deepLApiKey().get()
        if (key.isBlank()) return TranslationResult.Error("DeepL API key missing", TranslationResult.ErrorCode.API_KEY_MISSING)
        return try {
            val body = StringBuilder()
            texts.forEach { body.append("&text=").append(enc(it)) }
            body.append("&target_lang=").append(enc(targetLanguage.uppercase()))
            if (sourceLanguage != "auto") body.append("&source_lang=").append(enc(sourceLanguage.uppercase()))
            val request = Request.Builder()
                .url(if (key.endsWith(":fx")) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate")
                .addHeader("Authorization", "DeepL-Auth-Key $key")
                .post(body.removePrefix("&").toString().toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val obj = executeJson(request)
            val arr = obj.getJSONArray("translations")
            TranslationResult.Success(List(arr.length()) { arr.getJSONObject(it).getString("text") })
        } catch (e: Throwable) {
            error(e)
        }
    }
}

class GoogleCloudTranslateEngine : BaseEngine() {
    override val id = TranslationEngineManager.ENGINE_GOOGLE_CLOUD
    override val name = "Google Cloud"

    override fun isConfigured(): Boolean = preferences.googleApiKey().get().isNotBlank()

    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): TranslationResult {
        val key = preferences.googleApiKey().get()
        if (key.isBlank()) return TranslationResult.Error("Google API key missing", TranslationResult.ErrorCode.API_KEY_MISSING)
        return try {
            val body = JSONObject()
                .put("q", JSONArray(texts))
                .put("target", targetLanguage)
                .put("format", "text")
            if (sourceLanguage != "auto") body.put("source", sourceLanguage)
            val obj = executeJson(jsonPost("https://translation.googleapis.com/language/translate/v2?key=$key", body = body))
            val arr = obj.getJSONObject("data").getJSONArray("translations")
            TranslationResult.Success(
                List(arr.length()) { arr.getJSONObject(it).getString("translatedText") },
                arr.optJSONObject(0)?.optString("detectedSourceLanguage")?.takeIf { it.isNotBlank() },
            )
        } catch (e: Throwable) {
            error(e)
        }
    }
}

class GoogleTranslateScraperEngine : BaseEngine() {
    override val id = TranslationEngineManager.ENGINE_GOOGLE_SCRAPER
    override val name = "Google Translate"

    override fun isConfigured(): Boolean = true

    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): TranslationResult {
        return try {
            val translated = texts.map { text ->
                val url = "https://translate.googleapis.com/translate_a/single"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("client", "gtx")
                    .addQueryParameter("sl", sourceLanguage)
                    .addQueryParameter("tl", targetLanguage)
                    .addQueryParameter("dt", "t")
                    .addQueryParameter("q", text)
                    .build()
                withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) throw TranslationHttpException(response.code, response.message)
                        val root = JSONArray(response.body.string())
                        val sentences = root.getJSONArray(0)
                        buildString {
                            for (i in 0 until sentences.length()) {
                                append(sentences.getJSONArray(i).optString(0))
                            }
                        }
                    }
                }
            }
            TranslationResult.Success(translated)
        } catch (e: Throwable) {
            error(e)
        }
    }
}

class HuggingFaceTranslateEngine : BaseEngine() {
    override val id = TranslationEngineManager.ENGINE_HUGGING_FACE
    override val name = "Hugging Face"

    override fun isConfigured(): Boolean = preferences.huggingFaceApiKey().get().isNotBlank()

    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): TranslationResult {
        val key = preferences.huggingFaceApiKey().get()
        if (key.isBlank()) return TranslationResult.Error("Hugging Face API key missing", TranslationResult.ErrorCode.API_KEY_MISSING)
        return try {
            val model = "Helsinki-NLP/opus-mt-${if (sourceLanguage == "auto") "mul" else sourceLanguage}-$targetLanguage"
            val headers = Headers.Builder().add("Authorization", "Bearer $key").build()
            val translated = texts.map { text ->
                val obj = executeJson(
                    jsonPost(
                        "https://api-inference.huggingface.co/models/$model",
                        headers,
                        JSONObject().put("inputs", text),
                    ),
                )
                obj.optString("translation_text").ifBlank { text }
            }
            TranslationResult.Success(translated)
        } catch (e: Throwable) {
            error(e)
        }
    }
}

class SystranTranslateEngine : BaseEngine() {
    override val id = TranslationEngineManager.ENGINE_SYSTRAN
    override val name = "SYSTRAN"

    override fun isConfigured(): Boolean = preferences.systranApiKey().get().isNotBlank()

    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): TranslationResult {
        val key = preferences.systranApiKey().get()
        if (key.isBlank()) return TranslationResult.Error("SYSTRAN API key missing", TranslationResult.ErrorCode.API_KEY_MISSING)
        return try {
            val headers = Headers.Builder().add("Authorization", "Key $key").build()
            val body = JSONObject()
                .put("input", JSONArray(texts))
                .put("target", targetLanguage)
            if (sourceLanguage != "auto") body.put("source", sourceLanguage)
            val obj = executeJson(jsonPost("https://api-translate.systran.net/translation/text/translate", headers, body))
            val arr = obj.getJSONArray("outputs")
            TranslationResult.Success(List(arr.length()) { arr.getJSONObject(it).getString("output") })
        } catch (e: Throwable) {
            error(e)
        }
    }
}

class CustomHttpTranslateEngine : BaseEngine() {
    override val id = TranslationEngineManager.ENGINE_CUSTOM_HTTP
    override val name = "Custom HTTP"

    override fun isConfigured(): Boolean = preferences.customHttpUrl().get().isNotBlank()

    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): TranslationResult {
        val url = preferences.customHttpUrl().get()
        if (url.isBlank()) return TranslationResult.Error("Custom HTTP URL missing", TranslationResult.ErrorCode.API_KEY_MISSING)
        return try {
            val apiKey = preferences.customHttpApiKey().get()
            val textArray = JSONArray(texts).toString()
            val bodyText = preferences.customHttpRequestTemplate().get()
                .replace("{texts}", textArray)
                .replace("{text}", texts.firstOrNull().orEmpty())
                .replace("{text_esc}", texts.firstOrNull().orEmpty().jsonEscaped())
                .replace("{source}", sourceLanguage)
                .replace("{target}", targetLanguage)
                .replace("{source_name}", LanguageCodes.displayName(sourceLanguage))
                .replace("{target_name}", LanguageCodes.displayName(targetLanguage))
            val headers = Headers.Builder()
                .add("Content-Type", "application/json")
                .apply {
                    if (apiKey.isNotBlank()) add("Authorization", "Bearer $apiKey")
                    preferences.customHttpHeaders().get()
                        .split(';', '\n')
                        .map { it.trim() }
                        .filter { ':' in it }
                        .forEach {
                            val name = it.substringBefore(':').trim()
                            val value = it.substringAfter(':').trim().replace("{apiKey}", apiKey)
                            set(name, value)
                        }
                }
                .build()
            val request = if (preferences.customHttpMethod().get().equals("GET", ignoreCase = true)) {
                Request.Builder().url(url).headers(headers).get().build()
            } else {
                Request.Builder().url(url).headers(headers).post(bodyText.toRequestBody(JSON)).build()
            }
            val obj = executeJson(request)
            val extracted = extractPath(obj, preferences.customHttpResponsePath().get())
            TranslationResult.Success(extracted.ifEmpty { texts })
        } catch (e: Throwable) {
            error(e)
        }
    }

    private fun extractPath(obj: JSONObject, path: String): List<String> {
        var current: Any? = obj
        path.split('.').forEach { part ->
            val name = part.substringBefore('[')
            current = when (val node = current) {
                is JSONObject -> node.opt(name)
                else -> null
            }
            Regex("\\[(\\d+)]").find(part)?.groupValues?.get(1)?.toIntOrNull()?.let { index ->
                current = (current as? JSONArray)?.opt(index)
            }
        }
        return when (val node = current) {
            is JSONArray -> List(node.length()) { node.optString(it) }.filter { it.isNotBlank() }
            is JSONObject -> listOfNotNull(node.optString("text").takeIf { it.isNotBlank() })
            is String -> listOf(node)
            else -> emptyList()
        }
    }
}

private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun String.jsonEscaped(): String = JSONObject.quote(this).removeSurrounding("\"")
