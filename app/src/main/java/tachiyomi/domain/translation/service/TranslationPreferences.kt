package tachiyomi.domain.translation.service

import eu.kanade.tachiyomi.core.preference.PreferenceStore

class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun translationEnabled() = preferenceStore.getBoolean("translation_enabled", false)
    fun selectedEngineId() = preferenceStore.getLong("translation_engine_id", 10L)
    fun sourceLanguage() = preferenceStore.getString("translation_source_language", "auto")
    fun targetLanguage() = preferenceStore.getString("translation_target_language", "en")
    fun smartAutoTranslate() = preferenceStore.getBoolean("pref_auto_translate", false)
    fun realTimeTranslation() = preferenceStore.getBoolean("translation_realtime", false)
    fun cacheTranslations() = preferenceStore.getBoolean("translation_cache_enabled", true)
    fun rateLimitDelayMs() = preferenceStore.getInt("translation_rate_limit_delay", 3000)
    fun translationTimeoutMs() = preferenceStore.getLong("translation_timeout_ms", 120000L)
    fun translationChunkSize() = preferenceStore.getInt("translation_chunk_size", 50)
    fun contextualAnchoringEnabled() = preferenceStore.getBoolean("translation_contextual_anchoring_enabled", true)
    fun contextualAnchoringParagraphs() = preferenceStore.getInt("translation_contextual_anchoring_paragraphs", 2)

    fun openAiApiKey() = preferenceStore.getString("translation_openai_api_key", "")
    fun openAiBaseUrl() = preferenceStore.getString("translation_openai_base_url", "https://api.openai.com/v1/chat/completions")
    fun openAiModel() = preferenceStore.getString("translation_openai_model", "gpt-4o-mini")
    fun openAiSystemPrompt() = preferenceStore.getString("translation_openai_system_prompt", "")
    fun openAiUserPrompt() = preferenceStore.getString("translation_openai_user_prompt", "")

    fun deepSeekApiKey() = preferenceStore.getString("translation_deepseek_api_key", "")
    fun geminiApiKey() = preferenceStore.getString("translation_gemini_api_key", "")
    fun geminiModel() = preferenceStore.getString("translation_gemini_model", "gemini-2.0-flash")

    fun nvidiaNimBaseUrl() = preferenceStore.getString("translation_nvidia_nim_base_url", "http://localhost:8000")
    fun nvidiaNimApiKey() = preferenceStore.getString("translation_nvidia_nim_api_key", "")
    fun nvidiaNimModel() = preferenceStore.getString("translation_nvidia_nim_model", "")

    fun ollamaUrl() = preferenceStore.getString("translation_ollama_url", "http://localhost:11434")
    fun ollamaModel() = preferenceStore.getString("translation_ollama_model", "llama3")
    fun ollamaPrompt() = preferenceStore.getString("translation_ollama_prompt", "")

    fun libreTranslateUrl() = preferenceStore.getString("translation_libretranslate_url", "https://libretranslate.com/translate")
    fun libreTranslateApiKey() = preferenceStore.getString("translation_libretranslate_api_key", "")
    fun deepLApiKey() = preferenceStore.getString("translation_deepl_api_key", "")
    fun googleApiKey() = preferenceStore.getString("translation_google_api_key", "")
    fun systranApiKey() = preferenceStore.getString("translation_systran_api_key", "")
    fun huggingFaceApiKey() = preferenceStore.getString("translation_huggingface_api_key", "")

    fun customHttpUrl() = preferenceStore.getString("translation_custom_http_url", "")
    fun customHttpApiKey() = preferenceStore.getString("translation_custom_http_api_key", "")
    fun customHttpMethod() = preferenceStore.getString("translation_custom_http_method", "POST")
    fun customHttpHeaders() = preferenceStore.getString("translation_custom_http_headers", "")
    fun customHttpRequestTemplate() = preferenceStore.getString(
        "translation_custom_http_request_template",
        """{"q": {texts}, "source": "{source}", "target": "{target}"}""",
    )
    fun customHttpResponsePath() = preferenceStore.getString("translation_custom_http_response_path", "translatedText")
}
