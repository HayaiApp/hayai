package dev.ahmedmohamed.hayai.adult

import dev.ahmedmohamed.hayai.source.SourceCapabilityRegistry
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.source.Source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LewdClassifierTest {
    @Test
    fun `fixed adult source is lewd without metadata`() {
        assertTrue(LewdClassifier.isLewd(manga(), source(SourceCapabilityRegistry.EH_SOURCE_ID, "E-Hentai")))
    }

    @Test
    fun `non-h tag exempts an adult source`() {
        assertFalse(LewdClassifier.isLewd(manga("Non-H"), source(SourceCapabilityRegistry.EH_SOURCE_ID, "E-Hentai")))
        assertFalse(LewdClassifier.isLewd(manga("Non-H"), source(43, "nHentai")))
    }

    @Test
    fun `non-h tag does not exempt other adult sources`() {
        assertTrue(LewdClassifier.isLewd(manga("Non-H"), source(44, "Hentai2Read")))
    }

    @Test
    fun `ordinary source is classified from tags`() {
        assertTrue(LewdClassifier.isLewd(manga("Action, Smut"), source(42, "Ordinary")))
        assertFalse(LewdClassifier.isLewd(manga("Action, Drama"), source(42, "Ordinary")))
    }

    private fun manga(genres: String? = null) =
        MangaImpl().apply {
            url = "/title"
            title = "Title"
            genre = genres
        }

    private fun source(
        sourceId: Long,
        sourceName: String,
    ) = object : Source {
        override val id = sourceId
        override val name = sourceName
    }
}
