package dev.ahmedmohamed.hayai.novel.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelTranslationPresentationTest {
    @Test
    fun `translation keeps every source line as a separate displayed paragraph`() {
        val presentation = NovelTranslationPresentation.from("First\nSecond\n\nThird", "fr")

        assertEquals(listOf("First", "Second", "Third"), presentation.paragraphs)
        assertEquals("First\n\nSecond\n\nThird", presentation.text)
        assertEquals("fr", presentation.languageTag)
    }
}
