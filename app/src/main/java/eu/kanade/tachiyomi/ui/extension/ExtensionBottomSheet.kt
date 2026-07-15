package eu.kanade.tachiyomi.ui.extension

import yokai.util.koin.get
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExtensionOff
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.ExtensionsBottomSheetBinding
import eu.kanade.tachiyomi.databinding.ExtensionsTypePageBinding
import eu.kanade.tachiyomi.databinding.RecyclerWithScrollerBinding
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.InstalledExtensionsOrder
import eu.kanade.tachiyomi.ui.extension.details.ExtensionDetailsController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.migration.BaseMigrationInterface
import eu.kanade.tachiyomi.ui.migration.MangaAdapter
import eu.kanade.tachiyomi.ui.migration.MangaItem
import eu.kanade.tachiyomi.ui.migration.SourceAdapter
import eu.kanade.tachiyomi.ui.migration.SourceItem
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.ui.source.BrowseController
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.collapse
import eu.kanade.tachiyomi.util.view.doOnApplyWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.expand
import eu.kanade.tachiyomi.util.view.isExpanded
import eu.kanade.tachiyomi.util.view.popupMenu
import eu.kanade.tachiyomi.util.view.setMessage
import eu.kanade.tachiyomi.util.view.setNegativeButton
import eu.kanade.tachiyomi.util.view.setPositiveButton
import eu.kanade.tachiyomi.util.view.setText
import eu.kanade.tachiyomi.util.view.setTitle
import eu.kanade.tachiyomi.util.view.smoothScrollToTop
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.widget.EmptyView
import yokai.util.koin.injectLazy
import yokai.domain.base.BasePreferences
import yokai.domain.base.BasePreferences.ExtensionInstaller
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR
// NOVEL -->
import hayai.novel.plugin.NovelPluginManager
import hayai.novel.plugin.model.NovelPluginIndex
import hayai.novel.ui.NovelPluginAdapter
import hayai.novel.ui.NovelPluginGroupItem
import hayai.novel.ui.NovelPluginItem
// NOVEL <--
import yokai.presentation.extension.repo.ExtensionRepoController

