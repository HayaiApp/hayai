package dev.ahmedmohamed.hayai.novel.tracker

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.chapter.updateTrackChapterRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface NovelChapterTrackSync {
    suspend fun sync(mangaId: Long, chapterNumber: Float)

    companion object {
        val None = NovelChapterTrackSync { _, _ -> }
    }
}

internal suspend fun NovelChapterTrackSync.syncIfRead(mangaId: Long, chapter: Chapter) {
    if (chapter.read) sync(mangaId, chapter.chapter_number)
}

internal class J2kNovelChapterTrackSync(
    database: DatabaseHelper,
    preferences: PreferencesHelper,
) : NovelChapterTrackSync by NovelChapterTrackSyncCoordinator(
    enabled = preferences::autoUpdateTrack,
    dispatcher = Dispatchers.IO,
    update = { mangaId, chapterNumber ->
        updateTrackChapterRead(database, preferences, mangaId, chapterNumber, retryWhenOnline = true)
    },
)

internal class NovelChapterTrackSyncCoordinator(
    private val enabled: () -> Boolean,
    private val dispatcher: CoroutineDispatcher,
    private val update: suspend (Long, Float) -> Unit,
) : NovelChapterTrackSync {
    override suspend fun sync(mangaId: Long, chapterNumber: Float) {
        if (!enabled() || mangaId <= 0 || chapterNumber <= 0f || !chapterNumber.isFinite()) return
        withContext(dispatcher) { update(mangaId, chapterNumber) }
    }
}
