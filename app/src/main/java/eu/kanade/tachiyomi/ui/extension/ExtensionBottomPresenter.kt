package eu.kanade.tachiyomi.ui.extension

import android.content.pm.PackageInstaller
import dev.ahmedmohamed.hayai.extension.managed.ApkExtensionSnapshot
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionEntry
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionFilter
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionKey
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionProjector
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionState
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginManager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.ExtensionInstallerJob
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.migration.BaseMigrationPresenter
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

typealias ExtensionIntallInfo = Pair<InstallStep, PackageInstaller.SessionInfo?>

/** J2K migration presenter with one Hayai-owned projection for APK and JS extensions. */
class ExtensionBottomPresenter : BaseMigrationPresenter<ExtensionBottomSheet>() {
    private val pluginManager: NovelPluginManager = Injekt.get()
    private var extensions = emptyList<ExtensionItem>()
    private var currentDownloads = hashMapOf<String, ExtensionIntallInfo>()
    private var pluginOperations = emptySet<String>()
    private var firstLoad = true

    override fun onCreate() {
        super.onCreate()
        presenterScope.launch {
            listOf(
                async { extensionManager.findAvailableExtensions() },
                async { pluginManager.refresh() },
                async { firstTimeMigration() },
            ).awaitAll()
            rebuildExtensions(updateController = false)
        }
        presenterScope.launch {
            pluginManager.catalog.collectLatest { rebuildExtensions() }
        }
        presenterScope.launch {
            extensionManager.downloadSharedFlow.collect { event ->
                if (event.first.startsWith("Finished") || event.first.startsWith("Uninstalled")) {
                    if (event.first.startsWith("Finished")) {
                        firstLoad = true
                        currentDownloads.clear()
                    }
                    rebuildExtensions()
                    return@collect
                }
                val item = extensions.firstOrNull { row ->
                    (row.extension.key as? ManagedExtensionKey.Apk)?.packageName == event.first
                } ?: return@collect
                val pkgName = (item.extension.key as ManagedExtensionKey.Apk).packageName
                when (event.second.first) {
                    InstallStep.Installed, InstallStep.Error -> currentDownloads.remove(pkgName)
                    else -> currentDownloads[pkgName] = event.second
                }
                updateInstallStep(item.extension, event.second.first, event.second.second)?.let { updated ->
                    withContext(NonCancellable + Dispatchers.Main) { view?.downloadUpdate(updated) }
                }
            }
        }
    }

    fun refreshExtensions() {
        presenterScope.launch { rebuildExtensions(updateController = false) }
    }

    private suspend fun rebuildExtensions(updateController: Boolean = true) {
        val projected = ManagedExtensionProjector.project(
            apk = ApkExtensionSnapshot(
                installed = extensionManager.installedExtensionsFlow.value,
                untrusted = extensionManager.untrustedExtensionsFlow.value,
                available = extensionManager.availableExtensionsFlow.value,
                failures = extensionManager.failedExtensionsFlow.value,
            ),
            js = pluginManager.catalog.value,
            filter = ManagedExtensionFilter(
                enabledLanguages = preferences.enabledLanguages().get(),
                showNsfwSources = preferences.showNsfwSources().get(),
            ),
        )
        extensions = toItems(projected)
        withContext(NonCancellable + Dispatchers.Main) {
            view?.setExtensions(extensions, projected.notices, updateController)
        }
    }

    @Synchronized
    private fun toItems(state: ManagedExtensionState): List<ExtensionItem> {
        val context = view?.context ?: return emptyList()
        if (firstLoad) {
            state.entries.forEach { entry ->
                val pkg = (entry.key as? ManagedExtensionKey.Apk)?.packageName ?: return@forEach
                extensionManager.getInstallInfo(pkg)?.let { currentDownloads[pkg] = it }
            }
            firstLoad = false
        }
        return buildList {
            if (state.updates.isNotEmpty()) {
                val header = ExtensionGroupItem(
                    context.resources.getQuantityString(R.plurals._updates_pending, state.updates.size, state.updates.size),
                    state.updates.size,
                    canUpdate = true,
                )
                addAll(state.updates.map { it.toItem(header) })
            }
            if (state.installed.isNotEmpty()) {
                val header = ExtensionGroupItem(context.getString(R.string.installed), state.installed.size)
                addAll(state.installed.map { it.toItem(header) })
            }
            if (state.available.isNotEmpty()) {
                val header = ExtensionGroupItem(context.getString(R.string.hayai_extension_available), state.available.size)
                addAll(state.available.map { it.toItem(header) })
            }
        }
    }

    private fun ManagedExtensionEntry.toItem(header: ExtensionGroupItem): ExtensionItem {
        val apkInfo = (key as? ManagedExtensionKey.Apk)?.packageName?.let(currentDownloads::get)
        val pluginStep = (key as? ManagedExtensionKey.Js)?.pluginId?.takeIf(pluginOperations::contains)?.let { InstallStep.Loading }
        return ExtensionItem(this, header, pluginStep ?: apkInfo?.first, apkInfo?.second)
    }

