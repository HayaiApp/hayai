package tachiyomi.domain.translation.model

data class TranslatedChapter(
    val chapterId: Long,
    val mangaId: Long,
    val targetLanguage: String,
    val engineId: String,
    val translatedContent: String,
    val dateTranslated: Long = System.currentTimeMillis(),
)

data class TranslationTask(
    val id: Long = 0,
    val chapterId: Long,
    val mangaId: Long,
    val chapterName: String = "",
    val mangaTitle: String = "",
    val sourceLanguage: String,
    val targetLanguage: String,
    val engineId: Long,
    val priority: Int = 0,
    val status: TranslationStatus = TranslationStatus.QUEUED,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val forceRetranslate: Boolean = false,
)

enum class TranslationStatus {
    QUEUED,
    TRANSLATING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class TranslationProgress(
    val totalChapters: Int = 0,
    val completedChapters: Int = 0,
    val currentChapterName: String? = null,
    val currentChapterProgress: Float = 0f,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val currentChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val isCancelling: Boolean = false,
) {
    val overallProgress: Float
        get() = if (totalChapters > 0) {
            (completedChapters + currentChapterProgress) / totalChapters
        } else {
            0f
        }
}

interface TranslationEngine {
    val id: Long
    val name: String
    val isOffline: Boolean
        get() = false
    val isRateLimited: Boolean
        get() = true
    val supportedLanguages: List<Pair<String, String>>

    fun isConfigured(): Boolean

    suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationResult

    suspend fun translateSingle(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationResult = translate(listOf(text), sourceLanguage, targetLanguage)
}

sealed interface TranslationResult {
    data class Success(
        val translatedTexts: List<String>,
        val detectedLanguage: String? = null,
    ) : TranslationResult

    data class Error(
        val message: String,
        val code: ErrorCode = ErrorCode.UNKNOWN,
    ) : TranslationResult

    enum class ErrorCode {
        API_KEY_MISSING,
        API_KEY_INVALID,
        RATE_LIMITED,
        QUOTA_EXCEEDED,
        SERVICE_UNAVAILABLE,
        NETWORK,
        PARSE,
        UNKNOWN,
    }
}

object LanguageCodes {
    val common: List<Pair<String, String>> = listOf(
        "auto" to "Detect automatically",
        "en" to "English",
        "ar" to "Arabic",
        "bg" to "Bulgarian",
        "zh" to "Chinese",
        "cs" to "Czech",
        "da" to "Danish",
        "nl" to "Dutch",
        "fi" to "Finnish",
        "fr" to "French",
        "de" to "German",
        "el" to "Greek",
        "hi" to "Hindi",
        "id" to "Indonesian",
        "it" to "Italian",
        "ja" to "Japanese",
        "ko" to "Korean",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "es" to "Spanish",
        "sv" to "Swedish",
        "th" to "Thai",
        "tr" to "Turkish",
        "uk" to "Ukrainian",
        "vi" to "Vietnamese",
    )

    fun displayName(code: String): String =
        common.firstOrNull { it.first.equals(code, ignoreCase = true) }?.second ?: code.uppercase()
}
