package eu.kanade.tachiyomi.ui.extension

import yokai.util.koin.get
import android.content.pm.PackageInstaller
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.extension.ExtensionInstallerJob
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.InstalledExtensionsOrder
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.ui.migration.BaseMigrationPresenter
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.withUIContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yokai.i18n.MR
import yokai.util.lang.getString
// NOVEL -->
import hayai.novel.plugin.NovelPluginManager
import hayai.novel.plugin.model.NovelPluginIndex
import hayai.novel.ui.NovelPluginGroupItem
import hayai.novel.ui.NovelPluginItem
// NOVEL <--

typealias ExtensionTuple =
    Triple<List<Extension.Installed>, List<Extension.Untrusted>, List<Extension.Available>>
typealias ExtensionIntallInfo = Pair<InstallStep, PackageInstaller.SessionInfo?>

/**
 * Presenter of [ExtensionBottomSheet].
 */
class ExtensionBottomPresenter : BaseMigrationPresenter<ExtensionBottomSheet>() {

    private var extensions = emptyList<ExtensionItem>()

    val downloadManager: DownloadManager = get()

    private var currentDownloads = hashMapOf<String, ExtensionIntallInfo>()

    private var firstLoad = true

    override fun onCreate() {
        super.onCreate()

        presenterScope.launch {
            val extensionJob = async {
                extensionManager.findAvailableExtensions()
                extensions = toItems(
                    Triple(
                        extensionManager.installedExtensionsFlow.value,
                        extensionManager.untrustedExtensionsFlow.value,
                        extensionManager.availableExtensionsFlow.value,
                    ),
                )
                withContext(Dispatchers.Main) { view?.setExtensions(extensions, false) }
            }
            val migrationJob = async { firstTimeMigration() }
            listOf(migrationJob, extensionJob).awaitAll()
        }
        // NOVEL: preload novel plugins on sheet create (mirroring extensionJob above) so the
        // Novel tab shows cached data immediately on first tap instead of rendering empty
        // then filling in once refreshNovelPlugins finishes.
        refreshNovelPlugins()
        presenterScope.launch {
            extensionManager.downloadSharedFlow
                .collect {
                    if (it.first.startsWith("Finished") || it.first.startsWith("Uninstalled")) {
                        if (it.first.startsWith("Finished")) {
                            firstLoad = true
                            currentDownloads.clear()
                        }
                        rebuildExtensions()
                        return@collect
                    }
                    val extension = extensions.find { item ->
                        it.first == item.extension.pkgName
                    } ?: return@collect
                    when (it.second.first) {
                        InstallStep.Installed, InstallStep.Error, InstallStep.Done -> {
                            currentDownloads.remove(extension.extension.pkgName)
                        }
                        else -> {
                            currentDownloads[extension.extension.pkgName] = it.second
                        }
                    }
                    if (it.second.first.isCompleted()) {
                        // The extension just finished installing (or failed/was cancelled) and
                        // may have moved from the Available/Updates group into the Installed
                        // group. A single updateItem() call only patches the row in place and
                        // can't relocate it between groups, so it'd keep showing under its old
                        // group with a stale status until the sheet was reopened. Rebuild the
                        // whole list instead so it lands in the right place. Note: some installs
                        // only ever emit a generic "Done" (from the install-status poll loop's
                        // onCompletion) rather than an explicit "Installed", so Done must be
                        // treated as terminal here too or those items never get unstuck.
                        rebuildExtensions()
                        return@collect
                    }
                    val item = updateInstallStep(extension.extension, it.second.first, it.second.second)
                    if (item != null) {
                        withUIContext { view?.downloadUpdate(item) }
                    }
                }
        }
    }

    private suspend fun rebuildExtensions() {
        extensions = toItems(
            Triple(
                extensionManager.installedExtensionsFlow.value,
                extensionManager.untrustedExtensionsFlow.value,
                extensionManager.availableExtensionsFlow.value,
            ),
        )
        withUIContext { view?.setExtensions(extensions) }
    }

