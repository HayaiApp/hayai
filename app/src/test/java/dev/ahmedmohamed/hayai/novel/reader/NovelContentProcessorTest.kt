package dev.ahmedmohamed.hayai.novel.reader

import dev.ahmedmohamed.hayai.novel.source.NovelContentType
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelContentProcessorTest {
    private val processor = NovelContentProcessor()

    @Test
    fun `plain text escapes markup and preserves paragraphs`() {
        val result = processor.process(document("One <two>\nline\n\nThree", NovelContentType.PlainText), "Chapter", options())
        val body = Jsoup.parseBodyFragment(result.html).body()
        assertEquals(2, body.select("p").size)
        assertEquals("One <two> line", body.select("p").first()!!.text())
    }

    @Test
    fun `markdown escapes embedded html`() {
        val result =
            processor.process(
                document("# Title\n\n<script>alert(1)</script> **safe**", NovelContentType.Markdown),
                "Chapter",
                options(),
            )
        assertFalse(result.html.contains("<script", ignoreCase = true))
        assertTrue(Jsoup.parseBodyFragment(result.html).text().contains("safe"))
    }

    @Test
    fun `unsafe active content and event handlers are removed`() {
        val html = """<h1>Chapter 1</h1><p onclick="steal()">Text</p><a href="javascript:steal()">link</a><iframe src="https://bad"></iframe>"""
        val result = processor.process(document(html), "Chapter 1", options(hideChapterTitle = true))
        assertFalse(result.html.contains("onclick", ignoreCase = true))
        assertFalse(result.html.contains("javascript:", ignoreCase = true))
        assertFalse(result.html.contains("iframe", ignoreCase = true))
        assertFalse(result.html.contains("<h1", ignoreCase = true))
    }

    @Test
    fun `full html excludes metadata and a matching leading title in show and hide modes`() {
        val html = """<!doctype html><html><head><title>Chapter 1</title><meta name="description" content="Metadata only"></head><body><h1>Chapter 1</h1><p>Readable chapter body.</p></body></html>"""
        for (hideTitle in listOf(false, true)) {
            val result = processor.process(document(html), "Chapter 1", options(hideChapterTitle = hideTitle))
            val body = Jsoup.parseBodyFragment(result.html).body()
            assertEquals("Readable chapter body.", body.text())
            assertTrue(body.select("title, meta, h1, head").isEmpty())
            assertEquals("https://example.test/book/", result.baseUrl)
        }
    }

    @Test
    fun `fragments remove only an exact matching first readable heading`() {
        for (hideTitle in listOf(false, true)) {
            val wrapped = "<main><section>\n<h2>CHAPTER - 1</h2><p>Story.</p><h2>Chapter 1</h2></section></main>"
            val result = processor.process(document(wrapped), "Chapter 1", options(hideChapterTitle = hideTitle))
            val body = Jsoup.parseBodyFragment(result.html).body()
            assertEquals(1, body.select("h2").size)
            assertEquals("Story. Chapter 1", body.text())

            val nonmatching = processor.process(document("<h1>Chapter 10</h1><p>Story.</p>"), "Chapter 1", options(hideChapterTitle = hideTitle))
            assertEquals("Chapter 10", Jsoup.parseBodyFragment(nonmatching.html).selectFirst("h1")!!.text())

            val narrative = processor.process(document("<p>Story begins.</p><h1>Chapter 1</h1>"), "Chapter 1", options(hideChapterTitle = hideTitle))
            assertEquals("Chapter 1", Jsoup.parseBodyFragment(narrative.html).selectFirst("h1")!!.text())

            val earlierHeading = processor.process(document("<h2>Introduction</h2><h1>Chapter 1</h1>"), "Chapter 1", options(hideChapterTitle = hideTitle))
            assertEquals(2, Jsoup.parseBodyFragment(earlierHeading.html).select("h1, h2").size)
        }
    }

    @Test
    fun `full xhtml preserves explicitly enabled head resources without exposing metadata`() {
        val html = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Book metadata</title><style id="head-style">p{color:red}</style><link rel="stylesheet" href="chapter.css"><script id="head-script">setup()</script></head><body><section><h1>Chapter 1</h1><style id="body-style">p{margin:1em}</style><script id="body-script">ready()</script><p>Story.</p></section></body></html>"""
        val blocked = processor.process(document(html), "Chapter 1", options())
        assertTrue(Jsoup.parseBodyFragment(blocked.html).select("title, style, link, script, h1").isEmpty())
        assertEquals("Story.", Jsoup.parseBodyFragment(blocked.html).body().text())

        val cssOnly = processor.process(document(html), "Chapter 1", options(keepEmbeddedCss = true))
        val cssBody = Jsoup.parseBodyFragment(cssOnly.html).body()
        assertEquals(2, cssBody.select("style").size)
        assertEquals("chapter.css", cssBody.selectFirst("link[rel=stylesheet]")!!.attr("href"))
        assertTrue(cssBody.select("title, script, h1").isEmpty())
        assertEquals("Story.", cssBody.text())

        val both = processor.process(document(html), "Chapter 1", options(keepEmbeddedCss = true, keepEmbeddedJs = true))
        val bothBody = Jsoup.parseBodyFragment(both.html).body()
        assertEquals(2, bothBody.select("style").size)
        assertEquals(2, bothBody.select("script").size)
        assertEquals(listOf("head-style", "", "head-script"), bothBody.children().take(3).map { it.id() })
        assertTrue(bothBody.select("title, h1").isEmpty())
        assertEquals("Story.", bothBody.text())
    }

    @Test
    fun `source scripts and styles are separately gated`() {
        val html = "<style>p{color:red}</style><script>safe()</script><p>Text</p>"
        val cssOnly = processor.process(document(html), "Chapter", options(keepEmbeddedCss = true))
        assertTrue(cssOnly.html.contains("<style>"))
        assertFalse(cssOnly.html.contains("<script>"))
        val both = processor.process(document(html), "Chapter", options(keepEmbeddedCss = true, keepEmbeddedJs = true))
        assertTrue(both.html.contains("<script>"))
    }

    @Test
    fun `media blocking removes every embedded media family`() {
        val html = "<picture><source src='a.webp'><img src='a.jpg'></picture><video src='v.mp4'></video><audio src='a.mp3'></audio>"
        val result = processor.process(document(html), "Chapter", options(blockMedia = true))
        assertTrue(Jsoup.parseBodyFragment(result.html).select("picture, source, img, video, audio").isEmpty())
    }

    @Test
    fun `regex replacement ignores invalid rules and applies valid rules`() {
        val rules = """[{"title":"valid","pattern":"cat","replacement":"dog"},{"title":"bad","pattern":"[","replacement":"x"}]"""
        val result = processor.process(document("<p>cat sat</p>"), "Chapter", options(regexReplacements = rules))
        assertEquals("dog sat", Jsoup.parseBodyFragment(result.html).text())
    }

    @Test
    fun `lowercase and paragraph splitting preserve code`() {
        val words = (1..25).joinToString(" ") { "WORD$it" }
        val result =
            processor.process(
                document("<p>$words</p><pre>KEEP CODE</pre>"),
                "Chapter",
                options(forceLowercase = true, autoSplitText = true, autoSplitWordCount = 10),
            )
        val body = Jsoup.parseBodyFragment(result.html).body()
        assertEquals(3, body.select("p").size)
        assertEquals("KEEP CODE", body.select("pre").text())
        assertTrue(body.select("p").all { it.text() == it.text().lowercase() })
    }

    @Test
    fun `raw html mode displays source as text`() {
        val result = processor.process(document("<b>source</b>"), "Chapter", options(showRawHtml = true))
        assertEquals("<b>source</b>", Jsoup.parseBodyFragment(result.html).select("pre").text())
    }

    private fun document(
        content: String,
        type: NovelContentType = NovelContentType.Html,
    ) = NovelDocument(content, type, "https://example.test/book/")

    private fun options(
        hideChapterTitle: Boolean = false,
        forceLowercase: Boolean = false,
        blockMedia: Boolean = false,
        keepEmbeddedCss: Boolean = false,
        keepEmbeddedJs: Boolean = false,
        showRawHtml: Boolean = false,
        autoSplitText: Boolean = false,
        autoSplitWordCount: Int = 50,
        regexReplacements: String = "[]",
    ) = NovelContentOptions(
        hideChapterTitle,
        forceLowercase,
        blockMedia,
        keepEmbeddedCss,
        keepEmbeddedJs,
        showRawHtml,
        autoSplitText,
        autoSplitWordCount,
        regexReplacements,
    )
}
