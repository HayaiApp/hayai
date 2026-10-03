package dev.ahmedmohamed.hayai.novel.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelWordDensityTest {
    @Test fun `average counts readable chapters and coverage distinguishes missing documents`() {
        val density = NovelWordDensity(1000, 2, 5, 1)
        assertEquals(500L, density.averageWords)
        assertEquals(2, density.notDownloadedChapters)
        assertEquals(2, density.tier)
    }

    @Test fun `density boundaries follow Tsundoku tiers and cap at ten`() {
        assertEquals(listOf(1, 1, 2, 2, 3, 10), listOf(0L, 400L, 401L, 500L, 501L, 100000L).map { NovelWordDensity(it, 1, 1, 0).tier })
        assertEquals(0L, NovelWordDensity(0, 0, 5, 1).averageWords)
    }
}
