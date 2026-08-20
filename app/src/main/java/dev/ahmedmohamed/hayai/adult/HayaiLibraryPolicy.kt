package dev.ahmedmohamed.hayai.adult

import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import dev.ahmedmohamed.hayai.preferences.LewdLibraryFilter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.SourceManager

class HayaiLibraryPolicy(
    private val preferences: HayaiPreferences,
    private val sourceManager: SourceManager,
) {
    fun includes(manga: Manga): Boolean {
        val isLewd = LewdClassifier.isLewd(manga, sourceManager.getOrStub(manga.source))
        return LewdLibraryFilter
            .fromPersistedValue(preferences.lewdLibraryFilter.get())
            .includes(isLewd)
    }

    fun isFiltering(): Boolean =
        LewdLibraryFilter.fromPersistedValue(preferences.lewdLibraryFilter.get()) != LewdLibraryFilter.Disabled
}
