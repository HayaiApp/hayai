package dev.ahmedmohamed.hayai.adult.eh.update

import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EhRevisionMergerTest {
    @Test
    fun `merge preserves progress history bookmarks and downloaded identity`() {
        val key = GalleryKey.parse("/g/100/old-token/")
        val result = EhRevisionMerger.merge(
            remote = listOf(EhRemoteRevision(key, "Remote title", 1000)),
            local = listOf(
                local(7, key, read = true, lastPage = 4, history = 50),
                local(3, key, bookmark = true, lastPage = 8, downloaded = true, history = 100),
            ),
        )

        val mutation = result.mutations.single()
        assertEquals(3, mutation.localId)
        assertTrue(mutation.read)
        assertTrue(mutation.bookmark)
        assertTrue(mutation.downloaded)
        assertEquals(8, mutation.lastPageRead)
        assertEquals(100, mutation.historyLastRead)
        assertEquals(0, result.newRevisionCount)
    }

    @Test
    fun `merge inserts missing revisions and retains chronological numbering order`() {
        val old = GalleryKey.parse("/g/100/old-token/")
        val newest = GalleryKey.parse("/g/101/new-token/")
        val result = EhRevisionMerger.merge(
            remote = listOf(EhRemoteRevision(newest, "New", 2000), EhRemoteRevision(old, "Old", 1000)),
            local = listOf(local(2, old)),
        )

        assertEquals(listOf(old, newest), result.mutations.map { it.remote.key })
        assertEquals(1, result.newRevisionCount)
        assertFalse(result.mutations.last().read)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty remote chains are rejected`() {
        EhRevisionMerger.merge(emptyList(), emptyList())
    }

    private fun local(
        id: Long,
        key: GalleryKey,
        read: Boolean = false,
        bookmark: Boolean = false,
        lastPage: Int = 0,
        downloaded: Boolean = false,
        history: Long = 0,
    ) = EhLocalRevision(
        id = id,
        url = key.normalizedPath,
        title = "Local",
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPage,
        pagesLeft = 0,
        downloaded = downloaded,
        historyLastRead = history,
        historyTimeRead = history,
    )
}

