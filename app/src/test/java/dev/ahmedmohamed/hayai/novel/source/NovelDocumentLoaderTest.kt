package dev.ahmedmohamed.hayai.novel.source

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NovelDocumentLoaderTest {
    @Test
    fun `image source marked novel has a recoverable text capability failure`() = runBlocking {
        val source = object : Source {
            override val id = 1L
            override val name = "Images"
            override suspend fun getPageList(chapter: SChapter) = listOf(Page(0, "page.jpg"))
        }
        try {
            NovelDocumentLoader.load(source, SChapter.create(), explicitlyNovel = true)
            fail("An image source cannot supply novel text")
        } catch (failure: NovelFailure) {
            assertEquals(NovelFailure.Code.SourceNotNovel, failure.code)
        }
    }

    @Test
    fun `explicit novel can load legacy text pages without source marker`() = runBlocking {
        val source = object : Source {
            override val id = 1L
            override val name = "Legacy text"
            override suspend fun getPageList(chapter: SChapter) = listOf(Page(0, "chapter.txt").apply { text = "Saved chapter text" })
        }
        val loaded = NovelDocumentLoader.load(source, SChapter.create(), explicitlyNovel = true)
        assertEquals("Saved chapter text", loaded.content)
        assertEquals(NovelContentType.PlainText, loaded.contentType)
    }

    @Test
    fun `detects local text formats and defaults extensionless online content to html`() {
        assertEquals(NovelContentType.PlainText, NovelDocumentLoader.detectContentType(listOf(Page(0, "chapter.txt")), "text"))
        assertEquals(NovelContentType.Markdown, NovelDocumentLoader.detectContentType(listOf(Page(0, "chapter.md")), "# title"))
        assertEquals(NovelContentType.Html, NovelDocumentLoader.detectContentType(listOf(Page(0, "chapter.xhtml#part")), "<p>x</p>"))
        assertEquals(NovelContentType.Html, NovelDocumentLoader.detectContentType(listOf(Page(0, "https://example/chapter")), "<html>x"))
    }
}
