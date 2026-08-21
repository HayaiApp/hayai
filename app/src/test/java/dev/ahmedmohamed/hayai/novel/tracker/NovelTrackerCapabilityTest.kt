package dev.ahmedmohamed.hayai.novel.tracker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelTrackerCapabilityTest {
    @Test fun `filters manga-only services and deduplicates search`() = runBlocking {
        val adapter = FakeAdapter()
        val capability = NovelTrackerCapability(adapter)
        assertEquals(listOf("NovelUpdates"), capability.available().map { it.name })
        assertEquals(1, capability.search(1, "book").size)
    }
    @Test fun `progress is monotonic and asks J2K to apply reading status semantics`() = runBlocking {
        val adapter = FakeAdapter()
        val capability = NovelTrackerCapability(adapter)
        val state = NovelTrackState(1, 2, 4.5f, 10, 5, 0f, null, null)

        val updated = capability.updateProgress(9, state, 3f)

        assertEquals(4.5f, updated.chapterRead)
        assertEquals(true, adapter.lastSetToRead)
    }
    private class FakeAdapter : J2kNovelTrackingAdapter {
        var lastSetToRead = false
        override fun services() = listOf(NovelTrackerDescriptor(1, "NovelUpdates", true), NovelTrackerDescriptor(2, "Manga only", false))
        override suspend fun search(serviceId: Long, query: String) = listOf(NovelTrackSearchResult(1, query, "https://example.test/1", 3), NovelTrackSearchResult(1, query, "https://example.test/1", 3))
        override suspend fun bind(mangaId: Long, serviceId: Long, result: NovelTrackSearchResult) = NovelTrackState(serviceId, result.remoteId, 0f, result.totalChapters ?: 0, 1, 0f, null, null)
        override suspend fun update(mangaId: Long, state: NovelTrackState, setToRead: Boolean): NovelTrackState {
            lastSetToRead = setToRead
            return state
        }
        override suspend fun remove(mangaId: Long, serviceId: Long) = Unit
    }
}
