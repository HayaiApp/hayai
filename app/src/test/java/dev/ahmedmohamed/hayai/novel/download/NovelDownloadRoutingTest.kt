package dev.ahmedmohamed.hayai.novel.download

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelDownloadRoutingTest {
    @Test
    fun `ordinary manga sources stay on the J2K image storage path`() {
        assertEquals(ChapterDownloadStorage.J2kImages, ChapterDownloadRouting.storageFor(FakeSource(isNovel = false)))
    }

    @Test
    fun `novel sources use Hayai storage behind the J2K queue`() {
        val source = FakeSource(isNovel = true)

        assertEquals(ChapterDownloadStorage.HayaiNovel, ChapterDownloadRouting.storageFor(source))
        assertTrue(NovelDownloadQueueSource.from(source) is HttpSource)
        assertSame(NovelDownloadQueueSource.from(source), NovelDownloadQueueSource.from(source))
    }

    @Test
    fun `ordinary non HTTP sources cannot enter the image download queue`() {
        assertNull(NovelDownloadQueueSource.from(FakeSource(isNovel = false)))
    }

    @Test
    fun `ordinary HTTP sources enter the queue without an adapter`() {
        val source = FakeHttpSource()

        assertSame(source, NovelDownloadQueueSource.from(source))
    }

    @Test
    fun `offline refresh preserves active J2K queue states`() {
        assertEquals(
            Download.State.QUEUE,
            NovelDownloadPresentationState.merge(Download.State.QUEUE, isOffline = false),
        )
        assertEquals(
            Download.State.DOWNLOADING,
            NovelDownloadPresentationState.merge(Download.State.DOWNLOADING, isOffline = false),
        )
        assertEquals(
            Download.State.ERROR,
            NovelDownloadPresentationState.merge(Download.State.ERROR, isOffline = false),
        )
    }

    @Test
    fun `offline refresh reflects the document store`() {
        assertEquals(
            Download.State.DOWNLOADED,
            NovelDownloadPresentationState.merge(Download.State.QUEUE, isOffline = true),
        )
        assertEquals(
            Download.State.NOT_DOWNLOADED,
            NovelDownloadPresentationState.merge(Download.State.DOWNLOADED, isOffline = false),
        )
    }

    private class FakeSource(
        private val isNovel: Boolean,
    ) : Source {
        override val id: Long = 1L
        override val name: String = "Fake"
        override val isNovelSource: Boolean = isNovel
    }

    private class FakeHttpSource : HttpSource() {
        override val name: String = "Fake HTTP"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false
        override val baseUrl: String = "https://example.com"
    }
}
