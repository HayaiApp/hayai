package dev.ahmedmohamed.hayai.source.enhanced

import dev.ahmedmohamed.hayai.source.SourceFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnhancedSourceUrlMapperTest {
    @Test
    fun `maps supported gallery URLs to extension paths`() {
        assertEquals(
            "/comics/album/Foo/Bar",
            map(SourceFamily.EightMuses, "https://comics.8muses.com/comics/picture/Foo/Bar/123"),
        )
        assertEquals("/123/c00001/", map(SourceFamily.HBrowse, "https://hbrowse.com/123/c00002/"))
        assertEquals("/gallery/42/title", map(SourceFamily.Pururin, "https://pururin.me/gallery/42/title"))
        assertEquals("/g/177013/", map(SourceFamily.NHentai, "https://nhentai.net/g/177013/3/"))
        assertEquals(
            "/manga/123e4567-e89b-12d3-a456-426614174000",
            map(SourceFamily.MangaDex, "https://mangadex.org/title/123e4567-e89b-12d3-a456-426614174000/name"),
        )
        assertEquals("/reader?id=abc", map(SourceFamily.Lanraragi, "http://server.local/reader?id=abc", "http://server.local"))
    }

    @Test
    fun `rejects foreign hosts malformed identities and active content schemes`() {
        assertNull(map(SourceFamily.NHentai, "https://example.com/g/1/"))
        assertNull(map(SourceFamily.NHentai, "https://nhentai.net/g/not-a-number/"))
        assertNull(map(SourceFamily.MangaDex, "https://mangadex.org/title/not-a-uuid"))
        assertNull(map(SourceFamily.Lanraragi, "http://other.local/reader?id=abc", "http://server.local"))
        assertNull(map(SourceFamily.Pururin, "javascript:alert(1)"))
    }

    private fun map(
        family: SourceFamily,
        input: String,
        baseUrl: String = "https://unused.invalid",
    ): String? {
        val definition = EnhancedSourceDefinitions.all.first { it.family == family }
        return EnhancedSourceUrlMapper.map(definition, baseUrl, input)
    }
}
