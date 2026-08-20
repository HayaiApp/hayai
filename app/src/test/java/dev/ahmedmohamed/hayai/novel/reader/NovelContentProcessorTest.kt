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
