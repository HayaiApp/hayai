package dev.ahmedmohamed.hayai.novel.settings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelCustomizationModelsTest {
    @Test
    fun `legacy snippet json defaults run on append to false`() {
        val snippet = Json.decodeFromString<NovelCodeSnippet>("""{"id":"a","title":"A","code":"x()","enabled":true}""")
        assertFalse(snippet.runOnAppend)
    }

    @Test
    fun `run on append survives serialization`() {
        val encoded = Json.encodeToString(NovelCodeSnippet("a", "A", "x()", runOnAppend = true))
        assertTrue(Json.decodeFromString<NovelCodeSnippet>(encoded).runOnAppend)
    }

    @Test
    fun `literal replacement honors whole word and case settings`() {
        val rule = NovelRegexReplacement("Name", "cat", "fox", isRegex = false, matchWholeWord = true, caseSensitive = false)
        assertEquals("fox scatter fox", NovelReplacementEngine.apply("Cat scatter cat", rule).getOrThrow())
    }

    @Test
    fun `unsafe regex is rejected by the test engine`() {
        val rule = NovelRegexReplacement("Unsafe", "(a+)+", "x")
        assertTrue(NovelReplacementEngine.apply("aaaa", rule).isFailure)
    }
}
