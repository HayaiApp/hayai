package dev.ahmedmohamed.hayai.novel.search

import dev.ahmedmohamed.hayai.novel.download.NovelDownloadStore
import dev.ahmedmohamed.hayai.novel.source.NovelContentType
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import eu.kanade.tachiyomi.data.database.models.Chapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelChapterTextLibraryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `search scans stored visible text and distinguishes corrupt from missing chapters`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val store = NovelDownloadStore(root)
        val chapters = (1L..3L).map(::chapter)
        store.save(7, chapters[0].url, NovelDocument("<head><title>needle</title></head><body><script>needle</script><p>A needle here.</p></body>", NovelContentType.Html), null)
        store.save(7, chapters[1].url, NovelDocument("needle", NovelContentType.PlainText), null)
        root.walkTopDown().first { it.name == "document.txt" && it.readText() == "needle" }.writeText("tampered")
        val library = NovelChapterTextLibrary(chapters, store, 7)

        val result = library.search(NovelChapterTextSearch.pattern("needle", NovelTextSearchOptions()))

        assertEquals(listOf(1L), result.matches.map { it.chapter.id })
        assertEquals(1, result.matches.single().matches.count)
        assertEquals(1, result.searched)
        assertEquals(1, result.missing)
        assertEquals(1, result.unreadable)
        val statistics = library.statistics()
        assertEquals(3L, statistics.totalWords)
        assertEquals(1, statistics.countedChapters)
        assertEquals(1, statistics.notDownloadedChapters)
        assertEquals(1, statistics.unreadableChapters)
    }

    @Test fun `imported local chapters contribute to text search without a download copy`() = runBlocking {
        val store = NovelDownloadStore(temporaryFolder.newFolder())
        val library = NovelChapterTextLibrary(listOf(chapter(1)), store, 8) { NovelDocument("# Heading\n\nImported **needle**", NovelContentType.Markdown) }
        assertEquals(1, library.search(NovelChapterTextSearch.pattern("needle", NovelTextSearchOptions())).searched)
        assertEquals(3L, library.statistics().totalWords)
    }

    private fun chapter(number: Long): Chapter = Chapter.create().apply { id = number; name = "Chapter $number"; url = "/chapter/$number" }
}
