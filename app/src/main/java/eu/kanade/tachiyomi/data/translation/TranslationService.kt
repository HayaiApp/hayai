package eu.kanade.tachiyomi.data.translation

import android.content.Context
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.translation.model.LanguageCodes
import tachiyomi.domain.translation.model.TranslationProgress
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.model.TranslationTask
import tachiyomi.domain.translation.service.TranslationPreferences
import java.util.concurrent.ConcurrentHashMap

class TranslationService(
    context: Context,
    private val preferences: TranslationPreferences,
    private val engineManager: TranslationEngineManager,
    private val cache: TranslationCache,
) {
    private val store = TranslationStore(context)
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private val _queueState = MutableStateFlow(store.load())
    val queueState = _queueState.asStateFlow()

    private val _progressState = MutableStateFlow(TranslationProgress())
    val progressState = _progressState.asStateFlow()

    fun isEnabled(): Boolean =
        preferences.translationEnabled().get() && preferences.realTimeTranslation().get()

    fun getLastTargetLanguage(): String = preferences.targetLanguage().get()

    fun setTargetLanguage(language: String) {
        preferences.targetLanguage().set(language)
    }

    fun hasTranslation(chapterId: Long?, mangaId: Long?): Boolean {
        if (chapterId == null || mangaId == null) return false
        return cache.hasTranslation(mangaId, chapterId, preferences.targetLanguage().get())
    }

    fun deleteTranslation(chapterId: Long?, mangaId: Long?) {
        if (chapterId == null || mangaId == null) return
        cache.deleteTranslation(mangaId, chapterId, preferences.targetLanguage().get())
    }

    fun persistQueue(tasks: List<TranslationTask>) {
        val ordered = tasks.sortedWith(compareByDescending<TranslationTask> { it.priority }.thenBy { it.id })
        _queueState.value = ordered
        store.save(ordered)
    }

    fun clearQueue() {
        persistQueue(emptyList())
    }

    suspend fun translateChapterContent(
        content: String,
        chapterId: Long?,
        mangaId: Long?,
        forceRetranslate: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext content

        val engine = engineManager.getEngine() ?: run {
            Logger.w { "Translation requested but no configured engine is available" }
            return@withContext content
        }
        val targetLanguage = preferences.targetLanguage().get()
        val sourceLanguage = preferences.sourceLanguage().get()
        val cacheEnabled = preferences.cacheTranslations().get() && chapterId != null && mangaId != null

        if (cacheEnabled && !forceRetranslate) {
            val cached = cache.getTranslation(mangaId!!, chapterId!!, targetLanguage)
            if (TranslationCachePolicy.shouldServeCached(cached)) {
                return@withContext cached!!.translatedContent
            }
        }

        val key = "${mangaId ?: -1}:${chapterId ?: -1}:$targetLanguage:${engine.id}"
        val mutex = mutexes.getOrPut(key) { Mutex() }
        mutex.withLock {
            if (cacheEnabled && !forceRetranslate) {
                val cached = cache.getTranslation(mangaId!!, chapterId!!, targetLanguage)
                if (TranslationCachePolicy.shouldServeCached(cached)) {
                    return@withLock cached!!.translatedContent
                }
            }

            val translated = runCatching {
                translateHtmlContent(content, sourceLanguage, targetLanguage)
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                Logger.e(e) { "Chapter translation failed" }
                content
            }

            if (cacheEnabled && translated != content) {
                cache.saveTranslation(
                    mangaId = mangaId!!,
                    chapterId = chapterId!!,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    originalContent = content,
                    translatedContent = translated,
                    engineId = engine.id.toString(),
                )
            }
            translated
        }
    }

    suspend fun detectLanguage(content: String, mangaId: Long? = null): String {
        val text = TranslationHtmlUtils.extractTextFromHtml(content).take(4000)
        if (text.isBlank()) return preferences.sourceLanguage().get().takeUnless { it == "auto" } ?: "unknown"
        val cjk = text.count { it.code in 0x3040..0x30ff || it.code in 0x4e00..0x9fff }
        val hangul = text.count { it.code in 0xac00..0xd7af }
        val arabic = text.count { it.code in 0x0600..0x06ff }
        val cyrillic = text.count { it.code in 0x0400..0x04ff }
        val thai = text.count { it.code in 0x0e00..0x0e7f }
        val letters = text.count { it.isLetter() }.coerceAtLeast(1)
        return when {
            cjk > letters * 0.2 -> "ja"
            hangul > letters * 0.2 -> "ko"
            arabic > letters * 0.2 -> "ar"
            cyrillic > letters * 0.2 -> "ru"
            thai > letters * 0.2 -> "th"
            text.count { it.code < 128 && it.isLetter() } > letters * 0.8 -> "en"
            else -> preferences.sourceLanguage().get().takeUnless { it == "auto" } ?: "unknown"
        }
    }

    private suspend fun translateHtmlContent(
        content: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): String {
        if (preferences.smartAutoTranslate().get()) {
            val detected = detectLanguage(content)
            if (detected != "unknown" && TranslationHtmlUtils.languageCodesMatch(detected, targetLanguage)) {
                return content
            }
        }

        val engine = engineManager.getEngine() ?: return content
        val (htmlWithoutImages, images) = TranslationHtmlUtils.extractImages(content)
        val plainText = TranslationHtmlUtils.extractTextFromHtml(htmlWithoutImages)
        val paragraphs = TranslationHtmlUtils.splitParagraphsPreserving(plainText)
        if (paragraphs.isEmpty()) return content

        val chunks = TranslationHtmlUtils.buildChunks(paragraphs, preferences.translationChunkSize().get())
        val translatedChunks = mutableListOf<String>()
        _progressState.value = TranslationProgress(
            totalChapters = 1,
            completedChapters = 0,
            currentChapterName = null,
            currentChapterProgress = 0f,
            isRunning = true,
            isPaused = false,
            totalChunks = chunks.size,
        )

        for ((index, chunk) in chunks.withIndex()) {
            val textToTranslate = withContextualAnchor(chunk, translatedChunks)
            val result = engine.translate(listOf(textToTranslate), sourceLanguage, targetLanguage)
            val translated = when (result) {
                is TranslationResult.Success -> result.translatedTexts.firstOrNull().orEmpty()
                is TranslationResult.Error -> {
                    Logger.w { "Translation error from ${engine.name}: ${result.message}" }
                    chunk
                }
            }
            translatedChunks += TranslationHtmlUtils.stripContextLeakage(translated)
            _progressState.value = TranslationProgress(
                totalChapters = 1,
                completedChapters = 0,
                currentChapterName = null,
                currentChapterProgress = (index + 1).toFloat() / chunks.size,
                isRunning = true,
                isPaused = false,
                currentChunkIndex = index + 1,
                totalChunks = chunks.size,
            )
        }

        _progressState.value = TranslationProgress(totalChapters = 1, completedChapters = 1)
        val translatedHtml = TranslationHtmlUtils.wrapTextInHtml(translatedChunks.joinToString("\n\n"))
        return TranslationHtmlUtils.reinsertImages(translatedHtml, images)
    }

    private fun withContextualAnchor(chunk: String, translatedChunks: List<String>): String {
        if (!preferences.contextualAnchoringEnabled().get() || translatedChunks.isEmpty()) return chunk
        val anchorParagraphs = preferences.contextualAnchoringParagraphs().get().coerceAtLeast(1)
        val context = translatedChunks
            .flatMap { TranslationHtmlUtils.splitParagraphsPreserving(it) }
            .takeLast(anchorParagraphs)
            .joinToString("\n\n")
        if (context.isBlank()) return chunk
        return """
            === PREVIOUS TRANSLATED CONTEXT (do not repeat) ===
            $context

            === TEXT TO TRANSLATE (${LanguageCodes.displayName(preferences.targetLanguage().get())}) ===
            $chunk
        """.trimIndent()
    }

    companion object {
        const val PRIORITY_MANUAL_READ = 100
        const val PRIORITY_AHEAD = 10
    }
}
