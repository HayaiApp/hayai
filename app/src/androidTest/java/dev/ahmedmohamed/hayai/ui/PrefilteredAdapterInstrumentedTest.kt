package dev.ahmedmohamed.hayai.ui

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.library.LibraryCategoryAdapter
import eu.kanade.tachiyomi.ui.library.LibraryHeaderItem
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaDetailsAdapter
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterItem
import eu.kanade.tachiyomi.widget.AutofitRecyclerView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class PrefilteredAdapterInstrumentedTest {
    private lateinit var activity: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        activity.close()
    }

    @Test
    fun chapterSearchKeepsMatchesHeaderAndSameQueryRefresh() {
        lateinit var adapter: MangaDetailsAdapter
        lateinit var controller: MangaDetailsController
        val manga = Manga.create("/adapter-fixture", "Adapter fixture").apply { id = -123L }
        val first = chapter(manga, 1, "Needle chapter")
        val scanlator = chapter(manga, 2, "Another chapter", "Needle group")
        val other = chapter(manga, 3, "Unrelated chapter")
        val later = chapter(manga, 4, "New needle chapter")
        activity.onActivity {
            controller = MangaDetailsController(manga)
            adapter = MangaDetailsAdapter(controller)
            adapter.addScrollableHeader(controller.presenter.headerItem)
            adapter.items = listOf(first, scanlator, other)
            adapter.performFilter()
        }
        await { chapterIds(adapter) == listOf(1L, 2L, 3L) }

        activity.onActivity {
            adapter.setFilter("needle")
            adapter.performFilter()
        }
        await { chapterIds(adapter) == listOf(1L, 2L) }
        activity.onActivity {
            assertTrue(adapter.hasFilter())
            assertEquals("needle", adapter.getFilter(String::class.java))
            assertTrue(adapter.currentItems.contains(controller.presenter.headerItem))
            adapter.items = listOf(first, scanlator, other, later)
            adapter.performFilter()
        }
        await { chapterIds(adapter) == listOf(1L, 2L, 4L) }

        activity.onActivity {
            adapter.setFilter("missing")
            adapter.performFilter()
        }
        await { chapterIds(adapter).isEmpty() && adapter.currentItems.contains(controller.presenter.headerItem) }
        activity.onActivity {
            adapter.setFilter("")
            adapter.performFilter()
        }
        await { chapterIds(adapter) == listOf(1L, 2L, 3L, 4L) }
    }

    @Test
    fun librarySearchRefreshesSameQueryAndKeepsCategoryPlaceholder() {
        lateinit var adapter: LibraryCategoryAdapter
        val category = Category.create("Fixture").apply { id = 123 }
        val header = LibraryHeaderItem({ category }, 123)
        val first = libraryItem(header, 1, "Needle manga")
        val other = libraryItem(header, 2, "Unrelated manga")
        val later = libraryItem(header, 3, "New needle manga")
        val placeholder = LibraryItem(LibraryManga.createBlank(123), header, null)
        activity.onActivity { host ->
            LibraryItem.updateDisplayPrefs()
            adapter = LibraryCategoryAdapter(null)
            val recycler = AutofitRecyclerView(host)
            recycler.useStaggered(Injekt.get<PreferencesHelper>())
            recycler.adapter = adapter
            adapter.setFilter("title:needle")
            adapter.setItems(listOf(first, other))
        }
        await { libraryIds(adapter) == listOf(1L) }
        activity.onActivity {
            assertTrue(adapter.hasFilter())
            assertEquals("title:needle", adapter.getFilter(String::class.java))
            assertTrue(adapter.currentItems.contains(header))
            adapter.setItems(listOf(first, other, later))
        }
        await { libraryIds(adapter) == listOf(1L, 3L) }

        activity.onActivity {
            adapter.setFilter("title:missing")
            // The single-category no-results branch supplies this same blank row to updateDataSet.
            adapter.updateDataSet(listOf(placeholder), true)
        }
        await { libraryIds(adapter) == listOf(Long.MIN_VALUE) }
        activity.onActivity {
            assertTrue(adapter.currentItems.contains(header))
            assertTrue(adapter.hasFilter())
            adapter.updateDataSet(listOf(placeholder), false)
            assertEquals(listOf(Long.MIN_VALUE), libraryIds(adapter))
            assertTrue(adapter.hasFilter())
            adapter.setFilter("")
            adapter.setItems(listOf(first, other, later))
        }
        await { libraryIds(adapter) == listOf(1L, 2L, 3L) }
    }

    private fun chapter(manga: Manga, id: Long, name: String, scanlator: String? = null): ChapterItem =
        ChapterItem(
            Chapter.create().apply {
                this.id = id
                manga_id = manga.id
                url = "/chapter/$id"
                this.name = name
                this.scanlator = scanlator
                chapter_number = id.toFloat()
            },
            manga,
        )

    private fun libraryItem(header: LibraryHeaderItem, id: Long, title: String): LibraryItem =
        LibraryItem(
            LibraryManga().apply {
                this.id = id
                url = "/manga/$id"
                this.title = title
                category = header.catId
            },
            header,
            null,
        )

    private fun chapterIds(adapter: MangaDetailsAdapter): List<Long?> =
        adapter.currentItems.filterIsInstance<ChapterItem>().map { it.id }

    private fun libraryIds(adapter: LibraryCategoryAdapter): List<Long?> =
        adapter.currentItems.filterIsInstance<LibraryItem>().map { it.manga.id }

    private fun await(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        var passed = false
        while (!passed && SystemClock.uptimeMillis() < end) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            activity.onActivity { passed = check() }
            if (!passed) SystemClock.sleep(50)
        }
        assertTrue("The production adapter did not show the expected filtered rows", passed)
    }
}
