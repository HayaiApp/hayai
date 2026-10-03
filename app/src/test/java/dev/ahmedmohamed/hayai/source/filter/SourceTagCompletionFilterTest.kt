package dev.ahmedmohamed.hayai.source.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTagCompletionFilterTest {
    private val completion = object : SourceTagCompletionFilter {
        override val hint = ""
        override val maximumSelections = 8
        override val validPrefixes = setOf('-', '~')
        override fun selections(): List<String> = emptyList()
        override fun setSelections(values: List<String>) = Unit
        override fun suggestions(input: String, limit: Int): List<String> = emptyList()
    }

    @Test
    fun `qualified namespace choices stay editable in every supported mode`() {
        assertTrue(completion.isNamespaceSuggestion("female:"))
        assertTrue(completion.isNamespaceSuggestion("-female:"))
        assertTrue(completion.isNamespaceSuggestion(" ~Artist: "))
        assertFalse(completion.isNamespaceSuggestion("female:big breasts"))
        assertFalse(completion.isNamespaceSuggestion("-male:yaoi"))
        assertFalse(completion.isNamespaceSuggestion("+female:"))
        assertFalse(completion.isNamespaceSuggestion("female:person:"))
    }
}
