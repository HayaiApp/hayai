package dev.ahmedmohamed.hayai.source.presentation

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelSource
import eu.kanade.tachiyomi.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BundledSourceIconResolverTest {
    @Test
    fun `bundled source ids resolve their dedicated artwork`() {
        assertEquals(R.mipmap.ic_ehentai_source, BundledSourceIconResolver.resource(EhSite.EHentai.sourceId))
        assertEquals(R.mipmap.ic_exhentai_source, BundledSourceIconResolver.resource(EhSite.ExHentai.sourceId))
        assertEquals(R.drawable.ic_local_novel_source, BundledSourceIconResolver.resource(LocalNovelSource.ID))
        assertNull(BundledSourceIconResolver.resource(Long.MAX_VALUE))
    }
}
