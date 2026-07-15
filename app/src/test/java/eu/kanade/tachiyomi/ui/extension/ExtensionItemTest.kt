package eu.kanade.tachiyomi.ui.extension

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ExtensionItemTest {

    @Test
    fun `binding signature is stable for equivalent rows`() {
        assertEquals(item().bindingContentSignature(), item().bindingContentSignature())
    }

    @Test
    fun `binding signature includes content hidden by identity equality`() {
        val original = item()
        val updated = original.copy(
            extension = extension(versionName = "2.0", isNsfw = true),
        )

        // FlexibleAdapter identity remains package-based, while binding content changed.
        assertEquals(original, updated)
        assertNotEquals(original.bindingContentSignature(), updated.bindingContentSignature())
    }

    @Test
    fun `binding signature includes progress and header controls`() {
        val original = item()
        val downloading = original.copy(installStep = InstallStep.Downloading)
        val disabledHeader = original.copy(
            header = ExtensionGroupItem("Installed", 1, canUpdate = false),
        )

        assertNotEquals(original.bindingContentSignature(), downloading.bindingContentSignature())
        assertNotEquals(original.bindingContentSignature(), disabledHeader.bindingContentSignature())
    }

    private fun item() = ExtensionItem(
        extension = extension(),
        header = ExtensionGroupItem("Installed", 1, canUpdate = true),
    )

    private fun extension(
        versionName: String = "1.0",
        isNsfw: Boolean = false,
    ) = Extension.Untrusted(
        name = "Example",
        pkgName = "org.example.extension",
        versionName = versionName,
        versionCode = 1,
        libVersion = 1.5,
        signatureHash = "signature",
        lang = "en",
        isNsfw = isNsfw,
    )
}
