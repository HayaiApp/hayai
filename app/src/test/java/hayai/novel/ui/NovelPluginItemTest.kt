package hayai.novel.ui

import hayai.novel.plugin.model.NovelPluginIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class NovelPluginItemTest {

    @Test
    fun `binding signature is stable for equivalent rows`() {
        assertEquals(item().bindingContentSignature(), item().bindingContentSignature())
    }

    @Test
    fun `binding signature includes version and installation state`() {
        val original = item()
        val updated = original.copy(
            plugin = original.plugin.copy(version = "2.0"),
            isInstalling = true,
        )

        assertEquals(original, updated)
        assertNotEquals(original.bindingContentSignature(), updated.bindingContentSignature())
    }

    @Test
    fun `binding signature includes header controls`() {
        val original = item()
        val updated = original.copy(
            header = NovelPluginGroupItem("Installed", 2, installedSorting = 1),
        )

        assertNotEquals(original.bindingContentSignature(), updated.bindingContentSignature())
    }

    private fun item() = NovelPluginItem(
        plugin = NovelPluginIndex(
            id = "example",
            name = "Example",
            lang = "en",
            version = "1.0",
            iconUrl = "https://example.invalid/icon.png",
        ),
        header = NovelPluginGroupItem("Installed", 1, installedSorting = 0),
        isInstalled = true,
        installedVersion = "1.0",
    )
}
