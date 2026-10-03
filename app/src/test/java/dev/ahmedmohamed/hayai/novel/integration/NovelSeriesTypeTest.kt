package dev.ahmedmohamed.hayai.novel.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelSeriesTypeTest {
    @Test
    fun `explicit novel overrides historical manga tags`() {
        assertTrue(NovelSeriesType.hasNovelTags("Manga, Action, Novel"))
        assertTrue(NovelSeriesType.hasNovelTags(" Web Novel , Fantasy"))
        assertTrue(NovelSeriesType.hasNovelTags("LIGHT NOVEL"))
    }

    @Test
    fun `ordinary genres do not change content kind`() {
        assertFalse(NovelSeriesType.hasNovelTags("Manga, Fantasy"))
        assertFalse(NovelSeriesType.hasNovelTags("Novelty, Comic"))
        assertFalse(NovelSeriesType.hasNovelTags(null))
    }
}
