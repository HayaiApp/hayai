package dev.ahmedmohamed.hayai.adult.eh.network

import dev.ahmedmohamed.hayai.adult.eh.domain.EhFailure
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSearchCursor
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.EhTagWeight
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EhHtmlParserTest {
    @Test
    fun `page previews parse direct and sprite thumbnail layouts`() {
        val html = """
            <html><body><div id="gdt">
              <div class="gdtl"><a href="https://e-hentai.org/s/one/100-1"><img src="https://ehgt.org/direct.jpg"></a></div>
              <div class="gdtm"><a href="https://e-hentai.org/s/two/100-2"><div style="width:100px;height:140px;background:transparent url(https://ehgt.org/sprite.jpg) -200px -5px no-repeat"></div></a></div>
            </div></body></html>
        """.trimIndent()

        val previews = EhHtmlParser.parsePreviews(html, "https://e-hentai.org/g/100/token/?p=0", EhSite.EHentai)

        assertEquals(2, previews.size)
        assertEquals("https://ehgt.org/direct.jpg", previews[0].imageUrl)
        assertEquals(200, previews[1].crop?.x)
        assertEquals(5, previews[1].crop?.y)
        assertEquals(100, previews[1].crop?.width)
        assertEquals(140, previews[1].crop?.height)
        assertEquals(listOf(1, 2), previews.map { it.index })
    }

    @Test
    fun `preview page keeps absolute indices and parses navigation`() {
        val html = """
            <html><body><div id="gdt">
              <div class="gdtl"><a href="https://e-hentai.org/s/one/100-41"><img alt="41" src="https://ehgt.org/41.jpg"></a></div>
              <div class="gdtl"><a href="https://e-hentai.org/s/two/100-42"><img alt="42" src="https://ehgt.org/42.jpg"></a></div>
            </div><table class="ptt"><tr><td><a>1</a></td><td class="ptds">2</td><td><a>3</a></td></tr></table></body></html>
        """.trimIndent()

        val page = EhHtmlParser.parsePreviewPage(html, "https://e-hentai.org/g/100/token/?p=1", EhSite.EHentai)

        assertEquals(listOf(41, 42), page.previews.map { it.index })
        assertEquals(3, page.totalPages)
        assertEquals(true, page.hasNextPage)
    }

    @Test
    fun `preview page rejects an oversized response instead of silently truncating it`() {
        val cells = (1..3).joinToString("") { index ->
            "<div class='gdtl'><a href='https://e-hentai.org/s/hash/100-$index'><img alt='$index' src='https://ehgt.org/$index.jpg'></a></div>"
        }

        assertThrows(EhFailure.BoundsExceeded::class.java) {
            EhHtmlParser.parsePreviewPage(
                "<div id='gdt'>$cells</div>",
                "https://e-hentai.org/g/100/token/?p=0",
                EhSite.EHentai,
                maxItems = 2,
            )
        }
    }

    @Test
    fun `extended browse parses metadata tags favorites and forward cursor`() {
        val html = """
            <html><body>
            <table class="itg"><tbody><tr>
              <td class="gl1e"><img title="Extended Gallery" src="https://ehgt.org/thumb.jpg"></td>
              <td class="gl3c"><a href="https://e-hentai.org/g/10000000000/abcdef1234/"><div class="glink">Extended Gallery</div></a></td>
              <td class="gl3e">
                <div style="border-color:#f00"></div>
                <div onclick="document.location='/Doujinshi'">Doujinshi</div>
                <div>2026-08-19 10:11</div>
                <div title="Rating: 4.5"></div>
                <div><a>uploader</a></div>
                <div>42 pages</div>
                <table><tr><td class="tc">female:</td><td><div class="gtl">tag one</div></td></tr></table>
              </td>
            </tr></tbody></table>
            <div class="searchnav"><a href="?next=10000000000">next</a></div>
            </body></html>
        """.trimIndent()

        val page = EhHtmlParser.parseBrowse(html, "https://e-hentai.org/?f_search=test", EhSite.EHentai)
        val gallery = page.galleries.single()

        assertEquals("10000000000", gallery.metadata.key.id.value)
        assertEquals("Extended Gallery", gallery.metadata.title)
        assertEquals("Doujinshi", gallery.metadata.category)
        assertEquals("uploader", gallery.metadata.uploader)
        assertEquals(42, gallery.metadata.pageCount)
        assertEquals(4.5, gallery.metadata.averageRating!!, 0.001)
        assertEquals(1, gallery.favoriteCategory)
        assertEquals(EhTagWeight.Light, gallery.metadata.tags.single().weight)
        assertEquals("10000000000", (page.nextCursor as EhSearchCursor.Gallery).id.value)
    }

    @Test
    fun `compact browse parses safe fallback shape and reverse cursor`() {
        val padding = (0..7).joinToString("") { "<div>unused</div>" }
        val html = """
            <html><body><table class="itg"><tbody><tr>
              <td class="gl1c"><div onclick="document.location='/Manga'">Manga</div><img title="Compact Gallery" src="https://ehgt.org/c.jpg"></td>
              <td class="gl2c"><div class="glthumb"><img title="Compact Gallery" src="https://ehgt.org/c.jpg"></div><div>$padding<div>2025-01-02 03:04</div><div title="Rating: 3.5"></div></div></td>
              <td class="gl2e"><div><a href="https://exhentai.org/g/200/abc123def4/"><div class="glink">Compact Gallery</div></a></div></td>
              <td class="gl4c"><div><a>compact-uploader</a></div><div>12 pages</div></td>
            </tr></tbody></table>
            <div class="searchnav"><a href="?prev=200">prev</a></div>
            </body></html>
        """.trimIndent()

        val page = EhHtmlParser.parseBrowse(
            html,
            "https://exhentai.org/?TEH_REVERSE=on&prev=300",
            EhSite.ExHentai,
        )

        assertEquals("Compact Gallery", page.galleries.single().metadata.title)
        assertEquals("Manga", page.galleries.single().metadata.category)
        assertEquals("200", (page.nextCursor as EhSearchCursor.Gallery).id.value)
    }

    @Test
    fun `browse warning and access documents become typed failures`() {
        assertThrows(EhFailure.RemoteWarning::class.java) {
            EhHtmlParser.parseBrowse("<div class=searchwarn>No unfiltered results.</div>", "https://e-hentai.org/", EhSite.EHentai)
        }
        assertThrows(EhFailure.AuthenticationRequired::class.java) {
            EhHtmlParser.parseBrowse(
                "<form action='/Login'><input type=password></form>",
                "https://exhentai.org/",
                EhSite.ExHentai,
            )
        }
        assertThrows(EhFailure.AccessDenied::class.java) {
            EhHtmlParser.parseBrowse("<title>Just a moment...</title><form id=challenge-form></form>", "https://e-hentai.org/", EhSite.EHentai)
        }
        assertThrows(EhFailure.RateLimited::class.java) {
            EhHtmlParser.parseBrowse("<body>Your IP address has been temporarily banned for excessive pageloads.</body>", "https://e-hentai.org/", EhSite.EHentai)
        }
    }

    @Test
    fun `details parse complete metadata parent tags and revisions`() {
        val html = """
            <html><body>
              <h1 id="gn">English title</h1><h1 id="gj">日本語</h1>
              <div id="gd1"><div style="background: url('https://ehgt.org/cover.jpg')"></div></div>
              <div class="cs" onclick="document.location='/Doujinshi'">Doujinshi</div><div id="gdn">artist-uploader</div>
              <table id="gdd">
                <tr><td class="gdt1">Posted:</td><td class="gdt2">2024-01-02 03:04</td></tr>
                <tr><td class="gdt1">Parent:</td><td class="gdt2"><a href="https://e-hentai.org/g/99/parent1234/">parent</a></td></tr>
                <tr><td class="gdt1">Visible:</td><td class="gdt2">Yes</td></tr>
                <tr><td class="gdt1">Language:</td><td class="gdt2">English TR</td></tr>
                <tr><td class="gdt1">File Size:</td><td class="gdt2">1.5 MB</td></tr>
                <tr><td class="gdt1">Length:</td><td class="gdt2">44 pages</td></tr>
                <tr><td class="gdt1">Favorited:</td><td class="gdt2">123 times</td></tr>
              </table>
              <div id="rating_label">Average: 4.75</div><div id="rating_count">88</div>
              <table id="taglist"><tr><td class="tc">artist:</td><td><div class="gt">someone</div><div class="gtw">weak tag</div></td></tr></table>
              <div id="gnd"><a href="https://e-hentai.org/g/101/newer1234/">Newer edition</a>, added 2025-02-03 04:05</div>
            </body></html>
        """.trimIndent()

        val details = EhHtmlParser.parseDetails(html, "https://e-hentai.org/g/100/current123/", EhSite.EHentai)
        val metadata = details.metadata

        assertEquals("100", metadata.key.id.value)
        assertEquals("English title", metadata.title)
        assertEquals("日本語", metadata.alternateTitle)
        assertEquals("https://ehgt.org/cover.jpg", metadata.thumbnailUrl)
        assertEquals("99", metadata.parent?.id?.value)
        assertEquals("English", metadata.language)
        assertEquals(true, metadata.translated)
        assertEquals(1_572_864L, metadata.sizeBytes)
        assertEquals(44, metadata.pageCount)
        assertEquals(123, metadata.favoriteCount)
        assertEquals(88, metadata.ratingCount)
        assertEquals(4.75, metadata.averageRating!!, 0.001)
        assertEquals(2, metadata.tags.size)
        assertEquals(EhTagWeight.Weak, metadata.tags.last().weight)
        assertEquals("101", details.newerRevisions.single().key.id.value)
    }

    @Test
    fun `details reject missing identity title duplicate revisions and unavailable gallery`() {
        assertThrows(EhFailure.MalformedDocument::class.java) {
            EhHtmlParser.parseDetails("<h1 id=gn>Title</h1>", "https://e-hentai.org/not-gallery", EhSite.EHentai)
        }
        assertThrows(EhFailure.MalformedDocument::class.java) {
            EhHtmlParser.parseDetails("<div id=gn></div>", "https://e-hentai.org/g/1/token/", EhSite.EHentai)
        }
        assertThrows(EhFailure.MalformedDocument::class.java) {
            EhHtmlParser.parseDetails(
                "<h1 id=gn>Title</h1><div id=gnd><a href='/g/2/token2/'>A</a><a href='/g/2/token2/'>B</a></div>",
                "https://e-hentai.org/g/1/token1/",
                EhSite.EHentai,
            )
        }
        assertThrows(EhFailure.GalleryNotFound::class.java) {
            EhHtmlParser.parseDetails("<title>Gallery Not Available</title>", "https://e-hentai.org/g/1/token1/", EhSite.EHentai)
        }
    }

    @Test
    fun `page and image parsers handle pagination retries quota and unsafe URLs`() {
        val pages = EhHtmlParser.parsePageBatch(
            """
                <div class="gdtm"><a href="/s/a/1-2"><img alt="2"></a></div>
                <div class="gdtm"><a href="/s/b/1-1"><img alt="1"></a></div>
                <a onclick="return false" href="?p=1">&gt;</a>
            """.trimIndent(),
            "https://e-hentai.org/g/1/token/?p=0",
        )
        assertEquals(listOf(1, 2), pages.pages.map { it.index })
        assertEquals("https://e-hentai.org/g/1/token/?p=1", pages.nextListingUrl)

        val image = EhHtmlParser.parseImagePage(
            "<img id=img src='https://cdn.example/image.jpg'><a id=loadfail onclick=\"return nl('retry123')\"></a>",
            "https://e-hentai.org/s/page/1-1",
        )
        assertEquals("https://cdn.example/image.jpg", image.imageUrl)
        assertEquals("retry123", requireNotNull(image.retryPageUrl?.toHttpUrlOrNull()).queryParameter("nl"))

        assertThrows(EhFailure.QuotaExceeded::class.java) {
            EhHtmlParser.parseImagePage("<img id=img src='https://ehgt.org/g/509.gif'>", "https://e-hentai.org/s/page/1-1")
        }
        assertThrows(EhFailure.MalformedDocument::class.java) {
            EhHtmlParser.parsePageBatch("<div class=gdtm><a href='https://example.com/s/a/1-1'><img alt=1></a></div>", "https://e-hentai.org/g/1/token/")
        }
    }
}
