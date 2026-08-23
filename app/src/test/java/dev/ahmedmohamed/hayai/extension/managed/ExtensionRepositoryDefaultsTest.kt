package dev.ahmedmohamed.hayai.extension.managed

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionRepositoryDefaultsTest {
    @Test
    fun `first run catalogs match the configured production indexes`() {
        assertEquals(
            setOf(
                "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
                "https://raw.githubusercontent.com/mojuru/cursed-manga-repo/repo/index.pb",
            ),
            ExtensionRepositoryDefaults.manga,
        )
        assertEquals(
            "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json",
            ExtensionRepositoryDefaults.LNREADER_NOVELS,
        )
    }
}
