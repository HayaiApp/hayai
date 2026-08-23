package dev.ahmedmohamed.hayai.adult.eh.presentation

import dev.ahmedmohamed.hayai.adult.eh.domain.EhGalleryMetadata
import dev.ahmedmohamed.hayai.source.presentation.SourceBrowsePresentation
import dev.ahmedmohamed.hayai.source.presentation.SourceBrowseLayout
import dev.ahmedmohamed.hayai.source.presentation.SourceContentType

object EhBrowsePresentation {
    fun from(
        metadata: EhGalleryMetadata,
        enhancedList: Boolean = false,
    ): SourceBrowsePresentation =
        SourceBrowsePresentation(
            type = metadata.category?.trim()?.takeIf(String::isNotBlank)?.let(::SourceContentType),
            uploader = metadata.uploader?.trim()?.takeIf(String::isNotBlank),
            rating = metadata.averageRating?.coerceIn(0.0, 5.0),
            language = browseLanguage(metadata),
            pageCount = metadata.pageCount?.takeIf { it > 0 },
            postedAtMillis = metadata.postedAtMillis?.takeIf { it >= 0 },
            layout = if (enhancedList) SourceBrowseLayout.DetailedList else SourceBrowseLayout.Default,
        )

    private fun languageTag(value: String): String = LANGUAGE_TAGS[value.lowercase()] ?: value

    private fun browseLanguage(metadata: EhGalleryMetadata): String? {
        val declared = metadata.language
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: metadata.tags
                .firstOrNull { it.namespace.equals("language", ignoreCase = true) }
                ?.name
                ?.trim()
                ?.takeIf(String::isNotBlank)
        if (declared != null) return languageTag(declared)

        // E-Hentai omits tag metadata in some browse layouts. Only infer scripts
        // that identify a language confidently; Latin titles intentionally stay
        // unbadged because they are commonly translated or romanized.
        return when {
            metadata.title.any { it in '\u3040'..'\u30ff' } -> "ja"
            metadata.title.any { it in '\uac00'..'\ud7af' } -> "ko"
            metadata.title.any { it in '\u3400'..'\u9fff' } -> "zh"
            else -> null
        }
    }

    private val LANGUAGE_TAGS = mapOf(
        "japanese" to "ja",
        "english" to "en",
        "chinese" to "zh",
        "dutch" to "nl",
        "french" to "fr",
        "german" to "de",
        "hungarian" to "hu",
        "italian" to "it",
        "korean" to "ko",
        "polish" to "pl",
        "portuguese" to "pt",
        "russian" to "ru",
        "spanish" to "es",
        "thai" to "th",
        "vietnamese" to "vi",
    )
}
