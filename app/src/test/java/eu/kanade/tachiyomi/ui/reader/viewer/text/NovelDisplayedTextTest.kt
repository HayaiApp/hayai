package eu.kanade.tachiyomi.ui.reader.viewer.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelDisplayedTextTest {

    @Test
    fun `translation state ignores unchanged visible text`() {
        val state = NovelDisplayedText.translationState(
            translationEnabled = true,
            originalContent = "<p>Hello world</p>",
            displayedContent = "<div><p>Hello   world</p></div>",
            language = "en",
        )

        assertFalse(state.translated)
        assertNull(state.language)
    }

    @Test
    fun `translation state detects changed displayed text`() {
        val state = NovelDisplayedText.translationState(
            translationEnabled = true,
            originalContent = "<p>Bonjour le monde</p>",
            displayedContent = "<p>Hello world</p>",
            language = "en",
        )

        assertTrue(state.translated)
        assertEquals("en", state.language)
    }

    @Test
    fun `translated quote keeps displayed selection and original paragraph`() {
        val quoteText = NovelDisplayedText.quoteText(
            displayedText = "Hello world",
            originalParagraph = "Bonjour le monde",
            translationState = NovelDisplayedTranslationState(
                translated = true,
                language = "en",
            ),
        )

        assertEquals("Hello world", quoteText.displayedContent)
        assertEquals("Bonjour le monde", quoteText.originalContent)
        assertEquals("Hello world", quoteText.translatedContent)
        assertEquals("en", quoteText.language)
    }

    @Test
    fun `plain quote does not get translation metadata`() {
        val quoteText = NovelDisplayedText.quoteText(
            displayedText = "Hello world",
            originalParagraph = "Hello world",
            translationState = NovelDisplayedTranslationState(
                translated = false,
                language = null,
            ),
        )

        assertEquals("Hello world", quoteText.displayedContent)
        assertNull(quoteText.originalContent)
        assertNull(quoteText.translatedContent)
        assertNull(quoteText.language)
    }
}
