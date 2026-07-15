package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class LibraryItemTest {

    @Test
    fun `binding signature is stable for equivalent rows`() {
        assertEquals(item().bindingContentSignature(), item().bindingContentSignature())
    }

    @Test
    fun `binding signature includes fields hidden by manga identity`() {
        val original = item()
        val changed = item(unread = 4, title = "Changed")

        assertEquals(original, changed)
        assertNotEquals(original.bindingContentSignature(), changed.bindingContentSignature())
    }

    @Test
    fun `binding signature includes category and badge state`() {
        val original = item()
        val changed = item(downloadCount = 2, categoryHidden = true)

        assertNotEquals(original.bindingContentSignature(), changed.bindingContentSignature())
    }

    private fun item(
        unread: Int = 1,
        title: String = "Example",
        downloadCount: Int = 0,
        categoryHidden: Boolean = false,
    ): LibraryMangaItem {
        val category = Category.create("Library").apply {
            id = 1
            isHidden = categoryHidden
        }
        val header = LibraryHeaderItem({ category }, category.id!!)
        val manga = MangaImpl(id = 10, source = 20, url = "/example").apply {
            this.title = title
            author = "Author"
            thumbnail_url = "https://example.invalid/cover.jpg"
        }
        return LibraryMangaItem(
            manga = LibraryManga(manga = manga, unread = unread, category = category.id!!),
            header = header,
            context = null,
        ).apply {
            this.downloadCount = downloadCount
        }
    }
}
