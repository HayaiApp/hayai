package dev.ahmedmohamed.hayai.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadChapterDeletionPolicyTest {
    @Test
    fun `completing a duplicate respects every assigned category exclusion`() {
        for (slots in listOf(0, 2)) {
            assertFalse(ReadChapterDeletionPolicy.allowsDeletion(slots, setOf(8)) { listOf(3, 8) })
        }
    }

    @Test
    fun `default category protects a manga without explicit categories`() {
        assertFalse(ReadChapterDeletionPolicy.allowsDeletion(0, setOf(0)) { emptyList() })
        assertTrue(ReadChapterDeletionPolicy.allowsDeletion(0, setOf(0)) { listOf(3) })
    }

    @Test
    fun `disabled deletion never queries manga categories`() {
        assertFalse(ReadChapterDeletionPolicy.allowsDeletion(-1, setOf(8)) { error("Unexpected category lookup") })
    }

    @Test
    fun `unrestricted deletion needs no category lookup`() {
        assertTrue(ReadChapterDeletionPolicy.allowsDeletion(0, emptySet()) { error("Unexpected category lookup") })
    }

    @Test
    fun `unrelated category exclusion permits normal and duplicate deletion`() {
        for (slots in listOf(0, 2)) {
            assertTrue(ReadChapterDeletionPolicy.allowsDeletion(slots, setOf(8)) { listOf(3, 7) })
        }
    }
}
