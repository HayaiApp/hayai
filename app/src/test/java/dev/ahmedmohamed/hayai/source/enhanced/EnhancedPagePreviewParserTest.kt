package dev.ahmedmohamed.hayai.source.enhanced

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedPagePreviewParserTest {
    @Test
    fun `nhentai parser retains page order and creates tappable page links`() {
        val body = """
            {"id":123,"media_id":"55","pages":[
              {"thumbnail":"galleries/55/1t.jpg"},
              {"thumbnail":"https://cdn.example/2t.jpg"},
              {"thumbnail":null}
            ]}
        """.trimIndent()

        val previews = EnhancedPagePreviewParser.nhentai(body, "https://nhentai.net", listOf("https://t1.nhentai.net"))

        assertEquals(listOf(1, 2), previews.map { it.index })
        assertEquals("https://t1.nhentai.net/galleries/55/1t.jpg", previews[0].imageUrl)
        assertEquals("https://nhentai.net/g/123/2/", previews[1].pageUrl)
    }

    @Test
    fun `nhentai config ignores insecure thumbnail servers`() {
        val servers = EnhancedPagePreviewParser.nhentaiConfig(
            """{"thumb_servers":["http://unsafe.example","https://safe.example"]}""",
        )

        assertEquals(listOf("https://safe.example"), servers)
    }

    @Test
    fun `lanraragi parser paginates every archive page with thumbnail flags`() {
        val id = "a".repeat(40)
        val previews = EnhancedPagePreviewParser.lanraragi(
            """{"arcid":"$id","pagecount":3}""",
            "https://lanraragi.local/",
        )

        assertEquals(3, previews.size)
        assertFalse(previews[0].imageUrl.contains("no_fallback"))
        assertTrue(previews[2].imageUrl.endsWith("?page=3&no_fallback=true"))
        assertEquals("https://lanraragi.local/reader?id=$id#page=3", previews[2].pageUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `lanraragi parser rejects unbounded page counts`() {
        EnhancedPagePreviewParser.lanraragi(
            """{"arcid":"${"a".repeat(40)}","pagecount":10001}""",
            "https://lanraragi.local",
        )
    }

    @Test
    fun `preview pagination is bounded and reports navigation metadata`() {
        val previews = (1..25).map { EnhancedPagePreview(it, "https://safe.example/$it", null) }

        val second = EnhancedPagePreviewParser.paginate(previews, page = 2, pageSize = 12)
        val beyond = EnhancedPagePreviewParser.paginate(previews, page = 4, pageSize = 12)

        assertEquals((13..24).toList(), second.previews.map { it.index })
        assertTrue(second.hasNextPage)
        assertEquals(3, second.totalPages)
        assertTrue(beyond.previews.isEmpty())
        assertFalse(beyond.hasNextPage)
    }
}
