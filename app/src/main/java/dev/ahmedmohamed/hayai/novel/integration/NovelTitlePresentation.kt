package dev.ahmedmohamed.hayai.novel.integration

import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.data.database.models.Manga

class NovelTitlePresentation(
    private val preferences: HayaiPreferences,
    private val novels: NovelJ2kIntegration,
) {
    fun maxLines(
        manga: Manga,
        authorMatched: Boolean,
    ): Int =
        when {
            authorMatched -> 1
            novels.isNovel(manga) -> preferences.novelTitleMaxLines.get().coerceIn(1, 5)
            else -> 2
        }
}
