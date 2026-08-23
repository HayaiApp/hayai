package dev.ahmedmohamed.hayai.novel.reader

import eu.kanade.tachiyomi.data.database.models.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelProgressTest {
    @Test
    fun `progress is clamped and remaining percentage is maintained`() {
        val chapter = chapter()
        NovelProgress.apply(chapter, 45, 95)
        assertEquals(45, chapter.last_page_read)
        assertEquals(55, chapter.pages_left)
        assertFalse(chapter.read)

        NovelProgress.apply(chapter, 120, 95)
        assertEquals(100, chapter.last_page_read)
        assertEquals(0, chapter.pages_left)
        assertTrue(chapter.read)
    }

    @Test
    fun `threshold is clamped to a valid percentage`() {
        val chapter = chapter()
        NovelProgress.apply(chapter, 1, 0)
        assertTrue(chapter.read)
    }

    @Test
    fun `reset clears read state and all progress metadata`() {
        val chapter =
            chapter().apply {
                read = true
                last_page_read = 100
                pages_left = 0
            }
        NovelProgress.reset(chapter)
        assertFalse(chapter.read)
        assertEquals(0, chapter.last_page_read)
        assertEquals(100, chapter.pages_left)
    }

    @Test
    fun `opening an unread chapter never restores completed progress`() {
        val chapter =
            chapter().apply {
                read = false
                last_page_read = 100
                pages_left = 0
            }

        assertEquals(0, NovelProgress.opening(chapter))
        assertFalse(chapter.read)
        assertEquals(100, chapter.last_page_read)
    }

    @Test
    fun `opening a partially read chapter restores its progress without mutation`() {
        val chapter =
            chapter().apply {
                read = false
                last_page_read = 37
                pages_left = 63
            }

        assertEquals(37, NovelProgress.opening(chapter))
        assertFalse(chapter.read)
        assertEquals(37, chapter.last_page_read)
        assertEquals(63, chapter.pages_left)
    }

    private fun chapter() =
        Chapter.create().apply {
            url = "chapter"
            name = "Chapter"
        }
}
