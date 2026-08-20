package dev.ahmedmohamed.hayai.novel.quote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NovelQuoteTextTest {
    @Test
    fun `normalizes line endings and surrounding whitespace`() {
        assertEquals("first\nsecond", NovelQuoteText.normalize("  first\r\nsecond  "))
    }

    @Test
    fun `rejects empty and excessively large selections`() {
        assertThrows(IllegalArgumentException::class.java) { NovelQuoteText.normalize("  \n ") }
        assertThrows(IllegalArgumentException::class.java) { NovelQuoteText.normalize("x".repeat(NovelQuoteText.MAX_LENGTH + 1)) }
    }
}
