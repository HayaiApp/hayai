package dev.ahmedmohamed.hayai.novel.download

import dev.ahmedmohamed.hayai.novel.source.NovelContentType
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAssetReferencesTest {
    @Test
    fun `extracts deduplicated encoded assets from both supported schemes`() {
        val content =
            """
            <img src="hayai-novel-image://OEBPS/Images/cover%20art.jpg">
            ![](novel-image://OEBPS/Images/cover%20art.jpg)
            <audio src='novel-image:///Audio/chapter%201.mp3'></audio>
            <img src='novel-image://Images/panel.png?size=large#page'>
            """.trimIndent()

        val references = NovelAssetReferences.extract(NovelDocument(content, NovelContentType.Html))
        assertEquals(3, references.size)
        assertEquals("OEBPS/Images/cover art.jpg", NovelAssetReferences.providerPath(references.first()))
    }

    @Test
    fun `rejects traversal references`() {
        assertEquals(null, NovelAssetReferences.providerPath("novel-image://../secret"))
    }

    @Test
    fun `rewrites relative remote srcset and css resources without retaining a base url`() {
        val document =
            NovelDocument(
                """<link href="style.css"><img srcset="/a.png 1x, https://cdn.test/b.png 2x"><style>x{background:url('bg.jpg')}</style>""",
                NovelContentType.Html,
                "https://example.test/book/chapter/",
            )
        val references = NovelAssetReferences.extract(document)
        val rewritten = NovelAssetReferences.rewrite(document, references.associateWith { "offline/${it.hashCode()}" })

        assertEquals(setOf("style.css", "/a.png", "https://cdn.test/b.png", "bg.jpg"), references)
        assertEquals(null, rewritten.baseUrl)
        references.forEach { reference -> assertEquals(false, rewritten.content.contains(reference)) }
    }

    @Test
    fun `rewrites markdown image resources`() {
        val document = NovelDocument("![cover](images/cover.jpg \"Cover\")", NovelContentType.Markdown, "https://example.test/")
        val rewritten = NovelAssetReferences.rewrite(document, mapOf("images/cover.jpg" to "offline/image"))

        assertEquals(setOf("images/cover.jpg"), NovelAssetReferences.extract(document))
        assertEquals(false, rewritten.content.contains("images/cover.jpg"))
        assertEquals(null, rewritten.baseUrl)
    }
}
