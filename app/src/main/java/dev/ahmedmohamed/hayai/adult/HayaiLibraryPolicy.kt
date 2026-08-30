package dev.ahmedmohamed.hayai.adult

import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import dev.ahmedmohamed.hayai.preferences.LewdLibraryFilter
import dev.ahmedmohamed.hayai.preferences.NovelLibraryFilter
import dev.ahmedmohamed.hayai.novel.integration.NovelContentIdentity
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.SourceManager

class HayaiLibraryPolicy(
    private val preferences: HayaiPreferences,
    private val sourceManager: SourceManager,
    private val novelIdentity: NovelContentIdentity,
) {
    fun includes(manga: Manga): Boolean {
        val isLewd = LewdClassifier.isLewd(manga, sourceManager.getOrStub(manga.source))
        val lewdIncluded = LewdLibraryFilter
            .fromPersistedValue(preferences.lewdLibraryFilter.get())
            .includes(isLewd)
        val novelFilter = NovelLibraryFilter.fromPersistedValue(preferences.novelLibraryFilter.get())
        val novelIncluded = novelFilter == NovelLibraryFilter.Disabled || novelFilter.includes(novelIdentity.isNovel(manga))
        return lewdIncluded && novelIncluded
    }

    fun isFiltering(): Boolean =
        LewdLibraryFilter.fromPersistedValue(preferences.lewdLibraryFilter.get()) != LewdLibraryFilter.Disabled ||
            NovelLibraryFilter.fromPersistedValue(preferences.novelLibraryFilter.get()) != NovelLibraryFilter.Disabled
}
