package dev.ahmedmohamed.hayai.novel.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelRepositoryTrustBootstrapTest {
    @Test
    fun `persisted repositories missing trust are repaired on bootstrap`() {
        val configured =
            listOf(
                NovelPluginRepository("LNReader", "https://repo.example/plugins.json", enabled = true),
                NovelPluginRepository("Signed", "https://signed.example/plugins.json", enabled = true),
            )

        val missing = NovelRepositoryTrustBootstrap.missingTrust(configured) { repository -> repository.name == "Signed" }

        assertEquals(listOf(configured.first()), missing)
    }
}
