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
        if (!preferences.hentaiFeaturesEnabled.get()) {
            return !LewdClassifier.isLewd(manga, sourceManager.getOrStub(manga.source))
        }
        val isLewd = LewdClassifier.isLewd(manga, sourceManager.getOrStub(manga.source))
        return when (LewdLibraryFilter.fromPersistedValue(preferences.lewdLibraryFilter.get())) {
            LewdLibraryFilter.Show -> true
            LewdLibraryFilter.Hide -> !isLewd
            LewdLibraryFilter.Only -> isLewd
        }
    }

    fun isFiltering(): Boolean =
        !preferences.hentaiFeaturesEnabled.get() ||
            LewdLibraryFilter.fromPersistedValue(preferences.lewdLibraryFilter.get()) != LewdLibraryFilter.Show
}