    fun refreshExtensions() {
        presenterScope.launch {
            extensions = toItems(
                Triple(
                    extensionManager.installedExtensionsFlow.value,
                    extensionManager.untrustedExtensionsFlow.value,
                    extensionManager.availableExtensionsFlow.value,
                ),
            )
            withContext(Dispatchers.Main) { view?.setExtensions(extensions, false) }
        }
    }

    @Synchronized
    private fun toItems(tuple: ExtensionTuple): List<ExtensionItem> {
        val context = view?.context ?: return emptyList()
        val activeLangs = preferences.enabledLanguages().get()
        val showNsfwSources = preferences.showNsfwSources().get()

        val (installed, untrusted, available) = tuple

        val items = mutableListOf<ExtensionItem>()

        if (firstLoad) {
            val listOfExtensions = installed + untrusted + available
            listOfExtensions.forEach {
                val installInfo = extensionManager.getInstallInfo(it.pkgName) ?: return@forEach
                currentDownloads[it.pkgName] = installInfo
            }
            firstLoad = false
        }

        val updatesSorted = installed.filter { it.hasUpdate && (showNsfwSources || !it.isNsfw) }.sortedBy { it.name }
        val sortOrder = InstalledExtensionsOrder.fromPreference(preferences)
        val installedSorted = installed
            .filter { !it.hasUpdate && (showNsfwSources || !it.isNsfw) }
            .sortedWith(
                compareBy(
                    { !it.isObsolete },
                    {
                        when (sortOrder) {
                            InstalledExtensionsOrder.Name -> it.name
                            InstalledExtensionsOrder.RecentlyUpdated -> Long.MAX_VALUE - ExtensionLoader.extensionUpdateDate(context, it)
                            InstalledExtensionsOrder.RecentlyInstalled -> Long.MAX_VALUE - ExtensionLoader.extensionInstallDate(context, it)
                            InstalledExtensionsOrder.Language -> it.lang
                        }
                    },
                    { it.name },
                ),
            )
        val untrustedSorted = untrusted.sortedBy { it.name }
        val availableSorted = available
            // Filter out already installed extensions and disabled languages
            .filter { avail ->
                installed.none { it.pkgName == avail.pkgName } &&
                    untrusted.none { it.pkgName == avail.pkgName } &&
                    (avail.lang in activeLangs) &&
                    (showNsfwSources || !avail.isNsfw)
            }
            .sortedBy { it.name }

        if (updatesSorted.isNotEmpty()) {
            val header = ExtensionGroupItem(
                context.getString(
                    MR.plurals._updates_pending,
                    updatesSorted.size,
                    updatesSorted.size,
                ),
                updatesSorted.size,
                items.count { it.extension.pkgName in currentDownloads.keys } != updatesSorted.size,
            )
            items += updatesSorted.map { extension ->
                ExtensionItem(extension, header, currentDownloads[extension.pkgName])
            }
        }
        if (installedSorted.isNotEmpty() || untrustedSorted.isNotEmpty()) {
            val header = ExtensionGroupItem(context.getString(MR.strings.installed), installedSorted.size + untrustedSorted.size, installedSorting = preferences.installedExtensionsOrder().get())
            items += installedSorted.map { extension ->
                ExtensionItem(extension, header, currentDownloads[extension.pkgName])
            }
            items += untrustedSorted.map { extension ->
                ExtensionItem(extension, header)
            }
        }
        if (availableSorted.isNotEmpty()) {
            val availableGroupedByLang = availableSorted
                .groupBy { LocaleHelper.getSourceDisplayName(it.lang, context) }
                .toSortedMap()

            availableGroupedByLang
                .forEach {
                    val header = ExtensionGroupItem(it.key, it.value.size)
                    items += it.value.map { extension ->
                        ExtensionItem(extension, header, currentDownloads[extension.pkgName])
                    }
                }
        }

        this.extensions = items
        return items
    }

