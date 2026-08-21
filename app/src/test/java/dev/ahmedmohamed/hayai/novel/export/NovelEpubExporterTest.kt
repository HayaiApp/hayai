package dev.ahmedmohamed.hayai.novel.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class NovelEpubExporterTest {
    @Test
    fun `writes epub mimetype first and navigation`() {
        val output = ByteArrayOutputStream()
        NovelEpubExporter().write(
            NovelEpubBook(
                NovelEpubMetadata("A Book", listOf("Author"), "en", "urn:test:1"),
                listOf(NovelEpubChapter("One", "<p>Hello</p>")),
            ),
            output,
        )

        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            assertEquals("mimetype", zip.nextEntry.name)
            assertEquals("application/epub+zip", zip.readBytes().toString(Charsets.UTF_8))
            val entries = generateSequence { zip.nextEntry?.name }.toList()
            assertTrue("OEBPS/nav.xhtml" in entries)
            assertTrue("OEBPS/text/chapter-1.xhtml" in entries)
        }
    }

    @Test
    fun `filename rejects windows reserved name`() {
        assertEquals("_CON.epub", NovelEpubNaming.safeFileName("CON"))
    }

    @Test
    fun `rewrites every embedded asset form and emits xhtml void tags`() {
        val output = ByteArrayOutputStream()
        NovelEpubExporter().write(
            NovelEpubBook(
                metadata = NovelEpubMetadata("Book", identifier = "id"),
                chapters =
                    listOf(
                        NovelEpubChapter(
                            title = "One",
                            html =
                                """
                                <style>.hero { background: url('pic.png') }</style>
                                <picture>
                                  <source srcset="pic.png 1x, large.png 2x">
                                  <img src="pic.png" style="background-image:url(large.png)">
                                </picture>
                                """.trimIndent(),
                            sourceUrl = "https://example.test/c/",
                        ),
                    ),
                assets =
                    listOf(
                        NovelEpubAsset("pic.png", "image/png", byteArrayOf(1), "https://example.test/c/pic.png"),
                        NovelEpubAsset("large.png", "image/png", byteArrayOf(2), "https://example.test/c/large.png"),
                    ),
            ),
            output,
        )

        val chapter = chapterDocument(output)
        assertTrue(chapter.contains("../assets/asset-1-"))
        assertTrue(chapter.contains("../assets/asset-2-"))
        assertTrue(chapter.contains("srcset="))
        assertTrue(chapter.contains("background"))
        assertTrue(chapter.contains("<img") && chapter.contains(" />"))
        assertTrue(!chapter.contains("https://example.test/c/pic.png"))
    }

    @Test
    fun `duplicate asset names are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            NovelEpubExporter().write(
                NovelEpubBook(
                    NovelEpubMetadata("Book", identifier = "id"),
                    listOf(NovelEpubChapter("One", "x")),
                    listOf(
                        NovelEpubAsset("same.png", "image/png", byteArrayOf(1)),
                        NovelEpubAsset("SAME.png", "image/png", byteArrayOf(2)),
                    ),
                ),
                ByteArrayOutputStream(),
            )
        }
    }

    private fun chapterDocument(output: ByteArrayOutputStream): String {
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.endsWith("chapter-1.xhtml")) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        error("Chapter document was not written")
    }
}
