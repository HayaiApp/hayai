package dev.ahmedmohamed.hayai.adult

import dev.ahmedmohamed.hayai.source.SourceCapability
import dev.ahmedmohamed.hayai.source.SourceCapabilityRegistry
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.Source

object LewdClassifier {
    private val adultTags =
        setOf(
            "18+",
            "adult",
            "erotica",
            "hentai",
            "lewd",
            "mature",
            "nsfw",
            "pornographic",
            "smut",
        )

    fun isLewd(
        manga: Manga,
        source: Source,
    ): Boolean {
        val genres = manga.getGenres().orEmpty()
        val descriptor = SourceCapabilityRegistry.descriptor(source)
        if (descriptor.capabilities.contains(SourceCapability.Adult) && genres.any(::isNonHentai)) {
            return false
        }
        return descriptor.capabilities.contains(SourceCapability.Adult) ||
            genres.any { tag -> adultTags.any { marker -> tag.contains(marker, ignoreCase = true) } }
    }

    private fun isNonHentai(tag: String): Boolean = tag.contains("non-h", ignoreCase = true)
}
