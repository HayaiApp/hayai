package dev.ahmedmohamed.hayai.novel.extension

import android.content.Context
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.ui.extension.ExtensionIntallInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

interface NovelApkRepositoryRegistry {
    suspend fun repositories(): Set<String>
    suspend fun add(indexUrl: String)
    suspend fun remove(indexUrl: String)
}

class J2kNovelApkRepositoryRegistry(context: Context, private val preferences: PreferencesHelper) : NovelApkRepositoryRegistry {
    private val tags = context.getSharedPreferences("hayai_novel_apk_repositories", Context.MODE_PRIVATE)
    override suspend fun repositories(): Set<String> = tags.getStringSet(KEY, emptySet()).orEmpty().toSet()
    override suspend fun add(indexUrl: String) {
        val global = preferences.extensionRepos().get()
        val owned = tags.getStringSet(OWNED_KEY, emptySet()).orEmpty().toSet()
        check(
            tags.edit()
                .putStringSet(KEY, repositories() + indexUrl)
                .putStringSet(OWNED_KEY, if (indexUrl in global) owned else owned + indexUrl)
                .commit(),
        )
        if (indexUrl !in global) preferences.extensionRepos().set(global + indexUrl)
    }
    override suspend fun remove(indexUrl: String) {
        val owned = tags.getStringSet(OWNED_KEY, emptySet()).orEmpty().toSet()
        if (indexUrl in owned) preferences.extensionRepos().set(preferences.extensionRepos().get() - indexUrl)
        check(
            tags.edit()
                .putStringSet(KEY, repositories() - indexUrl)
                .putStringSet(OWNED_KEY, owned - indexUrl)
                .commit(),
        )
    }
    private companion object { const val KEY = "urls_v1"; const val OWNED_KEY = "owned_global_urls_v1" }
}

data class NovelApkExtensionCatalog(
    val updates: List<Extension.Available> = emptyList(),
    val installed: List<Extension.Installed> = emptyList(),
    val available: List<Extension.Available> = emptyList(),
    val untrusted: List<Extension.Untrusted> = emptyList(),
    val repositories: Set<String> = emptySet(),
    val refreshing: Boolean = false,
    val error: String? = null,
)

class NovelApkExtensionManager(
    private val j2k: ExtensionManager,
    private val repositories: NovelApkRepositoryRegistry,
    scope: CoroutineScope,
) {
    private val _catalog = MutableStateFlow(NovelApkExtensionCatalog())
    val catalog: StateFlow<NovelApkExtensionCatalog> = _catalog.asStateFlow()

    init {
        scope.launch {
            combine(
                j2k.installedExtensionsFlow,
                j2k.availableExtensionsFlow,
                j2k.untrustedExtensionsFlow,
            ) { installed, available, untrusted ->
                Triple(installed, available, untrusted)
            }
                .collect { (installed, available, untrusted) -> rebuild(installed, available, untrusted) }
        }
    }

    suspend fun refresh() {
        _catalog.value = _catalog.value.copy(refreshing = true, error = null)
        runCatching { j2k.findAvailableExtensions() }.onFailure {
            _catalog.value = _catalog.value.copy(error = it.message ?: "Extension catalog refresh failed")
        }
        rebuild(j2k.installedExtensionsFlow.value, j2k.availableExtensionsFlow.value, j2k.untrustedExtensionsFlow.value)
        _catalog.value = _catalog.value.copy(refreshing = false)
    }

    suspend fun addRepository(indexUrl: String) {
        validateRepositoryUrl(indexUrl); repositories.add(indexUrl); refresh()
    }
    suspend fun removeRepository(indexUrl: String) {
        repositories.remove(indexUrl)
        refresh()
    }

    suspend fun install(extension: Extension.Available, scope: CoroutineScope): Flow<ExtensionIntallInfo> {
        require(extension in _catalog.value.available || extension in _catalog.value.updates) { "Extension is not from a configured novel repository" }
        return j2k.installExtension(ExtensionManager.ExtensionInfo(extension), scope)
    }

    suspend fun update(extension: Extension.Installed, scope: CoroutineScope): Flow<ExtensionIntallInfo> {
        val available = _catalog.value.updates.firstOrNull { it.pkgName == extension.pkgName } ?: error("No update is available")
        return j2k.installExtension(ExtensionManager.ExtensionInfo(available), scope)
    }

    fun remove(extension: Extension.Installed) {
        require(extension in _catalog.value.installed)
        j2k.uninstallExtension(extension.pkgName)
    }

    fun trust(extension: Extension.Untrusted) {
        require(extension in _catalog.value.untrusted)
        j2k.trust(extension.pkgName, extension.versionCode, extension.signatureHash)
    }

    private suspend fun rebuild(installed: List<Extension.Installed>, available: List<Extension.Available>, untrusted: List<Extension.Untrusted>) {
        val repos = repositories.repositories()
        val installedNovel = installed.filter { extension -> extension.sources.any { it.isNovelSource() } }
        val installedPackages = installedNovel.mapTo(mutableSetOf(), Extension.Installed::pkgName)
        val fromNovelRepos = available.filter { extension -> repos.any { sameRepository(it, extension.repoUrl) } || extension.pkgName in installedPackages }
        val updates = fromNovelRepos.filter { remote -> installedNovel.any { it.pkgName == remote.pkgName && remote.versionCode > it.versionCode } }
        val availableOnly = fromNovelRepos.filter { remote -> installedNovel.none { it.pkgName == remote.pkgName } }
        val untrustedNovel = untrusted.filter { it.pkgName in fromNovelRepos.map(Extension.Available::pkgName) || it.pkgName.contains(".novel.") }
        _catalog.value = _catalog.value.copy(
            updates = updates.sortedBy { it.name },
            installed = installedNovel.sortedBy { it.name },
            available = availableOnly.sortedBy { it.name },
            untrusted = untrustedNovel.sortedBy { it.name },
            repositories = repos,
        )
    }

    private fun sameRepository(configured: String, actual: String): Boolean =
        configured.trimEnd('/').equals(actual.trimEnd('/'), true) ||
            actual.startsWith(configured.substringBeforeLast('/').trimEnd('/') + "/")

    private fun validateRepositoryUrl(value: String) {
        val uri = runCatching { java.net.URI(value) }.getOrNull()
        require(uri?.scheme == "https" && uri.host != null && uri.userInfo == null) {
            "Novel extension repositories must use HTTPS"
        }
        require(value.length <= 8_192)
    }
}
