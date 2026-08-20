package dev.ahmedmohamed.hayai.adult.eh.network

import dev.ahmedmohamed.hayai.adult.eh.domain.EhJumpTarget
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSearchCursor
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSearchSpec
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.EhToplist
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

object EhRequestBuilder {
    fun latest(site: EhSite, cursor: EhSearchCursor.Gallery? = null): HttpUrl =
        site.baseUrl.toHttpUrl().newBuilder().apply {
            cursor?.let { addQueryParameter("next", it.id.value) }
        }.build()

    fun popular(site: EhSite): HttpUrl = site.baseUrl.toHttpUrl().newBuilder().addPathSegment("popular").build()

    fun search(
        site: EhSite,
        spec: EhSearchSpec,
        cursor: EhSearchCursor? = null,
    ): HttpUrl {
        if (spec.toplist != EhToplist.None) return toplist(spec.toplist, cursor)
        require(cursor == null || cursor is EhSearchCursor.Gallery) { "Gallery search requires a gallery cursor" }

        val builder = site.baseUrl.toHttpUrl().newBuilder()
        if (spec.watched) builder.addPathSegment("watched")
        builder.addQueryParameter("f_apply", "Apply Filter")
        val tags = EhTagQueryCodec.encode(spec.tags)
        builder.addQueryParameter("f_search", listOf(spec.query.trim(), tags).filter(String::isNotBlank).joinToString(" "))
        builder.addQueryParameter("f_cats", spec.excludedCategories.sumOf { it.exclusionBit }.toString())
        if (spec.browseExpunged) builder.addQueryParameter("f_sh", "on")
        if (spec.requireTorrent) builder.addQueryParameter("f_sto", "on")
        spec.minimumRating?.let {
            builder.addQueryParameter("f_sr", "on")
            builder.addQueryParameter("f_srdd", it.toString())
        }
        if (spec.minimumPages != null || spec.maximumPages != null) {
            builder.addQueryParameter("f_sp", "on")
            spec.minimumPages?.let { builder.addQueryParameter("f_spf", it.toString()) }
            spec.maximumPages?.let { builder.addQueryParameter("f_spt", it.toString()) }
        }
        if (spec.disableLanguageFilter) builder.addQueryParameter("f_sfl", "on")
        if (spec.disableUploaderFilter) builder.addQueryParameter("f_sfu", "on")
        if (spec.disableTagFilter) builder.addQueryParameter("f_sft", "on")
        if (spec.reverse) builder.addQueryParameter(REVERSE_PARAMETER, "on")
        (cursor as? EhSearchCursor.Gallery)?.let {
            builder.addQueryParameter(if (spec.reverse) "prev" else "next", it.id.value)
        }
        if (cursor == null) {
            when (val jump = spec.jumpTarget) {
                is EhJumpTarget.Date -> builder.addQueryParameter("seek", jump.value)
                is EhJumpTarget.Relative -> builder.addQueryParameter("jump", jump.value)
                null -> Unit
            }
        }
        return builder.build()
    }

    private fun toplist(toplist: EhToplist, cursor: EhSearchCursor?): HttpUrl {
        require(toplist != EhToplist.None) { "A toplist must be selected" }
        require(cursor == null || cursor is EhSearchCursor.ToplistPage) { "Toplist search requires a page cursor" }
        val page = (cursor as? EhSearchCursor.ToplistPage)?.page ?: 1
        return EH_TOPLIST.toHttpUrl().newBuilder()
            .addQueryParameter("tl", requireNotNull(toplist.requestValue).toString())
            .addQueryParameter("p", (page - 1).toString())
            .build()
    }

    const val REVERSE_PARAMETER = "TEH_REVERSE"
    private const val EH_TOPLIST = "https://e-hentai.org/toplist.php"
}
