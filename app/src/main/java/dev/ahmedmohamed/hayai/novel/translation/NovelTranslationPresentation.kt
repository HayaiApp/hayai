package dev.ahmedmohamed.hayai.novel.translation

internal data class NovelTranslationPresentation(
    val languageTag: String,
    val paragraphs: List<String>,
) {
    val text: String = paragraphs.joinToString("\n\n")

    companion object {
        fun from(
            translatedText: String,
            languageTag: String,
        ): NovelTranslationPresentation =
            NovelTranslationPresentation(
                languageTag = languageTag,
                paragraphs =
                    translatedText
                        .lineSequence()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .toList()
                        .ifEmpty { listOf(translatedText.trim()) }
                        .filter(String::isNotBlank),
            )
    }
}
