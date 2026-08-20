package dev.ahmedmohamed.hayai.adult.eh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EhIdentityTest {
    @Test
    fun `gallery URLs normalize without losing Long-sized IDs`() {
        val key = GalleryKey.parse("https://g.e-hentai.org/g/9223372036854775807/abcDEF_123/?foo=bar")

        assertEquals("9223372036854775807", key.id.value)
        assertEquals("abcDEF_123", key.token.value)
        assertEquals("/g/9223372036854775807/abcDEF_123/?nw=always", key.normalizedPath)
        assertEquals("https://exhentai.org/g/9223372036854775807/abcDEF_123/?nw=always", key.absoluteUrl(EhSite.ExHentai))
    }

    @Test
    fun `gallery identity rejects foreign hosts traversal and numeric overflow`() {
        assertThrows(IllegalArgumentException::class.java) { GalleryKey.parse("https://example.com/g/1/abc/") }
        assertThrows(IllegalArgumentException::class.java) { GalleryKey.parse("/x/1/abc/") }
        assertThrows(IllegalArgumentException::class.java) { GalleryId.parse("9223372036854775808") }
        assertThrows(IllegalArgumentException::class.java) { GalleryToken.parse("bad/token") }
    }

    @Test
    fun `image page imports preserve page token and page number`() {
        val page = EhImagePageRef.parse("https://exhentai.org/s/page_token/12345678901-42")

        assertEquals("12345678901", page.galleryId.value)
        assertEquals("page_token", page.pageToken.value)
        assertEquals(42, page.page)
        assertEquals(EhSite.EHentai, EhSite.fromHost("G.E-HENTAI.ORG"))
        assertNull(EhSite.fromHost("example.com"))
    }
}
