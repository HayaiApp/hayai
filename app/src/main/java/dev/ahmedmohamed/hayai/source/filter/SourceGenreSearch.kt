package dev.ahmedmohamed.hayai.source.filter

import dev.ahmedmohamed.hayai.adult.eh.domain.EhCategory
import dev.ahmedmohamed.hayai.adult.eh.source.EhCategoriesFilter
import dev.ahmedmohamed.hayai.source.SourceCapabilityRegistry
import dev.ahmedmohamed.hayai.source.SourceFamily
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import java.util.Locale

object SourceGenreSearch {
    fun usesGalleryTags(source: Source): Boolean =
        SourceCapabilityRegistry.descriptor(source).family in galleryFamilies

    fun apply(source: Source, filters: FilterList, tags: List<String>): Boolean {
        if (!usesGalleryTags(source) || tags.isEmpty()) return false
        category(tags)?.let { category ->
            val categories = filters.filterIsInstance<EhCategoriesFilter>().firstOrNull() ?: return false
            categories.state.forEach { it.state = it.category != category }
            return true
        }
        val completion = filters.filterIsInstance<SourceTagCompletionFilter>().firstOrNull() ?: return false
        return runCatching { completion.setSelections(tags); true }.getOrDefault(false)
    }

    internal fun category(tags: List<String>): EhCategory? {
        val value = tags.singleOrNull()?.trim()?.lowercase(Locale.ROOT)?.replace(" ", "")?.replace("-", "") ?: return null
        return EhCategory.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == value }
    }

    private val galleryFamilies = setOf(SourceFamily.EHentai, SourceFamily.ExHentai)
}
