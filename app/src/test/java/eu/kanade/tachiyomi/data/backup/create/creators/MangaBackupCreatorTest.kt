package eu.kanade.tachiyomi.data.backup.create.creators

import android.content.Context
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import hayai.novel.reader.quote.Quote
import hayai.novel.reader.quote.QuoteManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import yokai.data.DatabaseHandler
import yokai.domain.category.interactor.GetCategories
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.history.interactor.GetHistory
import yokai.domain.series.SeriesKnowledgeRepository
import yokai.domain.series.model.SeriesKnowledgeBundle
import yokai.domain.series.model.SeriesTranslationCanon
import yokai.domain.series.model.TranslationMode
import yokai.domain.track.interactor.GetTrack

class MangaBackupCreatorTest {

    @Test
    fun `custom manga info option does not gate series knowledge and novel quotes`() = runTest {
        val mangaId = 42L
        val repository = mockk<SeriesKnowledgeRepository>(relaxed = true)
        coEvery { repository.get(mangaId) } returns SeriesKnowledgeBundle.Empty.copy(
            canon = SeriesTranslationCanon(
                mangaId = mangaId,
                mode = TranslationMode.ADVANCED,
                sourceLanguage = "ja",
                targetLanguage = "en",
                summary = "Keep names consistent.",
                styleGuide = null,
                createdAt = 1L,
                updatedAt = 2L,
            ),
        )

        val quoteManager = mockk<QuoteManager>()
        coEvery { quoteManager.getQuotes(mangaId) } returns listOf(
            Quote(
                id = "quote-1",
                novelName = "Novel",
                chapterName = "Chapter 1",
                content = "Displayed translation",
                originalContent = "Original text",
                translatedContent = "Displayed translation",
                language = "en",
                timestamp = 3L,
            ),
        )

        val creator = MangaBackupCreator(
            context = mockk<Context>(relaxed = true),
            customMangaManager = mockk<CustomMangaManager>(relaxed = true),
            handler = mockk<DatabaseHandler>(relaxed = true),
            getCategories = mockk<GetCategories>(relaxed = true),
            getChapter = mockk<GetChapter>(relaxed = true),
            getHistory = mockk<GetHistory>(relaxed = true),
            getTrack = mockk<GetTrack>(relaxed = true),
            seriesKnowledgeRepository = repository,
            quoteManagerFactory = { quoteManager },
        )

        val backup = creator(
            mangas = listOf(manga(mangaId)),
            options = BackupOptions(
                categories = false,
                chapters = false,
                tracking = false,
                history = false,
                customInfo = false,
            ),
        ).single()

        assertNull(backup.customTitle)
        assertNotNull(backup.seriesKnowledge)
        assertEquals("advanced", backup.seriesKnowledge?.canon?.mode)
        assertEquals("quote-1", backup.novelQuotes.single().id)
        assertEquals("Original text", backup.novelQuotes.single().originalContent)
    }

    private fun manga(id: Long) = MangaImpl(id = id, source = 1L, url = "/novel").apply {
        ogTitle = "Novel"
    }
}
