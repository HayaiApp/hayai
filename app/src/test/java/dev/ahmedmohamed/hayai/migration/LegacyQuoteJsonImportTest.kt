package dev.ahmedmohamed.hayai.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyQuoteJsonImportTest {
    @Test
    fun `recovers the complete archived series quotes shape`() {
        val quote =
            LegacyQuoteJsonCodec.decodeArchivedRow(
                """
                {
                  "quote_id": "archived-quote",
                  "manga_id": 100,
                  "novel_name": "Archived Novel",
                  "chapter_name": "Chapter 3",
                  "displayed_content": "Archived display",
                  "original_content": "Archived original",
                  "translated_content": "Archived translation",
                  "language": "ar",
                  "timestamp": 1600000000000
                }
                """.trimIndent(),
            )

        assertEquals("archived-quote", quote.id)
        assertEquals(100L, quote.mangaId)
        assertEquals("Archived Novel", quote.novelName)
        assertEquals("Chapter 3", quote.chapterName)
        assertEquals("Archived display", quote.displayedContent)
        assertEquals("Archived original", quote.originalContent)
        assertEquals("Archived translation", quote.translatedContent)
        assertEquals("ar", quote.language)
        assertEquals(1600000000000L, quote.timestamp)
    }

    @Test
    fun `imports the complete legacy quote file shape`() {
        val document =
            LegacyQuoteJsonCodec.decode(
                """
                {
                  "novelId": 42,
                  "quotes": [
                    {
                      "id": "quote-1",
                      "novelName": "Legacy Novel",
                      "chapterName": "Chapter 7",
                      "content": "Displayed text",
                      "originalContent": "Original text",
                      "translatedContent": "Translated text",
                      "language": "ja",
                      "timestamp": 1700000000000
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(42L, document.novelId)
        assertEquals(1, document.quotes.size)
        with(document.quotes.single()) {
            assertEquals("quote-1", id)
            assertEquals("Legacy Novel", novelName)
            assertEquals("Chapter 7", chapterName)
            assertEquals("Displayed text", displayedContent)
            assertEquals("Original text", originalContent)
            assertEquals("Translated text", translatedContent)
            assertEquals("ja", language)
            assertEquals(1700000000000L, timestamp)
        }
    }

    @Test
    fun `accepts nullable fields written by the old quote manager`() {
        val document =
            LegacyQuoteJsonCodec.decode(
                """{"novelId":100,"quotes":[{"id":"old","novelName":"Verification Novel","chapterName":"Legacy chapter","content":"Restored","originalContent":"Restored","translatedContent":null,"language":"en","timestamp":1700000000000}]}""",
            )

        assertEquals(100L, document.novelId)
        assertEquals(null, document.quotes.single().translatedContent)
    }

    @Test
    fun `imports files idempotently without overwriting typed quotes`() {
        val inserted = mutableListOf<LegacyQuoteJsonRow>()
        val result =
            LegacyQuoteJsonImporter.import(
                files =
                    listOf(
                        LegacyQuoteJsonFile(
                            "novel_42.json",
                            """{"novelId":42,"quotes":[{"id":"existing","novelName":"Old","chapterName":"One","content":"Do not overwrite"},{"id":"missing","novelName":"Old","chapterName":"Two","content":"Restore me"}]}""",
                        ),
                    ),
                resolveMangaId = { id, names -> id.takeIf { it == 42L && names == listOf("Old", "Old") } },
                insertIfAbsent = { row ->
                    if (row.id == "existing") false else inserted.add(row)
                },
            )

        assertTrue(result.complete)
        assertEquals(2, result.discoveredQuotes)
        assertEquals(1, result.insertedQuotes)
        assertEquals(listOf("missing"), inserted.map { it.id })
    }

    @Test
    fun `keeps migration pending when a quote file cannot be attached safely`() {
        val result =
            LegacyQuoteJsonImporter.import(
                files =
                    listOf(
                        LegacyQuoteJsonFile(
                            "novel_77.json",
                            """{"novelId":77,"quotes":[{"id":"orphan","novelName":"Missing","chapterName":"One","content":"Keep retrying"}]}""",
                        ),
                    ),
                resolveMangaId = { _, _ -> null },
                insertIfAbsent = { error("An orphaned quote must not be inserted") },
            )

        assertFalse(result.complete)
        assertEquals(1, result.pendingFiles)
        assertEquals(0, result.insertedQuotes)
    }

    @Test
    fun `uses the resolved manga identity instead of a colliding legacy id`() {
        var restored: LegacyQuoteJsonRow? = null
        val result =
            LegacyQuoteJsonImporter.import(
                files =
                    listOf(
                        LegacyQuoteJsonFile(
                            "novel_100.json",
                            """{"novelId":100,"quotes":[{"id":"remapped","novelName":"Right Novel","chapterName":"One","content":"Keep me"}]}""",
                        ),
                    ),
                resolveMangaId = { id, names -> 321L.takeIf { id == 100L && names == listOf("Right Novel") } },
                insertIfAbsent = { row -> restored = row; true },
            )

        assertTrue(result.complete)
        assertEquals(321L, restored?.mangaId)
    }
}
