package dev.ahmedmohamed.hayai.novel.archive

import dev.ahmedmohamed.hayai.novel.archive.EpubReader.EpubChapter
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubReaderFragmentTest {

    private fun doc(html: String) = Jsoup.parse(html, "", Parser.xmlParser())

    private fun toc(vararg hrefs: String) =
        hrefs.mapIndexed { index, href -> EpubChapter(title = "T$index", href = href, order = index) }

    // Fragment gate: whole file for a lone entry, slice only when a file is shared.

    @Test
    fun `single toc entry per file must not slice`() {
        val entries = toc(
            "Text/section-0004.html#auto_bookmark_toc_top",
            "Text/section-0005.html#auto_bookmark_toc_top",
        )
        assertEquals(1, EpubReader.tocEntryCountForPath(entries, "Text/section-0005.html"))
    }

    @Test
    fun `several toc entries in one file are counted as shared`() {
        val entries = toc(
            "Text/chapter.xhtml#part1",
            "Text/chapter.xhtml#part2",
            "Text/chapter.xhtml#part3",
        )
        assertEquals(3, EpubReader.tocEntryCountForPath(entries, "Text/chapter.xhtml"))
    }

    @Test
    fun `count ignores fragments and surrounding whitespace`() {
        val entries = toc(" a.xhtml#x ", "a.xhtml", "b.xhtml#y")
        assertEquals(2, EpubReader.tocEntryCountForPath(entries, "a.xhtml"))
        assertEquals(1, EpubReader.tocEntryCountForPath(entries, "b.xhtml"))
        assertEquals(0, EpubReader.tocEntryCountForPath(entries, "missing.xhtml"))
    }

    @Test
    fun `count decodes percent-encoded hrefs before matching the decoded entry path`() {
        val entries = toc(
            "Text/Chapter%201.xhtml#a",
            "Text/Chapter%201.xhtml#b",
        )
        assertEquals(2, EpubReader.tocEntryCountForPath(entries, "Text/Chapter 1.xhtml"))
    }

    // Fragment materialization.

    @Test
    fun `anchor on a leading paragraph slices only that paragraph`() {
        val document = doc(
            """
            <html><body>
              <p id="top"><b>Chapter 1</b></p>
              <p>First body paragraph.</p>
              <p>Second body paragraph.</p>
            </body></html>
            """.trimIndent(),
        )

        val html = EpubReader.extractFragmentHtml(document, "top")

        assertTrue(html!!.contains("Chapter 1"))
        assertTrue(!html.contains("First body paragraph"))
    }

    @Test
    fun `heading anchor gathers following siblings until the next same-level heading`() {
        val document = doc(
            """
            <html><body>
              <h2 id="c1">Chapter 1</h2>
              <p>Intro for one.</p>
              <h3 id="s1">Sub</h3>
              <p>Sub body.</p>
              <h2 id="c2">Chapter 2</h2>
              <p>Intro for two.</p>
            </body></html>
            """.trimIndent(),
        )

        val html = EpubReader.extractFragmentHtml(document, "c1")!!

        assertTrue(html.contains("Chapter 1"))
        assertTrue(html.contains("Intro for one."))
        assertTrue(html.contains("Sub body."))
        assertTrue(!html.contains("Chapter 2"))
        assertTrue(!html.contains("Intro for two."))
    }

    @Test
    fun `anchor resolves through the name attribute`() {
        val document = doc(
            """
            <html><body>
              <p name="mark">Body reached through a name attribute, comfortably past the eighty character floor.</p>
            </body></html>
            """.trimIndent(),
        )

        val html = EpubReader.extractFragmentHtml(document, "mark")!!
        assertTrue(html.contains("name attribute"))
    }

    @Test
    fun `url-encoded fragment matches a decoded id`() {
        val document = doc(
            """
            <html><body>
              <h2 id="Chapter 1">Chapter 1</h2>
              <p>Body.</p>
            </body></html>
            """.trimIndent(),
        )

        val html = EpubReader.extractFragmentHtml(document, "Chapter%201")!!
        assertTrue(html.contains("Chapter 1"))
    }

    @Test
    fun `unknown fragment returns null`() {
        val document = doc("<html><body><p>Only paragraph.</p></body></html>")
        assertNull(EpubReader.extractFragmentHtml(document, "nope"))
    }
}
