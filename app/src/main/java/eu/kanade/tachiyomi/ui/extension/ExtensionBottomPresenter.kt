package eu.kanade.tachiyomi.ui.extension

import android.content.pm.PackageInstaller
import dev.ahmedmohamed.hayai.extension.managed.ApkExtensionSnapshot
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionEntry
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionFilter
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionKey
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionProjector
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionState
import dev.ahmedmohamed.hayai.novel.integration.ContentKind
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginManager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.ExtensionInstallerJob
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.InstalledExtensionsOrder
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.ui.migration.BaseMigrationPresenter
import eu.kanade.tachiyomi.util.system.LocaleHelper
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
    private var extensions = ContentKind.entries.associateWith { emptyList<ExtensionItem>() }
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
                val item = extensions.values.flatten().firstOrNull { row ->
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
        val rebuilt = ContentKind.entries.associateWith { kind -> toItems(projected, kind) }
        extensions = rebuilt
        withContext(NonCancellable + Dispatchers.Main) {
            ContentKind.entries.forEachIndexed { index, kind ->
                view?.setExtensions(kind, rebuilt.getValue(kind), projected.notices, updateController && index == 0)
            }
        }
    }

    @Synchronized
    private fun toItems(
        state: ManagedExtensionState,
        contentKind: ContentKind,
    ): List<ExtensionItem> {
        val context = view?.context ?: return emptyList()
        if (firstLoad) {
            state.entries.forEach { entry ->
                val pkg = (entry.key as? ManagedExtensionKey.Apk)?.packageName ?: return@forEach
                extensionManager.getInstallInfo(pkg)?.let { currentDownloads[pkg] = it }
            }
            firstLoad = false
        }
        val updates = state.updates.filter { contentKind in it.contentKinds }
        val installed = state.installed.filter { contentKind in it.contentKinds }
        val available = state.available.filter { contentKind in it.contentKinds }
        val sortOrder = InstalledExtensionsOrder.fromPreference(preferences)
        val installedSorted =
            installed.sortedWith(
                compareBy(
                    { entry ->
                        val apk = entry.apkInstalled()
                        apk?.isObsolete != true
                    },
                    { entry ->
                        val apk = entry.apkInstalled()
                        when (sortOrder) {
                            InstalledExtensionsOrder.Name -> entry.name
                            InstalledExtensionsOrder.RecentlyUpdated ->
                                apk?.let { Long.MAX_VALUE - ExtensionLoader.extensionUpdateDate(context, it) } ?: Long.MAX_VALUE
                            InstalledExtensionsOrder.RecentlyInstalled ->
                                apk?.let { Long.MAX_VALUE - ExtensionLoader.extensionInstallDate(context, it) } ?: Long.MAX_VALUE
                            InstalledExtensionsOrder.Language -> entry.language
                        }
                    },
                    ManagedExtensionEntry::name,
                ),
            )
        return buildList {
            if (updates.isNotEmpty()) {
                val header = ExtensionGroupItem(
                    context.resources.getQuantityString(R.plurals._updates_pending, updates.size, updates.size),
                    updates.size,
                    canUpdate = updates.any { entry ->
                        val item = entry.toItem(null)
                        item.installStep == null || item.installStep == InstallStep.Error
                    },
                )
                addAll(updates.sortedBy(ManagedExtensionEntry::name).map { it.toItem(header) })
            }
            if (installedSorted.isNotEmpty()) {
                val header = ExtensionGroupItem(
                    context.getString(R.string.installed),
                    installedSorted.size,
                    installedSorting = preferences.installedExtensionsOrder().get(),
                )
                addAll(installedSorted.map { it.toItem(header) })
            }
            available
                .sortedBy(ManagedExtensionEntry::name)
                .groupBy { entry -> LocaleHelper.getSourceDisplayName(entry.language.orEmpty(), context) }
                .toSortedMap()
                .forEach { (language, entries) ->
                    val header = ExtensionGroupItem(language, entries.size)
                    addAll(entries.map { it.toItem(header) })
                }
        }
    }

    private fun ManagedExtensionEntry.apkInstalled(): Extension.Installed? {
        val pkgName = (key as? ManagedExtensionKey.Apk)?.packageName ?: return null
        return extensionManager.installedExtensionsFlow.value.firstOrNull { it.pkgName == pkgName }
    }

    private fun ManagedExtensionEntry.toItem(header: ExtensionGroupItem?): ExtensionItem {
        val apkInfo = (key as? ManagedExtensionKey.Apk)?.packageName?.let(currentDownloads::get)
        val pluginStep = (key as? ManagedExtensionKey.Js)?.pluginId?.takeIf(pluginOperations::contains)?.let { InstallStep.Loading }
        return ExtensionItem(
            extension = this,
            header = header,
            installStep = pluginStep ?: apkInfo?.first,
            session = apkInfo?.second,
            apkInstalled = apkInstalled(),
        )
    }

    fun getExtensionUpdateCount(): Int = extensions.values.flatten().distinctBy { it.extension.key }.count { it.extension.hasUpdate }

    @Synchronized
    private fun updateInstallStep(
        extension: ManagedExtensionEntry,
        state: InstallStep?,
        session: PackageInstaller.SessionInfo?,
    ): ExtensionItem? {
        var firstUpdated: ExtensionItem? = null
        ContentKind.entries.filter { it in extension.contentKinds }.forEach { kind ->
            val items = extensions.getValue(kind).toMutableList()
            val position = items.indexOfFirst { it.extension.key == extension.key }
            if (position != -1) {
                val item = items[position].copy(installStep = state, session = session)
                items[position] = item
                extensions = extensions + (kind to items)
                firstUpdated = firstUpdated ?: item
            }
        }
        return firstUpdated
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
                extensions.values.flatten().firstOrNull { it.extension.key == ManagedExtensionKey.Apk(pkgName) }?.extension?.let { entry ->
                    updateInstallStep(entry, info.first, info.second)?.let { item ->
                        withContext(NonCancellable + Dispatchers.Main) { view?.downloadUpdate(item) }
                    }
                }
            }
        }
    }

    fun updateAllPendingExtensions(contentKind: ContentKind) {
        val apkUpdates = extensionManager.installedExtensionsFlow.value.filter { contentKind.accepts(it) && it.hasUpdate }
        updateExtensions(apkUpdates)
        extensions.getValue(contentKind)
            .mapNotNull { (it.extension.key as? ManagedExtensionKey.Js)?.pluginId?.takeIf { _ -> it.extension.hasUpdate } }
            .forEach { id -> pluginAction(id) { pluginManager.update(id) } }
    }

    fun updateExtensions(extensions: List<Extension.Installed>) {
        if (extensions.isEmpty()) return
        val context = view?.context ?: return
        extensions.forEach { extension ->
            currentDownloads[extension.pkgName] = InstallStep.Pending to null
            this.extensions.values.flatten().firstOrNull { it.extension.key == ManagedExtensionKey.Apk(extension.pkgName) }?.extension?.let { entry ->
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
