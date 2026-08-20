package dev.ahmedmohamed.hayai.novel.source

import eu.kanade.tachiyomi.source.model.Page
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelDocumentLoaderTest {
    @Test
    fun `detects local text formats and defaults extensionless online content to html`() {
        assertEquals(NovelContentType.PlainText, NovelDocumentLoader.detectContentType(listOf(Page(0, "chapter.txt")), "text"))
        assertEquals(NovelContentType.Markdown, NovelDocumentLoader.detectContentType(listOf(Page(0, "chapter.md")), "# title"))
        assertEquals(NovelContentType.Html, NovelDocumentLoader.detectContentType(listOf(Page(0, "chapter.xhtml#part")), "<p>x</p>"))
        assertEquals(NovelContentType.Html, NovelDocumentLoader.detectContentType(listOf(Page(0, "https://example/chapter")), "<html>x"))
    }
}
