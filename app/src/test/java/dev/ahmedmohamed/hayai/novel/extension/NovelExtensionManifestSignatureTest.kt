package dev.ahmedmohamed.hayai.novel.extension

import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginDescriptor
import org.junit.Assert.assertThrows
import org.junit.Test

class NovelExtensionManifestSignatureTest {
    @Test fun `plugin signature metadata must be complete`() {
        assertThrows(IllegalArgumentException::class.java) { NovelPluginDescriptor("id", "Name", lang = "en", version = "1", url = "plugin.js", signingKey = "AA==").validate("https://example.test/index.json") }
    }
}