    fun getExtensionUpdateCount(): Int = extensions.count { it.extension.hasUpdate }

    @Synchronized
    private fun updateInstallStep(
        extension: ManagedExtensionEntry,
        state: InstallStep?,
        session: PackageInstaller.SessionInfo?,
    ): ExtensionItem? {
        val position = extensions.indexOfFirst { it.extension.key == extension.key }
        if (position == -1) return null
        val item = extensions[position].copy(installStep = state, session = session)
        extensions = extensions.toMutableList().also { it[position] = item }
        return item
    }

    fun cancelExtensionInstall(item: ExtensionItem) {
        item.session?.sessionId?.let(extensionManager::cancelInstallation)
    }

    fun install(entry: ManagedExtensionEntry) {
        when (val key = entry.key) {
            is ManagedExtensionKey.Apk -> installApk(key.packageName)
            is ManagedExtensionKey.Js -> pluginAction(key.pluginId) { pluginManager.install(key.pluginId) }
        }
    }

    fun update(entry: ManagedExtensionEntry) {
        when (val key = entry.key) {
            is ManagedExtensionKey.Apk -> installApk(key.packageName)
            is ManagedExtensionKey.Js -> pluginAction(key.pluginId) { pluginManager.update(key.pluginId) }
        }
    }

    private fun installApk(pkgName: String) {
        val context = view?.context
        if (context?.isOnline() == false) {
            context.toast(R.string.no_network_connection)
            return
        }
        val available = extensionManager.availableExtensionsFlow.value.firstOrNull { it.pkgName == pkgName } ?: return
        presenterScope.launch {
            extensionManager.installExtension(ExtensionManager.ExtensionInfo(available), presenterScope).collect { info ->
                when (info.first) {
                    InstallStep.Installed, InstallStep.Error -> currentDownloads.remove(pkgName)
                    else -> currentDownloads[pkgName] = info
                }
                extensions.firstOrNull { it.extension.key == ManagedExtensionKey.Apk(pkgName) }?.extension?.let { entry ->
                    updateInstallStep(entry, info.first, info.second)?.let { item ->
                        withContext(NonCancellable + Dispatchers.Main) { view?.downloadUpdate(item) }
                    }
                }
            }
        }
    }

    fun updateAllPendingExtensions() {
        val apkUpdates = extensionManager.installedExtensionsFlow.value.filter(Extension.Installed::hasUpdate)
        updateExtensions(apkUpdates)
        extensions.mapNotNull { (it.extension.key as? ManagedExtensionKey.Js)?.pluginId?.takeIf { _ -> it.extension.hasUpdate } }
            .forEach { id -> pluginAction(id) { pluginManager.update(id) } }
    }

    fun updateExtensions(extensions: List<Extension.Installed>) {
        if (extensions.isEmpty()) return
        val context = view?.context ?: return
        extensions.forEach { extension ->
            currentDownloads[extension.pkgName] = InstallStep.Pending to null
            this.extensions.firstOrNull { it.extension.key == ManagedExtensionKey.Apk(extension.pkgName) }?.extension?.let { entry ->
                updateInstallStep(entry, InstallStep.Pending, null)?.let { view?.downloadUpdate(it) }
            }
        }
        ExtensionInstallerJob.start(
            context,
            extensions.mapNotNull { installed -> extensionManager.availableExtensionsFlow.value.find { it.pkgName == installed.pkgName } },
        )
    }

    fun uninstall(entry: ManagedExtensionEntry) {
        when (val key = entry.key) {
            is ManagedExtensionKey.Apk -> extensionManager.uninstallExtension(key.packageName)
            is ManagedExtensionKey.Js -> pluginAction(key.pluginId) { pluginManager.uninstall(key.pluginId) }
        }
    }

    fun findAvailableExtensions() {
        presenterScope.launch {
            listOf(async { extensionManager.findAvailableExtensions() }, async { pluginManager.refresh() }).awaitAll()
            rebuildExtensions()
        }
    }

    fun trustExtension(
        pkgName: String,
        versionCode: Long,
        signatureHash: String,
    ) = extensionManager.trust(pkgName, versionCode, signatureHash)

    fun availableExtension(pkgName: String): Extension.Available? =
        extensionManager.availableExtensionsFlow.value.firstOrNull { it.pkgName == pkgName }

    fun untrustedExtension(pkgName: String): Extension.Untrusted? =
        extensionManager.untrustedExtensionsFlow.value.firstOrNull { it.pkgName == pkgName }

    private fun pluginAction(pluginId: String, action: suspend () -> Any?) {
        if (pluginId in pluginOperations) return
        pluginOperations += pluginId
        presenterScope.launch {
            rebuildExtensions()
            runCatching { action() }.onFailure { failure ->
                withContext(Dispatchers.Main) { view?.context?.toast(failure.message ?: view?.context?.getString(R.string.unknown_error).orEmpty()) }
            }
            pluginOperations -= pluginId
            rebuildExtensions()
        }
    }
}
