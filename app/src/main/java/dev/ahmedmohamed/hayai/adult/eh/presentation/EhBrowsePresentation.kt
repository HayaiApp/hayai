package dev.ahmedmohamed.hayai.adult.eh.presentation

import dev.ahmedmohamed.hayai.adult.eh.domain.EhGalleryMetadata
import dev.ahmedmohamed.hayai.source.presentation.SourceBrowsePresentation
import dev.ahmedmohamed.hayai.source.presentation.SourceContentType

object EhBrowsePresentation {
    fun from(metadata: EhGalleryMetadata): SourceBrowsePresentation =
        SourceBrowsePresentation(
            type = metadata.category?.trim()?.takeIf(String::isNotBlank)?.let(::SourceContentType),
            uploader = metadata.uploader?.trim()?.takeIf(String::isNotBlank),
            rating = metadata.averageRating?.coerceIn(0.0, 5.0),
            language = metadata.language?.trim()?.takeIf(String::isNotBlank)?.let(::languageTag),
            pageCount = metadata.pageCount?.takeIf { it > 0 },
            postedAtMillis = metadata.postedAtMillis?.takeIf { it >= 0 },
        )

    private fun languageTag(value: String): String = LANGUAGE_TAGS[value.lowercase()] ?: value

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
