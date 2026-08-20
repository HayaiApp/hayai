package dev.ahmedmohamed.hayai.source.enhanced

import dev.ahmedmohamed.hayai.source.SourceCapabilityRegistry
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedSourceRegistryTest {
    @Test
    fun `wraps recognized dynamic families and preserves source identity and filters`() {
        val original = FakeHttpSource(77L, "NHentai", "https://nhentai.net")

        val wrapped = EnhancedSourceRegistry.wrap(original)

        assertTrue(wrapped is HayaiEnhancedHttpSource)
        assertEquals(original.id, wrapped.id)
        assertEquals(original.name, wrapped.name)
        assertEquals(original.lang, wrapped.lang)
        assertEquals(original.getFilterList(), wrapped.getFilterList())
    }

    @Test
    fun `fixed legacy source IDs remain enhanced after a host move`() {
        val original = FakeHttpSource(SourceCapabilityRegistry.EIGHT_MUSES_SOURCE_ID, "Renamed source", "https://new.example")

        assertTrue(EnhancedSourceRegistry.wrap(original) is HayaiEnhancedHttpSource)
    }

    @Test
    fun `does not wrap a family name on an unrelated host`() {
        val original = FakeHttpSource(88L, "NHentai mirror", "https://example.com")

        assertSame(original, EnhancedSourceRegistry.wrap(original))
    }

    @Test
    fun `wrapping is idempotent`() {
        val wrapped = EnhancedSourceRegistry.wrap(FakeHttpSource(77L, "NHentai", "https://nhentai.net"))

        assertSame(wrapped, EnhancedSourceRegistry.wrap(wrapped))
    }

    @Test
    fun `matching URL search opens one gallery while a foreign URL remains a normal query`() = runBlocking {
        val original = FakeHttpSource(77L, "NHentai", "https://nhentai.net")
        val wrapped = EnhancedSourceRegistry.wrap(original)

        val imported = wrapped.getSearchManga(1, "https://nhentai.net/g/123/4/", FilterList())
        wrapped.getSearchManga(1, "https://example.com/g/123/", FilterList())

        assertEquals(listOf("/g/123/"), original.detailUrls)
        assertEquals(listOf("https://example.com/g/123/"), original.searchQueries)
        assertEquals("Imported", imported.mangas.single().title)
    }

    @Test
    fun `combined updates remain one delegate operation`() = runBlocking {
        val original = FakeHttpSource(77L, "NHentai", "https://nhentai.net")
        val wrapped = EnhancedSourceRegistry.wrap(original)
        val manga = SManga.create().apply { url = "/g/123/"; title = "Existing" }

        val update = wrapped.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)

        assertEquals(1, original.combinedUpdateCalls)
        assertEquals("Combined", update.manga.title)
        assertEquals(1, update.chapters.size)
    }
}

private class FakeHttpSource(
    override val id: Long,
    override val name: String,
    override val baseUrl: String,
) : HttpSource() {
    override val lang: String = "all"
    override val supportsLatest: Boolean = true
    private val filters = FilterList(object : Filter.CheckBox("Enabled") {})
    val detailUrls = mutableListOf<String>()
    val searchQueries = mutableListOf<String>()
    var combinedUpdateCalls = 0
    override fun getFilterList(): FilterList = filters

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        searchQueries += query
        return MangasPage(emptyList(), false)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        detailUrls += manga.url
        return manga.apply {
            title = "Imported"
            initialized = true
        }
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<eu.kanade.tachiyomi.source.model.SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        combinedUpdateCalls++
        val updated = manga.apply { title = "Combined"; initialized = true }
        val updatedChapters = if (fetchChapters) listOf(eu.kanade.tachiyomi.source.model.SChapter.create().apply { url = "/1"; name = "One" }) else chapters
        return SMangaUpdate(updated, updatedChapters)
    }
}
