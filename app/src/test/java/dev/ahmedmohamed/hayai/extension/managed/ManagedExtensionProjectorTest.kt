package dev.ahmedmohamed.hayai.extension.managed

import dev.ahmedmohamed.hayai.extension.ApkLoadFailure
import dev.ahmedmohamed.hayai.novel.plugin.InstalledNovelPlugin
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginCatalog
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginDescriptor
import dev.ahmedmohamed.hayai.novel.integration.ContentKind
import eu.kanade.tachiyomi.extension.model.Extension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedExtensionProjectorTest {
    private val filter = ManagedExtensionFilter(setOf("en"), showNsfwSources = false)

    @Test
    fun `apk and javascript identities cannot collide`() {
        val state = ManagedExtensionProjector.project(
            apk = ApkExtensionSnapshot(emptyList(), emptyList(), listOf(availableApk("same")), emptyList()),
            js = NovelPluginCatalog(available = listOf(plugin("same", "1.0.0")), availableOrigins = mapOf("same" to REPO)),
            filter = filter,
        )

        assertEquals(setOf("apk:same", "js:same"), state.available.map { it.key.stableValue }.toSet())
        assertEquals(setOf(ContentKind.Manga), state.available.single { it.key.stableValue == "apk:same" }.contentKinds)
        assertEquals(setOf(ContentKind.Novel), state.available.single { it.key.stableValue == "js:same" }.contentKinds)
    }

    @Test
    fun `installed entries survive filters and missing repositories`() {
        val installedApk = installedApk("adult.apk", nsfw = true)
        val installedJs = InstalledNovelPlugin(plugin("offline-js", "1.0.0"), REPO, 1L, "a".repeat(64))

        val state = ManagedExtensionProjector.project(
            apk = ApkExtensionSnapshot(listOf(installedApk), emptyList(), emptyList(), emptyList()),
            js = NovelPluginCatalog(installed = listOf(installedJs)),
            filter = filter,
        )

        assertEquals(setOf("apk:adult.apk", "js:offline-js"), state.installed.map { it.key.stableValue }.toSet())
        assertEquals(
            setOf(ContentKind.Manga),
            state.installed.single { it.key.stableValue == "apk:adult.apk" }.contentKinds,
        )
    }

    @Test
    fun `failed installed apk and failed javascript runtime stay visible`() {
        val installedJs = InstalledNovelPlugin(plugin("broken-js", "1.0.0"), REPO, 1L, "b".repeat(64))
        val state = ManagedExtensionProjector.project(
            apk = ApkExtensionSnapshot(
                emptyList(),
                emptyList(),
                emptyList(),
                listOf(ApkLoadFailure("broken.apk", "Broken APK", "1.0", ApkLoadFailure.Reason.ClassLoader)),
            ),
            js = NovelPluginCatalog(installed = listOf(installedJs), runtimeErrors = mapOf("broken-js" to IllegalStateException("boom"))),
            filter = filter,
        )

        assertEquals(2, state.installed.size)
        assertTrue(state.installed.all { it.health is ManagedHealth.LoadFailed })
    }

    @Test
    fun `failed apk update overlays the installed row instead of being discarded`() {
        val state = ManagedExtensionProjector.project(
            apk = ApkExtensionSnapshot(
                installed = listOf(installedApk("broken-update.apk")),
                untrusted = emptyList(),
                available = listOf(availableApk("broken-update.apk", 2)),
                failures = listOf(ApkLoadFailure("broken-update.apk", reason = ApkLoadFailure.Reason.SourceConstruction)),
            ),
            js = NovelPluginCatalog(),
            filter = filter,
        )

        assertEquals(1, state.entries.size)
        assertTrue(state.entries.single().health is ManagedHealth.LoadFailed)
    }

    @Test
    fun `updates are projected without hiding the installed record`() {
        val apk = installedApk("update.apk").copy(hasUpdate = true)
        val installedJs = InstalledNovelPlugin(plugin("update-js", "1.0.0"), REPO, 1L, "c".repeat(64))
        val state = ManagedExtensionProjector.project(
            apk = ApkExtensionSnapshot(listOf(apk), emptyList(), listOf(availableApk("update.apk", 2)), emptyList()),
            js = NovelPluginCatalog(
                available = listOf(plugin("update-js", "2.0.0")),
                installed = listOf(installedJs),
                availableOrigins = mapOf("update-js" to REPO),
            ),
            filter = filter,
        )

        assertEquals(setOf("apk:update.apk", "js:update-js"), state.updates.map { it.key.stableValue }.toSet())
    }

    private fun plugin(id: String, version: String) = NovelPluginDescriptor(
        id = id,
        name = id,
        lang = "en",
        version = version,
        url = "https://repo.example/$id.js",
    )

    private fun availableApk(pkg: String, version: Long = 1) = Extension.Available(
        name = pkg,
        pkgName = pkg,
        versionName = "1.6.$version",
        versionCode = version,
        libVersion = 1.6,
        lang = "en",
        isNsfw = false,
        isNovel = false,
        apkName = "$pkg.apk",
        iconUrl = "https://repo.example/icon.png",
        sources = emptyList(),
        repoUrl = REPO,
    )

    private fun installedApk(pkg: String, nsfw: Boolean = false) = Extension.Installed(
        name = pkg,
        pkgName = pkg,
        versionName = "1.6.1",
        versionCode = 1,
        libVersion = 1.6,
        lang = "fr",
        isNsfw = nsfw,
        isNovel = false,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        isShared = true,
    )

    private companion object {
        const val REPO = "https://repo.example/repo.json"
    }
}
