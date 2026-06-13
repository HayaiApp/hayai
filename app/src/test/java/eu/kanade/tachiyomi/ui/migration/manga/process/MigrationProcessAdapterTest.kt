package eu.kanade.tachiyomi.ui.migration.manga.process

import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.Source
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.series.SeriesKnowledgeRepository

class MigrationProcessAdapterTest {

    @Test
    fun `copy migration copies series knowledge without removing it from old manga`() = runTest {
        val repository = seriesKnowledgeRepository()
        val updateManga = updateManga()

        MigrationProcessAdapter.migrateMangaInternal(
            flags = 0,
            enhancedServices = emptyList(),
            coverCache = mockk<CoverCache>(relaxed = true),
            customMangaManager = mockk<CustomMangaManager>(relaxed = true),
            prevSource = null,
            source = mockk<Source>(relaxed = true),
            prevManga = manga(id = OLD_MANGA_ID, title = "Old novel"),
            manga = manga(id = NEW_MANGA_ID, title = "New novel"),
            replace = false,
            seriesKnowledgeRepository = repository,
            updateManga = updateManga,
        )

        coVerify(exactly = 1) { repository.copy(OLD_MANGA_ID, NEW_MANGA_ID) }
        coVerify(exactly = 0) { repository.relink(any(), any()) }
    }

    @Test
    fun `replace migration relinks series knowledge to the new manga`() = runTest {
        val repository = seriesKnowledgeRepository()
        val updateManga = updateManga()

        MigrationProcessAdapter.migrateMangaInternal(
            flags = 0,
            enhancedServices = emptyList(),
            coverCache = mockk<CoverCache>(relaxed = true),
            customMangaManager = mockk<CustomMangaManager>(relaxed = true),
            prevSource = null,
            source = mockk<Source>(relaxed = true),
            prevManga = manga(id = OLD_MANGA_ID, title = "Old novel", dateAdded = 123L),
            manga = manga(id = NEW_MANGA_ID, title = "New novel"),
            replace = true,
            seriesKnowledgeRepository = repository,
            updateManga = updateManga,
        )

        coVerify(exactly = 1) { repository.relink(OLD_MANGA_ID, NEW_MANGA_ID) }
        coVerify(exactly = 0) { repository.copy(any(), any()) }
    }

    private fun seriesKnowledgeRepository(): SeriesKnowledgeRepository =
        mockk {
            coEvery { copy(any(), any()) } just Runs
            coEvery { relink(any(), any()) } just Runs
        }

    private fun updateManga(): UpdateManga =
        mockk {
            coEvery { await(any()) } returns true
        }

    private fun manga(id: Long, title: String, dateAdded: Long = 0L): Manga {
        val manga = mockk<Manga>(relaxed = true)
        every { manga.id } returns id
        every { manga.title } returns title
        every { manga.favorite } returns true
        every { manga.date_added } returns dateAdded
        return manga
    }

    private companion object {
        const val OLD_MANGA_ID = 10L
        const val NEW_MANGA_ID = 20L
    }
}
