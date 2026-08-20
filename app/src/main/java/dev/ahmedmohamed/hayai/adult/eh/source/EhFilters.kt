package dev.ahmedmohamed.hayai.adult.eh.source

import dev.ahmedmohamed.hayai.adult.eh.domain.EhCategory
import dev.ahmedmohamed.hayai.adult.eh.domain.EhJumpTarget
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSearchSpec
import dev.ahmedmohamed.hayai.adult.eh.domain.EhTagMode
import dev.ahmedmohamed.hayai.adult.eh.domain.EhTagTerm
import dev.ahmedmohamed.hayai.adult.eh.domain.EhToplist
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal class EhToplistFilter : Filter.Select<String>("Toplist", arrayOf("None", "All time", "Past year", "Past month", "Yesterday"))
internal class EhWatchedFilter : Filter.CheckBox("Watched list")
internal class EhCategoryFilter(val category: EhCategory) : Filter.CheckBox(category.name.replace("Cg", " CG").replace("NonH", "Non-H"))
internal class EhCategoriesFilter : Filter.Group<EhCategoryFilter>("Categories to exclude", EhCategory.entries.map(::EhCategoryFilter))
internal class EhTagFilter : Filter.Text("Tags (supports namespace, -, ~, and OR)")
internal class EhExpungedFilter : Filter.CheckBox("Browse expunged galleries")
internal class EhTorrentFilter : Filter.CheckBox("Require gallery torrent")
internal class EhRatingFilter : Filter.Select<String>("Minimum rating", arrayOf("Any", "2", "3", "4", "5"))
internal class EhMinimumPagesFilter : Filter.Text("Minimum pages")
internal class EhMaximumPagesFilter : Filter.Text("Maximum pages")
internal class EhDisableLanguageFilter : Filter.CheckBox("Disable language filter")
internal class EhDisableUploaderFilter : Filter.CheckBox("Disable uploader filter")
internal class EhDisableTagFilter : Filter.CheckBox("Disable tag filter")
internal class EhReverseFilter : Filter.CheckBox("Reverse results")
internal class EhJumpFilter : Filter.Text("Jump or seek (date, year, 7d, 2w, 3m)")

internal fun ehFilterList(): FilterList = FilterList(
    Filter.Header("A selected toplist ignores the query and other filters"),
    EhToplistFilter(),
    EhWatchedFilter(),
    EhCategoriesFilter(),
    EhTagFilter(),
    EhExpungedFilter(),
    EhTorrentFilter(),
    EhRatingFilter(),
    EhMinimumPagesFilter(),
    EhMaximumPagesFilter(),
    EhDisableLanguageFilter(),
    EhDisableUploaderFilter(),
    EhDisableTagFilter(),
    EhReverseFilter(),
    EhJumpFilter(),
)

internal fun FilterList.toEhSpec(query: String): EhSearchSpec {
    fun <T : Filter<*>> one(type: Class<T>): T? = firstOrNull { type.isInstance(it) }?.let(type::cast)
    val toplist = EhToplist.entries.getOrElse(one(EhToplistFilter::class.java)?.state ?: 0) { EhToplist.None }
    if (toplist != EhToplist.None) return EhSearchSpec(toplist = toplist)
    val minimum = one(EhMinimumPagesFilter::class.java)?.state?.trim()?.takeIf(String::isNotEmpty)?.toIntOrNull()
    val maximum = one(EhMaximumPagesFilter::class.java)?.state?.trim()?.takeIf(String::isNotEmpty)?.toIntOrNull()
    val jump = one(EhJumpFilter::class.java)?.state?.trim()?.takeIf(String::isNotEmpty)?.let(EhJumpTarget::parse)
    return EhSearchSpec(
        query = query,
        tags = parseTags(one(EhTagFilter::class.java)?.state.orEmpty()),
        watched = one(EhWatchedFilter::class.java)?.state == true,
        excludedCategories = one(EhCategoriesFilter::class.java)?.state.orEmpty().filter { it.state }.mapTo(linkedSetOf()) { it.category },
        browseExpunged = one(EhExpungedFilter::class.java)?.state == true,
        requireTorrent = one(EhTorrentFilter::class.java)?.state == true,
        minimumRating = one(EhRatingFilter::class.java)?.state?.takeIf { it > 0 }?.plus(1),
        minimumPages = minimum,
        maximumPages = maximum,
        disableLanguageFilter = one(EhDisableLanguageFilter::class.java)?.state == true,
        disableUploaderFilter = one(EhDisableUploaderFilter::class.java)?.state == true,
        disableTagFilter = one(EhDisableTagFilter::class.java)?.state == true,
        reverse = one(EhReverseFilter::class.java)?.state == true,
        jumpTarget = jump,
    )
}

private fun parseTags(input: String): List<EhTagTerm> {
    val terms = Regex("(?:\\\"[^\\\"]+\\\"|\\S+)").findAll(input.trim()).map { match ->
        var value = match.value.trim().removeSurrounding("\"")
        val mode = when {
            value.startsWith("-") -> EhTagMode.Exclude.also { value = value.drop(1) }
            value.startsWith("~") -> EhTagMode.Any.also { value = value.drop(1) }
            else -> EhTagMode.Include
        }
        val namespace = value.substringBefore(':', "").takeIf(String::isNotBlank)
        val name = if (namespace == null) value else value.substringAfter(':')
        require(name.isNotBlank()) { "A tag name is empty" }
        EhTagTerm(namespace, name, mode)
    }.toList()
    require(terms.size <= 8) { "E-Hentai supports at most eight tag terms" }
    return terms
}
