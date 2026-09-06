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

    @Test
    fun `selection anchor is cached before the floating toolbar collapses it`() {
        val html = NovelHtmlDocumentBuilder.build(ProcessedNovelContent("<p>Selectable body</p>", null), "Chapter", style())

        assertTrue(html.contains("const selectionAnchor = () =>"))
        assertTrue(html.contains("const anchor = selectionAnchor() || lastSelection"))
        assertTrue(html.contains("lastSelection = null"))
        assertTrue(html.contains("closest('.hayai-chapter-block')"))
    }

    @Test
    fun `paged vertical documents expose page counting and vertical Japanese css`() {
        val html =
            NovelHtmlDocumentBuilder.build(
                ProcessedNovelContent("<p>縦書き</p>", null),
                "Chapter",
                style(renderingMode = "paged", writingDirection = NovelWritingDirection.VerticalRl),
            )

        assertTrue(html.contains("class=\"hayai-paged\""))
        assertTrue(html.contains("data-writing-direction=\"vertical-rl\""))
        assertTrue(html.contains("writing-mode:vertical-rl"))
        assertTrue(html.contains("text-orientation:mixed"))
        assertTrue(html.contains("html.hayai-paged { height:100%; overflow-x:auto"))
        assertTrue(html.contains("const pageRects = block =>"))
        assertTrue(html.contains("return blockPageLocation(activeBlock())"))
        assertTrue(html.contains("const pageStride = () => Math.max(1, scroller().clientWidth || innerWidth)"))
        assertTrue(html.contains("direction)*pageStride()"))
        assertFalse(html.contains("innerWidth * fraction * direction"))
        assertTrue(html.contains("column-gap:32px"))
        assertTrue(html.contains("break-before:column"))
        assertTrue(html.contains("line-break:strict"))
        assertTrue(html.contains("text-combine-upright:digits 2"))
        assertTrue(html.contains("HayaiReader.onPageLocation"))
        assertTrue(html.contains("ResizeObserver"))
    }

    @Test
    fun `custom script is rerun after translation and pristine original restoration`() {
        val html =
            NovelHtmlDocumentBuilder.build(
                ProcessedNovelContent("<p>He said \"hello\"</p>", null),
                "Chapter",
                style(customJs = "document.body.dataset.customized = 'yes';"),
            )

        assertTrue(html.contains("window.hayaiRunCustomScript = () =>"))
        assertTrue(html.contains("window.hayaiReader.prepareCustomization();"))
        assertTrue(html.contains("const root=rememberOriginal(activeBlock())"))
        assertTrue(html.contains("originalHtml.get(String(root.dataset.chapterId))"))
        assertTrue(html.contains("root.innerHTML=original;rerunCustomScript();"))
        assertTrue(html.contains("document.body.dataset.customized = 'yes';"))
    }

    @Test
    fun `custom script closing tags cannot escape its wrapper`() {
        val html =
            NovelHtmlDocumentBuilder.build(
                ProcessedNovelContent("<p>Body</p>", null),
                "Chapter",
                style(customJs = "document.body.dataset.value = '</script>';"),
            )

        assertTrue(html.contains("<\\/script>"))
        assertFalse(html.contains("dataset.value = '</script>'"))
    }

    private fun style(
        fontFamily: String = "sans-serif",
        hideTitle: Boolean = false,
        renderingMode: String = "default",
        writingDirection: NovelWritingDirection = NovelWritingDirection.Horizontal,
        customJs: String = "",
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
        renderingMode = renderingMode,
        customCss = "",
        customJs = customJs,
        ttsHighlightColor = 0xFFFFFF00.toInt(),
        ttsHighlightTextColor = 0xFF000000.toInt(),
        ttsHighlightStyle = "background",
        keepTtsHighlightInView = true,
        writingDirection = writingDirection,
    )
}
