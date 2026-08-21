package dev.ahmedmohamed.hayai.novel.tracker

import eu.kanade.tachiyomi.data.database.models.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelChapterTrackSyncTest {
    @Test
    fun `valid completion delegates to J2K sync on the configured dispatcher`() = runBlocking {
        val updates = mutableListOf<Pair<Long, Float>>()
        val sync = NovelChapterTrackSyncCoordinator({ true }, Dispatchers.Unconfined) { mangaId, chapter ->
            updates += mangaId to chapter
        }

        sync.sync(7, 12.5f)

        assertEquals(listOf(7L to 12.5f), updates)
    }

    @Test
    fun `disabled and invalid progress never reaches J2K sync`() = runBlocking {
        var updates = 0
        val disabled = NovelChapterTrackSyncCoordinator({ false }, Dispatchers.Unconfined) { _, _ -> updates++ }
        val enabled = NovelChapterTrackSyncCoordinator({ true }, Dispatchers.Unconfined) { _, _ -> updates++ }

        disabled.sync(7, 1f)
        enabled.sync(0, 1f)
        enabled.sync(7, 0f)
        enabled.sync(7, Float.NaN)

        assertEquals(0, updates)
    }

    @Test
    fun `only a read chapter enters the J2K tracking path`() = runBlocking {
        val updates = mutableListOf<Pair<Long, Float>>()
        val sync = NovelChapterTrackSync { mangaId, chapter -> updates += mangaId to chapter }
        val chapter = Chapter.create().apply { chapter_number = 8.5f }

        sync.syncIfRead(4, chapter)
        chapter.read = true
        sync.syncIfRead(4, chapter)

        assertEquals(listOf(4L to 8.5f), updates)
    }
}
