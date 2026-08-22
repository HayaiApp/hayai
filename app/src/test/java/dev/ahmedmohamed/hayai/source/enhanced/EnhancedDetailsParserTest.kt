package dev.ahmedmohamed.hayai.source.enhanced

import dev.ahmedmohamed.hayai.source.SourceFamily
import org.junit.Assert.assertEquals
import org.junit.Test

class EnhancedDetailsParserTest {
    @Test
    fun `parses EightMuses album metadata`() {
        val html = """
            <div class="top-menu-breadcrumb"><ol><li><a>Comics</a></li><li><a>Artist</a></li><li><a>Album</a></li></ol></div>
            <div class="album-tags"><a>color</a><a>series</a></div>
            <div class="gallery"><div class="c-tile"><img class="lazyload" data-src="/cover.jpg"></div></div>
        """.trimIndent()

        val details = requireNotNull(EnhancedDetailsParser.parse(SourceFamily.EightMuses, html, "https://8muses.com/album"))

        assertEquals("Album", details.title)
        assertEquals("Artist", details.artist)
        assertEquals(listOf("color", "series"), details.genres)
        assertEquals("https://8muses.com/cover.jpg", details.thumbnailUrl)
        assertEquals("/album", details.descriptionRows.single { it.label == EnhancedDescriptionLabel.Path }.value)
    }

    @Test
    fun `parses HBrowse tables without assuming optional rows`() {
        val html = """
            <div id="main"><table class="listTable">
            <tr><td>Title</td><td>Gallery</td></tr><tr><td>Length</td><td>20 pages</td></tr>
            <tr><td>Artist</td><td><a>Alice</a></td></tr></table></div>
        """.trimIndent()

        val details = requireNotNull(EnhancedDetailsParser.parse(SourceFamily.HBrowse, html, "https://hbrowse.com/thumbnails/1/c00001/"))

        assertEquals("Gallery", details.title)
        assertEquals("20 pages", details.descriptionRows.single { it.label == EnhancedDescriptionLabel.Length }.value)
        assertEquals("1", details.descriptionRows.single { it.label == EnhancedDescriptionLabel.Id }.value)
        assertEquals("/1/c00001/", details.descriptionRows.single { it.label == EnhancedDescriptionLabel.Url }.value)
        assertEquals(listOf("artist: Alice"), details.genres)
    }

    @Test
    fun `parses Pururin details and namespaced tags`() {
        val html = """
            <div class="content-wrapper"><div class="title"><h1>Title</h1></div><div class="alt-title">Alt</div>
            <div class="cover-wrapper"><v-lazy-image src="/cover.jpg"></v-lazy-image></div>
            <table class="table-gallery-info"><tbody><tr><td>Pages</td><td>12</td></tr>
            <tr><td>Artists</td><td><a>Alice</a></td></tr></tbody></table></div>
        """.trimIndent()

        val details = requireNotNull(EnhancedDetailsParser.parse(SourceFamily.Pururin, html, "https://pururin.me/gallery/1/a"))

        assertEquals("Title", details.title)
        assertEquals("Alt", details.alternateTitle)
        assertEquals(listOf("artists: Alice"), details.genres)
    }

    @Test
    fun `parses NHentai API details`() {
        val body = """{"title":{"english":"English","japanese":"日本語"},"num_pages":12,"num_favorites":4,"tags":[{"type":"artist","name":"Alice"}]}"""

        val details = requireNotNull(EnhancedDetailsParser.parse(SourceFamily.NHentai, body, "https://nhentai.net/api/gallery/1"))

        assertEquals("English", details.title)
        assertEquals("日本語", details.alternateTitle)
        assertEquals(listOf("artist: Alice"), details.genres)
        assertEquals("4", details.descriptionRows.single { it.label == EnhancedDescriptionLabel.Favorites }.value)
    }

    @Test
    fun `parses MangaDex API details`() {
        val body = """{"data":{"attributes":{"title":{"en":"Title"},"altTitles":[{"ja":"別名"}],"description":{"en":"Summary"},"tags":[{"attributes":{"name":{"en":"Action"}}}]}}}"""

        val details = requireNotNull(EnhancedDetailsParser.parse(SourceFamily.MangaDex, body, "https://api.mangadex.org/manga/id"))

        assertEquals("Title", details.title)
        assertEquals("別名", details.alternateTitle)
        assertEquals(listOf("Action"), details.genres)
        assertEquals("Summary", details.descriptionRows.single { it.label == EnhancedDescriptionLabel.Description }.value)
    }

    @Test
    fun `parses LANraragi API details and tolerates missing fields`() {
        val body = """{"arcid":"abc","title":"Archive","summary":"Summary","pagecount":9,"tags":"artist:Alice, language:English, date_added:0, untagged"}"""

        val details = requireNotNull(EnhancedDetailsParser.parse(SourceFamily.Lanraragi, body, "http://server/api/archives/id/metadata"))

        assertEquals("Archive", details.title)
        assertEquals(listOf("artist:Alice", "language:English", "date_added:1970-01-01 00:00", "other:untagged"), details.genres)
        assertEquals("9", details.descriptionRows.single { it.label == EnhancedDescriptionLabel.Pages }.value)
        assertEquals("abc", details.descriptionRows.single { it.label == EnhancedDescriptionLabel.Id }.value)
        assertEquals("http://server", details.descriptionRows.single { it.label == EnhancedDescriptionLabel.BaseUrl }.value)
    }
}
