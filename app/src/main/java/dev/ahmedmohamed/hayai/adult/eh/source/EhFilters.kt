package dev.ahmedmohamed.hayai.adult.eh.source

import android.content.Context
import dev.ahmedmohamed.hayai.adult.eh.domain.EhCategory
import dev.ahmedmohamed.hayai.adult.eh.domain.EhJumpTarget
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSearchSpec
import dev.ahmedmohamed.hayai.adult.eh.domain.EhTagMode
import dev.ahmedmohamed.hayai.adult.eh.domain.EhTagTerm
import dev.ahmedmohamed.hayai.adult.eh.domain.EhToplist
import dev.ahmedmohamed.hayai.adult.eh.settings.EhPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal class EhToplistFilter(context: Context) : Filter.Select<String>(
    context.getString(R.string.hayai_eh_filter_toplist),
    arrayOf(
        context.getString(R.string.none),
        context.getString(R.string.hayai_eh_filter_all_time),
        context.getString(R.string.hayai_eh_filter_past_year),
        context.getString(R.string.hayai_eh_filter_past_month),
        context.getString(R.string.hayai_eh_filter_yesterday),
    ),
)
internal class EhWatchedFilter(context: Context, state: Boolean) : Filter.CheckBox(context.getString(R.string.hayai_eh_filter_watched_list), state)
internal class EhCategoryFilter(context: Context, val category: EhCategory, state: Boolean) :
    Filter.CheckBox(category.localizedName(context), state)
internal class EhCategoriesFilter(context: Context, excluded: Set<EhCategory>) :
    Filter.Group<EhCategoryFilter>(
        context.getString(R.string.hayai_eh_filter_categories_exclude),
        EhCategory.entries.map { EhCategoryFilter(context, it, it in excluded) },
    )
internal class EhTagFilter(context: Context) : Filter.Text(context.getString(R.string.hayai_eh_filter_tags))
internal class EhExpungedFilter(context: Context) : Filter.CheckBox(context.getString(R.string.hayai_eh_filter_expunged))
internal class EhTorrentFilter(context: Context) : Filter.CheckBox(context.getString(R.string.hayai_eh_filter_torrent))
internal class EhRatingFilter(context: Context) : Filter.Select<String>(
    context.getString(R.string.hayai_eh_filter_minimum_rating),
    arrayOf(context.getString(R.string.hayai_eh_filter_any), "2", "3", "4", "5"),
)
internal class EhMinimumPagesFilter(context: Context) : Filter.Text(context.getString(R.string.hayai_eh_filter_minimum_pages))
internal class EhMaximumPagesFilter(context: Context) : Filter.Text(context.getString(R.string.hayai_eh_filter_maximum_pages))
internal class EhDisableLanguageFilter(context: Context) : Filter.CheckBox(context.getString(R.string.hayai_eh_filter_disable_language))
internal class EhDisableUploaderFilter(context: Context) : Filter.CheckBox(context.getString(R.string.hayai_eh_filter_disable_uploader))
internal class EhDisableTagFilter(context: Context) : Filter.CheckBox(context.getString(R.string.hayai_eh_filter_disable_tag))
internal class EhReverseFilter(context: Context) : Filter.CheckBox(context.getString(R.string.hayai_eh_filter_reverse))
internal class EhJumpFilter(context: Context) : Filter.Text(context.getString(R.string.hayai_eh_filter_jump))

internal fun ehFilterList(context: Context, preferences: EhPreferences): FilterList = FilterList(
    Filter.Header(context.getString(R.string.hayai_eh_filter_toplist_warning)),
    EhToplistFilter(context),
    EhWatchedFilter(context, preferences.watchedListDefault.get()),
    EhCategoriesFilter(context, preferences.excludedCategories()),
    EhTagFilter(context),
    EhExpungedFilter(context),
    EhTorrentFilter(context),
    EhRatingFilter(context),
    EhMinimumPagesFilter(context),
    EhMaximumPagesFilter(context),
    EhDisableLanguageFilter(context),
    EhDisableUploaderFilter(context),
    EhDisableTagFilter(context),
    EhReverseFilter(context),
    EhJumpFilter(context),
)

internal fun FilterList.toEhSpec(query: String, context: Context): EhSearchSpec {
    fun <T : Filter<*>> one(type: Class<T>): T? = firstOrNull { type.isInstance(it) }?.let(type::cast)
    val toplist = EhToplist.entries.getOrElse(one(EhToplistFilter::class.java)?.state ?: 0) { EhToplist.None }
    if (toplist != EhToplist.None) return EhSearchSpec(toplist = toplist)
    require(query.length <= 1_024) { context.getString(R.string.hayai_eh_filter_query_too_long) }
    require(query.none(Char::isISOControl)) { context.getString(R.string.hayai_eh_filter_query_control_character) }
    val minimum = one(EhMinimumPagesFilter::class.java)?.state.toPageCount(context, R.string.hayai_eh_filter_minimum_pages_invalid)
    val maximum = one(EhMaximumPagesFilter::class.java)?.state.toPageCount(context, R.string.hayai_eh_filter_maximum_pages_invalid)
    require(minimum == null || maximum == null || minimum <= maximum) { context.getString(R.string.hayai_eh_filter_page_range_invalid) }
    val jump = one(EhJumpFilter::class.java)?.state?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
        runCatching { EhJumpTarget.parse(value) }.getOrElse { throw IllegalArgumentException(context.getString(R.string.hayai_eh_filter_invalid_jump), it) }
    }
    return EhSearchSpec(
        query = query,
        tags = parseTags(one(EhTagFilter::class.java)?.state.orEmpty(), context),
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

private fun parseTags(input: String, context: Context): List<EhTagTerm> {
    val terms = Regex("(?:\\\"[^\\\"]+\\\"|\\S+)").findAll(input.trim()).map { match ->
        var value = match.value.trim().removeSurrounding("\"")
        val mode = when {
            value.startsWith("-") -> EhTagMode.Exclude.also { value = value.drop(1) }
            value.startsWith("~") -> EhTagMode.Any.also { value = value.drop(1) }
            else -> EhTagMode.Include
        }
        val namespace = value.substringBefore(':', "").takeIf(String::isNotBlank)
        val name = if (namespace == null) value else value.substringAfter(':')
        require(name.isNotBlank()) { context.getString(R.string.hayai_eh_filter_empty_tag) }
        EhTagTerm(namespace, name, mode)
    }.toList()
    require(terms.size <= 8) { context.getString(R.string.hayai_eh_filter_too_many_tags) }
    return terms
}

private fun String?.toPageCount(context: Context, message: Int): Int? {
    val value = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return value.toIntOrNull()?.takeIf { it in 1..100_000 }
        ?: throw IllegalArgumentException(context.getString(message))
}

private fun EhCategory.localizedName(context: Context): String =
    name
        .replace("Cg", context.getString(R.string.hayai_eh_category_cg))
        .replace("NonH", context.getString(R.string.hayai_eh_category_non_h))
        .replace("ImageSet", context.getString(R.string.hayai_eh_category_image_set))
        .replace("AsianPorn", context.getString(R.string.hayai_eh_category_asian_porn))
