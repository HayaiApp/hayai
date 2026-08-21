package dev.ahmedmohamed.hayai.source.enhanced

import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedBatchEntry
import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedResolveResult
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedSourceActionsTest {
    @Test
    fun `nhentai details expose in-app import batch and preview actions`() {
        val source = EnhancedSourceRegistry.wrap(EnhancedTestHttpSource(1, "NHentai", "https://nhentai.net")) as HayaiEnhancedHttpSource
        val manga = SManga.create().apply { url = "/g/123/"; title = "Gallery" }

        val actions = EnhancedSourceActionCatalog.forManga(source, manga)

        assertTrue(actions.any { it is EnhancedSourceAction.OpenInApp })
        assertTrue(actions.any { it is EnhancedSourceAction.OpenBatchAdd })
        assertTrue(actions.any { it is EnhancedSourceAction.OpenPagePreviews })
        assertFalse(actions.any { it is EnhancedSourceAction.OpenSourceSettings })
    }

    @Test
    fun `link router rejects ambiguity instead of selecting an arbitrary extension`() {
        val first = EnhancedSourceRegistry.wrap(EnhancedTestHttpSource(1, "NHentai", "https://nhentai.net")) as HayaiEnhancedHttpSource
        val second = EnhancedSourceRegistry.wrap(EnhancedTestHttpSource(2, "NHentai mirror", "https://nhentai.net")) as HayaiEnhancedHttpSource
        val result = EnhancedSourceLinkRouter { listOf(first, second) }.resolve(
            EnhancedBatchEntry.parse("https://nhentai.net/g/123/"),
        )

        assertTrue(result is EnhancedResolveResult.Failure)
    }

    @Test
    fun `pururin numeric search maps without treating ordinary text as a URL`() {
        val definition = EnhancedSourceDefinitions.all.single { it.family == dev.ahmedmohamed.hayai.source.SourceFamily.Pururin }

        assertEquals("/gallery/123/-", EnhancedSourceUrlMapper.mapQuery(definition, "https://pururin.me", "id:123"))
        assertEquals(null, EnhancedSourceUrlMapper.mapQuery(definition, "https://pururin.me", "artist name"))
    }
}
