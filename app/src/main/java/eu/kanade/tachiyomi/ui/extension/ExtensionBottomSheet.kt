package eu.kanade.tachiyomi.ui.extension

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionBackend
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionEntry
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionKey
import dev.ahmedmohamed.hayai.extension.managed.ManagedExtensionNotice
import dev.ahmedmohamed.hayai.extension.managed.ManagedHealth
import dev.ahmedmohamed.hayai.extension.managed.ManagedInstallation
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.ExtensionsBottomSheetBinding
import eu.kanade.tachiyomi.databinding.RecyclerWithScrollerBinding
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.InstalledExtensionsOrder
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.ui.extension.details.ExtensionDetailsController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.migration.BaseMigrationInterface
import eu.kanade.tachiyomi.ui.migration.MangaAdapter
import eu.kanade.tachiyomi.ui.migration.MangaItem
import eu.kanade.tachiyomi.ui.migration.SourceAdapter
import eu.kanade.tachiyomi.ui.migration.SourceItem
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.ui.source.BrowseController
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.collapse
import eu.kanade.tachiyomi.util.view.doOnApplyWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.expand
import eu.kanade.tachiyomi.util.view.isExpanded
import eu.kanade.tachiyomi.util.view.popupMenu
import eu.kanade.tachiyomi.util.view.smoothScrollToTop
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionBottomSheet
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs),
        ExtensionAdapter.OnButtonClickListener,
        FlexibleAdapter.OnItemClickListener,
        FlexibleAdapter.OnItemLongClickListener,
        SourceAdapter.OnAllClickListener,
        BaseMigrationInterface {
        var sheetBehavior: BottomSheetBehavior<*>? = null

        var shouldCallApi = false

        /**
         * Adapter containing the list of extensions
         */
        private var extAdapter: ExtensionAdapter? = null
        private var migAdapter: FlexibleAdapter<IFlexible<*>>? = null

        val adapters
            get() = listOf(extAdapter, migAdapter)

        val presenter = ExtensionBottomPresenter()
        var currentSourceTitle: String? = null

        private var extensions = emptyList<ExtensionItem>()
        private var notices = emptyList<ManagedExtensionNotice>()
        var canExpand = false
        private lateinit var binding: ExtensionsBottomSheetBinding

        lateinit var controller: BrowseController
        var boundViews = arrayListOf<RecyclerWithScrollerView>()

        val extensionFrameLayout: RecyclerWithScrollerView?
            get() = binding.pager.findViewWithTag("TabbedRecycler0") as? RecyclerWithScrollerView
        val migrationFrameLayout: RecyclerWithScrollerView?
            get() = binding.pager.findViewWithTag("TabbedRecycler1") as? RecyclerWithScrollerView

        var isExpanding = false

        override fun onFinishInflate() {
            super.onFinishInflate()
            binding = ExtensionsBottomSheetBinding.bind(this)
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
            sheetBehavior = BottomSheetBehavior.from(this)
            // Create recycler and set adapter.

            binding.pager.adapter = TabbedSheetAdapter()
            binding.tabs.setupWithViewPager(binding.pager)
            this.controller = controller
            binding.pager.doOnApplyWindowInsetsCompat { _, insets, _ ->
                val bottomBar = controller.activityBinding?.bottomNav
                val bottomH = bottomBar?.height ?: insets.getInsets(systemBars()).bottom
                extensionFrameLayout?.binding?.recycler?.updatePaddingRelative(bottom = bottomH)
                migrationFrameLayout?.binding?.recycler?.updatePaddingRelative(bottom = bottomH)
            }
            binding.tabs.addOnTabSelectedListener(
                object : TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(tab: TabLayout.Tab?) {
                        isExpanding = !sheetBehavior.isExpanded()
                        if (canExpand) {
                            this@ExtensionBottomSheet.sheetBehavior?.expand()
                        }
                        this@ExtensionBottomSheet.controller.updateTitleAndMenu()
                        when (tab?.position) {
                            0 -> extensionFrameLayout
                            else -> migrationFrameLayout
                        }?.binding?.recycler?.isNestedScrollingEnabled = true
                        when (tab?.position) {
                            0 -> extensionFrameLayout
                            else -> migrationFrameLayout
                        }?.binding?.recycler?.requestLayout()
                        sheetBehavior?.isDraggable = true
                        updateExtUpdateAllButton()
                    }

                    override fun onTabUnselected(tab: TabLayout.Tab?) {
                        when (tab?.position) {
                            0 -> extensionFrameLayout
                            else -> migrationFrameLayout
                        }?.binding?.recycler?.isNestedScrollingEnabled = false
                        if (tab?.position == 1) {
                            presenter.deselectSource()
                        }
                    }

                    override fun onTabReselected(tab: TabLayout.Tab?) {
                        isExpanding = !sheetBehavior.isExpanded()
                        this@ExtensionBottomSheet.sheetBehavior?.expand()
                        when (tab?.position) {
                            0 -> extensionFrameLayout
                            else -> migrationFrameLayout
                        }?.binding?.recycler?.isNestedScrollingEnabled = true
                        sheetBehavior?.isDraggable = true
                        if (!isExpanding) {
                            when (tab?.position) {
                                0 -> extensionFrameLayout
                                else -> migrationFrameLayout
                            }?.binding?.recycler?.smoothScrollToTop()
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

        fun isOnView(view: View): Boolean = "TabbedRecycler${binding.pager.currentItem}" == view.tag

        fun updatedNestedRecyclers() {
            listOf(extensionFrameLayout, migrationFrameLayout).forEachIndexed { index, recyclerWithScrollerBinding ->
                recyclerWithScrollerBinding?.binding?.recycler?.isNestedScrollingEnabled = binding.pager.currentItem == index
            }
        }

        fun fetchOnlineExtensionsIfNeeded() {
            if (shouldCallApi) {
                presenter.findAvailableExtensions()
                shouldCallApi = false
            }
        }

        fun updateAllPendingExtensions() {
            presenter.updateAllPendingExtensions()
        }

        fun updateExtTitle() {
            val extCount = extensions.count { it.extension.hasUpdate }
            if (extCount > 0) binding.tabs.getTabAt(0)?.orCreateBadge?.number = extCount else binding.tabs.getTabAt(0)?.removeBadge()
        }

        override fun onButtonClick(position: Int) {
            val extension = (selectedExtensionAdapter()?.getItem(position) as? ExtensionItem)?.extension ?: return
            when {
                extension.installation == ManagedInstallation.Untrusted -> openTrustDialog(extension)
                extension.health is ManagedHealth.LoadFailed && extension.availableVersion != null -> presenter.install(extension)
                extension.hasUpdate -> presenter.update(extension)
                extension.installation == ManagedInstallation.Available -> confirmInstall(extension)
                else -> openDetails(extension)
            }
        }

        override fun onWebViewClick(position: Int) {
            val extension = (selectedExtensionAdapter()?.getItem(position) as? ExtensionItem)?.extension ?: return
            val website = extension.websiteUrl ?: return
            if (extension.backend == ManagedExtensionBackend.JavaScript) {
                context.openInBrowser(website)
                return
            }
            val pkgName = (extension.key as? ManagedExtensionKey.Apk)?.packageName ?: return
            val source = presenter.availableExtension(pkgName)
                ?.sources
                ?.firstOrNull { it.baseUrl.isNotBlank() } ?: return
            val activity = controller.activity ?: return
            activity.startActivity(
                WebViewActivity.newIntent(activity, source.baseUrl, source.id, source.name),
            )
        }

        override fun onCancelClick(position: Int) {
            val extension = (selectedExtensionAdapter()?.getItem(position) as? ExtensionItem) ?: return
            presenter.cancelExtensionInstall(extension)
        }

        override fun onUpdateAllClicked(position: Int) {
            (controller.activity as? MainActivity)?.showNotificationPermissionPrompt()
            if (presenter.preferences.extensionInstaller().get() != ExtensionInstaller.SHIZUKU &&
                !presenter.preferences.hasPromptedBeforeUpdateAll().get()
            ) {
                controller.activity!!
                    .materialAlertDialog()
                    .setTitle(R.string.update_all)
                    .setMessage(R.string.some_extensions_may_prompt)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        presenter.preferences.hasPromptedBeforeUpdateAll().set(true)
                        updateAllExtensions(position)
                    }.show()
            } else {
                updateAllExtensions(position)
            }
        }

        override fun onExtSortClicked(
            view: TextView,
            position: Int,
        ) {
            view.popupMenu(
                InstalledExtensionsOrder.entries.map { it.value to it.nameRes },
                presenter.preferences.installedExtensionsOrder().get(),
            ) {
                presenter.preferences.installedExtensionsOrder().set(itemId)
                selectedExtensionAdapter()?.installedSortOrder = itemId
                view.setText(InstalledExtensionsOrder.fromValue(itemId).nameRes)
                presenter.refreshExtensions()
            }
        }

        private fun updateAllExtensions(position: Int) {
            presenter.updateAllPendingExtensions()
        }

        override fun onItemClick(
            view: View?,
            position: Int,
        ): Boolean {
            when (binding.tabs.selectedTabPosition) {
                0 -> {
                    val extension =
                        (selectedExtensionAdapter()?.getItem(position) as? ExtensionItem)?.extension ?: return false
                    if (extension.installation == ManagedInstallation.Untrusted) {
                        openTrustDialog(extension)
                    } else if (extension.installation == ManagedInstallation.Installed) {
                        openDetails(extension)
                    }
                }
                else -> {
                    val item = migAdapter?.getItem(position) ?: return false

                    if (item is MangaItem) {
                        PreMigrationController.navigateToMigration(
                            Injekt.get<PreferencesHelper>().skipPreMigration().get(),
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
            if (binding.tabs.selectedTabPosition == 0) {
                val extension = (selectedExtensionAdapter()?.getItem(position) as? ExtensionItem)?.extension ?: return
                if (extension.installation != ManagedInstallation.Available) {
                    confirmUninstall(extension)
                }
            }
        }

        override fun onAllClick(position: Int) {
            val item = migAdapter?.getItem(position) as? SourceItem ?: return

            val sourceMangas =
                presenter.mangaItems[item.source.id]?.mapNotNull { it.manga.id }?.toList()
                    ?: emptyList()
            PreMigrationController.navigateToMigration(
                Injekt.get<PreferencesHelper>().skipPreMigration().get(),
                controller.router,
                sourceMangas,
            )
        }

        private fun openDetails(extension: ManagedExtensionEntry) {
            val apk = extension.key as? ManagedExtensionKey.Apk
            if (apk != null && extension.health !is ManagedHealth.LoadFailed) {
                val controller = ExtensionDetailsController(apk.packageName)
                this.controller.router.pushController(controller.withFadeTransaction())
                return
            }
            controller.activity?.materialAlertDialog()
                ?.setTitle(extension.name)
                ?.setMessage(
                    buildString {
                        append(extension.backend.name)
                        extension.installedVersion?.let { append("\n").append(context.getString(R.string.version)).append(" ").append(it) }
                        (extension.health as? ManagedHealth.LoadFailed)?.let { append("\n").append(it.diagnostic) }
                    },
                )
                ?.setPositiveButton(android.R.string.ok, null)
                ?.setNegativeButton(R.string.uninstall) { _, _ -> confirmUninstall(extension) }
                ?.show()
        }

        private fun openTrustDialog(extension: ManagedExtensionEntry) {
            val activity = controller.activity ?: return
            val pkgName = (extension.key as? ManagedExtensionKey.Apk)?.packageName ?: return
            val untrusted = presenter.untrustedExtension(pkgName) ?: return
            activity
                .materialAlertDialog()
                .setTitle(R.string.untrusted_extension)
                .setMessage(R.string.untrusted_extension_message)
                .setPositiveButton(R.string.trust) { _, _ ->
                    trustExtension(untrusted.pkgName, untrusted.versionCode, untrusted.signatureHash)
                }.setNegativeButton(R.string.uninstall) { _, _ ->
                    presenter.uninstall(extension)
                }.show()
        }

        private fun confirmInstall(extension: ManagedExtensionEntry) {
            if (extension.backend == ManagedExtensionBackend.Apk) {
                presenter.install(extension)
                return
            }
            controller.activity?.materialAlertDialog()
                ?.setTitle(extension.name)
                ?.setMessage(R.string.hayai_novel_plugin_install_warning)
                ?.setNegativeButton(android.R.string.cancel, null)
                ?.setPositiveButton(R.string.install) { _, _ -> presenter.install(extension) }
                ?.show()
        }

        private fun confirmUninstall(extension: ManagedExtensionEntry) {
            controller.activity?.materialAlertDialog()
                ?.setTitle(extension.name)
                ?.setMessage(context.getString(R.string.hayai_extension_uninstall_confirmation, extension.name))
                ?.setNegativeButton(android.R.string.cancel, null)
                ?.setPositiveButton(R.string.uninstall) { _, _ -> presenter.uninstall(extension) }
                ?.show()
        }

        fun setExtensions(
            extensions: List<ExtensionItem>,
            notices: List<ManagedExtensionNotice>,
            updateController: Boolean = true,
        ) {
            val newNotice = notices.firstOrNull { notice ->
                this.notices.none { it.identity == notice.identity && it.diagnostic == notice.diagnostic }
            }
            this.extensions = extensions
            this.notices = notices
            newNotice?.let { context.toast(context.getString(R.string.hayai_extension_catalog_problem, it.diagnostic)) }
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
            migAdapter?.updateDataSet(sources, changingAdapters)
            controller.updateTitleAndMenu()
        }

        override fun setMigrationManga(
            title: String,
            manga: List<MangaItem>?,
        ) {
            currentSourceTitle = title
            val changingAdapters = migAdapter !is MangaAdapter
            if (migAdapter !is MangaAdapter) {
                migAdapter = MangaAdapter(this, presenter.preferences.outlineOnCovers().get())
                migrationFrameLayout?.onBind(migAdapter!!)
                migAdapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            migAdapter?.updateDataSet(manga, changingAdapters)
            controller.updateTitleAndMenu()
        }

        fun drawExtensions() {
            extAdapter?.updateDataSet(
                if (controller.extQuery.isBlank()) extensions else extensions.filter { it.extension.name.contains(controller.extQuery, ignoreCase = true) },
            )
            updateExtTitle()
            updateExtUpdateAllButton()
        }

        fun canStillGoBack(): Boolean =
            (binding.tabs.selectedTabPosition == 1 && migAdapter is MangaAdapter) ||
                (binding.tabs.selectedTabPosition == 0 && binding.sheetToolbar.hasExpandedActionView())

        fun canGoBack(): Boolean =
            if (binding.tabs.selectedTabPosition == 1 && migAdapter is MangaAdapter) {
                presenter.deselectSource()
                false
            } else if (binding.sheetToolbar.hasExpandedActionView()) {
                binding.sheetToolbar.collapseActionView()
                false
            } else {
                true
            }

        fun downloadUpdate(item: ExtensionItem) {
            extAdapter?.updateItem(item, item.installStep)
            updateExtUpdateAllButton()
        }

        private fun updateExtUpdateAllButton() {
            val adapter = selectedExtensionAdapter() ?: return
            val updateHeader =
                adapter.headerItems.find { it is ExtensionGroupItem && it.canUpdate != null } as? ExtensionGroupItem
                    ?: return
            val items = adapter.getSectionItemPositions(updateHeader) ?: return
            updateHeader.canUpdate =
                items.any {
                    val extItem = (adapter.getItem(it) as? ExtensionItem) ?: return
                    extItem.installStep == null || extItem.installStep == InstallStep.Error
                }
            adapter.updateItem(updateHeader)
        }

        private fun trustExtension(
            pkgName: String,
            versionCode: Long,
            signatureHash: String,
        ) {
            presenter.trustExtension(pkgName, versionCode, signatureHash)
        }

        fun setCanInstallPrivately(installPrivately: Boolean) {
            extAdapter?.installPrivately = installPrivately
        }

        fun onDestroy() {
            presenter.onDestroy()
        }

        private inner class TabbedSheetAdapter : RecyclerViewPagerAdapter() {
            override fun getCount(): Int = 2

            override fun getPageTitle(position: Int): CharSequence =
                context.getString(
                    when (position) {
                        0 -> R.string.extensions
                        else -> R.string.migration
                    },
                )

            /**
             * Creates a new view for this adapter.
             *
             * @return a new view.
             */
            override fun createView(container: ViewGroup): View {
                val binding =
                    RecyclerWithScrollerBinding.inflate(
                        LayoutInflater.from(container.context),
                        container,
                        false,
                    )
                val view: RecyclerWithScrollerView = binding.root
                val height =
                    this@ExtensionBottomSheet
                        .controller.activityBinding
                        ?.bottomNav
                        ?.height
                        ?: view.rootWindowInsetsCompat?.getInsets(systemBars())?.bottom ?: 0
                view.setUp(this@ExtensionBottomSheet, binding, height)

                return view
            }

            /**
             * Binds a view with a position.
             *
             * @param view the view to bind.
             * @param position the position in the adapter.
             */
            override fun bindView(
                view: View,
                position: Int,
            ) {
                (view as RecyclerWithScrollerView).onBind(adapters[position]!!)
                view.setTag("TabbedRecycler$position")
                boundViews.add(view)
            }

            /**
             * Recycles a view.
             *
             * @param view the view to recycle.
             * @param position the position in the adapter.
             */
            override fun recycleView(
                view: View,
                position: Int,
            ) {
                // (view as RecyclerWithScrollerView).onRecycle()
                boundViews.remove(view)
            }

            /**
             * Returns the position of the view.
             */
            override fun getItemPosition(obj: Any): Int {
                val view = (obj as? RecyclerWithScrollerView) ?: return POSITION_NONE
                val index = adapters.indexOfFirst { it == view.binding?.recycler?.adapter }
                return if (index == -1) POSITION_NONE else index
            }
        }

        private fun selectedExtensionAdapter(): ExtensionAdapter? = extAdapter.takeIf { binding.tabs.selectedTabPosition == 0 }
    }
