package dev.ahmedmohamed.hayai.novel.integration

import java.util.Locale

object NovelSeriesType {
    const val TYPE_NOVEL = 6

    fun isNovelTag(tag: String): Boolean = tag.trim().lowercase(Locale.ROOT) in novelTags

    fun hasNovelTags(tags: String?): Boolean = tags?.split(',')?.any(::isNovelTag) == true

    private val novelTags = setOf("novel", "light novel", "web novel")
}
