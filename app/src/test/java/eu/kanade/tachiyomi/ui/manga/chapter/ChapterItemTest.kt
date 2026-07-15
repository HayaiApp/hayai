package eu.kanade.tachiyomi.ui.manga.chapter

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.download.model.Download
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ChapterItemTest {

    @Test
    fun `binding signature is stable for equivalent rows`() {
        assertEquals(item().bindingContentSignature(), item().bindingContentSignature())
    }

    @Test
    fun `binding signature includes visible chapter fields hidden by identity equality`() {
        val original = item()
        val changed = item().also {
            it.chapter.read = true
            it.chapter.scanlator = "Another group"
        }

        assertEquals(original, changed)
        assertNotEquals(original.bindingContentSignature(), changed.bindingContentSignature())
    }

    @Test
    fun `binding signature includes download lock and translation state`() {
        val original = item()
        val changed = item().also {
            it.status = Download.State.DOWNLOADING
            it.isLocked = true
            it.hasCachedTranslation = true
        }

        assertNotEquals(original.bindingContentSignature(), changed.bindingContentSignature())
    }

    private fun item(): ChapterItem {
        val manga = MangaImpl(id = 1L, source = 2L, url = "/series").apply {
            title = "Series"
            genre = "Manga"
        }
        val chapter = ChapterImpl().apply {
            id = 3L
            manga_id = manga.id
            url = "/chapter-3"
            name = "Chapter 3"
            scanlator = "Group"
            date_fetch = 10L
            date_upload = 11L
            chapter_number = 3f
        }
        return ChapterItem(chapter, manga)
    }
}
