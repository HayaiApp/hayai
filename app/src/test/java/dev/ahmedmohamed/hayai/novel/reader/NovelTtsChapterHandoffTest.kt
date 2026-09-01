package dev.ahmedmohamed.hayai.novel.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelTtsChapterHandoffTest {
    @Test
    fun `scheduled chapter resumes exactly once`() {
        val handoff = NovelTtsChapterHandoff()

        handoff.schedule(42)

        assertTrue(handoff.consume(42))
        assertFalse(handoff.consume(42))
    }

    @Test
    fun `unrelated chapter cancels pending resume`() {
        val handoff = NovelTtsChapterHandoff()

        handoff.schedule(42)
        handoff.cancelUnlessTarget(7)

        assertFalse(handoff.consume(42))
    }
}
