package dev.ahmedmohamed.hayai.novel.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelHtmlDocumentBuilderTest {
    @Test
    fun `android argb colors are emitted as css rgba hex`() {
        assertTrue(NovelHtmlDocumentBuilder.color(0x80402010.toInt()) == "#40201080")
    }

    @Test
    fun `chapter title and font family are escaped`() {
        val html =
            NovelHtmlDocumentBuilder.build(
                ProcessedNovelContent("<p>Body</p>", null),
                "A <chapter>",
                style(fontFamily = "A\"B"),
            )
        assertTrue(html.contains("A &lt;chapter&gt;"))
        assertTrue(html.contains("font-family:\"A\\\"B\""))
    }

    @Test
    fun `chapter title can be omitted`() {
        val html = NovelHtmlDocumentBuilder.build(ProcessedNovelContent("<p>Body</p>", null), "Hidden", style(hideTitle = true))
        assertFalse(html.contains("<h1 class=\"hayai-chapter-title\""))
    }

    private fun style(
        fontFamily: String = "sans-serif",
        hideTitle: Boolean = false,
    ) = NovelReaderStyle(
        fontSize = 16,
        fontFamily = fontFamily,
        lineHeight = 1.5f,
        textAlign = "left",
        textColor = 0xFF111111.toInt(),
        backgroundColor = 0xFFFFFFFF.toInt(),
        linkColor = 0xFF0000FF.toInt(),
        paragraphIndent = 0f,
        paragraphSpacing = 0.5f,
        marginLeft = 16,
        marginRight = 16,
        marginTop = 16,
        marginBottom = 16,
        useOriginalFonts = false,
        textSelectable = true,
        hideChapterTitle = hideTitle,
        sourceCssPriority = false,
        renderingMode = "default",
        customCss = "",
        customJs = "",
        ttsHighlightColor = 0xFFFFFF00.toInt(),
        ttsHighlightTextColor = 0xFF000000.toInt(),
        ttsHighlightStyle = "background",
        keepTtsHighlightInView = true,
    )
}
