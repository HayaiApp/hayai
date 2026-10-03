package dev.ahmedmohamed.hayai.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelTtsQueueTest {
    private val paragraphs = listOf("First paragraph.", "Second paragraph.", "Third paragraph.")

    @Test
    fun `read from viewport seeks even when chapter text has already been loaded`() {
        val queue = NovelTtsQueue()
        queue.update(1, paragraphs, 100)
        assertTrue(queue.update(1, paragraphs, 100, startParagraph = 2))
        assertEquals("Third paragraph.", queue.current?.text)
        assertTrue(queue.update(1, paragraphs, 100, startParagraph = 0))
        assertEquals("First paragraph.", queue.current?.text)
    }

    @Test
    fun `document refresh leaves paused or playing paragraph unchanged`() {
        val queue = NovelTtsQueue()
        queue.update(1, paragraphs, 100, startParagraph = 1)
        assertFalse(queue.update(1, paragraphs, 100))
        assertEquals(1, queue.paragraphIndex)
    }

    @Test
    fun `completed text can play again instead of stopping without an utterance`() {
        val queue = NovelTtsQueue()
        queue.update(1, paragraphs, 100)
        repeat(3) { queue.advance() }
        assertEquals(null, queue.current)
        queue.restartIfCompleted()
        assertEquals("First paragraph.", queue.current?.text)
    }

    @Test
    fun `moving to another mounted chapter resets the queue even for identical text`() {
        val queue = NovelTtsQueue()
        queue.update(1, paragraphs, 100, startParagraph = 2)
        assertTrue(queue.update(2, paragraphs, 100))
        assertEquals(0, queue.paragraphIndex)
    }

    @Test
    fun `paragraph navigation uses the first speech chunk of a long paragraph`() {
        val queue = NovelTtsQueue()
        queue.update(1, listOf("a long paragraph with multiple speech chunks", "second"), 12)
        queue.advance()
        assertEquals(1, queue.seekParagraph(1))
        assertEquals("second", queue.current?.text)
        assertEquals(0, queue.seekParagraph(-1))
        assertEquals("a long", queue.current?.text)
    }
}
