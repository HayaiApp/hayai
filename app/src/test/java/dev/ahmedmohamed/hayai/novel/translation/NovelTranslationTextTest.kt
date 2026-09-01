package dev.ahmedmohamed.hayai.novel.translation

import dev.ahmedmohamed.hayai.novel.source.NovelContentType
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NovelTranslationTextTest {
    @Test
    fun `canonical text preserves paragraph boundaries without reader chrome`() {
        val document =
            NovelDocument(
                "<h1>Source heading</h1><p>First  paragraph</p><p>Second paragraph</p>",
                NovelContentType.Html,
            )

        val text = NovelTranslationText.canonical(document)

        assertEquals("Source heading\nFirst paragraph\nSecond paragraph", text)
    }

    @Test
    fun `provider fingerprint excludes rotating credentials but includes provider shape`() {
        val first = NovelTranslationSettings(endpoint = "https://example.test", apiKey = "first").providerConfigHash()
        val rotated = NovelTranslationSettings(endpoint = "https://example.test", apiKey = "second").providerConfigHash()
        val otherEndpoint = NovelTranslationSettings(endpoint = "https://other.test", apiKey = "first").providerConfigHash()

        assertEquals(first, rotated)
        assertNotEquals(first, otherEndpoint)
    }
}
