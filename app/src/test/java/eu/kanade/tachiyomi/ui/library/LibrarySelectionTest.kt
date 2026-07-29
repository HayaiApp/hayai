package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.data.database.models.MangaImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibrarySelectionTest {

    @Test
    fun `selection is stable when a manga's mutable identity fields change`() {
        val manga = MangaImpl(id = 1, source = 10, url = "/one")
        val selection = LibrarySelection()

        assertEquals(mapOf(1L to true), selection.update(listOf(manga), selected = true))
        manga.url = "/renamed"

        assertEquals(mapOf(1L to false), selection.update(listOf(manga), selected = false))
        assertTrue(selection.isEmpty)
    }

    @Test
    fun `selection keeps the latest row model without duplicating its stable id`() {
        val selection = LibrarySelection()
        val original = MangaImpl(id = 1, source = 10, url = "/one")
        val refreshed = MangaImpl(id = 1, source = 10, url = "/one")

        selection.update(listOf(original), selected = true)
        assertTrue(selection.update(listOf(refreshed), selected = true).isEmpty())

        assertEquals(1, selection.mangas.size)
        assertSame(refreshed, selection.mangas.single())
        assertTrue(selection.contains(refreshed))
        assertFalse(selection.isEmpty)
    }
}
