package dev.ahmedmohamed.hayai.extension

import eu.kanade.tachiyomi.extension.model.Extension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionCatalogReconcilerTest {
    @Test
    fun `newest package copy is shared by every catalog consumer`() {
        val copies = listOf(
            available(versionCode = 2, libVersion = 1.5, repo = "https://old.example/repo.json"),
            available(versionCode = 2, libVersion = 1.6, repo = "https://new.example/repo.json"),
            available(versionCode = 1, libVersion = 1.6, repo = "https://other.example/repo.json"),
        )

        val selected = ExtensionCatalogReconciler.newestCopies(copies).single()

        assertEquals(2, selected.versionCode)
        assertEquals(1.6, selected.libVersion, 0.0)
        assertEquals("https://new.example/repo.json", selected.repoUrl)
    }

    @Test
    fun `update comparison clears after both installed values catch up`() {
        val installed = installed(versionCode = 2, libVersion = 1.6)

        assertFalse(ExtensionCatalogReconciler.hasUpdate(installed, available(2, 1.6)))
        assertTrue(ExtensionCatalogReconciler.hasUpdate(installed, available(3, 1.6)))
        assertTrue(ExtensionCatalogReconciler.hasUpdate(installed, available(2, 1.7)))
    }

    private fun available(
        versionCode: Long,
        libVersion: Double,
        repo: String = "https://repo.example/repo.json",
    ) = Extension.Available(
        name = "Example",
        pkgName = "dev.example.extension",
        versionName = "$libVersion.$versionCode",
        versionCode = versionCode,
        libVersion = libVersion,
        lang = "en",
        isNsfw = false,
        isNovel = false,
        apkName = "example.apk",
        iconUrl = "https://repo.example/icon.png",
        sources = emptyList(),
        repoUrl = repo,
    )

    private fun installed(
        versionCode: Long,
        libVersion: Double,
    ) = Extension.Installed(
        name = "Example",
        pkgName = "dev.example.extension",
        versionName = "$libVersion.$versionCode",
        versionCode = versionCode,
        libVersion = libVersion,
        lang = "en",
        isNsfw = false,
        isNovel = false,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        isShared = true,
    )
}
