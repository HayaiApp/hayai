package dev.ahmedmohamed.hayai.adult.eh.network

import dev.ahmedmohamed.hayai.adult.eh.domain.EhCategory
import dev.ahmedmohamed.hayai.adult.eh.domain.EhFailure
import dev.ahmedmohamed.hayai.adult.eh.domain.EhImagePageRef
import dev.ahmedmohamed.hayai.adult.eh.domain.EhJumpTarget
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSearchCursor
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSearchSpec
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.EhTagMode
import dev.ahmedmohamed.hayai.adult.eh.domain.EhToplist
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryId
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EhProtocolCodecTest {
    @Test
    fun `preview page omits the one-shot warning bypass that resets pagination`() {
        val url =
            EhRequestBuilder.previews(
                EhSite.EHentai,
                GalleryKey.parse("/g/123/token/"),
                page = 2,
            )

        assertEquals("https://e-hentai.org/g/123/token/?p=2", url.toString())
    }

    @Test
    fun `text tag grammar supports namespaces phrases exclusion and OR`() {
        val terms = EhTagQueryCodec.parse("female:\"big breasts\" -male:yaoi ~artist:someone misc")

        assertEquals(4, terms.size)
        assertEquals("female", terms[0].namespace)
        assertEquals("big breasts", terms[0].value)
        assertEquals(EhTagMode.Exclude, terms[1].mode)
        assertEquals(EhTagMode.Any, terms[2].mode)
        assertEquals("female:\"big breasts\"$ -male:yaoi$ ~artist:someone$ misc$", EhTagQueryCodec.encode(terms))
    }

    @Test
    fun `tag grammar rejects ambiguous and excessive input`() {
        assertThrows(EhFailure.MalformedDocument::class.java) { EhTagQueryCodec.parse("female:\"unterminated") }
        assertThrows(EhFailure.MalformedDocument::class.java) { EhTagQueryCodec.parse("bad!:value") }
        assertThrows(EhFailure.BoundsExceeded::class.java) {
            EhTagQueryCodec.parse((1..9).joinToString(" ") { "tag$it" })
        }
    }

    @Test
    fun `search request encodes every independent filter and gallery cursor`() {
        val spec = EhSearchSpec(
            query = "sample",
            tags = EhTagQueryCodec.parse("female:\"big breasts\" -male:yaoi"),
            watched = true,
            excludedCategories = setOf(EhCategory.Misc, EhCategory.Western),
            browseExpunged = true,
            requireTorrent = true,
            minimumRating = 4,
            minimumPages = 10,
            maximumPages = 100,
            disableLanguageFilter = true,
            disableUploaderFilter = true,
            disableTagFilter = true,
            reverse = true,
            jumpTarget = EhJumpTarget.parse("2024-05"),
        )
        val first = EhRequestBuilder.search(EhSite.ExHentai, spec)
        val next = EhRequestBuilder.search(EhSite.ExHentai, spec, EhSearchCursor.Gallery(GalleryId.parse("9876543210")))

        assertEquals("/watched", first.encodedPath)
        assertEquals("sample female:\"big breasts\"$ -male:yaoi$", first.queryParameter("f_search"))
        assertEquals("513", first.queryParameter("f_cats"))
        assertEquals("4", first.queryParameter("f_srdd"))
        assertEquals("10", first.queryParameter("f_spf"))
        assertEquals("100", first.queryParameter("f_spt"))
        assertEquals("2024-05", first.queryParameter("seek"))
        assertEquals("9876543210", next.queryParameter("prev"))
        assertEquals(null, next.queryParameter("seek"))
    }

    @Test
    fun `toplist request is exclusive and uses one-based cursor`() {
        val url = EhRequestBuilder.search(
            EhSite.ExHentai,
            EhSearchSpec(query = "ignored", toplist = EhToplist.PastMonth),
            EhSearchCursor.ToplistPage(4),
        )

        assertEquals("e-hentai.org", url.host)
        assertEquals("/toplist.php", url.encodedPath)
        assertEquals("13", url.queryParameter("tl"))
        assertEquals("3", url.queryParameter("p"))
        assertEquals(null, url.queryParameter("f_search"))
    }

    @Test
    fun `gallery token API codec handles success remote error and malformed response`() {
        val page = EhImagePageRef.parse("https://e-hentai.org/s/pageToken/2147483648-7")
        val request = EhApiCodec.galleryTokenRequest(page)
        val key = EhApiCodec.parseGalleryTokenResponse("""{"tokenlist":[{"gid":2147483648,"token":"galleryToken"}]}""")

        assertEquals("gtoken", Regex("\"method\":\"([^\"]+)\"").find(request)?.groupValues?.get(1))
        assertEquals("2147483648", key.id.value)
        assertThrows(EhFailure.RemoteWarning::class.java) {
            EhApiCodec.parseGalleryTokenResponse("""{"error":"Gallery not found"}""")
        }
        assertThrows(EhFailure.MalformedDocument::class.java) { EhApiCodec.parseGalleryTokenResponse("{}") }
    }
}
