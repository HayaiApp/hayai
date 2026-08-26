package dev.ahmedmohamed.hayai.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelLibraryFilterTest {
    @Test
    fun `persisted scopes resolve without changing library ownership`() {
        assertSame(NovelLibraryFilter.Disabled, NovelLibraryFilter.fromPersistedValue(0))
        assertSame(NovelLibraryFilter.NovelsOnly, NovelLibraryFilter.fromPersistedValue(1))
        assertSame(NovelLibraryFilter.MangaOnly, NovelLibraryFilter.fromPersistedValue(2))
        assertSame(NovelLibraryFilter.Disabled, NovelLibraryFilter.fromPersistedValue(99))
    }

    @Test
    fun `each visible scope includes only its requested content`() {
        assertTrue(NovelLibraryFilter.Disabled.includes(isNovel = false))
        assertTrue(NovelLibraryFilter.Disabled.includes(isNovel = true))
        assertTrue(NovelLibraryFilter.NovelsOnly.includes(isNovel = true))
        assertFalse(NovelLibraryFilter.NovelsOnly.includes(isNovel = false))
        assertTrue(NovelLibraryFilter.MangaOnly.includes(isNovel = false))
        assertFalse(NovelLibraryFilter.MangaOnly.includes(isNovel = true))
    }
}
