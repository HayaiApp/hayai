package dev.ahmedmohamed.hayai.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextUtilsTest {
    @Test
    fun `blank text has no chunks`() {
        assertTrue(TtsTextUtils.splitTextForTts(" \n ", 20).isEmpty())
    }

    @Test
    fun `short text remains intact`() {
        assertEquals(listOf("A short sentence."), TtsTextUtils.splitTextForTts("  A short sentence.  ", 100))
    }

    @Test
    fun `chunks never exceed the requested limit`() {
        val chunks = TtsTextUtils.splitTextForTts("one two three four five six seven eight nine ten", 12)
        assertTrue(chunks.all { it.length <= 12 })
        assertEquals("one two three four five six seven eight nine ten", chunks.joinToString(" "))
    }

    @Test
    fun `sentence punctuation is preferred for latin and cjk text`() {
        val latin = TtsTextUtils.splitTextForTts("First sentence. Second sentence has more text.", 25)
        val cjk = TtsTextUtils.splitTextForTts("最初の文です。次の文章にはさらに文字があります。", 12)
        assertEquals("First sentence.", latin.first())
        assertTrue(cjk.first().endsWith("。"))
    }

    @Test
    fun `long unbroken words are split without data loss`() {
        val text = "abcdefghijklmnopqrstuvwxyz"
        val chunks = TtsTextUtils.splitTextForTts(text, 5)
        assertTrue(chunks.all { it.length <= 5 })
        assertEquals(text, chunks.joinToString(""))
    }
}
