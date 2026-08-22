package dev.ahmedmohamed.hayai.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelChapterQueueTest {
    @Test
    fun `prefetch insertion never changes the current chapter`() {
        val queue = NovelChapterQueue<Int, Int>({ it }, 3)
        queue.replaceCurrent(2)

        queue.prepend(1)
        queue.append(3)

        assertEquals(2, queue.currentKey)
        assertEquals(listOf(1, 2, 3), queue.snapshot())
    }

    @Test
    fun `focus rejects chapters outside the bounded window`() {
        val queue = NovelChapterQueue<Int, Int>({ it }, 2)
        queue.replaceCurrent(2)
        queue.append(3)

        assertFalse(queue.focus(1))
        assertTrue(queue.focus(3))
        assertEquals(3, queue.currentKey)
    }

    @Test
    fun `capacity trimming preserves the focused chapter`() {
        val queue = NovelChapterQueue<Int, Int>({ it }, 5)
        queue.replaceCurrent(3)
        queue.prepend(2)
        queue.prepend(1)
        queue.append(4)
        queue.append(5)

        queue.capacity = 3

        assertEquals(listOf(2, 3, 4), queue.snapshot())
        assertEquals(3, queue.currentKey)
    }

    @Test
    fun `append and prepend reject duplicate stable ids`() {
        val queue = NovelChapterQueue<Int, Int>({ it }, 3)
        queue.replaceCurrent(2)

        queue.append(2)
        queue.prepend(2)

        assertEquals(listOf(2), queue.snapshot())
        assertEquals(2, queue.currentKey)
    }

    @Test
    fun `retaining a window clears focus only when current is removed`() {
        val queue = NovelChapterQueue<Int, Int>({ it }, 3)
        queue.replaceCurrent(2)
        queue.prepend(1)
        queue.append(3)

        queue.retain(setOf(2, 3))
        assertEquals(2, queue.currentKey)
        queue.retain(setOf(3))
        assertNull(queue.currentKey)
    }
}