    fun getExtensionUpdateCount(): Int = preferences.extensionUpdatesCount().get()

    @Synchronized
    private fun updateInstallStep(
        extension: Extension,
        state: InstallStep?,
        session: PackageInstaller.SessionInfo?,
    ): ExtensionItem? {
        val extensions = extensions.toMutableList()
        val position = extensions.indexOfFirst { it.extension.pkgName == extension.pkgName }

        return if (position != -1) {
            val item = extensions[position].copy(
                installStep = state,
                session = session,
            )
            extensions[position] = item

            this.extensions = extensions
            item
        } else {
            null
        }
    }

    fun cancelExtensionInstall(extItem: ExtensionItem) {
        val sessionId = extItem.session?.sessionId ?: return
        extensionManager.cancelInstallation(sessionId)
    }

    fun installExtension(extension: Extension.Available) {
        presenterScope.launch {
            extensionManager.installExtension(
                ExtensionManager.ExtensionInfo(extension),
                presenterScope,
            )
                .collect {
                    when (it.first) {
                        InstallStep.Installed, InstallStep.Error, InstallStep.Done -> {
                            currentDownloads.remove(extension.pkgName)
                        }
                        else -> {
                            currentDownloads[extension.pkgName] = it
                        }
                    }
                    if (it.first.isCompleted()) {
                        // See the comment in the downloadSharedFlow collector above: this needs
                        // a full rebuild so the extension relocates into the Installed group,
                        // and Done must be treated as terminal too, not just Installed/Error.
                        rebuildExtensions()
                        return@collect
                    }
                    val item = updateInstallStep(extension, it.first, it.second)
                    if (item != null) {
                        withUIContext { view?.downloadUpdate(item) }
                    }
                }
        }
    }

    fun updateExtension(extension: Extension.Installed) {
        val availableExt =
            extensionManager.availableExtensionsFlow.value.find { it.pkgName == extension.pkgName } ?: return
        installExtension(availableExt)
    }

    fun updateExtensions(extensions: List<Extension.Installed>) {
        if (extensions.isEmpty()) return
        val context = view?.context ?: return
        extensions.forEach {
            val pkgName = it.pkgName
            currentDownloads[pkgName] = InstallStep.Pending to null
            val item = updateInstallStep(it, InstallStep.Pending, null) ?: return@forEach
            view?.downloadUpdate(item)
        }
        ExtensionInstallerJob.start(
            context,
            extensions.mapNotNull { extension ->
                extensionManager.availableExtensionsFlow.value.find { it.pkgName == extension.pkgName }
            },
        )
    }

    fun uninstallExtension(pkgName: String) {
        extensionManager.uninstallExtension(pkgName)
    }

    fun findAvailableExtensions() {
        presenterScope.launch {
            extensionManager.findAvailableExtensions()
        }
    }

    fun trustExtension(pkgName: String, versionCode: Long, signatureHash: String) {
        presenterScope.launch {
            extensionManager.trust(pkgName, versionCode, signatureHash)
        }
    }

    // NOVEL -->
    private val novelPluginManager: NovelPluginManager = get()

    fun refreshNovelPlugins() {
        presenterScope.launch {
            novelPluginManager.ensureInstalledPluginsLoaded()
            novelPluginManager.refreshAvailablePlugins()
            val available = novelPluginManager.availablePluginsFlow.value
            val items = toNovelPluginItems(available)
            withUIContext { view?.setNovelPlugins(items) }
        }
    }

    fun installNovelPlugin(plugin: NovelPluginIndex) {
        presenterScope.launch {
            novelPluginManager.installPlugin(plugin)
            refreshNovelPlugins()
        }
    }

    fun updateNovelPlugins(plugins: List<NovelPluginIndex>) {
        if (plugins.isEmpty()) return
        presenterScope.launch {
            plugins.forEach { plugin ->
                novelPluginManager.installPlugin(plugin)
            }
            refreshNovelPlugins()
        }
    }

