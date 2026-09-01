package dev.ahmedmohamed.hayai.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyTranslationCacheMigrationTest {
    @Test
    fun `legacy completed cache row decodes without depending on queue state`() {
        val row =
            LegacyTranslationCacheCodec.decode(
                """{"manga_id":7,"chapter_id":9,"source_language":"ja","target_language":"en","original_hash":"${"A".repeat(64)}","translated_content":"complete text","engine_id":"google","created_at":123}""",
            )

        assertEquals(7, row.mangaId)
        assertEquals(9, row.chapterId)
        assertEquals("complete text", row.translatedContent)
        assertTrue(row.isValid())
    }

    @Test
    fun `legacy promotion validation refuses partial or unverifiable results`() {
        val valid =
            LegacyTranslationCacheRow(
                mangaId = 7,
                chapterId = 9,
                sourceLanguage = "ja",
                targetLanguage = "en",
                originalHash = "a".repeat(64),
                translatedContent = "complete text",
                engineId = "google",
                createdAt = 123,
            )

        assertFalse(valid.copy(originalHash = "legacy-hash").isValid())
        assertFalse(valid.copy(translatedContent = "").isValid())
        assertFalse(valid.copy(chapterId = 0).isValid())
    }
}
