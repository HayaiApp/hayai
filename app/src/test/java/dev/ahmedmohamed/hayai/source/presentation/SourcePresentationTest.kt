package dev.ahmedmohamed.hayai.source.presentation

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourcePresentationTest {
    @Test
    fun `EH sources are labeled as bundled adult sources`() {
        assertEquals("Bundled · Adult", SourcePresentation.badgeText(FakeSource(EhSite.EHentai.sourceId)))
    }

    @Test
    fun `local novels are labeled as bundled`() {
        assertEquals("Bundled · Novel", SourcePresentation.badgeText(FakeSource(LocalNovelSource.ID, isNovelSource = true)))
    }

    @Test
    fun `ordinary extensions do not receive a misleading badge`() {
        assertNull(SourcePresentation.badgeText(FakeSource(42L)))
    }
}

private class FakeSource(
    override val id: Long,
    override val isNovelSource: Boolean = false,
) : Source {
    override val name: String = "Fake"
    override suspend fun getMangaDetails(manga: SManga): SManga = manga
    override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
}
