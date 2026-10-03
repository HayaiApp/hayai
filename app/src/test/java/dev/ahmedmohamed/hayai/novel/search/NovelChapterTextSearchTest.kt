package dev.ahmedmohamed.hayai.novel.search

import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelChapterTextSearchTest {
    @Test fun `literal searches quote regex metacharacters and support match case`() {
        val text = "A.B a.b axb"
        assertEquals(2, search(text, "a.b").count)
        assertEquals(1, search(text, "a.b", NovelTextSearchOptions(caseSensitive = true)).count)
    }

    @Test fun `whole word matching respects Unicode letters and numbers`() {
        assertEquals(2, search("مرحبا بالمرحبا مرحبا2 مرحبا", "مرحبا", NovelTextSearchOptions(wholeWord = true)).count)
    }

    @Test fun `regex counts all matches but only retains three correctly highlighted snippets`() {
        val result = search("Chapter 12. Chapter 34. Chapter 56. Chapter 78.", "[0-9]+", NovelTextSearchOptions(regex = true))
        assertEquals(4, result.count)
        assertEquals(listOf("12", "34", "56"), result.snippets.map { it.text.substring(it.start, it.end) })
    }

    @Test fun `zero width regex matches do not create empty results`() {
        assertEquals(0, search("text", "\\b", NovelTextSearchOptions(regex = true)).count)
    }

    @Test(expected = NovelFailure::class) fun `existing regex safety rejects nested repetition`() {
        search("a".repeat(1000), "(a+)+$", NovelTextSearchOptions(regex = true))
    }

    @Test(expected = CancellationException::class) fun `cancellation interrupts the matcher before a chapter finishes`() {
        var checks = 0
        NovelChapterTextSearch.find("a".repeat(10000), NovelChapterTextSearch.pattern("z", NovelTextSearchOptions())) { ++checks < 2 }
    }

    @Test fun `long matches keep snippet previews bounded`() {
        val result = search("x".repeat(10000), "x+", NovelTextSearchOptions(regex = true))
        val snippet = result.snippets.single()
        assertTrue(snippet.text.length <= 322)
        assertEquals("x".repeat(200), snippet.text.substring(snippet.start, snippet.end))
    }

    private fun search(text: String, query: String, options: NovelTextSearchOptions = NovelTextSearchOptions()) = NovelChapterTextSearch.find(text, NovelChapterTextSearch.pattern(query, options))
}
