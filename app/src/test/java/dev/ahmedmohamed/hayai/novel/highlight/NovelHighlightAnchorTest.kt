package dev.ahmedmohamed.hayai.novel.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NovelHighlightAnchorTest {
    @Test fun `context resolves repeated text after insertion`() {
        val original = "Alpha repeated phrase first. Middle repeated phrase chosen. Omega"
        val start = original.indexOf("repeated phrase", 10)
        val anchor = NovelHighlightAnchor.capture(original, start, start + "repeated phrase".length)
        val edited = "Preface inserted. $original"
        assertEquals(edited.indexOf("repeated phrase", 40), anchor.resolve(edited)?.start)
    }

    @Test fun `fuzzy recovery tolerates a small source edit`() {
        val text = "A sufficiently long highlighted sentence for recovery."
        val anchor = NovelHighlightAnchor.capture(text, 2, text.length)
        assertNotNull(anchor.resolve("A sufficiently long highlighted phrase for recovery."))
    }
}
