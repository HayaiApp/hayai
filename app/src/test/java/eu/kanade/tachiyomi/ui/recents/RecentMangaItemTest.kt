package eu.kanade.tachiyomi.ui.recents

import eu.kanade.tachiyomi.data.database.models.ChapterHistory
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.database.models.HistoryImpl
import eu.kanade.tachiyomi.data.database.models.MangaChapterHistory
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class RecentMangaItemTest {

    @Test
    fun `binding signature is stable for equivalent model snapshots`() {
        assertEquals(item().bindingContentSignature(), item().bindingContentSignature())
    }

    @Test
    fun `binding signature changes when a visible chapter field changes`() {
        val original = item()
        val changed = item().also { it.chapter.read = true }

        assertNotEquals(original.bindingContentSignature(), changed.bindingContentSignature())
    }

    @Test
    fun `binding signature changes when an extra chapter history changes`() {
        val original = item(extraLastRead = 20L)
        val changed = item(extraLastRead = 21L)

        assertNotEquals(original.bindingContentSignature(), changed.bindingContentSignature())
    }

    private fun item(extraLastRead: Long = 20L): RecentMangaItem {
        val manga = MangaImpl(id = 1L, source = 2L, url = "/series").apply {
            title = "Series"
            thumbnail_url = "https://example.test/cover.jpg"
            date_added = 10L
            cover_last_modified = 11L
        }
        val chapter = chapter(id = 3L, url = "/chapter-3", name = "Chapter 3")
        val history = HistoryImpl().apply {
            id = 4L
            chapter_id = 3L
            last_read = 12L
            time_read = 13L
        }
        val extraHistory = HistoryImpl().apply {
            id = 6L
            chapter_id = 5L
            last_read = extraLastRead
            time_read = 22L
        }
        val model = MangaChapterHistory(
            manga = manga,
            chapter = chapter,
            history = history,
            extraChapters = listOf(
                ChapterHistory(
                    chapter = chapter(id = 5L, url = "/chapter-2", name = "Chapter 2"),
                    history = extraHistory,
                ),
            ),
        )
        return RecentMangaItem(model, chapter, header = null)
    }

    private fun chapter(id: Long, url: String, name: String) = ChapterImpl().apply {
        this.id = id
        manga_id = 1L
        this.url = url
        this.name = name
        date_fetch = 14L
        date_upload = 15L
        chapter_number = id.toFloat()
    }
}
