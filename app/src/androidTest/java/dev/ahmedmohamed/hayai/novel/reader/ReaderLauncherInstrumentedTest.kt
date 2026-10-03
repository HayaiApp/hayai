package dev.ahmedmohamed.hayai.novel.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahmedmohamed.hayai.novel.integration.NovelSeriesType
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class ReaderLauncherInstrumentedTest {
    @Test
    fun manualNovelChoiceSurvivesSourceRefreshAndReloadOfExistingCustomMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = Injekt.get<CustomMangaManager>()
        val manga = Manga.create(Long.MAX_VALUE).apply {
            id = Long.MAX_VALUE - 17
            url = "/manual-novel-fixture"
            title = "Original title"
            genre = "Manga"
            favorite = true
        }
        val previous = manager.getCustomInfo(manga)
        try {
            manager.saveMangaInfo(CustomMangaManager.MangaJson(manga.id, genre = arrayOf("Novel", "Fantasy")))
            manga.copyFrom(SManga.create().apply {
                title = "Refreshed title"
                genre = "Manga, Action"
            })
            assertEquals(NovelSeriesType.TYPE_NOVEL, manga.seriesType())
            assertTrue(ReaderLauncher.isNovel(manga))
            assertEquals("Novel, Fantasy", CustomMangaManager(context).getManga(manga)?.genre)
            assertEquals("Novel, Fantasy", BackupManga.copyFrom(manga, manager).getCustomMangaInfo()?.genre?.joinToString(", "))
            assertEquals(context.getString(R.string.hayai_source_badge_novel), context.resources.getStringArray(R.array.series_type).last())
        } finally {
            manager.saveMangaInfo(previous ?: CustomMangaManager.MangaJson(manga.id))
        }
    }

    @Test
    fun manuallyClassifiedNovelPreviewOpensReaderWithoutAnImageIndex() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manga = Manga.create(Long.MAX_VALUE).apply {
            id = 901L
            genre = "Novel"
        }
        val chapter = Chapter.create().apply { id = 902L }
        val intent = ReaderLauncher.newIntent(context, manga, chapter, initialPage = 8)
        assertEquals(ReaderActivity::class.java.name, intent.component?.className)
        assertEquals(901L, intent.getLongExtra("manga", -1L))
        assertEquals(902L, intent.getLongExtra("chapter", -1L))
        assertFalse(intent.hasExtra(ReaderLauncher.EXTRA_INITIAL_PAGE))
    }
}
