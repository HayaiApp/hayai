package eu.kanade.tachiyomi.ui.reader.viewer.text

import org.jsoup.Jsoup

data class NovelDisplayedTranslationState(
    val translated: Boolean,
    val language: String?,
)

data class NovelDisplayedQuoteText(
    val displayedContent: String,
    val originalContent: String?,
    val translatedContent: String?,
    val language: String?,
)

object NovelDisplayedText {

    fun translationState(
        translationEnabled: Boolean,
        originalContent: String,
        displayedContent: String,
        language: String?,
    ): NovelDisplayedTranslationState {
        val translated = translationEnabled &&
            comparableText(originalContent).isNotBlank() &&
            comparableText(displayedContent).isNotBlank() &&
            comparableText(originalContent) != comparableText(displayedContent)

        return NovelDisplayedTranslationState(
            translated = translated,
            language = language?.takeIf { translated }?.takeUnless { it.isBlank() },
        )
    }

    fun quoteText(
        displayedText: String,
        originalParagraph: String?,
        translationState: NovelDisplayedTranslationState?,
    ): NovelDisplayedQuoteText {
        val displayed = displayedText.trim()
        val translated = translationState?.translated == true
        val original = originalParagraph
            ?.trim()
            ?.takeIf { translated }
            ?.takeUnless { comparableText(it) == comparableText(displayed) }
            ?.takeUnless { it.isBlank() }

        return NovelDisplayedQuoteText(
            displayedContent = displayed,
            originalContent = original,
            translatedContent = displayed.takeIf { translated && it.isNotBlank() },
            language = translationState?.language?.takeIf { translated },
        )
    }

    private fun comparableText(value: String): String {
        val visibleText = runCatching { Jsoup.parse(value).body().text() }
            .getOrDefault(value)
        return visibleText
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
