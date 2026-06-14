package eu.kanade.tachiyomi.ui.source

import android.app.Activity
import android.os.Build
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import androidx.activity.BackEventCompat
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnNextLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.RecyclerView
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.snackbar.Snackbar
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferenceValues
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.BrowseControllerBinding
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.base.controller.BaseLegacyController
import eu.kanade.tachiyomi.ui.extension.ExtensionFilterController
import eu.kanade.tachiyomi.ui.main.BottomSheetController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import eu.kanade.tachiyomi.ui.base.TabItem
import eu.kanade.tachiyomi.ui.base.TabMode
import eu.kanade.tachiyomi.ui.setting.controllers.SettingsBrowseController
import eu.kanade.tachiyomi.ui.setting.controllers.SettingsSourcesController
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getBottomGestureInsets
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.system.spToPx
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.appBar
import eu.kanade.tachiyomi.util.view.checkHeightThen
import eu.kanade.tachiyomi.util.view.collapse
import eu.kanade.tachiyomi.util.view.expand
import eu.kanade.tachiyomi.util.view.isCollapsed
import eu.kanade.tachiyomi.util.view.installLocalMenu
import eu.kanade.tachiyomi.util.view.isCompose
import eu.kanade.tachiyomi.util.view.isControllerVisible
import eu.kanade.tachiyomi.util.view.onAnimationsFinished
import eu.kanade.tachiyomi.util.view.scrollViewWith
import eu.kanade.tachiyomi.util.view.searchToolbar
import eu.kanade.tachiyomi.util.view.setAction
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.smoothScrollToTop
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.toolbarHeight
import eu.kanade.tachiyomi.util.view.updateGradiantBGRadius
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.widget.LinearLayoutManagerAccurateOffset
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.parcelize.Parcelize
import yokai.util.koin.injectLazy
import yokai.domain.base.BasePreferences
import yokai.domain.base.BasePreferences.ExtensionInstaller
import yokai.i18n.MR
import yokai.presentation.extension.repo.ExtensionRepoController
import yokai.util.lang.getString
import java.util.*
import kotlin.math.max

/**
 * This controller shows and manages the different catalogues enabled by the user.
 * This controller should only handle UI actions, IO actions should be done by [SourcePresenter]
 * [SourceAdapter.SourceListener] call function data on browse item click.
 */
