package dev.ahmedmohamed.hayai.adult

import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import dev.ahmedmohamed.hayai.preferences.LewdLibraryFilter
import dev.ahmedmohamed.hayai.preferences.NovelLibraryFilter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.isNovelSource

class HayaiLibraryPolicy(
    private val preferences: HayaiPreferences,
    private val sourceManager: SourceManager,
) {
    fun includes(manga: Manga): Boolean {
        val isLewd = LewdClassifier.isLewd(manga, sourceManager.getOrStub(manga.source))
        val lewdIncluded = LewdLibraryFilter
            .fromPersistedValue(preferences.lewdLibraryFilter.get())
            .includes(isLewd)
        val novelIncluded = NovelLibraryFilter
            .fromPersistedValue(preferences.novelLibraryFilter.get())
            .includes(sourceManager.getOrStub(manga.source).isNovelSource())
        return lewdIncluded && novelIncluded
    }

    fun isFiltering(): Boolean =
        LewdLibraryFilter.fromPersistedValue(preferences.lewdLibraryFilter.get()) != LewdLibraryFilter.Disabled ||
            NovelLibraryFilter.fromPersistedValue(preferences.novelLibraryFilter.get()) != NovelLibraryFilter.Disabled
}
