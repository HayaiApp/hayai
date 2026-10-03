package dev.ahmedmohamed.hayai.source.filter

import dev.ahmedmohamed.hayai.adult.eh.domain.EhCategory
import dev.ahmedmohamed.hayai.source.SourceCapabilityRegistry
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceGenreSearchTest {
    @Test
    fun `gallery tags keep namespaces and multiword values`() {
        val tags = CompletionFilter()
        val source = FakeSource(SourceCapabilityRegistry.EXH_SOURCE_ID)
        assertTrue(SourceGenreSearch.apply(source, FilterList(tags), listOf("female:big breasts")))
        assertEquals(listOf("female:big breasts"), tags.values)
    }

    @Test
    fun `ordinary sources keep their existing genre matching`() {
        val tags = CompletionFilter()
        assertFalse(SourceGenreSearch.apply(FakeSource(1), FilterList(tags), listOf("Action")))
        assertTrue(tags.values.isEmpty())
    }

    @Test
    fun `gallery categories distinguish category filters from tags`() {
        assertEquals(EhCategory.ArtistCg, SourceGenreSearch.category(listOf("Artist CG")))
        assertEquals(EhCategory.NonH, SourceGenreSearch.category(listOf("Non-H")))
        assertEquals(null, SourceGenreSearch.category(listOf("female:manga")))
    }

    private class FakeSource(override val id: Long) : Source { override val name = "Source" }

    private class CompletionFilter : Filter.Text("Tags"), SourceTagCompletionFilter {
        var values = emptyList<String>()
        override val hint = ""
        override val maximumSelections = 8
        override val validPrefixes = setOf('-', '~')
        override fun selections() = values
        override fun setSelections(values: List<String>) { this.values = values }
        override fun suggestions(input: String, limit: Int) = emptyList<String>()
    }
}