class BrowseController :
    BaseLegacyController<BrowseControllerBinding>(),
    FlexibleAdapter.OnItemClickListener,
    SourceAdapter.SourceListener,
    RootSearchInterface,
    FloatingSearchInterface,
    eu.kanade.tachiyomi.ui.main.RootTabContent,
    eu.kanade.tachiyomi.ui.main.TabbedInterface,
    eu.kanade.tachiyomi.ui.base.LocalAppBarOwner,
    BottomSheetController {

    override val hostsOwnAppBar: Boolean = true

    override fun localAppBar(): eu.kanade.tachiyomi.ui.base.ExpandedAppBarLayout? =
        if (isBindingInitialized) binding.appBar else null

    override fun onSetupLocalChrome() {
        val appBar = binding.appBar
        // When Browse's extension sheet is expanded, the sheet owns the top of the
        // screen — hide the appBar so it doesn't render over the sheet content.
        // Sheet expand/collapse mutates these properties directly via updateTitleAndMenu
        // and setBottomSheetTabs (the per-frame drag fade).
        appBar.alpha = 1f
        appBar.isInvisible = showingExtensions
        appBar.lockYPos = false
        appBar.hideBigView(useSmall = false)
        appBar.setToolbarModeBy(this)
        appBar.applyTabs(
            items = BrowseSourceType.entries.map {
                TabItem.Label(activity?.getString(it.stringRes).orEmpty())
            },
            selectedIndex = presenter.currentType.ordinal,
            mode = TabMode.Fixed,
            onSelected = { idx -> presenter.setCurrentType(BrowseSourceType.fromOrdinal(idx)) },
            onReselected = { binding.sourceRecycler.smoothScrollToTop() },
            pagerSync = null,
        )
        appBar.y = 0f
        appBar.updateAppBarAfterY(binding.sourceRecycler)
        setupToolbarMenu()
    }

    private fun setupToolbarMenu() {
        installLocalMenu(R.menu.catalogue_main)

        val pill = searchToolbar() ?: return
        pill.searchQueryHint = view?.context?.getString(MR.strings.global_search)
        setOnQueryTextChangeListener(pill.searchView, true) {
            if (!it.isNullOrBlank()) performGlobalSearch(it)
            true
        }
    }

    override val showActivityTabs: Boolean = true

    private val basePreferences: BasePreferences by injectLazy()

    /**
     * Application preferences.
     */
    private val preferences: PreferencesHelper by injectLazy()

    /**
     * Adapter containing sources.
     */
    private var adapter: SourceAdapter? = null

    var extQuery = ""
        private set

    var headerHeight = 0

    var showingExtensions = false

    var snackbar: Snackbar? = null

    private var ogRadius = 0f
    private var deviceRadius = 0f to 0f
    private var lastScale = 1f

    override val mainRecycler: RecyclerView
        get() = binding.sourceRecycler

    override fun getTitle(): String? = view?.context?.getString(MR.strings.browse)

    override fun getSearchTitle(): String? {
        return searchTitle(view?.context?.getString(MR.strings.sources)?.lowercase(Locale.ROOT))
    }

    val presenter = SourcePresenter(this)

    override fun createBinding(inflater: LayoutInflater) = BrowseControllerBinding.inflate(inflater)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        val isReturning = adapter != null
        adapter = SourceAdapter(this)
        // Create binding.sourceRecycler and set adapter.
        binding.sourceRecycler.layoutManager = LinearLayoutManagerAccurateOffset(view.context)
        binding.sourceRecycler.setHasFixedSize(true)
        binding.sourceRecycler.setItemViewCacheSize(8)
        binding.sourceRecycler.itemAnimator = null
        // Use a process-static pool so holders survive Browse controller destruction;
        // without it, every root-nav re-entry re-inflates the visible deficit (~12
        // source_item rows × ~10ms = 120ms/frame). Default per-type cap of 5 was also
        // too low for the typical visible row count.
        binding.sourceRecycler.setRecycledViewPool(persistentSourcePool)
        persistentSourcePool.setMaxRecycledViews(R.layout.source_item, 30)
        persistentSourcePool.setMaxRecycledViews(R.layout.source_header_item, 8)

        binding.sourceRecycler.adapter = adapter
        binding.sourceRecycler.onAnimationsFinished {
            (activity as? MainActivity)?.releaseSplash()
        }
        adapter?.isSwipeEnabled = true
        adapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        scrollViewWith(
            binding.sourceRecycler,
            afterInsets = {
                headerHeight = binding.sourceRecycler.paddingTop
                binding.sourceRecycler.updatePaddingRelative(
                    bottom = (activityBinding?.bottomNav?.height ?: it.getBottomGestureInsets()) + 58.spToPx,
                )
                if (activityBinding?.bottomNav == null) {
                    setBottomPadding()
                }
                deviceRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val wInsets = it.toWindowInsets()
                    val lCorner = wInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                    val rCorner = wInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                    (lCorner?.radius?.toFloat() ?: 0f) to (rCorner?.radius?.toFloat() ?: 0f)
                } else {
                    ogRadius to ogRadius
                }
            },
            onBottomNavUpdate = {
                setBottomPadding()
            },
        )
        if (!isReturning) {
            appBar()?.lockYPos = true
        }
        // Grab the BottomSheetBehavior reference now even though full sheet wiring is
        // deferred — setBottomSheetTabs reads it for the initial pill alpha. The
        // behavior itself is created by CoordinatorLayout from XML
        // (app:layout_behavior in extensions_bottom_sheet.xml); we're just holding a ref.
        binding.bottomSheet.root.sheetBehavior =
            BottomSheetBehavior.from(binding.bottomSheet.root)
        binding.sourceRecycler.post {
            setBottomSheetTabs(if (binding.bottomSheet.root.sheetBehavior.isCollapsed()) 0f else 1f)
            binding.sourceRecycler.updatePaddingRelative(
                bottom = (activityBinding?.bottomNav?.height ?: 0) + 58.spToPx,
            )
            updateTitleAndMenu()
        }
        ogRadius = view.resources.getDimension(R.dimen.rounded_radius)

        // Source loading kicks off background coroutines on Dispatchers.Default, so
        // it stays on the cold-entry frame — the recycler is empty until the load
        // completes, no point deferring it.
        presenter.onCreate()
        if (presenter.sourceItems.isNotEmpty()) {
            setSources(presenter.sourceItems, presenter.lastUsedItem)
        } else {
            binding.sourceRecycler.checkHeightThen {
                binding.sourceRecycler.scrollToPosition(0)
            }
        }

        // Wire the bottom sheet up front so it's ready instantly. We previously deferred this
        // 280ms past cross-fade settle to avoid cold-entry jank, but the visible "sheet loading
        // in" delay was a worse user experience than the brief jank.
        initBottomSheet()
    }

    private var bottomSheetReady = false

    /**
     * Heavy bottom-sheet wiring deferred off the Browse cold-entry frame.
     *
     * Costs absorbed here (~80–150ms total on Samsung A35 cold):
     * - `ExtensionBottomSheet.onCreate(this)` — instantiates the three adapters and
     *   assigns `pager.adapter = TabbedSheetAdapter()`, which immediately inflates
     *   ViewPager pages 0 + 1 (offscreen-page-limit defaults to 1).
     * - `addBottomSheetCallback(...)` — slide callback allocates closures over
     *   `binding`; cheap on its own but stacks with the above on first frame.
     * - `setSheetToolbar()` — `inflateMenu(R.menu.extension_main)` parses + builds
     *   a Menu (parse already cached by [BrowseWarmup], but Menu construction
     *   still costs).
     *
     * Idempotent — guarded by [bottomSheetReady], reset in [onDestroyView] so a
     * pop-back recreation re-runs it.
     */
    private fun initBottomSheet() {
        if (bottomSheetReady) return
        if (!isBindingInitialized || view == null) return
        bottomSheetReady = true

        android.os.Trace.beginSection("Hayai/Browse.initBottomSheet")
        try {
            binding.bottomSheet.root.onCreate(this)

            basePreferences.extensionInstaller().changes()
                .drop(1)
                .onEach {
                    binding.bottomSheet.root.setCanInstallPrivately(it == ExtensionInstaller.PRIVATE)
                }
                .launchIn(viewScope)

            binding.bottomSheet.root.sheetBehavior?.isGestureInsetBottomIgnored = true

            binding.bottomSheet.root.sheetBehavior?.addBottomSheetCallback(
                object : BottomSheetBehavior
                .BottomSheetCallback() {
                    override fun onSlide(bottomSheet: View, progress: Float) {
                        val oldShow = showingExtensions
                        showingExtensions = progress > 0.92f
                        if (oldShow != showingExtensions) {
                            updateTitleAndMenu()
                            (activity as? MainActivity)?.reEnableBackPressedCallBack()
                        }
                        binding.bottomSheet.root.apply {
                            if (lastScale != 1f && scaleY != 1f) {
                                val scaleProgress = ((1f - progress) * (1f - lastScale)) + lastScale
                                scaleX = scaleProgress
                                scaleY = scaleProgress
                                for (i in 0 until childCount) {
                                    val childView = getChildAt(i)
                                    childView.scaleY = scaleProgress
                                }
                            }
                        }
                        binding.bottomSheet.sheetToolbar.isVisible = true
                        setBottomSheetTabs(max(0f, progress))
                    }

                    override fun onStateChanged(p0: View, state: Int) {
                        if (state == BottomSheetBehavior.STATE_SETTLING) {
                            binding.bottomSheet.root.updatedNestedRecyclers()
                        } else if (state == BottomSheetBehavior.STATE_EXPANDED && binding.bottomSheet.root.isExpanding) {
                            binding.bottomSheet.root.updatedNestedRecyclers()
                            binding.bottomSheet.root.isExpanding = false
                        }

                        binding.bottomSheet.root.apply {
                            if ((
                                state == BottomSheetBehavior.STATE_COLLAPSED ||
                                    state == BottomSheetBehavior.STATE_EXPANDED ||
                                    state == BottomSheetBehavior.STATE_HIDDEN
                                ) &&
                                scaleY != 1f
                            ) {
                                scaleX = 1f
                                scaleY = 1f
                                pivotY = 0f
                                translationX = 0f
                                for (i in 0 until childCount) {
                                    val childView = getChildAt(i)
                                    childView.scaleY = 1f
                                }
                                lastScale = 1f
                            }
                        }

                        val extBottomSheet = binding.bottomSheet.root
                        if (state == BottomSheetBehavior.STATE_EXPANDED ||
                            state == BottomSheetBehavior.STATE_COLLAPSED
                        ) {
                            binding.bottomSheet.root.sheetBehavior?.isDraggable = true
                            showingExtensions = state == BottomSheetBehavior.STATE_EXPANDED
                            binding.bottomSheet.sheetToolbar.isVisible = showingExtensions
                            updateTitleAndMenu()
                            if (state == BottomSheetBehavior.STATE_EXPANDED) {
                                extBottomSheet.fetchOnlineExtensionsIfNeeded()
                            } else {
                                extBottomSheet.shouldCallApi = true
                            }
                        }

                        retainViewMode = if (state == BottomSheetBehavior.STATE_EXPANDED) {
                            RetainViewMode.RETAIN_DETACH
                        } else {
                            RetainViewMode.RELEASE_DETACH
                        }
                        binding.bottomSheet.sheetLayout.isClickable = state == BottomSheetBehavior.STATE_COLLAPSED
                        binding.bottomSheet.sheetLayout.isFocusable = state == BottomSheetBehavior.STATE_COLLAPSED
                        if (state == BottomSheetBehavior.STATE_COLLAPSED || state == BottomSheetBehavior.STATE_EXPANDED) {
                            setBottomSheetTabs(if (state == BottomSheetBehavior.STATE_COLLAPSED) 0f else 1f)
                        }
                    }
                },
            )

            if (showingExtensions) {
                binding.bottomSheet.root.sheetBehavior?.expand()
            }

            setSheetToolbar()
            // The pre-defer state of canExpand stays at its default false; flip on now
            // that the sheet is wired so tab-activated / change-started honors it.
            binding.bottomSheet.root.canExpand = isControllerVisible
        } finally {
            android.os.Trace.endSection()
        }
    }

    private fun updateSheetMenu() {
        val tabPosition = binding.bottomSheet.tabs.selectedTabPosition
        val onMigrationTab = tabPosition == 2
        binding.bottomSheet.sheetToolbar.title =
            if (onMigrationTab) {
                binding.bottomSheet.root.currentSourceTitle
                    ?: view?.context?.getString(MR.strings.source_migration)
            } else if (tabPosition == 1) {
                view?.context?.getString(MR.strings.novels)
            } else {
                view?.context?.getString(MR.strings.extensions)
            }
        val onExtensionOrNovelTab = tabPosition == 0 || tabPosition == 1
        if (binding.bottomSheet.sheetToolbar.menu.findItem(if (onExtensionOrNovelTab) R.id.action_search else R.id.action_migration_guide) != null) {
            return
        }
        val oldSearchView = binding.bottomSheet.sheetToolbar.menu.findItem(R.id.action_search)?.actionView as? SearchView
        oldSearchView?.setOnQueryTextListener(null)
        binding.bottomSheet.sheetToolbar.menu.clear()
        binding.bottomSheet.sheetToolbar.inflateMenu(
            if (onExtensionOrNovelTab) {
                R.menu.extension_main
            } else {
                R.menu.migration_main
            },
        )

        val id = when (PreferenceValues.MigrationSourceOrder.fromPreference(preferences)) {
            PreferenceValues.MigrationSourceOrder.Alphabetically -> R.id.action_sort_alpha
            PreferenceValues.MigrationSourceOrder.MostEntries -> R.id.action_sort_largest
            PreferenceValues.MigrationSourceOrder.Obsolete -> R.id.action_sort_obsolete
        }
        binding.bottomSheet.sheetToolbar.menu.findItem(id)?.isChecked = true

        // Initialize search option.
        binding.bottomSheet.sheetToolbar.menu.findItem(R.id.action_search)?.let { searchItem ->
            val searchView = searchItem.actionView as SearchView

            // Change hint to show global search.
            searchView.queryHint = view?.context?.getString(MR.strings.search_extensions)
            if (extQuery.isNotEmpty()) {
                searchView.setOnQueryTextListener(null)
                searchItem.expandActionView()
                searchView.setQuery(extQuery, true)
                searchView.clearFocus()
            } else {
                searchItem.collapseActionView()
            }
            // Create query listener which opens the global search view.
            setOnQueryTextChangeListener(searchView) {
                extQuery = it ?: ""
                binding.bottomSheet.root.drawExtensions()
                true
            }
        }
        // Defensive re-attach: any menu rebuild (extension_main <-> migration_main) goes
        // through the toolbar's MenuItem expand/collapse machinery, which has been
        // observed to leave the leading nav button's click listener inactive until a
        // forced re-layout. Re-attaching here keeps the X functional across tab swaps.
        binding.bottomSheet.sheetToolbar.setNavigationOnClickListener {
            binding.bottomSheet.root.sheetBehavior?.collapse()
        }
    }

    private fun setSheetToolbar() {
        binding.bottomSheet.sheetToolbar.setOnMenuItemClickListener { item ->
            val sorting = when (item.itemId) {
                R.id.action_sort_alpha -> PreferenceValues.MigrationSourceOrder.Alphabetically
                R.id.action_sort_largest -> PreferenceValues.MigrationSourceOrder.MostEntries
                R.id.action_sort_obsolete -> PreferenceValues.MigrationSourceOrder.Obsolete
                else -> null
            }
            if (sorting != null) {
                preferences.migrationSourceOrder().set(sorting.value)
                binding.bottomSheet.root.presenter.refreshMigrations()
                item.isChecked = true
                return@setOnMenuItemClickListener true
            }
            when (item.itemId) {
                // Initialize option to open catalogue settings.
                R.id.action_filter -> {
                    router.pushController(ExtensionFilterController().withFadeTransaction())
                }
                R.id.action_migration_guide -> {
                    activity?.openInBrowser(HELP_URL)
                }
                R.id.action_sources_settings -> {
                    router.pushController(SettingsBrowseController().withFadeTransaction())
                }
                R.id.action_extension_repos_settings -> {
                    router.pushController(ExtensionRepoController().withFadeTransaction())
                }
            }
            return@setOnMenuItemClickListener true
        }
        // Inflate the menu BEFORE attaching the navigation click listener. The Extensions
        // tab's `action_search` MenuItem inflates a MiniSearchView action view whose
        // attachment + `collapseActionView()` on first inflate can leave the toolbar's
        // leading button in a state where a listener attached earlier doesn't dispatch
        // until something else triggers a re-layout (e.g. a tab tap). Attaching the nav
        // listener after the menu is set up avoids that initial dead-tap on tab 0.
        updateSheetMenu()
        binding.bottomSheet.sheetToolbar.setNavigationOnClickListener {
            binding.bottomSheet.root.sheetBehavior?.collapse()
        }
    }

    fun updateTitleAndMenu() {
        if (isControllerVisible) {
            val activity = (activity as? MainActivity) ?: return
            // Sheet expand/collapse changes only appBar visibility — apply directly on
            // the local appBar so the tab strip / scroll source aren't torn down.
            appBar()?.let {
                it.alpha = 1f
                it.isInvisible = showingExtensions
            }
            activity.setStatusBarColorTransparent(showingExtensions)
            // Skip the sheet menu rebuild before the deferred init wires the ViewPager
            // tabs — pre-init `tabs.selectedTabPosition` returns -1 (no selection) and
            // updateSheetMenu would inflate the wrong menu (migration_main instead of
            // extension_main). initBottomSheet's setSheetToolbar call covers the first
            // correct inflate; subsequent calls (sheet slide / tab swap) re-run it.
            if (bottomSheetReady) updateSheetMenu()
        }
    }

    fun setBottomSheetTabs(progress: Float) {
        val bottomSheet = binding.bottomSheet.root
        val halfStepProgress = (max(0.5f, progress) - 0.5f) * 2
        binding.bottomSheet.tabs.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = (
                (
                    appBar()?.paddingTop
                        ?.minus(9f.dpToPx)
                        ?.plus(toolbarHeight ?: 0) ?: 0f
                    ) * halfStepProgress
                ).toInt()
        }
        binding.bottomSheet.pill.alpha = (1 - progress) * 0.25f
        binding.bottomSheet.sheetToolbar.alpha = progress
        if (isControllerVisible) {
            // Sheet-drag fade: AppBar dims as the sheet expands. Mutate the local
            // appBar directly — when the user lifts their finger, updateTitleAndMenu
            // overwrites with the final on/off state.
            appBar()?.let {
                it.alpha = (1 - progress * 3) + 0.5f
                it.isInvisible = false
            }
        }

        binding.bottomSheet.root.updateGradiantBGRadius(
            ogRadius,
            deviceRadius,
            progress,
            binding.bottomSheet.sheetLayout,
        )

        val selectedColor = ColorUtils.setAlphaComponent(
            bottomSheet.context.getResourceColor(R.attr.tabBarIconColor),
            (progress * 255).toInt(),
        )
        val unselectedColor = ColorUtils.setAlphaComponent(
            bottomSheet.context.getResourceColor(R.attr.actionBarTintColor),
            153,
        )
        binding.bottomSheet.pager.alpha = progress * 10
        binding.bottomSheet.tabs.setSelectedTabIndicatorColor(selectedColor)
        binding.bottomSheet.tabs.setTabTextColors(
            ColorUtils.blendARGB(
                bottomSheet.context.getResourceColor(R.attr.actionBarTintColor),
                unselectedColor,
                progress,
            ),
            ColorUtils.blendARGB(
                bottomSheet.context.getResourceColor(R.attr.actionBarTintColor),
                selectedColor,
                progress,
            ),
        )

        /*binding.bottomSheet.sheetLayout.backgroundTintList = ColorStateList.valueOf(
            ColorUtils.blendARGB(
                bottomSheet.context.getResourceColor(R.attr.colorPrimaryVariant),
                bottomSheet.context.getResourceColor(R.attr.colorSurface),
                progress
            )
        )*/
    }

    private fun setBottomPadding() {
        val bottomBar = activityBinding?.bottomNav
        val pad = bottomBar?.translationY?.minus(bottomBar.height) ?: 0f
        val padding = max(
            (-pad).toInt(),
            view?.rootWindowInsetsCompat?.getBottomGestureInsets() ?: 0,
        )
        binding.bottomSheet.root.sheetBehavior?.peekHeight = 56.spToPx + padding
        binding.bottomSheet.root.extensionFrameLayout?.binding?.fastScroller?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = -pad.toInt()
        }
        binding.bottomSheet.root.migrationFrameLayout?.binding?.fastScroller?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = -pad.toInt()
        }
        binding.sourceRecycler.updatePaddingRelative(
            bottom = (
                activityBinding?.bottomNav?.height
                    ?: view?.rootWindowInsetsCompat?.getBottomGestureInsets() ?: 0
                ) + 58.spToPx,
        )
    }

    override fun showSheet() {
        if (!isBindingInitialized) return
        // User tapped the pill / triggered an external sheet-open before the deferred
        // init ran. Force the wiring through synchronously so the open actually does
        // something — postDelayed callback later becomes a no-op via bottomSheetReady.
        if (!bottomSheetReady) initBottomSheet()
        binding.bottomSheet.root.sheetBehavior?.expand()
    }

    override fun hideSheet() {
        if (!isBindingInitialized) return
        if (!bottomSheetReady) initBottomSheet()
        binding.bottomSheet.root.sheetBehavior?.collapse()
    }

    override fun toggleSheet() {
        if (!isBindingInitialized) return
        if (!bottomSheetReady) initBottomSheet()
        if (!binding.bottomSheet.root.sheetBehavior.isCollapsed()) {
            binding.bottomSheet.root.sheetBehavior?.collapse()
        } else {
            binding.bottomSheet.root.sheetBehavior?.expand()
        }
    }

    override fun canStillGoBack(): Boolean = showingExtensions

    override fun handleOnBackStarted(backEvent: BackEventCompat) {
        if (showingExtensions && !binding.bottomSheet.root.canStillGoBack()) {
            binding.bottomSheet.root.sheetBehavior?.startBackProgress(backEvent)
        }
    }

    override fun handleOnBackProgressed(backEvent: BackEventCompat) {
        if (showingExtensions && !binding.bottomSheet.root.canStillGoBack()) {
            binding.bottomSheet.root.sheetBehavior?.updateBackProgress(backEvent)
        } else {
            super.handleOnBackProgressed(backEvent)
        }
    }

    override fun handleOnBackCancelled() {
        if (showingExtensions && !binding.bottomSheet.root.canStillGoBack()) {
            binding.bottomSheet.root.sheetBehavior?.cancelBackProgress()
        } else {
            super.handleOnBackCancelled()
        }
    }

    override fun handleBack(): Boolean {
        if (showingExtensions) {
            if (binding.bottomSheet.root.canGoBack()) {
                lastScale = binding.bottomSheet.root.scaleX
                binding.bottomSheet.root.sheetBehavior?.collapse()
            }
            return true
        }
        return false
    }

    override fun onDestroyView(view: View) {
        adapter = null
        // onDestroy is a no-op on the bottom sheet if onCreate never ran (presenter
        // wasn't attached), but we still call it for the normal post-init path.
        if (bottomSheetReady) {
            binding.bottomSheet.root.onDestroy()
        }
        bottomSheetReady = false
        super.onDestroyView(view)
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.onDestroy()
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        android.os.Trace.beginSection(
            if (type.isEnter) "Hayai/BrowseController.onChangeStarted.enter"
            else "Hayai/BrowseController.onChangeStarted.exit",
        )
        try {
            super.onChangeStarted(handler, type)
            when (type) {
                ControllerChangeType.PUSH_ENTER -> {
                    // Initial creation; selectTab follows up with onTabActivated → onSetupLocalChrome.
                }
                ControllerChangeType.POP_ENTER -> {
                    // BaseController hoist already called onSetupLocalChrome — nothing extra here.
                }
                ControllerChangeType.PUSH_EXIT, ControllerChangeType.POP_EXIT -> {
                    // Drop out of Conductor's menu dispatch while something is on top.
                    setOptionsMenuHidden(true)
                    binding.bottomSheet.root.canExpand = false
                    binding.bottomSheet.sheetToolbar.menu.findItem(R.id.action_search)?.let { searchItem ->
                        (searchItem.actionView as? SearchView)?.clearFocus()
                    }
                }
            }
            setBottomPadding()
        } finally {
            android.os.Trace.endSection()
        }
    }

    /**
     * Called when the user swaps to the Browse tab via the bottom nav. Persistent tabs
     * mean the source list / extension sheet content is already in memory — we deliberately
     * do NOT trigger presenter refreshes here. The expensive `updateSources` /
     * `refreshExtensions` / `refreshNovelPlugins` / `refreshMigrations` work only runs on
     * real Conductor PUSH_ENTER (see [onChangeEnded]) and on activity resume.
     */
    override fun onTabActivated() {
        if (!isBindingInitialized) return
        // Tab swap is not a Conductor event; rewire the local chrome explicitly.
        onSetupLocalChrome()
        binding.bottomSheet.root.canExpand = true
        setBottomPadding()
        // The lastUsed flow collector started in SourcePresenter.loadLastUsedSource can miss
        // an update if the user opened a source (writing the pref) while Browse's view was
        // detached for a push — re-read synchronously on activation so the row is current.
        presenter.refreshLastUsed()
    }

    /**
     * Called when the user swaps away from the Browse tab. The incoming tab owns its
     * own local appBar / tab strip — we just collapse our internal sheet state.
     */
    override fun onTabDeactivated() {
        if (!isBindingInitialized) return
        binding.bottomSheet.root.canExpand = false
        binding.bottomSheet.sheetToolbar.menu.findItem(R.id.action_search)?.let { searchItem ->
            (searchItem.actionView as? SearchView)?.clearFocus()
        }
    }

    override fun onChangeEnded(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeEnded(handler, type)
        // Deferred from onChangeStarted: these refreshes trigger updateDataSet on the various
        // bottom-sheet recyclers which fired ~15 onCreateViewHolder/inflate per long frame
        // during root nav swaps (extension_card_item / extension_card_header / manga_grid_item
        // were 15–40ms each to inflate). Running on the post-fade idle frame keeps the swap
        // itself smooth.
        if (!type.isPush) {
            // POP_ENTER (returning from a pushed controller) — user is back to interact
            // with Browse, so force the deferred sheet wiring through now if it never
            // got a chance to run (e.g. user pushed within the 280ms defer window).
            // Without this, the refresh calls below would no-op because the sheet's
            // presenter has no `view` attached yet.
            if (!bottomSheetReady) initBottomSheet()
            android.os.Trace.beginSection("Hayai/Browse.refreshExtensions")
            binding.bottomSheet.root.updateExtTitle()
            binding.bottomSheet.root.presenter.refreshExtensions()
            android.os.Trace.endSection()
            android.os.Trace.beginSection("Hayai/Browse.refreshNovelPlugins")
            binding.bottomSheet.root.presenter.refreshNovelPlugins()
            android.os.Trace.endSection()
            android.os.Trace.beginSection("Hayai/Browse.updateSources")
            presenter.updateSources()
            android.os.Trace.endSection()
            if (type.isEnter && isControllerVisible) {
                android.os.Trace.beginSection("Hayai/Browse.updateSheetMenu")
                updateSheetMenu()
                android.os.Trace.endSection()
            }
        }
        if (type.isEnter) {
            // canExpand is the sheet's "user may drag" gate; the bottom-sheet init
            // flips it on at the end of initBottomSheet, but for POP_ENTER on an
            // already-initialised sheet we still need to reset it here.
            if (bottomSheetReady) binding.bottomSheet.root.canExpand = true
            setBottomPadding()
            // Defer the migration refresh + menu update past the cross-fade settle
            // (TAB_SWAP_DURATION_MS in RootTabsController = 250ms). The postDelayed
            // is FIFO with [initBottomSheet]'s — both run on the view's main-looper
            // handler — and initBottomSheet was enqueued first from onViewCreated, so
            // by the time this fires the sheet is ready.
            view?.postDelayed(POST_ENTRY_DEFER_MS) {
                if (!isBindingInitialized) return@postDelayed
                if (!bottomSheetReady) return@postDelayed
                android.os.Trace.beginSection("Hayai/Browse.refreshMigrations")
                binding.bottomSheet.root.presenter.refreshMigrations()
                android.os.Trace.endSection()
                android.os.Trace.beginSection("Hayai/Browse.updateTitleAndMenu")
                updateTitleAndMenu()
                android.os.Trace.endSection()
            }
        }
    }

    private inline fun View.postDelayed(delayMs: Long, crossinline action: () -> Unit) {
        postDelayed({ action() }, delayMs)
    }

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        if (!isBindingInitialized) return
        // Activity resume from background is a good time to interact with the sheet —
        // if the user backgrounded during the deferred-init window, init now so the
        // refresh below isn't lost to a null sheet presenter view.
        if (!bottomSheetReady) initBottomSheet()
        binding.bottomSheet.root.presenter.refreshExtensions()
        binding.bottomSheet.root.presenter.refreshMigrations()
        setBottomPadding()
        if (showingExtensions) {
            updateSheetMenu()
        }
    }

    override fun onItemClick(view: View, position: Int): Boolean {
        val item = adapter?.getItem(position) as? SourceItem ?: return false
        val source = item.source
        // Open the catalogue view.
        openCatalogue(source, BrowseSourceController(source))
        return false
    }

    fun hideCatalogue(position: Int) {
        val source = (adapter?.getItem(position) as? SourceItem)?.source ?: return
        val current = preferences.hiddenSources().get()
        preferences.hiddenSources().set(current + source.id.toString())

        presenter.updateSources()

        snackbar = view?.snack(MR.strings.source_hidden, Snackbar.LENGTH_INDEFINITE) {
            anchorView = binding.bottomSheet.root
            setAction(MR.strings.undo) {
                val newCurrent = preferences.hiddenSources().get()
                preferences.hiddenSources().set(newCurrent - source.id.toString())
                presenter.updateSources()
            }
        }
        (activity as? MainActivity)?.setUndoSnackBar(snackbar)
    }

    private fun pinCatalogue(source: Source, isPinned: Boolean) {
        // Pin against the source's own type bucket, regardless of which Browse tab is
        // currently visible. (A novel source's pin lands in pinnedNovelCatalogues even
        // if the user got to it via search / migration / a stale legacy entry.)
        val type = (source as? CatalogueSource)?.browseType ?: BrowseSourceType.Manga
        val pref = preferences.pinnedCataloguesFor(type)
        val id = source.id.toString()
        pref.set(if (isPinned) pref.get() - id else pref.get() + id)
        presenter.updateSources()
    }

    /**
     * Called when browse is clicked in [SourceAdapter]
     */
    override fun onPinClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return
        val isPinned = item.isPinned ?: item.header?.code?.equals(SourcePresenter.PINNED_KEY)
            ?: false
        pinCatalogue(item.source, isPinned)
    }

    /**
     * Called when latest is clicked in [SourceAdapter]
     */
    override fun onLatestClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return
        openCatalogue(item.source, BrowseSourceController(item.source, useLatest = true))
    }

    /**
     * Opens a catalogue with the given controller.
     */
    private fun openCatalogue(source: CatalogueSource, controller: BrowseSourceController) {
        if (!preferences.incognitoMode().get()) {
            // Mirror the write to both prefs: the legacy cross-type one is still read by
            // GlobalSearch / migration / cold MangaDetails re-opens, and the per-type one
            // drives Browse's tab-specific last-used row.
            preferences.lastUsedCatalogueSource().set(source.id)
            preferences.lastUsedSourceFor(source.browseType).set(source.id)
            if (source !is LocalSource) {
                val list = preferences.lastUsedSources().get().toMutableSet()
                list.removeAll { it.startsWith("${source.id}:") }
                list.add("${source.id}:${Date().time}")
                val sortedList = list.filter { it.split(":").size == 2 }
                    .sortedByDescending { it.split(":").last().toLong() }
                preferences.lastUsedSources()
                    .set(sortedList.take(2).toSet())
            }
        }
        router.pushController(controller.withFadeTransaction())
    }

    override fun expandSearch() {
        if (showingExtensions) {
            binding.bottomSheet.root.sheetBehavior?.collapse()
        } else {
            searchToolbar()?.menu?.findItem(R.id.action_search)?.expandActionView()
        }
    }

    private fun performGlobalSearch(query: String) {
        router.pushController(GlobalSearchController(query).withFadeTransaction())
    }

    /**
     * Called when an option menu item has been selected by the user.
     *
     * @param item The selected item.
     * @return True if this event has been consumed, false if it has not.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // Initialize option to open catalogue settings.
            R.id.action_filter -> {
                router.pushController(SettingsSourcesController().withFadeTransaction())
            }
            R.id.action_migration_guide -> {
                activity?.openInBrowser(HELP_URL)
            }
            R.id.action_sources_settings -> {
                router.pushController(SettingsBrowseController().withFadeTransaction())
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * Called to update adapter containing sources.
     */
    fun setSources(sources: List<IFlexible<*>>, lastUsed: SourceItem?) {
        adapter?.updateDataSet(sources, false)
        setLastUsedSource(lastUsed)
        if (isControllerVisible) {
            appBar()?.lockYPos = false
        }
    }

    /**
     * Called to set the last used catalogue at the top of the view.
     */
    fun setLastUsedSource(item: SourceItem?) {
        adapter?.removeAllScrollableHeaders()
        if (item != null) {
            adapter?.addScrollableHeader(item)
            adapter?.addScrollableHeader(LangItem(SourcePresenter.LAST_USED_KEY))
        }
    }

    @Parcelize
    data class SmartSearchConfig(val origTitle: String, val origMangaId: Long) : Parcelable

    companion object {
        const val HELP_URL = "https://mihon.app/docs/guides/source-migration"

        // Shared across BrowseController lifetimes so source_item / header holders
        // recycled on one entry are reused on the next; survives controller destruction.
        private val persistentSourcePool = RecyclerView.RecycledViewPool()

        /**
         * Delay used to push non-essential first-entry work past the cross-fade settle
         * (see [eu.kanade.tachiyomi.ui.main.RootTabsController.TAB_SWAP_DURATION_MS] +
         * a small safety margin). refreshMigrations + updateTitleAndMenu both block the
         * main thread; running them inside the first visible frame stacks with the XML
         * inflate cost of the bottom sheet and produces jank on cold Browse open.
         */
        private const val POST_ENTRY_DEFER_MS = 280L
    }
}
