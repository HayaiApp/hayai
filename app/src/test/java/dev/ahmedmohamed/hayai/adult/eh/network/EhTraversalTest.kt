package dev.ahmedmohamed.hayai.adult.eh.network

import dev.ahmedmohamed.hayai.adult.eh.domain.EhFailure
import dev.ahmedmohamed.hayai.adult.eh.domain.EhGalleryPage
import dev.ahmedmohamed.hayai.adult.eh.domain.EhPageBatch
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EhTraversalTest {
    @Test
    fun `parent traversal is ordered bounded and cycle safe`() {
        val one = GalleryKey.parse("/g/1/token1/")
        val two = GalleryKey.parse("/g/2/token2/")
        val three = GalleryKey.parse("/g/3/token3/")
        val traversal = EhParentTraversal(one, maxHops = 3)

        assertEquals(two, traversal.follow(two))
        assertEquals(three, traversal.follow(three))
        assertNull(traversal.follow(null))
        assertEquals(listOf(one, two, three), traversal.path())
        assertThrows(EhFailure.BoundsExceeded::class.java) { traversal.follow(GalleryKey.parse("/g/4/token4/")) }

        val cyclic = EhParentTraversal(one)
        cyclic.follow(two)
        assertThrows(EhFailure.MalformedDocument::class.java) { cyclic.follow(one) }
    }

    @Test
    fun `page accumulator deduplicates matching pages and rejects listing cycles`() {
        val accumulator = EhPageAccumulator(maxListingPages = 3, maxGalleryPages = 4)
        val next = accumulator.add(
            EhPageBatch(
                "https://e-hentai.org/g/1/token/?p=0",
                listOf(EhGalleryPage(1, "https://e-hentai.org/s/a/1-1")),
                "https://e-hentai.org/g/1/token/?p=1",
            ),
        )
        assertEquals("https://e-hentai.org/g/1/token/?p=1", next)
        accumulator.add(
            EhPageBatch(
                next!!,
                listOf(
                    EhGalleryPage(1, "https://e-hentai.org/s/a/1-1"),
                    EhGalleryPage(2, "https://e-hentai.org/s/b/1-2"),
                ),
                null,
            ),
        )
        assertEquals(listOf(1, 2), accumulator.pages().map { it.index })
        assertThrows(EhFailure.MalformedDocument::class.java) {
            accumulator.add(EhPageBatch(next, emptyList(), null))
        }
    }
}
