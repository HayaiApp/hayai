package dev.ahmedmohamed.hayai.novel.plugin

import dev.ahmedmohamed.hayai.novel.plugin.source.NovelPluginSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NovelPluginModelsTest {
    @Test
    fun `relative plugin assets resolve against repository`() {
        val descriptor = descriptor(url = "plugins/example.js", iconUrl = "../icons/example.png")

        descriptor.validate("https://repo.example/catalog/index.json")

        assertEquals(
            "https://repo.example/catalog/plugins/example.js",
            descriptor.resolvedCodeUrl("https://repo.example/catalog/index.json"),
        )
        assertEquals("https://repo.example/icons/example.png", descriptor.resolvedIconUrl("https://repo.example/catalog/index.json"))
    }

    @Test
    fun `remote cleartext and credential URLs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            descriptor(url = "http://repo.example/plugin.js").validate("https://repo.example/index.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor(url = "https://user:pass@repo.example/plugin.js").validate("https://repo.example/index.json")
        }
    }

    @Test
    fun `localhost cleartext remains available for LAN source development`() {
        descriptor(url = "http://127.0.0.1:8080/plugin.js").validate("http://localhost:8080/index.json")
    }

    @Test
    fun `cleartext content sites remain compatible while plugin delivery stays secure`() {
        descriptor().copy(site = "http://192.168.1.5:3000").validate("https://repo.example/index.json")
    }

    @Test
    fun `source identity survives metadata and version changes`() {
        val original = descriptor()
        assertEquals(original.sourceId(), original.copy(name = "Renamed", version = "9.0.0", lang = "Japanese").sourceId())
        assertNotEquals(original.sourceId(), original.copy(id = "different").sourceId())
    }

    @Test
    fun `semantic versions order releases prereleases and numeric segments`() {
        assertEquals(1, NovelPluginVersions.compare("1.10.0", "1.9.9"))
        assertEquals(1, NovelPluginVersions.compare("2.0.0", "2.0.0-beta.9"))
        assertEquals(-1, NovelPluginVersions.compare("2.0.0-beta.2", "2.0.0-beta.10"))
    }

    @Test
    fun `checksum validation rejects malformed metadata`() {
        assertThrows(IllegalArgumentException::class.java) { descriptor().copy(sha256 = "abc").validate("https://repo.example/index.json") }
    }

    @Test
    fun `chapter assets resolve to authenticated source URLs`() {
        val base = "https://novels.example/books/one/chapter-2"

        assertEquals(
            "https://novels.example/books/one/images/cover.jpg",
            NovelPluginSource.resolveChapterAssetUrl(base, "images/cover.jpg"),
        )
        assertEquals(
            "https://novels.example/shared/font.woff2",
            NovelPluginSource.resolveChapterAssetUrl(base, "/shared/font.woff2"),
        )
        assertEquals(
            "https://cdn.example/page.webp",
            NovelPluginSource.resolveChapterAssetUrl(base, "//cdn.example/page.webp"),
        )
        assertEquals(null, NovelPluginSource.resolveChapterAssetUrl(base, "data:image/png;base64,abc"))
        assertEquals(null, NovelPluginSource.resolveChapterAssetUrl(null, "relative.jpg"))
    }

    private fun descriptor(
        url: String = "https://repo.example/plugin.js",
        iconUrl: String = "",
    ) = NovelPluginDescriptor(
        id = "example",
        name = "Example",
        site = "https://novels.example",
        lang = "English",
        version = "1.0.0",
        url = url,
        iconUrl = iconUrl,
    )
}