    fun uninstallNovelPlugin(pluginId: String) {
        presenterScope.launch {
            novelPluginManager.uninstallPlugin(pluginId)
            refreshNovelPlugins()
        }
    }

    private fun toNovelPluginItems(available: List<NovelPluginIndex>): List<NovelPluginItem> {
        val context = view?.context ?: return emptyList()
        val activeLangs = preferences.enabledLanguages().get()
        val installedSortOrder = InstalledExtensionsOrder.fromPreference(preferences)
        val items = mutableListOf<NovelPluginItem>()
        val availableById = available.associateBy { it.id }
        val installedPlugins = novelPluginManager.installedPluginsFlow.value
        val installedIds = installedPlugins.map { it.id }.toSet()

        val installedItems = installedPlugins.map { installed ->
            val availablePlugin = availableById[installed.id]
            NovelPluginItem(
                plugin = availablePlugin ?: NovelPluginIndex(
                    id = installed.id,
                    name = installed.name,
                    site = installed.siteUrl,
                    lang = installed.lang,
                    version = installed.version,
                    iconUrl = installed.iconUrl,
                ),
                isInstalled = true,
                installedVersion = installed.version,
                isObsolete = availablePlugin == null,
            )
        }
        val installedWithUpdates = installedItems
            .filter { it.hasUpdate }
            .sortedBy { it.plugin.name }
        val installedUpToDateUnsorted = installedItems.filter { !it.hasUpdate }
        val installedUpToDate = when (installedSortOrder) {
            InstalledExtensionsOrder.Name -> {
                installedUpToDateUnsorted.sortedWith(
                    compareBy<NovelPluginItem>({ it.isObsolete }, { it.plugin.name }),
                )
            }
            InstalledExtensionsOrder.Language -> {
                installedUpToDateUnsorted.sortedWith(
                    compareBy<NovelPluginItem>({ it.isObsolete }, { NovelPluginManager.langFromLnReaderLang(it.plugin.lang) })
                        .thenBy { it.plugin.name },
                )
            }
            InstalledExtensionsOrder.RecentlyInstalled,
            InstalledExtensionsOrder.RecentlyUpdated,
            -> {
                installedUpToDateUnsorted.sortedWith(
                    compareBy<NovelPluginItem> { it.isObsolete }
                        .thenByDescending { novelPluginManager.getPluginLastModified(it.plugin.id) }
                        .thenBy { it.plugin.name },
                )
            }
        }

        // Filter available by enabled languages
        val availablePlugins = available.filter { plugin ->
            plugin.id !in installedIds &&
                NovelPluginManager.langFromLnReaderLang(plugin.lang) in activeLangs
        }.sortedBy { it.name }

        // Updates section
        if (installedWithUpdates.isNotEmpty()) {
            val header = NovelPluginGroupItem(
                context.getString(MR.plurals._updates_pending, installedWithUpdates.size, installedWithUpdates.size),
                installedWithUpdates.size,
                canUpdate = true,
            )
            installedWithUpdates.forEach { plugin ->
                items.add(plugin.copy(header = header))
            }
        }

        // Installed section
        if (installedUpToDate.isNotEmpty()) {
            val header = NovelPluginGroupItem(
                context.getString(MR.strings.installed),
                installedUpToDate.size,
                installedSorting = preferences.installedExtensionsOrder().get(),
            )
            installedUpToDate.forEach { plugin ->
                items.add(plugin.copy(header = header))
            }
        }

        // Available, grouped by language
        if (availablePlugins.isNotEmpty()) {
            val byLang = availablePlugins.groupBy { it.lang.ifBlank { "Other" } }.toSortedMap()
            for ((lang, plugins) in byLang) {
                val header = NovelPluginGroupItem(lang, plugins.size)
                plugins.forEach { plugin ->
                    items.add(NovelPluginItem(plugin, header, false, null))
                }
            }
        }

        return items
    }
    // NOVEL <--
}
