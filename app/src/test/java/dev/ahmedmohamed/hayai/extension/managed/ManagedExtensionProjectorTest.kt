package dev.ahmedmohamed.hayai.extension.managed

import dev.ahmedmohamed.hayai.extension.ApkLoadFailure
import dev.ahmedmohamed.hayai.extension.ExtensionCatalogReconciler
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

    @Test
    fun `first installed untrusted novel apk becomes a visible trust row immediately`() {
        val untrusted = Extension.Untrusted(
            name = "WTR-LAB",
            pkgName = "eu.kanade.tachiyomi.novelextension.all.wtrlab",
            versionName = "1.6.12",
            versionCode = 12,
            libVersion = 1.6,
            signatureHash = "a".repeat(64),
            lang = "all",
            isNovel = true,
        )
        val snapshot = ApkExtensionSnapshot(emptyList(), emptyList(), emptyList(), emptyList())
        val recorded = ExtensionCatalogReconciler.recordUntrusted(snapshot, untrusted)
        val repeated = ExtensionCatalogReconciler.recordUntrusted(recorded, untrusted)
        val state = ManagedExtensionProjector.project(repeated, NovelPluginCatalog(), filter)
        assertEquals(1, repeated.untrusted.size)
        assertEquals(ManagedInstallation.Untrusted, state.installed.single().installation)
        assertEquals(setOf(ContentKind.Novel), state.installed.single().contentKinds)
    }

    @Test
    fun `untrusted update replaces only its old installed record and failure`() {
        val target = installedApk("target")
        val other = installedApk("other")
        val untrusted = Extension.Untrusted("Target", "target", "1.6.2", 2, 1.6, "hash")
        val result = ExtensionCatalogReconciler.recordUntrusted(
            ApkExtensionSnapshot(
                listOf(target, other),
                emptyList(),
                emptyList(),
                listOf(ApkLoadFailure("target", reason = ApkLoadFailure.Reason.ClassLoader)),
            ),
            untrusted,
        )
        assertEquals(listOf(other), result.installed)
        assertEquals(listOf(untrusted), result.untrusted)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `novel update all dispatches apk and js once and skips active or untrusted entries`() {
        val installedJs = InstalledNovelPlugin(plugin("js", "1.0.0"), REPO, 1L, "c".repeat(64))
        val state = ManagedExtensionProjector.project(
            ApkExtensionSnapshot(
                listOf(installedApk("novel").copy(isNovel = true, hasUpdate = true)),
                emptyList(),
                listOf(availableApk("novel", 2).copy(isNovel = true)),
                emptyList(),
            ),
            NovelPluginCatalog(
                installed = listOf(installedJs),
                available = listOf(plugin("js", "2.0.0")),
                availableOrigins = mapOf("js" to REPO),
            ),
            filter,
        )
        val duplicated = state.entries + state.entries
        val plan = ManagedExtensionUpdatePlan.create(duplicated, ContentKind.Novel, emptySet())
        assertEquals(listOf("novel"), plan.apkPackages)
        assertEquals(listOf("js"), plan.pluginIds)
        val active = ManagedExtensionUpdatePlan.create(duplicated, ContentKind.Novel, setOf(ManagedExtensionKey.Js("js")))
        assertTrue(active.pluginIds.isEmpty())
        val manga = ManagedExtensionUpdatePlan.create(duplicated, ContentKind.Manga, emptySet())
        assertTrue(manga.apkPackages.isEmpty())
        assertTrue(manga.pluginIds.isEmpty())
        val untrusted = duplicated.map { it.copy(installation = ManagedInstallation.Untrusted) }
        assertTrue(ManagedExtensionUpdatePlan.create(untrusted, ContentKind.Novel, emptySet()).apkPackages.isEmpty())
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
