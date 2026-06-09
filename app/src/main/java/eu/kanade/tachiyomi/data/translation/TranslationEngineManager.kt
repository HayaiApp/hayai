package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.translation.engine.CustomHttpTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.DeepLTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.DeepSeekTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.GeminiTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.GoogleCloudTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.GoogleTranslateScraperEngine
import eu.kanade.tachiyomi.data.translation.engine.HuggingFaceTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.LibreTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.NvidiaNimTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.OllamaTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.OpenAITranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.SystranTranslateEngine
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.service.TranslationPreferences
import yokai.util.koin.get

class TranslationEngineManager(
    private val preferences: TranslationPreferences = get(),
) {
    val engines: List<TranslationEngine> by lazy {
        listOf(
            LibreTranslateEngine(),
            OpenAITranslateEngine(),
            NvidiaNimTranslateEngine(),
            DeepSeekTranslateEngine(),
            OllamaTranslateEngine(),
            HuggingFaceTranslateEngine(),
            SystranTranslateEngine(),
            DeepLTranslateEngine(),
            GoogleCloudTranslateEngine(),
            GeminiTranslateEngine(),
            GoogleTranslateScraperEngine(),
            CustomHttpTranslateEngine(),
        )
    }

    fun getSelectedEngine(): TranslationEngine {
        val selectedId = preferences.selectedEngineId().get()
        return engines.firstOrNull { it.id == selectedId } ?: engines.first()
    }

    fun getEngineById(id: Long): TranslationEngine? = engines.firstOrNull { it.id == id }

    fun setSelectedEngine(engine: TranslationEngine) {
        preferences.selectedEngineId().set(engine.id)
    }

    fun getEngine(): TranslationEngine? = getSelectedEngine().takeIf { it.isConfigured() }

    companion object {
        const val ENGINE_LIBRE_TRANSLATE = 1L
        const val ENGINE_OPENAI = 2L
        const val ENGINE_DEEPSEEK = 3L
        const val ENGINE_OLLAMA = 4L
        const val ENGINE_SYSTRAN = 5L
        const val ENGINE_DEEPL = 6L
        const val ENGINE_GOOGLE_CLOUD = 7L
        const val ENGINE_GEMINI = 8L
        const val ENGINE_GOOGLE_SCRAPER = 10L
        const val ENGINE_NVIDIA_NIM = 11L
        const val ENGINE_HUGGING_FACE = 12L
        const val ENGINE_CUSTOM_HTTP = 13L
    }
}
