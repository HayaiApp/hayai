package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * Compile-only extension ABI from Tsundoku b04f9a4d39aa4e99a9ca14e4bb76f38d35566998.
 * The package name is required for linking existing APKs. Source callbacks are optional;
 * Hayai's tracking services remain their own production contract.
 */
interface SourceTracker {
    val supportsChapterTracking: Boolean
        get() = true

    val supportsFavoritesTracking: Boolean
        get() = false

    suspend fun onChaptersRead(manga: SManga, changedChapters: List<SChapter>, allChapters: List<SChapter>, categories: List<String>) = Unit

    suspend fun onChaptersUnread(manga: SManga, changedChapters: List<SChapter>, allChapters: List<SChapter>, categories: List<String>) = Unit

    suspend fun onFavorited(manga: SManga, categories: List<String>) = Unit

    suspend fun onUnfavorited(manga: SManga, categories: List<String>) = Unit
}
