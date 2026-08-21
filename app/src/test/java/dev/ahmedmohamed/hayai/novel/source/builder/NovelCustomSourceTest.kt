package dev.ahmedmohamed.hayai.novel.source.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelCustomSourceTest {
    private val definition = NovelCustomSourceDefinition("example.novels", "Example", "en", "https://example.test", "/novels?page={page}", "/search?q={query}&page={page}", NovelListSelectors(".book", ".title", "a", "img"), NovelDetailsSelectors("h1"), NovelChapterSelectors(".chapter", "a", "a"), NovelContentSelectors("article", listOf(".ad")))
    @Test fun `valid definition compiles to runtime plugin`() { val result = NovelCustomSourceCompiler.compile(definition); assertEquals(result.checksum, result.descriptor.sha256); assertTrue(result.code.toString(Charsets.UTF_8).contains("parseChapter")) }
    @Test fun `preview resolves relative urls`() { val preview = NovelCustomSourcePreviewer.list("<div class=book><a href=/n><span class=title>Book</span><img src=/c.jpg></a></div>", "https://example.test/list", definition); assertEquals("https://example.test/n", preview.url); assertTrue(preview.issues.isEmpty()) }
    @Test fun `invalid selectors are reported together`() { val bad = definition.copy(searchPath = "/search", list = definition.list.copy(item = "")); assertEquals(setOf("searchPath", "list.item"), bad.validate().map { it.field }.toSet()) }
    @Test fun `portable definition rejects credential and transport headers`() {
        listOf("Authorization", "Cookie", "Proxy-Authorization", "Content-Length", "X-Api-Key").forEach { name ->
            assertTrue(definition.copy(headers = mapOf(name to "secret")).validate().any { it.field == "headers" })
        }
        assertTrue(definition.copy(headers = mapOf("Accept" to "text/html\r\nCookie: secret")).validate().any { it.field == "headers" })
        assertTrue(definition.copy(headers = mapOf("Accept-Language" to "en-US")).validate().isEmpty())
    }
}
