package dev.ahmedmohamed.hayai.novel.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NovelTranslationStoreTest {
    @Test
    fun `source hashing is stable`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            NovelTranslationHash.sha256("abc"),
        )
    }

    @Test
    fun `completed translation requires stable identity and bounded complete content`() {
        val valid =
            StoredNovelTranslation(
                locator = NovelTranslationLocator(9, 7, "/novel", "/chapter"),
                sourceLanguage = "ja",
                targetLanguage = "en",
                sourceHash = "a".repeat(64),
                translatedContent = "complete text",
                engineId = "GOOGLE_WEB",
                detectedLanguage = "ja",
                createdAt = 10,
                updatedAt = 11,
            )

        assertEquals(valid, valid.requireValid())
        assertThrows(IllegalArgumentException::class.java) { valid.copy(translatedContent = "").requireValid() }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(sourceHash = "not-a-sha256").requireValid() }
    }
}
