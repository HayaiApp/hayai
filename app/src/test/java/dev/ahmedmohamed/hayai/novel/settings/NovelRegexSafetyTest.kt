package dev.ahmedmohamed.hayai.novel.settings

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NovelRegexSafetyTest {
    @Test
    fun `accepts bounded ordinary expressions`() {
        assertNull(NovelRegexSafety.rejectionReason("(?i)chapter\\s+[0-9]+"))
    }

    @Test
    fun `rejects malformed backreference and nested repetition patterns`() {
        assertNotNull(NovelRegexSafety.rejectionReason("["))
        assertNotNull(NovelRegexSafety.rejectionReason("(a+)+"))
        assertNotNull(NovelRegexSafety.rejectionReason("(a)\\1"))
    }

    @Test
    fun `rejects patterns beyond the reader boundary`() {
        assertNotNull(NovelRegexSafety.rejectionReason("a".repeat(513)))
    }
}