class ExtensionBottomSheet @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs),
    ExtensionAdapter.OnButtonClickListener,
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    SourceAdapter.OnAllClickListener,
    BaseMigrationInterface,
    // NOVEL -->
    NovelPluginAdapter.OnButtonClickListener {
    // NOVEL <--

    private val basePreferences: BasePreferences by injectLazy()

    var sheetBehavior: BottomSheetBehavior<*>? = null

    var shouldCallApi = false

    /**
     * Adapter containing the list of extensions
     */
    private var extAdapter: ExtensionAdapter? = null
    private var migAdapter: FlexibleAdapter<IFlexible<*>>? = null
    // NOVEL -->
    private var novelPluginAdapter: NovelPluginAdapter? = null
    // NOVEL <--

    val presenter = ExtensionBottomPresenter()
    var currentSourceTitle: String? = null

    private var extensions: List<ExtensionItem> = emptyList()
    // NOVEL -->
    private var novelPlugins: List<NovelPluginItem> = emptyList()
    // NOVEL <--
    var canExpand = false
    private lateinit var binding: ExtensionsBottomSheetBinding

    lateinit var controller: BrowseController
    var boundViews = arrayListOf<RecyclerWithScrollerView>()
    private var selectedExtensionTypeTab = EXTENSION_TYPE_MANGA
    private var tabbedSheetAdapter: TabbedSheetAdapter? = null

    val extensionFrameLayout: RecyclerWithScrollerView?
        get() = findViewWithTag(EXTENSION_RECYCLER_TAG) as? RecyclerWithScrollerView
    // NOVEL -->
    val novelPluginFrameLayout: RecyclerWithScrollerView?
        get() = findViewWithTag(NOVEL_RECYCLER_TAG) as? RecyclerWithScrollerView
    // NOVEL <--
    val migrationFrameLayout: RecyclerWithScrollerView?
        get() = findViewWithTag(MIGRATION_RECYCLER_TAG) as? RecyclerWithScrollerView

    var isExpanding = false

    // Share holders only between recyclers owned by this sheet. A process-static pool retained
    // holders with the old Activity context and, more subtly, ExtensionHolder's old adapter /
    // click listener after controller recreation. Persistent root tabs already keep this sheet
    // alive during ordinary tab swaps, so instance scope preserves reuse without stale owners.
    val sharedExtensionPool = RecyclerView.RecycledViewPool()

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = ExtensionsBottomSheetBinding.bind(this)
    }

    companion object {
        // Defer non-essential tab-swap work (menu rebuild, migration adapter swap) past the
        // typical 300ms ViewPager settle so it lands after the swipe is visually complete.
        private const val SWAP_DEFER_MS = 350L
        private const val OUTER_TAB_EXTENSIONS = 0
        private const val OUTER_TAB_MIGRATION = 1
        private const val EXTENSION_TYPE_MANGA = 0
        private const val EXTENSION_TYPE_NOVELS = 1
        private const val EXTENSION_RECYCLER_TAG = "TabbedRecycler0"
        private const val NOVEL_RECYCLER_TAG = "TabbedRecycler1"
        private const val MIGRATION_RECYCLER_TAG = "TabbedRecycler2"
        private const val EXTENSION_PAGE_TAG = "TabbedSheetPageExtensions"
        private const val MIGRATION_PAGE_TAG = "TabbedSheetPageMigration"

    }

    fun onCreate(controller: BrowseController) {
        // Initialize adapter, scroll listener and recycler views
        presenter.attachView(this)
        extAdapter = ExtensionAdapter(this)
        extAdapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        if (migAdapter == null) {
            migAdapter = SourceAdapter(this)
        }
        migAdapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        // NOVEL -->
        if (novelPluginAdapter == null) {
            novelPluginAdapter = NovelPluginAdapter(this)
        }
        novelPluginAdapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        // NOVEL <--
        sheetBehavior = BottomSheetBehavior.from(this)
        // Assign controller BEFORE wiring the ViewPager. setAdapter() synchronously calls
        // populate() -> instantiateItem() -> TabbedSheetAdapter.createView(), which reads
        // this.controller — leaving it for after the adapter assignment crashed with
        // UninitializedPropertyAccessException.
        this.controller = controller

        tabbedSheetAdapter = TabbedSheetAdapter().also { binding.pager.adapter = it }
        binding.tabs.setupWithViewPager(binding.pager)
        binding.pager.doOnApplyWindowInsetsCompat { _, insets, _ ->
            val bottomBar = controller.activityBinding?.bottomNav
            val bottomH = bottomBar?.height ?: insets.getInsets(systemBars()).bottom
            extensionFrameLayout?.binding?.recycler?.updatePaddingRelative(bottom = bottomH)
            migrationFrameLayout?.binding?.recycler?.updatePaddingRelative(bottom = bottomH)
            // NOVEL -->
            novelPluginFrameLayout?.binding?.recycler?.updatePaddingRelative(bottom = bottomH)
            // NOVEL <--
        }
        binding.tabs.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    if (tab?.position == OUTER_TAB_MIGRATION) {
                        // Normally prepared just after the first sheet expansion. Keep this
                        // fallback for users who tap Migration while the sheet is collapsed.
                        tabbedSheetAdapter?.ensureMigrationPage()
                    }
                    isExpanding = !sheetBehavior.isExpanded()
                    if (canExpand) {
                        this@ExtensionBottomSheet.sheetBehavior?.expand()
                    }
                    val frame = getFrameLayoutForTab(tab?.position)
                    frame?.binding?.recycler?.isNestedScrollingEnabled = true
                    sheetBehavior?.isDraggable = true
                    // Don't refresh novel plugins here — presenter.onCreate already preloads
                    // them. Refresh-on-tap fired a redundant network call + UI update on every
                    // swap; onTabReselected still triggers an explicit refresh.
                    // Defer the menu rebuild past the ViewPager settle. updateSheetMenu does
                    // toolbar.menu.clear() + inflateMenu(...) + SearchView wiring when crossing
                    // between Migration (migration_main) and Extensions (extension_main) —
                    // synchronous XML parsing on the swipe-critical frame.
                    binding.pager.postDelayed(
                        { this@ExtensionBottomSheet.controller.updateTitleAndMenu() },
                        SWAP_DEFER_MS,
                    )
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {
                    getFrameLayoutForTab(tab?.position)?.binding?.recycler?.isNestedScrollingEnabled = false
                    if (tab?.position == OUTER_TAB_MIGRATION) {
                        // setMigrationSources may swap the migration adapter
                        // (MangaAdapter -> SourceAdapter) which triggers a full layout pass on
                        // the still-attached migration recycler. View.post fires on the next
                        // looper tick which is the swipe-settle frame — still visually
                        // competing. postDelayed past the typical 300ms settle so the swap
                        // lands off-screen.
                        binding.pager.postDelayed({ presenter.deselectSource() }, SWAP_DEFER_MS)
                    }
                }

                override fun onTabReselected(tab: TabLayout.Tab?) {
                    isExpanding = !sheetBehavior.isExpanded()
                    this@ExtensionBottomSheet.sheetBehavior?.expand()
                    getFrameLayoutForTab(tab?.position)?.binding?.recycler?.isNestedScrollingEnabled = true
                    sheetBehavior?.isDraggable = true
                    if (tab?.position == OUTER_TAB_EXTENSIONS &&
                        selectedExtensionTypeTab == EXTENSION_TYPE_NOVELS
                    ) {
                        presenter.refreshNovelPlugins()
                    }
                    if (!isExpanding) {
                        getFrameLayoutForTab(tab?.position)?.binding?.recycler?.smoothScrollToTop()
                    }
                }
            },
        )
        presenter.onCreate()
        updateExtTitle()

        binding.sheetLayout.setOnClickListener {
            if (!sheetBehavior.isExpanded()) {
                sheetBehavior?.expand()
                fetchOnlineExtensionsIfNeeded()
            } else {
                sheetBehavior?.collapse()
            }
        }
        presenter.getExtensionUpdateCount()
    }

    fun isOnView(view: View): Boolean {
        return activeFrameLayout()?.let { view == it || view.tag == it.tag } == true
    }

    fun updatedNestedRecyclers() {
        val activeFrameLayout = activeFrameLayout()
        listOf(extensionFrameLayout, novelPluginFrameLayout, migrationFrameLayout).forEach { recyclerWithScrollerBinding ->
            recyclerWithScrollerBinding?.binding?.recycler?.isNestedScrollingEnabled =
                recyclerWithScrollerBinding == activeFrameLayout
        }
    }

    /**
     * Materialize the off-screen migration recycler after expansion instead of during Browse
     * entry. Its adapter can receive data while detached, avoiding hidden ViewHolder inflation
     * on the root-navigation frame; once created, the page remains ready for smooth swipes.
     */
    fun prepareMigrationPage() {
        if (!::binding.isInitialized) return
        binding.pager.postOnAnimation {
            tabbedSheetAdapter?.ensureMigrationPage()
        }
    }

    fun fetchOnlineExtensionsIfNeeded() {
        if (shouldCallApi) {
            presenter.findAvailableExtensions()
            shouldCallApi = false
        }
    }

    fun updateExtTitle() {
        val extCount = presenter.getExtensionUpdateCount()
        if (extCount > 0) {
            binding.tabs.getTabAt(0)?.orCreateBadge
        } else {
            binding.tabs.getTabAt(0)?.removeBadge()
        }
    }

    override fun onButtonClick(position: Int) {
        val extension = (extAdapter?.getItem(position) as? ExtensionItem)?.extension ?: return
        when (extension) {
            is Extension.Installed -> {
                if (!extension.hasUpdate) {
                    openDetails(extension)
                } else {
                    presenter.updateExtension(extension)
                }
            }
            is Extension.Available -> {
                presenter.installExtension(extension)
            }
            is Extension.Untrusted -> {
                openTrustDialog(extension)
            }
        }
    }

    override fun onCancelClick(position: Int) {
        val extension = (extAdapter?.getItem(position) as? ExtensionItem) ?: return
        presenter.cancelExtensionInstall(extension)
    }

    override fun onUpdateAllClicked(position: Int) {
        (controller.activity as? MainActivity)?.showNotificationPermissionPrompt()
        if (basePreferences.extensionInstaller().get() != ExtensionInstaller.SHIZUKU &&
            !presenter.preferences.hasPromptedBeforeUpdateAll().get()
        ) {
            controller.activity!!.materialAlertDialog()
                .setTitle(MR.strings.update_all)
                .setMessage(MR.strings.some_extensions_may_prompt)
                .setPositiveButton(AR.string.ok) { _, _ ->
                    presenter.preferences.hasPromptedBeforeUpdateAll().set(true)
                    updateAllExtensions(position)
                }
                .show()
        } else {
            updateAllExtensions(position)
        }
    }

    override fun onExtSortClicked(view: TextView, position: Int) {
        view.popupMenu(
            InstalledExtensionsOrder.entries.map { it.value to it.nameRes },
            presenter.preferences.installedExtensionsOrder().get(),
        ) {
            presenter.preferences.installedExtensionsOrder().set(itemId)
            extAdapter?.installedSortOrder = itemId
            view.setText(InstalledExtensionsOrder.fromValue(itemId).nameRes)
            presenter.refreshExtensions()
        }
    }

    private fun updateAllExtensions(position: Int) {
        val header = (extAdapter?.getSectionHeader(position)) as? ExtensionGroupItem ?: return
        val items = extAdapter?.getSectionItemPositions(header)
        val extensions = items?.mapNotNull {
            val extItem = (extAdapter?.getItem(it) as? ExtensionItem) ?: return
            val extension = (extAdapter?.getItem(it) as? ExtensionItem)?.extension ?: return
            if ((extItem.installStep == null || extItem.installStep == InstallStep.Error) &&
                extension is Extension.Installed && extension.hasUpdate
            ) {
                extension
            } else {
                null
            }
        }.orEmpty()
        presenter.updateExtensions(extensions)
    }

    override fun onItemClick(view: View?, position: Int): Boolean {
        when (binding.tabs.selectedTabPosition) {
            OUTER_TAB_EXTENSIONS -> {
                if (selectedExtensionTypeTab != EXTENSION_TYPE_MANGA) return false
                val extension =
                    (extAdapter?.getItem(position) as? ExtensionItem)?.extension ?: return false
                if (extension is Extension.Installed) {
                    openDetails(extension)
                } else if (extension is Extension.Untrusted) {
                    openTrustDialog(extension)
                }
            }
            OUTER_TAB_MIGRATION -> {
                val item = migAdapter?.getItem(position) ?: return false

                if (item is MangaItem) {
                    PreMigrationController.navigateToMigration(
                        get<PreferencesHelper>().skipPreMigration().get(),
                        controller.router,
                        listOf(item.manga.id!!),
                    )
                } else if (item is SourceItem) {
                    presenter.setSelectedSource(item.source)
                }
            }
        }
        return false
    }

    override fun onItemLongClick(position: Int) {
        when (binding.tabs.selectedTabPosition) {
            OUTER_TAB_EXTENSIONS -> if (selectedExtensionTypeTab == EXTENSION_TYPE_MANGA) {
                val extension = (extAdapter?.getItem(position) as? ExtensionItem)?.extension ?: return
                if (extension is Extension.Installed || extension is Extension.Untrusted) {
                    uninstallExtension(extension.name, extension.pkgName)
                }
            } else {
                val item = (novelPluginAdapter?.getItem(position) as? NovelPluginItem) ?: return
                if (item.isInstalled) {
                    uninstallNovelPlugin(item.plugin.name, item.plugin.id)
                }
            }
        }
    }

    override fun onAllClick(position: Int) {
        val item = migAdapter?.getItem(position) as? SourceItem ?: return

        val sourceMangas =
            presenter.mangaItems[item.source.id]?.mapNotNull { it.manga.id }?.toList()
                ?: emptyList()
        PreMigrationController.navigateToMigration(
            get<PreferencesHelper>().skipPreMigration().get(),
            controller.router,
            sourceMangas,
        )
    }

    private fun openDetails(extension: Extension.Installed) {
        val controller = ExtensionDetailsController(extension.pkgName)
        this.controller.router.pushController(controller.withFadeTransaction())
    }

    private fun openTrustDialog(extension: Extension.Untrusted) {
        val activity = controller.activity ?: return
        activity.materialAlertDialog()
            .setTitle(MR.strings.untrusted_extension)
            .setMessage(MR.strings.untrusted_extension_message)
            .setPositiveButton(MR.strings.trust) { _, _ ->
                trustExtension(extension.pkgName, extension.versionCode, extension.signatureHash)
            }
            .setNegativeButton(MR.strings.uninstall) { _, _ ->
                uninstallExtension(extension.pkgName)
            }.show()
    }

    fun setExtensions(extensions: List<ExtensionItem>, updateController: Boolean = true) {
        this.extensions = extensions
        if (updateController) {
            controller.presenter.updateSources()
        }
        drawExtensions()
    }

    override fun setMigrationSources(sources: List<SourceItem>) {
        currentSourceTitle = null
        val changingAdapters = migAdapter !is SourceAdapter
        if (migAdapter !is SourceAdapter) {
            migAdapter = SourceAdapter(this)
            migrationFrameLayout?.onBind(migAdapter!!)
            migAdapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        val changed = (migAdapter as SourceAdapter).updateDataSetIfChanged(sources, changingAdapters)
        if (changed) controller.updateTitleAndMenu()
    }

    override fun setMigrationManga(title: String, manga: List<MangaItem>?) {
        currentSourceTitle = title
        val changingAdapters = migAdapter !is MangaAdapter
        if (migAdapter !is MangaAdapter) {
            migAdapter = MangaAdapter(this, presenter.uiPreferences.outlineOnCovers().get())
            migrationFrameLayout?.onBind(migAdapter!!)
            migAdapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        val changed = (migAdapter as MangaAdapter).updateDataSetIfChanged(manga, changingAdapters)
        if (changed) controller.updateTitleAndMenu()
    }

    fun drawExtensions() {
        val displayedExtensions = if (controller.extQuery.isNotBlank()) {
            extensions.filter {
                it.extension.name.contains(controller.extQuery, ignoreCase = true)
            }
        } else {
            extensions
        }
        extAdapter?.updateDataSetIfChanged(displayedExtensions)
        updateExtTitle()
        updateExtUpdateAllButton()
        // NOVEL -->
        drawNovelPlugins()
        // NOVEL <--
    }

    fun canStillGoBack(): Boolean {
        return (binding.tabs.selectedTabPosition == OUTER_TAB_MIGRATION && migAdapter is MangaAdapter) ||
            (binding.tabs.selectedTabPosition == OUTER_TAB_EXTENSIONS && binding.sheetToolbar.hasExpandedActionView())
    }

    fun canGoBack(): Boolean {
        return if (binding.tabs.selectedTabPosition == OUTER_TAB_MIGRATION && migAdapter is MangaAdapter) {
            presenter.deselectSource()
            false
        } else if (binding.sheetToolbar.hasExpandedActionView()) {
            binding.sheetToolbar.collapseActionView()
            false
        } else {
            true
        }
    }

    fun downloadUpdate(item: ExtensionItem) {
        extAdapter?.updateItem(item, item.installStep)
        updateExtUpdateAllButton()
    }

    private fun updateExtUpdateAllButton() {
        val updateHeader =
            extAdapter?.headerItems?.find { it is ExtensionGroupItem && it.canUpdate != null } as? ExtensionGroupItem
                ?: return
        val items = extAdapter?.getSectionItemPositions(updateHeader) ?: return
        val canUpdate = items.any {
            val extItem = (extAdapter?.getItem(it) as? ExtensionItem) ?: return
            extItem.installStep == null || extItem.installStep == InstallStep.Error
        }
        if (updateHeader.canUpdate == canUpdate) return
        updateHeader.canUpdate = canUpdate
        extAdapter?.updateItem(updateHeader)
    }

    private fun trustExtension(pkgName: String, versionCode: Long, signatureHash: String) {
        presenter.trustExtension(pkgName, versionCode, signatureHash)
    }

    private fun uninstallExtension(pkgName: String) {
        presenter.uninstallExtension(pkgName)
    }

    private fun uninstallExtension(extName: String, pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            presenter.uninstallExtension(pkgName)
        } else {
            controller.activity!!.materialAlertDialog()
                .setTitle(extName)
                .setPositiveButton(MR.strings.remove) { _, _ ->
                    presenter.uninstallExtension(pkgName)
                }
                .setNegativeButton(AR.string.cancel, null)
                .show()
        }
    }

    private fun uninstallNovelPlugin(pluginName: String, pluginId: String) {
        controller.activity!!.materialAlertDialog()
            .setTitle(pluginName)
            .setPositiveButton(MR.strings.remove) { _, _ ->
                presenter.uninstallNovelPlugin(pluginId)
            }
            .setNegativeButton(AR.string.cancel, null)
            .show()
    }

    fun setCanInstallPrivately(installPrivately: Boolean) {
        val adapter = extAdapter ?: return
        if (adapter.installPrivately == installPrivately) return
        adapter.installPrivately = installPrivately
        drawExtensions()
    }

    fun onDestroy() {
        boundViews.toList().forEach { view ->
            view.binding?.recycler?.adapter = null
        }
        boundViews.clear()
        binding.pager.adapter = null
        tabbedSheetAdapter = null
        sharedExtensionPool.clear()
        extAdapter = null
        migAdapter = null
        novelPluginAdapter = null
        presenter.onDestroy()
    }

    // NOVEL -->
    private fun getFrameLayoutForTab(position: Int?): RecyclerWithScrollerView? {
        return when (position) {
            OUTER_TAB_EXTENSIONS -> activeExtensionFrameLayout()
            OUTER_TAB_MIGRATION -> migrationFrameLayout
            else -> null
        }
    }

    private fun activeFrameLayout(): RecyclerWithScrollerView? {
        return when (binding.pager.currentItem) {
            OUTER_TAB_EXTENSIONS -> activeExtensionFrameLayout()
            OUTER_TAB_MIGRATION -> migrationFrameLayout
            else -> null
        }
    }

    private fun activeExtensionFrameLayout(): RecyclerWithScrollerView? {
        return if (selectedExtensionTypeTab == EXTENSION_TYPE_NOVELS) {
            novelPluginFrameLayout
        } else {
            extensionFrameLayout
        }
    }

    private fun showExtensionTypeTab(position: Int) {
        selectedExtensionTypeTab = position.coerceIn(EXTENSION_TYPE_MANGA, EXTENSION_TYPE_NOVELS)
        val showNovels = selectedExtensionTypeTab == EXTENSION_TYPE_NOVELS
        extensionFrameLayout?.isVisible = !showNovels
        novelPluginFrameLayout?.isVisible = showNovels
        updatedNestedRecyclers()
        if (showNovels) {
            updateNovelPluginsEmptyState(novelPluginAdapter?.mainItemCount == 0)
        } else {
            extensionFrameLayout?.hideEmptyState()
        }
    }

    fun setNovelPlugins(items: List<NovelPluginItem>) {
        novelPlugins = items
        drawNovelPlugins()
    }

    private fun drawNovelPlugins() {
        val displayedPlugins = if (controller.extQuery.isNotBlank()) {
            novelPlugins.filter {
                it.plugin.name.contains(controller.extQuery, ignoreCase = true)
            }
        } else {
            novelPlugins
        }
        novelPluginAdapter?.updateDataSetIfChanged(displayedPlugins)
        updateNovelPluginsEmptyState(displayedPlugins.isEmpty())
    }

    override fun onNovelPluginButtonClick(position: Int) {
        val item = (novelPluginAdapter?.getItem(position) as? NovelPluginItem) ?: return
        if (!item.isInstalled || item.hasUpdate) {
            presenter.installNovelPlugin(item.plugin)
        }
    }

    override fun onNovelUpdateAllClicked(position: Int) {
        val header = novelPluginAdapter?.getSectionHeader(position) as? NovelPluginGroupItem ?: return
        if (header.canUpdate != true) return

        val items = novelPluginAdapter?.getSectionItemPositions(header).orEmpty()
        val pluginsToUpdate = items.mapNotNull { index ->
            val pluginItem = novelPluginAdapter?.getItem(index) as? NovelPluginItem ?: return@mapNotNull null
            if (pluginItem.isInstalled && pluginItem.hasUpdate) pluginItem.plugin else null
        }
        presenter.updateNovelPlugins(pluginsToUpdate)
    }

    override fun onNovelSortClicked(view: TextView, position: Int) {
        view.popupMenu(
            InstalledExtensionsOrder.entries.map { it.value to it.nameRes },
            presenter.preferences.installedExtensionsOrder().get(),
        ) {
            presenter.preferences.installedExtensionsOrder().set(itemId)
            novelPluginAdapter?.installedSortOrder = itemId
            view.setText(InstalledExtensionsOrder.fromValue(itemId).nameRes)
            presenter.refreshNovelPlugins()
        }
    }

    private fun updateNovelPluginsEmptyState(displayedPluginsEmpty: Boolean) {
        val frameLayout = novelPluginFrameLayout ?: return
        if (!displayedPluginsEmpty) {
            frameLayout.hideEmptyState()
            return
        }

        val actions = if (novelPlugins.isEmpty()) {
            listOf(
                EmptyView.Action(MR.strings.repos) {
                    controller.router.pushController(ExtensionRepoController().withFadeTransaction())
                },
            )
        } else {
            emptyList()
        }

        val message = if (novelPlugins.isEmpty()) {
            context.getString(MR.strings.information_empty_novel_repos)
        } else {
            context.getString(MR.strings.no_results_found)
        }

        frameLayout.showEmptyState(
            image = Icons.Filled.ExtensionOff,
            message = message,
            actions = actions,
        )
    }
    // NOVEL <--

    private inner class TabbedSheetAdapter : RecyclerViewPagerAdapter() {

        override fun getCount(): Int {
            return 2
        }

        override fun getPageTitle(position: Int): CharSequence {
            return when (position) {
                OUTER_TAB_EXTENSIONS -> context.getString(MR.strings.extensions)
                else -> context.getString(MR.strings.migration)
            }
        }

        override fun createView(container: ViewGroup): View {
            return FrameLayout(container.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        }

        override fun bindView(view: View, position: Int) {
            val host = view as FrameLayout
            unregisterBoundRecyclers(host)
            host.removeAllViews()
            when (position) {
                OUTER_TAB_EXTENSIONS -> {
                    host.tag = EXTENSION_PAGE_TAG
                    host.addView(createExtensionsPage(host))
                    showExtensionTypeTab(selectedExtensionTypeTab)
                }
                else -> {
                    host.tag = MIGRATION_PAGE_TAG
                    // The legacy ViewPager eagerly binds its adjacent page. Leave Migration as
                    // a cheap host until the sheet expansion animation has completed.
                }
            }
        }

        override fun recycleView(view: View, position: Int) {
            unregisterBoundRecyclers(view)
        }

        override fun getItemPosition(obj: Any): Int {
            return when ((obj as? View)?.tag) {
                EXTENSION_PAGE_TAG -> OUTER_TAB_EXTENSIONS
                MIGRATION_PAGE_TAG -> OUTER_TAB_MIGRATION
                else -> POSITION_NONE
            }
        }

        fun ensureMigrationPage() {
            val host = binding.pager.findViewWithTag<FrameLayout>(MIGRATION_PAGE_TAG) ?: return
            if (host.childCount != 0) return
            val adapter = migAdapter ?: return
            host.addView(createRecyclerPage(host, MIGRATION_RECYCLER_TAG, adapter))
            migrationFrameLayout?.hideEmptyState()
            updatedNestedRecyclers()
        }

        private fun createExtensionsPage(container: ViewGroup): View {
            val pageBinding = ExtensionsTypePageBinding.inflate(
                LayoutInflater.from(container.context),
                container,
                false,
            )
            pageBinding.extensionTypeTabs.addTab(
                pageBinding.extensionTypeTabs.newTab().setText(context.getString(MR.strings.manga)),
                selectedExtensionTypeTab == EXTENSION_TYPE_MANGA,
            )
            pageBinding.extensionTypeTabs.addTab(
                pageBinding.extensionTypeTabs.newTab().setText(context.getString(MR.strings.novels)),
                selectedExtensionTypeTab == EXTENSION_TYPE_NOVELS,
            )
            pageBinding.extensionTypeTabs.addOnTabSelectedListener(
                object : TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(tab: TabLayout.Tab?) {
                        showExtensionTypeTab(tab?.position ?: EXTENSION_TYPE_MANGA)
                    }

                    override fun onTabUnselected(tab: TabLayout.Tab?) = Unit

                    override fun onTabReselected(tab: TabLayout.Tab?) {
                        showExtensionTypeTab(tab?.position ?: EXTENSION_TYPE_MANGA)
                        if (selectedExtensionTypeTab == EXTENSION_TYPE_NOVELS) {
                            presenter.refreshNovelPlugins()
                        }
                        activeExtensionFrameLayout()?.binding?.recycler?.smoothScrollToTop()
                    }
                },
            )
            pageBinding.extensionTypeContent.addView(
                createRecyclerPage(pageBinding.extensionTypeContent, EXTENSION_RECYCLER_TAG, extAdapter!!),
            )
            pageBinding.extensionTypeContent.addView(
                createRecyclerPage(pageBinding.extensionTypeContent, NOVEL_RECYCLER_TAG, novelPluginAdapter!!),
            )
            return pageBinding.root
        }

        private fun createRecyclerPage(
            container: ViewGroup,
            tag: String,
            adapter: FlexibleAdapter<IFlexible<*>>,
        ): RecyclerWithScrollerView {
            val binding = RecyclerWithScrollerBinding.inflate(
                LayoutInflater.from(container.context),
                container,
                false,
            )
            val view: RecyclerWithScrollerView = binding.root
            val height = this@ExtensionBottomSheet.controller.activityBinding?.bottomNav?.height
                ?: view.rootWindowInsetsCompat?.getInsets(systemBars())?.bottom ?: 0
            view.tag = tag
            view.setUp(this@ExtensionBottomSheet, binding, height)
            view.onBind(adapter)
            boundViews.add(view)
            return view
        }

        private fun unregisterBoundRecyclers(view: View) {
            if (view is RecyclerWithScrollerView) {
                boundViews.remove(view)
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    unregisterBoundRecyclers(view.getChildAt(i))
                }
            }
        }
    }
}
