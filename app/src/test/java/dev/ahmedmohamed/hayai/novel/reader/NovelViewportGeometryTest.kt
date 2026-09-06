package dev.ahmedmohamed.hayai.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelViewportGeometryTest {
    @Test
    fun `long chapter remains current until its trailing boundary reaches the viewport top`() {
        assertTrue(NovelViewportGeometry.contains(0, 10000, 9000))
        assertFalse(NovelViewportGeometry.contains(10000, 11000, 9000))
        assertFalse(NovelViewportGeometry.contains(0, 10000, 10000))
        assertTrue(NovelViewportGeometry.contains(10000, 11000, 10000))
    }

    @Test
    fun `chapter progress uses viewport height and stays at the end during chapter transition`() {
        assertEquals(50, NovelViewportGeometry.progress(1000, 3000, 1000, 2000))
        assertEquals(100, NovelViewportGeometry.progress(1000, 3000, 1000, 3500))
        assertEquals(0, NovelViewportGeometry.progress(1000, 3000, 1000, 0))
    }

    @Test
    fun `short chapter is fully visible without a synthetic one pixel scroll range`() {
        assertEquals(1000, NovelViewportGeometry.offset(1000, 600, 1000, 100))
        assertEquals(100, NovelViewportGeometry.progress(1000, 600, 1000, 1000))
    }

    @Test
    fun `seeking a chapter uses its local range and clamps external progress`() {
        assertEquals(2000, NovelViewportGeometry.offset(1000, 3000, 1000, 50))
        assertEquals(1000, NovelViewportGeometry.offset(1000, 3000, 1000, -10))
        assertEquals(3000, NovelViewportGeometry.offset(1000, 3000, 1000, 150))
    }

    @Test
    fun `large chapters do not overflow when computing progress`() {
        assertEquals(50, NovelViewportGeometry.progress(0, 60000000, 1000, 29999500))
        assertEquals(29999500, NovelViewportGeometry.offset(0, 60000000, 1000, 50))
    }
}
