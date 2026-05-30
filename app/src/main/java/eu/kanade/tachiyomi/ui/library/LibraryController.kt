package eu.kanade.tachiyomi.ui.library

import yokai.util.koin.get
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HeartBroken
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type.ime
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.github.florent37.viewtooltip.ViewTooltip
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import dev.icerock.moko.resources.StringResource
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.SelectableAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.davidea.flexibleadapter.items.IHeader
import eu.davidea.flexibleadapter.items.ISectionable
import com.google.android.material.R as materialR
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.LibraryControllerBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.ui.base.MaterialMenuSheet
import eu.kanade.tachiyomi.ui.base.MiniSearchView
import eu.kanade.tachiyomi.ui.base.controller.BaseCoroutineController
import eu.kanade.tachiyomi.ui.category.CategoryController
import eu.kanade.tachiyomi.ui.category.ManageCategoryDialog
import eu.kanade.tachiyomi.ui.library.LibraryGroup.BY_AUTHOR
import eu.kanade.tachiyomi.ui.library.LibraryGroup.BY_DEFAULT
import eu.kanade.tachiyomi.ui.library.LibraryGroup.BY_LANGUAGE
import eu.kanade.tachiyomi.ui.library.LibraryGroup.BY_SOURCE
import eu.kanade.tachiyomi.ui.library.LibraryGroup.BY_STATUS
import eu.kanade.tachiyomi.ui.library.LibraryGroup.BY_TAG
import eu.kanade.tachiyomi.ui.library.LibraryGroup.BY_TRACK_STATUS
import eu.kanade.tachiyomi.ui.library.LibraryGroup.UNGROUPED
import eu.kanade.tachiyomi.ui.library.display.TabbedLibraryDisplaySheet
import eu.kanade.tachiyomi.ui.library.filter.FilterBottomSheet
import eu.kanade.tachiyomi.ui.main.BottomSheetController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import eu.kanade.tachiyomi.ui.base.TabItem
import eu.kanade.tachiyomi.ui.base.TabMode
import eu.kanade.tachiyomi.ui.base.TabsPagerSync
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.moveCategories
import eu.kanade.tachiyomi.util.system.contextCompatDrawable
import eu.kanade.tachiyomi.util.system.disableItems
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.getResourceDrawable
import eu.kanade.tachiyomi.util.system.ignoredSystemInsets
import eu.kanade.tachiyomi.util.system.isImeVisible
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.appBar
import eu.kanade.tachiyomi.util.view.collapse
import eu.kanade.tachiyomi.util.view.compatToolTipText
import eu.kanade.tachiyomi.util.view.dismissSafely
import eu.kanade.tachiyomi.util.view.expand
import eu.kanade.tachiyomi.util.view.fullAppBarHeight
import eu.kanade.tachiyomi.util.view.getItemView
import eu.kanade.tachiyomi.util.view.hide
import eu.kanade.tachiyomi.util.view.isControllerVisible
import eu.kanade.tachiyomi.util.view.isExpanded
import eu.kanade.tachiyomi.util.view.installLocalMenu
import eu.kanade.tachiyomi.util.view.isHidden
import eu.kanade.tachiyomi.util.view.isSettling
import eu.kanade.tachiyomi.util.view.scrollViewWith
import eu.kanade.tachiyomi.util.view.searchToolbar
import eu.kanade.tachiyomi.util.view.setAppBarBG
import eu.kanade.tachiyomi.util.view.setAction
import eu.kanade.tachiyomi.util.view.setMessage
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.setPositiveButton
import eu.kanade.tachiyomi.util.view.setStyle
import eu.kanade.tachiyomi.util.view.setTitle
import eu.kanade.tachiyomi.util.view.smoothScrollToTop
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.text
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.widget.EmptyView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.random.nextInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import yokai.domain.ui.UiPreferences
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

open class LibraryController(
    bundle: Bundle? = null,
    val uiPreferences: UiPreferences = get(),
    val preferences: PreferencesHelper = get(),
) : BaseCoroutineController<LibraryControllerBinding, LibraryPresenter>(bundle),
    ActionMode.Callback,
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    FlexibleAdapter.OnItemMoveListener,
    LibraryCategoryAdapter.LibraryListener,
    BottomSheetController,
    RootSearchInterface,
    FloatingSearchInterface,
    eu.kanade.tachiyomi.ui.base.LocalAppBarOwner,
    eu.kanade.tachiyomi.ui.main.RootTabContent,
    eu.kanade.tachiyomi.ui.main.TabbedInterface {

    override val hostsOwnAppBar: Boolean = true

    override fun localAppBar(): eu.kanade.tachiyomi.ui.base.ExpandedAppBarLayout? =
        if (isBindingInitialized) binding.appBar else null

    override fun onSetupLocalChrome() {
        val appBar = binding.appBar ?: return
        appBar.alpha = 1f
        appBar.isInvisible = false
        appBar.lockYPos = false
        appBar.hideBigView(useSmall = false)
        appBar.setToolbarModeBy(this)
        refreshTabStrip()
        appBar.y = 0f
        appBar.updateAppBarAfterY(binding.libraryGridRecycler.recycler)
        setupToolbarMenu()
        seedSearchFromState()
    }

    // The only display-mode-dependent chunk of chrome. Extracted so reconcileDisplaySurface can
    // refresh just this without going through the full onSetupLocalChrome chain (which
    // used to drag setupToolbarMenu's now-removed seed block along — the recursion source).
    private fun refreshTabStrip() {
        val appBar = binding.appBar ?: return
        val visibleCats = if (isTabbedMode) visibleTabCategories() else emptyList()
        val showStrip = isTabbedMode && visibleCats.size > 1 && !presenter.forceShowAllCategories
        if (showStrip) {
            val selectedIdx = visibleCats.indexOfFirst { it.order == activeCategory }
                .takeIf { it >= 0 } ?: 0
            val showCounts = preferences.categoryNumberOfItems().get()
            appBar.applyTabs(
                items = visibleCats.map { cat ->
                    TabItem.Badged(
                        text = cat.name,
                        // Null hides the badge when "show number of items" is off — honoring the pref in tabbed mode.
                        count = if (showCounts) cat.id?.let { presenter.getItemCountInCategories(it) } ?: 0 else null,
                    )
                },
                selectedIndex = selectedIdx,
                mode = TabMode.Scrollable,
                onSelected = { idx ->
                    if (binding.libraryPager.currentItem != idx) {
                        binding.libraryPager.setCurrentItem(idx, true)
                    }
                },
                onReselected = {
                    pagerAdapter?.recyclerForPosition(binding.libraryPager.currentItem)
                        ?.smoothScrollToTop()
                },
                pagerSync = TabsPagerSync(
                    pager = binding.libraryPager,
                    onPageSelected = { position ->
                        onLibraryPageSelected(position, visibleCats)
                    },
                ),
            )
        } else {
            appBar.clearTabs()
        }
    }

    // Idempotent: inflates the menu, attaches the modifier icon, and installs the query
    // text listener. Does NOT seed the SearchView's text from this.query — that one-shot
    // restore lives in seedSearchFromState(), called from onSetupLocalChrome.
    private fun setupToolbarMenu() {
        installLocalMenu(R.menu.library)

        val pill = searchToolbar() ?: return
        val searchView = pill.searchView
        pill.setQueryHint(view?.context?.getString(MR.strings.library_search_hint), query.isEmpty())

        showAllCategoriesView = showAllCategoriesView ?: (searchView as? MiniSearchView)?.addSearchModifierIcon { context ->
            ImageView(context).apply {
                isSelected = presenter.forceShowAllCategories
                isGone = true
                setOnClickListener {
                    presenter.forceShowAllCategories = !presenter.forceShowAllCategories
                    presenter.updateLibrary()
                    isSelected = presenter.forceShowAllCategories
                }
                val pad = 12.dpToPx
                setPadding(pad, 0, pad, 0)
                setImageResource(R.drawable.ic_show_all_categories_24dp)
                background = context.getResourceDrawable(android.R.attr.selectableItemBackgroundBorderless)
                imageTintList = ColorStateList.valueOf(context.getResourceColor(R.attr.actionBarTintColor))
                compatToolTipText = view?.context?.getString(MR.strings.show_all_categories)
            }
        }

        setOnQueryTextChangeListener(searchView) {
            if (!it.isNullOrEmpty() && binding.recyclerCover.isClickable) {
                showCategories(false)
            }
            search(it)
        }
    }

    // Restore SearchView state from this.query. No-ops when already in sync. Safe to call
    // from any chrome path because the in-sync guard prevents the setQuery → listener →
    // search recursion that flatten-on-search used to trigger.
    private fun seedSearchFromState() {
        val pill = searchToolbar() ?: return
        val searchView = pill.searchView ?: return
        if (searchView.query?.toString().orEmpty() == query) return
        if (query.isNotEmpty()) {
            if (pill.isSearchExpanded != true) pill.searchItem?.expandActionView()
            searchView.setQuery(query, false)
            searchView.clearFocus()
        } else if (pill.isSearchExpanded == true) {
            pill.searchItem?.collapseActionView()
        }
    }

    /**
     * Library only owns the activity tab bar when the user picked tabbed display mode
     * AND the strip is actually rendered right now. fullAppBarHeight (via this flag)
     * decides whether to reserve 48dp for the strip in the recycler's top padding;
     * keeping it `true` while flatten-on-search has hidden the strip would leave a
     * dead 48dp gap above the results. Mirrors the `showStrip` condition in
     * [reconcileDisplaySurface] / [onSetupLocalChrome] so layout math and chrome agree.
     */
    override val showActivityTabs: Boolean
        get() = isTabbedMode &&
            !presenter.forceShowAllCategories &&
            visibleTabCategories().size > 1

    init {
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    /**
     * Position of the active category.
     */
    private var activeCategory: Int = preferences.lastUsedCategory().get()
    private var lastUsedCategory: Int = preferences.lastUsedCategory().get()

    private var justStarted = true

    /**
     * Action mode for selections.
     */
    private var actionMode: ActionMode? = null

    private var libraryLayout: Int = preferences.libraryLayout().get()

    val libraryLayoutValue: Int get() = libraryLayout

    private var pagerAdapter: LibraryPagerAdapter? = null

    private val isTabbedMode: Boolean
        get() = preferences.libraryDisplayMode().get() == LibraryItem.DISPLAY_MODE_TABBED

    private val isInSingleCategoryMode: Boolean
        get() = isTabbedMode || !preferences.showAllCategories().get()

    var singleCategory: Boolean = false
        private set
    var hopperAnimation: ValueAnimator? = null
    var catGestureDetector: GestureDetector? = null

    /**
     * Library search query.
     */
    private var query = ""

    /** Read-only accessor for the per-tab page adapters to seed their filter on bind. */
    val currentQuery: String get() = query

    val isSubClass: Boolean
        get() = this is FilteredLibraryController

    /**
     * Currently selected mangas.
     */
    private val selectedMangas = mutableSetOf<Manga>()

    private var mAdapter: LibraryCategoryAdapter? = null
    private val adapter: LibraryCategoryAdapter
        get() = mAdapter!!

    /** Active adapter that owns the visible click/selection surface. In tabbed mode, the current page's adapter; otherwise mAdapter. */
    private val currentLibraryAdapter: LibraryCategoryAdapter?
        get() = if (isTabbedMode) {
            pagerAdapter?.adapterForPosition(binding.libraryPager.currentItem) ?: mAdapter
        } else {
            mAdapter
        }

    /** Recycler that visually backs [currentLibraryAdapter]. */
    private val currentLibraryRecycler: RecyclerView
        get() = if (isTabbedMode) {
            pagerAdapter?.recyclerForPosition(binding.libraryPager.currentItem)
                ?: binding.libraryGridRecycler.recycler
        } else {
            binding.libraryGridRecycler.recycler
        }

    /** Iterate every live LibraryCategoryAdapter (mAdapter + each page) so selection edits stay in sync across modes. */
    private fun forEachLibraryAdapter(block: (LibraryCategoryAdapter, RecyclerView) -> Unit) {
        mAdapter?.let { block(it, binding.libraryGridRecycler.recycler) }
        pagerAdapter?.forEachPage(block)
    }

    /** Apply the controller-wide selection set/mode onto a freshly bound page adapter. Called from LibraryPagerAdapter.bindCategoryItems. */
    fun applySelectionStateTo(adapter: LibraryCategoryAdapter) {
        val targetMode = if (selectedMangas.isNotEmpty()) {
            SelectableAdapter.Mode.MULTI
        } else {
            SelectableAdapter.Mode.SINGLE
        }
        if (adapter.mode != targetMode) adapter.mode = targetMode
        if (selectedMangas.isEmpty()) return
        selectedMangas.forEach { manga ->
            adapter.allIndexOf(manga).forEach { adapter.addSelection(it) }
        }
    }

    private var lastClickPosition = -1

    private var lastItemPosition: Int? = null
    private var lastItem: IFlexible<*>? = null

    override var presenter = LibraryPresenter()

    private var observeLater: Boolean = false
    var searchItem = SearchGlobalItem()

    var snack: Snackbar? = null
    var displaySheet: TabbedLibraryDisplaySheet? = null

    private var scrollDistance = 0f
    private val scrollDistanceTilHidden = 1000.dpToPx
    private var textAnim: ViewPropertyAnimator? = null
    private var hasExpanded = false

    val hasActiveFilters: Boolean
        get() = presenter.hasActiveFilters

    var hopperGravity: Int = preferences.hopperGravity().get()
        @SuppressLint("RtlHardcoded")
        set(value) {
            field = value
            binding.jumperCategoryText.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                anchorGravity = when (value) {
                    0 -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    2 -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                    else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
                gravity = anchorGravity
            }
        }

    private var filterTooltip: ViewTooltip? = null
    private var isAnimatingHopper: Boolean? = null
    private var animatorSet: AnimatorSet? = null
    var hasMovedHopper = preferences.shownHopperSwipeTutorial().get()
    private var shouldScrollToTop = false
    private val showCategoryInTitle
        get() = preferences.showCategoryInTitle().get() && presenter.showAllCategories
    private lateinit var elevateAppBar: ((Boolean) -> Unit)

    // Mirrors scrollViewWith's internal isToolbarColor so per-tab scroll only invokes
    // elevateAppBar() (a colorToolbar callback that cancels/restarts a ValueAnimator) when the
    // elevated state actually flips — same gating recents uses, instead of firing every frame.
    private var isPageToolbarElevated = false

    /** Render surface for the library body: the per-category pager vs. the single continuous recycler. */
    private enum class DisplaySurface { TABBED, CONTINUOUS }

    /**
     * The surface currently shown, as last applied by [reconcileDisplaySurface]. Null until the
     * first reconcile. This is the single source of truth that prevents interleaved callers
     * (resume + library-update + tab-activate within one frame) from flipping the view tree more
     * than once: a reconcile only touches the recyclers when the target surface differs from this.
     */
    private var currentDisplaySurface: DisplaySurface? = null

    /**
     * The surface that SHOULD be shown right now, derived purely from the display-mode preference
     * plus the category set and flatten-on-search flag — i.e. the same `showStrip` predicate
     * [reconcileDisplaySurface] uses, hoisted so the data path ([onNextLibraryUpdate]) can gate on
     * the PREFERENCE-derived target instead of a transient live view-visibility flag. TABBED only
     * when the pref is tabbed, there's >1 visible category, and we're not flattened-on-search; every
     * other case (single category, sub-class picker, flatten) is CONTINUOUS.
     */
    private val targetDisplaySurface: DisplaySurface
        get() = if (
            isTabbedMode &&
            visibleTabCategories().size > 1 &&
            !presenter.forceShowAllCategories
        ) {
            DisplaySurface.TABBED
        } else {
            DisplaySurface.CONTINUOUS
        }

    private var hopperOffset = 0f
    private val maxHopperOffset: Float
        get() = if (activityBinding?.bottomNav != null && !isSubClass) {
            55f.dpToPx
        } else {
            (
                view?.rootWindowInsetsCompat?.getInsets(systemBars())?.bottom?.toFloat()
                    ?: 0f
                ) + 55f.dpToPx
        }

    override val mainRecycler: RecyclerView
        get() = binding.libraryGridRecycler.recycler
    private var staggeredBundle: Parcelable? = null
    private var staggeredObserver: ViewTreeObserver.OnGlobalLayoutListener? = null
    var isPoppingIn = false
    var tempItems: List<LibraryItem>? = null

    // Dynamically injected into the search bar, controls category visibility during search
    private var showAllCategoriesView: ImageView? = null
    override fun getTitle(): String? {
        setSubtitle()
        return view?.context?.getString(MR.strings.library)
    }

    override fun getSearchTitle(): String? {
        setSubtitle()
        return searchTitle(
            if (preferences.showLibrarySearchSuggestions().get() &&
                preferences.librarySearchSuggestion().get().isNotBlank()
            ) {
                "\"${preferences.librarySearchSuggestion().get()}\""
            } else {
                view?.context?.getString(MR.strings.your_library)?.lowercase(Locale.ROOT)
            },
        )
    }

    val cb = object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
        override fun onStart(
            animation: WindowInsetsAnimationCompat,
            bounds: WindowInsetsAnimationCompat.BoundsCompat,
        ): WindowInsetsAnimationCompat.BoundsCompat {
            hopperOffset = 0f
            updateHopperY()
            return bounds
        }

        override fun onProgress(
            insets: WindowInsetsCompat,
            runningAnimations: List<WindowInsetsAnimationCompat>,
        ): WindowInsetsCompat {
            updateHopperY(insets)
            return insets
        }

        override fun onEnd(animation: WindowInsetsAnimationCompat) {
            updateHopperY()
        }
    }

    private var scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            val recyclerCover = binding.recyclerCover
            if (!recyclerCover.isClickable && isAnimatingHopper != true) {
                if (preferences.autohideHopper().get()) {
                    hopperOffset += dy
                    hopperOffset = hopperOffset.coerceIn(0f, maxHopperOffset)
                }
                if (!preferences.hideBottomNavOnScroll().get() || activityBinding?.bottomNav == null ||
                    isSubClass
                ) {
                    updateFilterSheetY()
                }
                if (!binding.fastScroller.isFastScrolling) {
                    updateSmallerViewsTopMargins()
                }
                updateHopperAlpha()
            }
            if (!binding.filterBottomSheet.filterBottomSheet.sheetBehavior.isHidden()) {
                scrollDistance += abs(dy)
                if (scrollDistance > scrollDistanceTilHidden) {
                    binding.filterBottomSheet.filterBottomSheet.sheetBehavior?.hide()
                    scrollDistance = 0f
                }
            } else {
                scrollDistance = 0f
            }
            val currentCategory = getHeader()?.category ?: return
            if (currentCategory.order != activeCategory) {
                saveActiveCategory(currentCategory)
                if (!isTabbedMode && !showCategoryInTitle && presenter.categories.size > 1 && dy != 0 && recyclerView.translationY == 0f) {
                    showCategoryText(currentCategory.name)
                }
            }
            val savedCurrentCategory = getHeader(true)?.category ?: return
            if (savedCurrentCategory.order != lastUsedCategory) {
                lastUsedCategory = savedCurrentCategory.order
                if (!isSubClass) {
                    preferences.lastUsedCategory().set(savedCurrentCategory.order)
                }
            }
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            super.onScrollStateChanged(recyclerView, newState)
            when (newState) {
                RecyclerView.SCROLL_STATE_DRAGGING -> {
                    binding.fastScroller.showScrollbar()
                }
                RecyclerView.SCROLL_STATE_IDLE -> {
                    updateHopperPosition()
                }
            }
            if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                removeStaggeredObserver()
            }
        }
    }

    fun updateHopperAlpha() {
        binding.roundedCategoryHopper.upCategory.alpha = if (isAtTop()) 0.25f else 1f
        binding.roundedCategoryHopper.downCategory.alpha = if (isAtBottom()) 0.25f else 1f
    }

    private fun removeStaggeredObserver() {
        if (staggeredObserver != null) {
            binding.libraryGridRecycler.recycler.viewTreeObserver.removeOnGlobalLayoutListener(
                staggeredObserver,
            )
            staggeredObserver = null
        }
    }

    fun updateFilterSheetY() {
        val bottomBar = if (!isSubClass) activityBinding?.bottomNav else null
        val systemInsets = view?.rootWindowInsetsCompat?.getInsets(systemBars())
        val bottomSheet = binding.filterBottomSheet.filterBottomSheet
        if (bottomBar != null) {
            bottomSheet.translationY = if (bottomSheet.sheetBehavior.isHidden()) {
                bottomBar.translationY - bottomBar.height
            } else {
                0f
            }
            val pad = bottomBar.translationY - bottomBar.height
            val padding = max((-pad).toInt(), systemInsets?.bottom ?: 0)
            bottomSheet.updatePaddingRelative(bottom = padding)

            bottomSheet.sheetBehavior?.peekHeight = 60.dpToPx + padding
            updateHopperY()
            binding.fastScroller.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = -pad.toInt()
            }
        } else {
            bottomSheet.updatePaddingRelative(bottom = systemInsets?.bottom ?: 0)
            updateHopperY()
            bottomSheet.sheetBehavior?.peekHeight = 60.dpToPx + (systemInsets?.bottom ?: 0)
        }
    }

    fun updateHopperPosition() {
        val shortAnimationDuration = resources?.getInteger(
            AR.integer.config_shortAnimTime,
        ) ?: 0
        if (preferences.autohideHopper().get()) {
            val bottomBar = if (isSubClass) null else activityBinding?.bottomNav
            // Flow same snap rules as bottom nav
            val closerToHopperBottom = hopperOffset > maxHopperOffset / 2
            val halfWayBottom = bottomBar?.height?.toFloat()?.div(2) ?: 0f
            val closerToBottom = (bottomBar?.translationY ?: 0f) > halfWayBottom
            val atTop = !binding.libraryGridRecycler.recycler.canScrollVertically(-1)
            val closerToEdge =
                if (preferences.hideBottomNavOnScroll().get() && bottomBar != null) {
                    closerToBottom && !atTop
                } else {
                    closerToHopperBottom
                }
            val end = if (closerToEdge) maxHopperOffset else 0f
            hopperAnimation?.cancel()
            val alphaAnimation = ValueAnimator.ofFloat(hopperOffset, end)
            alphaAnimation.addUpdateListener { valueAnimator ->
                hopperOffset = valueAnimator.animatedValue as Float
                updateHopperY()
            }
            alphaAnimation.doOnEnd {
                hopperOffset = end
                updateHopperY()
            }
            alphaAnimation.duration = shortAnimationDuration.toLong()
            hopperAnimation = alphaAnimation
            alphaAnimation.start()
        }
    }

    fun saveActiveCategory(category: Category) {
        activeCategory = category.order
        val headerItem = getHeader() ?: return
        binding.headerTitle.text = headerItem.category.name
        setActiveCategory()
    }

    private fun setActiveCategory() {
        val currentCategory = presenter.categories.indexOfFirst {
            if (presenter.showAllCategories) it.order == activeCategory else presenter.currentCategoryId == it.id
        }
        if (currentCategory > -1) {
            binding.categoryRecycler.setCategories(currentCategory)
            binding.headerTitle.text = presenter.categories[currentCategory].name
            setSubtitle()
        }
    }

    /**
     * Library has two display modes: continuous (single scrolling RecyclerView for all
     * categories) and tabbed (a ViewPager with one RecyclerView per category and the
     * activity tab strip as the pill bar). The visible surface is a pure function of the
     * display-mode PREFERENCE plus the category set / flatten-on-search flag — never of the
     * live view-visibility flags, which transiently disagree while several callers
     * (onActivityResumed, onNextLibraryUpdate, onTabActivated, POP_ENTER, search) fire in the
     * same frame.
     *
     * Idempotent and the SINGLE entry point for surface changes: it tracks the shown surface in
     * [currentDisplaySurface] and only flips the view tree when the computed target differs.
     * Chrome that can change without a surface change (tab strip labels/counts, mini bar) is
     * refreshed every call. Full chrome wire-up still lives in [onSetupLocalChrome].
     *
     * WHY this kills the flash: the continuous recycler is only ever made visible by
     * [applyContinuousViewTree], and that runs only when the target is CONTINUOUS. An update
     * arriving mid-transition while the pref is tabbed computes target TABBED, sees the surface
     * is already TABBED, and is a no-op for the recyclers — so no caller can show-then-hide the
     * continuous recycler within a frame.
     */
    private fun reconcileDisplaySurface(forceRebuild: Boolean = false) {
        if (!isControllerVisible) return
        val target = targetDisplaySurface
        // forceRebuild reapplies the tree even when the surface is unchanged — used by the
        // onNextLibraryUpdate path when the category SET changed (reorder/add/remove) so the
        // pager rebinds its pages, since the surface tracker alone wouldn't detect that.
        if (target != currentDisplaySurface || forceRebuild) {
            when (target) {
                DisplaySurface.TABBED -> applyTabbedViewTree()
                DisplaySurface.CONTINUOUS -> applyContinuousViewTree()
            }
            currentDisplaySurface = target
        }
        refreshTabStrip()
        showMiniBar()
    }

    /**
     * Bring the per-category pager into view, push the continuous recycler off-screen,
     * and reset the appbar to the active page's scroll offset. Tab-strip state is
     * applied separately through [refreshTabStrip] — this method only manipulates
     * Library's own view subtree and the appbar Y position.
     */
    private fun applyTabbedViewTree() {
        // Contract: never show the pager unless the pref is tabbed. Guards against a stray direct
        // call ever revealing the pager in continuous mode.
        if (!isTabbedMode) return
        val visibleCats = visibleTabCategories()
        binding.libraryGridRecycler.recycler.isVisible = false
        binding.libraryPager.isVisible = true
        // Empty the continuous-mode adapter immediately. Even though its recycler is GONE,
        // the next library update would otherwise re-populate it before the guard in
        // onNextLibraryUpdate takes effect — leaving stale categories+headers ready to
        // render if the view is briefly measured during a transition.
        mAdapter?.setItems(emptyList())

        val adapter = pagerAdapter ?: LibraryPagerAdapter(this).also { pagerAdapter = it }
        adapter.categories = visibleCats
        if (binding.libraryPager.adapter !== adapter) {
            binding.libraryPager.adapter = adapter
        } else {
            adapter.refreshAll()
        }

        val selectedIdx = visibleCats.indexOfFirst { it.order == activeCategory }
            .takeIf { it >= 0 } ?: 0
        if (binding.libraryPager.currentItem != selectedIdx) {
            binding.libraryPager.setCurrentItem(selectedIdx, false)
        }

        // Category overlay margin tracks the tab strip height — recompute now that the
        // strip is about to appear, since inset events don't fire just for our addition.
        view?.post {
            val systemTop = appBar()?.rootWindowInsetsCompat
                ?.getInsets(systemBars())?.top ?: 0
            binding.categoryRecycler.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemTop +
                    (searchToolbar()?.height ?: 0) +
                    48.dpToPx + // tab strip height
                    12.dpToPx
            }
        }

        val pageRecycler = pagerAdapter?.recyclerForPosition(binding.libraryPager.currentItem)
        appBar()?.let { appBar ->
            appBar.lockYPos = false
            appBar.updateAppBarAfterY(pageRecycler)
        }
        // Direct bg apply bypasses the colorToolbar animator so there's no fade from a stale color.
        val notAtTop = pageRecycler?.canScrollVertically(-1) == true
        setAppBarBG(if (notAtTop) 1f else 0f, includeTabView = true)
        // Seed the elevated gate to match what we just painted so the first scroll only
        // fires the animator when the state genuinely flips.
        isPageToolbarElevated = notAtTop
    }

    /**
     * Detach the pager and bring the continuous recycler back into view. Called when
     * Library leaves tabbed mode, or when the tabbed-eligible category set drops to ≤1
     * (single category, or flatten-on-search). Tab-strip state is cleared separately
     * through [refreshTabStrip].
     */
    private fun applyContinuousViewTree() {
        // Contract: never reveal the continuous recycler while a multi-category tabbed surface is
        // the real target (pref tabbed, >1 category, not flatten-on-search). Continuous is still
        // the correct surface in tabbed mode for single-category and flatten-on-search, so the
        // guard mirrors the showStrip target rather than the bare pref.
        if (isTabbedMode && visibleTabCategories().size > 1 && !presenter.forceShowAllCategories) return
        binding.libraryPager.adapter = null
        binding.libraryPager.isVisible = false
        binding.libraryGridRecycler.recycler.isVisible = true
        // Repopulate mAdapter — it was emptied while tabbed view was active. Without
        // this, code paths that toggle without firing a fresh onNextLibraryUpdate
        // (e.g. flatten on search-across-tabs) would leave the recycler empty.
        if (mAdapter?.itemCount == 0) {
            mAdapter?.setItems(presenter.libraryItemsToDisplay)
        }
        view?.post {
            val systemTop = appBar()?.rootWindowInsetsCompat
                ?.getInsets(systemBars())?.top ?: 0
            binding.categoryRecycler.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemTop +
                    (searchToolbar()?.height ?: 0) +
                    12.dpToPx
            }
        }
        appBar()?.lockYPos = false
        if (::elevateAppBar.isInitialized) {
            elevateAppBar(binding.libraryGridRecycler.recycler.canScrollVertically(-1))
        }
        // Reset the tabbed-mode gate; reseeded the next time tabs are enabled.
        isPageToolbarElevated = false
    }

    fun pageRecyclerTopPadding(): Int {
        val systemTop = appBar()?.rootWindowInsets
            ?.getInsets(android.view.WindowInsets.Type.systemBars())?.top ?: 0
        // fullAppBarHeight already accounts for the tab strip when [showActivityTabs] is true.
        val appBarHeight = fullAppBarHeight ?: (appBar()?.attrToolbarHeight ?: 0)
        return systemTop + appBarHeight
    }

    /**
     * Same shape as [scrollViewWith]'s `atTopOfRecyclerView`: under a large toolbar with the activity
     * tab strip, "at top" means we haven't scrolled past the cardFrame area, not just dy>0.
     */
    private fun atTopOfPageRecycler(recycler: RecyclerView): Boolean {
        val ab = activityBinding ?: return true
        if (appBar()!!.useLargeToolbar == false) return !recycler.canScrollVertically(-1)
        return recycler.computeVerticalScrollOffset() - recycler.paddingTop <=
            0 - appBar()!!.paddingTop - ab.toolbar.height - 48.dpToPx
    }

    /** Gated wrapper around [elevateAppBar] so the underlying ValueAnimator only restarts on flips. */
    private fun setPageToolbarElevated(elevated: Boolean) {
        if (isPageToolbarElevated == elevated) return
        isPageToolbarElevated = elevated
        if (::elevateAppBar.isInitialized) elevateAppBar(elevated)
    }

    /**
     * Mirrors [scrollViewWith]'s onScrolled for the per-tab recycler. Two things keep this stable
     * vs. the previous version: (1) the appbar collapse/expand IS the visual cue, so we don't toggle
     * the bg color in place on every dy (avoids flicker on small libraries that barely cross the
     * elevated threshold); (2) [setPageToolbarElevated] is gated on the tracked state, mirroring
     * recents' [scrollViewWith.colorToolbar] gating, so the bg ValueAnimator only restarts on actual
     * flips instead of being cancelled-and-restarted every frame.
     */
    fun onPageRecyclerScrolled(recycler: RecyclerView, dy: Int) {
        if (!isControllerVisible) return
        val appBar = appBar() ?: return
        if (appBar.height <= 0) return
        if (!recycler.canScrollVertically(-1)) {
            appBar.y = 0f
            appBar.updateAppBarAfterY(recycler)
            setPageToolbarElevated(false)
        } else {
            appBar.y -= dy
            // Pin the small toolbar + search card + tabs strip when scrolled. updateAppBarAfterY's
            // own clamp on phones is [-realHeight, smallHeight] — wide enough that a fast dy or a
            // residual offset (e.g. swiping back to a previously-scrolled tab) pushes appBar.y
            // well below smallHeight, sending the tabs and search card off-screen. The bg color
            // paints onto the now-hidden appbar, so visually it looks like the header vanished
            // into an empty band at the top. Holding appBar.y at smallHeight keeps the scrolled
            // variant pinned (which is what the tabbed-mode design assumes).
            val pinnedY = -appBar.height.toFloat() +
                appBar.attrToolbarHeight + appBar.paddingTop + 48.dpToPx
            if (appBar.y < pinnedY) appBar.y = pinnedY
            appBar.updateAppBarAfterY(recycler)
            // Final reconcile against the large-toolbar offset threshold.
            val notAtTop = !atTopOfPageRecycler(recycler)
            if (notAtTop != isPageToolbarElevated) setPageToolbarElevated(notAtTop)
        }
    }

    fun onPageRecyclerScrollIdle(recycler: RecyclerView) {
        if (!isControllerVisible) return
        val appBar = appBar() ?: return
        if (appBar.height <= 0) return
        appBar.snapAppBarY(this, recycler, callback = null)
        setPageToolbarElevated(!atTopOfPageRecycler(recycler))
    }

    /**
     * Categories shown as tabs. The presenter already excludes the collapsed-from-continuous flag
     * for tabbed mode (see LibraryPresenter.getLibraryItems / getDynamicLibraryItems), so the
     * remaining `isHidden` filter is just a defensive guard against transient stale state.
     */
    private fun visibleTabCategories(): List<Category> =
        presenter.categories.filter { !it.isHidden }

    /**
     * Flatten-on-search transition: with `forceShowAllCategories` enabled, a non-blank
     * query merges every category into one flat result list, so the tab strip needs to
     * disappear. Routed through the same reconcile so the flatten↔unflatten flip can't
     * race the other surface callers.
     */
    private fun applyTabbedSearchVisibility() {
        if (!isTabbedMode) return
        reconcileDisplaySurface()
    }

    fun showMiniBar() {
        binding.headerCard.isVisible = showCategoryInTitle
        setSubtitle()
    }

    private fun setSubtitle() {
        if (isBindingInitialized && !singleCategory && presenter.showAllCategories &&
            !binding.headerTitle.text.isNullOrBlank() && !binding.recyclerCover.isClickable &&
            isControllerVisible
        ) {
            searchToolbar()?.subtitle = binding.headerTitle.text.toString()
        } else {
            searchToolbar()?.subtitle = null
        }
    }

    fun showCategoryText(name: String) {
        textAnim?.cancel()
        binding.jumperCategoryText.alpha = 1f
        binding.jumperCategoryText.text = name
        textAnim = binding.jumperCategoryText.animate().alpha(0f).setDuration(250L).setStartDelay(
            2000,
        )
        textAnim?.start()
    }

    fun isAtTop(): Boolean {
        return if (presenter.showAllCategories) {
            !binding.libraryGridRecycler.recycler.canScrollVertically(-1)
        } else {
            getVisibleHeader()?.category?.order == presenter.categories.minOfOrNull { it.order }
        }
    }

    fun isAtBottom(): Boolean {
        return if (presenter.showAllCategories) {
            !binding.libraryGridRecycler.recycler.canScrollVertically(1)
        } else {
            getVisibleHeader()?.category?.order == presenter.categories.maxOfOrNull { it.order }
        }
    }

    private fun showFilterTip() {
        if (preferences.shownFilterTutorial().get() || !hasExpanded) return
        if (filterTooltip != null) return
        val activityBinding = activityBinding ?: return
        val activity = activity ?: return
        val icon = (activityBinding.bottomNav ?: activityBinding.sideNav)?.getItemView(R.id.nav_library) ?: return
        filterTooltip =
            ViewTooltip.on(activity, icon).autoHide(false, 0L).align(ViewTooltip.ALIGN.START)
                .position(ViewTooltip.Position.TOP)
                .text(MR.strings.tap_library_to_show_filters)
                .textColor(activity.getResourceColor(R.attr.colorOnSecondary))
                .color(activity.getResourceColor(materialR.attr.colorSecondary))
                .textSize(TypedValue.COMPLEX_UNIT_SP, 15f).withShadow(false)
                .corner(30).arrowWidth(15).arrowHeight(15).distanceWithView(0)

        filterTooltip?.show()
    }

    private fun openRandomManga(global: Boolean) {
        val items =
            if (global) { presenter.currentLibraryItems } else { adapter.currentItems }
                .filterIsInstance<LibraryMangaItem>()
                .filter { !it.manga.manga.initialized || it.manga.unread > 0 }
        if (items.isNotEmpty()) {
            val item = items.random() as LibraryMangaItem
            openManga(item.manga.manga)
        }
    }

    internal fun showGroupOptions() {
        val groupItems = mutableListOf(BY_DEFAULT, BY_TAG, BY_SOURCE, BY_STATUS, BY_AUTHOR)
        if (presenter.isLoggedIntoTracking) {
            groupItems.add(BY_TRACK_STATUS)
        }
        groupItems.add(BY_LANGUAGE)
        if (presenter.isCategoryMoreThanOne()) {
            groupItems.add(UNGROUPED)
        }
        val items = groupItems.map { id ->
            MaterialMenuSheet.MenuSheetItem(
                id,
                LibraryGroup.groupTypeDrawableRes(id),
                LibraryGroup.groupTypeStringRes(id, presenter.isCategoryMoreThanOne()),
            )
        }
        MaterialMenuSheet(
            activity!!,
            items,
            activity!!.getString(MR.strings.group_library_by),
            presenter.groupType,
        ) { _, item ->
            if (!isSubClass) {
                preferences.groupLibraryBy().set(item)
            }
            presenter.groupType = item
            shouldScrollToTop = true
            presenter.updateLibrary()
            true
        }.show()
    }

    internal fun showDisplayOptions() {
        if (displaySheet == null) {
            displaySheet = TabbedLibraryDisplaySheet(this)
            displaySheet?.show()
        }
    }

    internal fun closeTip() {
        if (filterTooltip != null) {
            filterTooltip?.close()
            filterTooltip = null
            if (!isSubClass) {
                preferences.shownFilterTutorial().set(true)
            }
        }
    }

    override fun createBinding(inflater: LayoutInflater) = LibraryControllerBinding.inflate(inflater)

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        mAdapter = LibraryCategoryAdapter(this)
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        setRecyclerLayout()
        binding.libraryGridRecycler.recycler.setHasFixedSize(true)
        // Process-static pool so manga_grid_item holders are reused on every Library
        // re-entry. Without it, root-nav back to Library re-inflates ~14 grid items
        // each time (~18ms × 14 = 250ms/frame). Bumped per-type caps too — default 5
        // is well under typical visible row counts.
        binding.libraryGridRecycler.recycler.setRecycledViewPool(persistentLibraryPool)
        persistentLibraryPool.setMaxRecycledViews(R.layout.manga_grid_item, 40)
        persistentLibraryPool.setMaxRecycledViews(R.layout.manga_list_item, 40)
        persistentLibraryPool.setMaxRecycledViews(R.layout.library_category_header_item, 8)
        binding.libraryGridRecycler.recycler.adapter = adapter

        adapter.fastScroller = binding.fastScroller
        binding.fastScroller.controller = this
        binding.libraryGridRecycler.recycler.addOnScrollListener(scrollListener)

        binding.swipeRefresh.setStyle()

        binding.recyclerCover.setOnClickListener {
            showCategories(false)
        }
        binding.categoryRecycler.onCategoryClicked = {
            showCategories(show = false, closeSearch = true, category = it)
            scrollToHeader(it)
        }
        binding.categoryRecycler.setOnTouchListener { _, _ ->
            val searchView = searchToolbar()?.menu?.findItem(R.id.action_search)?.actionView
                ?: return@setOnTouchListener false
            val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm!!.hideSoftInputFromWindow(searchView.windowToken, 0)
            false
        }
        setupFilterSheet()
        setUpHopper()
        setPreferenceFlows()
        LibraryUpdateJob.updateFlow.onEach(::onUpdateManga).launchIn(viewScope)
        viewScope.launchUI {
            LibraryUpdateJob.isRunningFlow(view.context).collect {
                adapter.getHeaderPositions().forEach {
                    val holder = (binding.libraryGridRecycler.recycler.findViewHolderForAdapterPosition(it) as? LibraryHeaderHolder) ?: return@forEach
                    val category = holder.category ?: return@forEach
                    holder.notifyStatus(LibraryUpdateJob.categoryInQueue(category.id), category)
                }
            }
        }

        elevateAppBar =
            scrollViewWith(
                binding.libraryGridRecycler.recycler,
                swipeRefreshLayout = binding.swipeRefresh,
                ignoreInsetVisibility = true,
                afterInsets = { insets ->
                    val systemInsets = insets.ignoredSystemInsets
                    // In tabbed mode the appbar carries an extra 48dp tab strip below the
                    // search toolbar; without folding that into the category overlay's top
                    // margin the overlay starts UNDER the tabs and its first ~48dp is
                    // unreachable. Use the live tabsFrameLayout visibility (matches what
                    // ExpandedAppBarLayout uses internally for height calculations).
                    val tabsHeight = if (activityBinding?.tabsFrameLayout?.isVisible == true) {
                        48.dpToPx
                    } else {
                        0
                    }
                    binding.categoryRecycler.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        topMargin = systemInsets.top +
                            (searchToolbar()?.height ?: 0) +
                            tabsHeight +
                            12.dpToPx
                    }
                    updateSmallerViewsTopMargins()
                    binding.headerCard.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        topMargin = systemInsets.top + 4.dpToPx
                    }
                    updateFilterSheetY()
                },
                onLeavingController = {
                    binding.headerCard.isVisible = false
                },
                onBottomNavUpdate = {
                    updateFilterSheetY()
                },
            )

        viewScope.launchUI {
            delay(50)
            updateHopperY()
        }
        setSwipeRefresh()

        ViewCompat.setWindowInsetsAnimationCallback(view, cb)

        if (selectedMangas.isNotEmpty()) {
            createActionModeIfNeeded()
        }

        if (presenter.libraryItemsToDisplay.isNotEmpty() && !isSubClass) {
            presenter.restoreLibrary()
            if (justStarted) {
                val activityBinding = activityBinding ?: return
                val bigToolbarHeight = fullAppBarHeight ?: return
                if (lastUsedCategory > 0) {
                    appBar()!!.y =
                        -bigToolbarHeight + activityBinding.cardFrame.height.toFloat()
                    appBar()!!.useSearchToolbarForMenu(true)
                }
                appBar()!!.lockYPos = true
            }
        } else {
            binding.recyclerLayout.alpha = 0f
        }
    }

    private fun updateSmallerViewsTopMargins() {
        val bigToolbarHeight = fullAppBarHeight ?: return
        val value = max(
            0,
            bigToolbarHeight + appBar()!!.y.roundToInt(),
        ) + appBar()!!.paddingTop
        if (value != binding.fastScroller.marginTop) {
            binding.fastScroller.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = value
            }
            binding.emptyView.updatePadding(
                top = bigToolbarHeight + appBar()!!.paddingTop,
                bottom = binding.libraryGridRecycler.recycler.paddingBottom,
            )
            binding.progress.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = (bigToolbarHeight + appBar()!!.paddingTop) / 2
            }
        }
    }

    private fun setSwipeRefresh() = with(binding.swipeRefresh) {
        setOnRefreshListener {
            isRefreshing = false
            if (!LibraryUpdateJob.isRunning(context)) {
                val currentTabCategory = if (isTabbedMode) {
                    pagerAdapter?.categories?.getOrNull(binding.libraryPager.currentItem)
                } else {
                    null
                }
                when {
                    // Tabbed mode mirrors continuous's single-category behavior: pulling on a tab
                    // refreshes just that category, not the whole library. Whole-library refresh
                    // lives in the overflow menu instead.
                    currentTabCategory != null && presenter.groupType == BY_DEFAULT ->
                        updateLibrary(currentTabCategory)
                    !presenter.showAllCategories && presenter.groupType == BY_DEFAULT -> {
                        presenter.currentCategory?.let {
                            updateLibrary(it)
                        }
                    }
                    !presenter.showAllCategories -> updateCategory(0)
                    else -> updateLibrary()
                }
            }
        }
    }

    private fun setupFilterSheet() {
        binding.filterBottomSheet.filterBottomSheet.onCreate(this)

        binding.filterBottomSheet.filterBottomSheet.onGroupClicked = {
            when (it) {
                FilterBottomSheet.ACTION_REFRESH -> onRefresh()
                FilterBottomSheet.ACTION_FILTER -> onFilterChanged()
                FilterBottomSheet.ACTION_HIDE_FILTER_TIP -> showFilterTip()
                FilterBottomSheet.ACTION_DISPLAY -> showDisplayOptions()
                FilterBottomSheet.ACTION_EXPAND_COLLAPSE_ALL -> presenter.toggleAllCategoryVisibility()
                FilterBottomSheet.ACTION_GROUP_BY -> showGroupOptions()
            }
        }
    }

    @SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
    private fun setUpHopper() {
        binding.categoryHopperFrame.isVisible = false
        binding.roundedCategoryHopper.downCategory.setOnClickListener {
            jumpToNextCategory(true)
        }
        binding.roundedCategoryHopper.upCategory.setOnClickListener {
            jumpToNextCategory(false)
        }
        binding.roundedCategoryHopper.downCategory.setOnLongClickListener {
            binding.libraryGridRecycler.recycler.scrollToPosition(adapter.itemCount - 1)
            true
        }
        binding.roundedCategoryHopper.upCategory.setOnLongClickListener {
            binding.libraryGridRecycler.recycler.smoothScrollToTop()
            true
        }
        binding.roundedCategoryHopper.categoryButton.setOnClickListener {
            val items = presenter.categories.map { category ->
                MaterialMenuSheet.MenuSheetItem(
                    category.order,
                    text = category.name +
                        if (adapter.showNumber && adapter.itemsPerCategory[category.id] != null) {
                            " (${adapter.itemsPerCategory[category.id]})"
                        } else {
                            ""
                        },
                )
            }
            if (items.isEmpty()) return@setOnClickListener
            MaterialMenuSheet(
                activity!!,
                items,
                it.context.getString(MR.strings.jump_to_category),
                activeCategory,
                300.dpToPx,
            ) { _, item ->
                scrollToHeader(item)
                true
            }.show()
        }
        catGestureDetector = GestureDetector(binding.root.context, LibraryCategoryGestureDetector(this))

        binding.roundedCategoryHopper.categoryButton.setOnLongClickListener {
            when (preferences.hopperLongPressAction().get()) {
                5 -> openRandomManga(true)
                4 -> openRandomManga(false)
                3 -> showGroupOptions()
                2 -> showDisplayOptions()
                1 -> if (canCollapseOrExpandCategory() != null) presenter.toggleAllCategoryVisibility()
                else -> if (!isSubClass) {
                    searchToolbar()?.menu?.performIdentifierAction(
                        R.id.action_search,
                        0,
                    )
                }
            }
            true
        }

        val gravityPref = if (!hasMovedHopper) {
            Random.nextInt(0..2)
        } else {
            preferences.hopperGravity().get()
        }
        hideHopper(preferences.hideHopper().get())
        binding.categoryHopperFrame.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            gravity = Gravity.TOP or when (gravityPref) {
                0 -> Gravity.LEFT
                2 -> Gravity.RIGHT
                else -> Gravity.CENTER
            }
        }
        hopperGravity = gravityPref

        val gestureDetector = GestureDetector(binding.root.context, LibraryGestureDetector(this))
        with(binding.roundedCategoryHopper) {
            listOf(categoryHopperLayout, upCategory, downCategory, categoryButton).forEach {
                it.setOnTouchListener { _, event ->
                    if (event?.action == MotionEvent.ACTION_DOWN) {
                        animatorSet?.end()
                    }
                    if (event?.action == MotionEvent.ACTION_UP) {
                        val result = gestureDetector.onTouchEvent(event)
                        if (!result) {
                            binding.categoryHopperFrame.animate().setDuration(150L).translationX(0f)
                                .start()
                        }
                        result
                    } else {
                        gestureDetector.onTouchEvent(event)
                    }
                }
            }
        }
    }

    fun handleGeneralEvent(event: MotionEvent) {
        if (presenter.showAllCategories) return
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            val result = catGestureDetector?.onTouchEvent(event) ?: false
            if (!result && binding.libraryGridRecycler.recycler.translationX != 0f) {
                binding.libraryGridRecycler.recycler.animate().setDuration(150L)
                    .translationX(0f)
                    .start()
            }
        } else {
            catGestureDetector?.onTouchEvent(event)
        }
    }

    open fun updateHopperY(windowInsets: WindowInsetsCompat? = null) {
        val view = view ?: return
        val insets = windowInsets ?: view.rootWindowInsetsCompat
        val bottomNav = if (isSubClass) null else activityBinding?.bottomNav
        val listOfYs = mutableListOf(
            binding.filterBottomSheet.filterBottomSheet.y,
            bottomNav?.y ?: binding.filterBottomSheet.filterBottomSheet.y,
        )
        val insetBottom = insets?.getInsets(systemBars())?.bottom ?: 0
        if (!preferences.autohideHopper().get() || bottomNav == null) {
            listOfYs.add(view.height - (insetBottom).toFloat())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && insets?.isImeVisible() == true) {
            val insetKey = insets.getInsets(ime() or systemBars()).bottom
            listOfYs.add(view.height - (insetKey).toFloat())
        }
        binding.categoryHopperFrame.y = -binding.categoryHopperFrame.height +
            (listOfYs.minOrNull() ?: binding.filterBottomSheet.filterBottomSheet.y) +
            hopperOffset +
            binding.libraryGridRecycler.recycler.translationY
        if (view.height - insetBottom < binding.categoryHopperFrame.y) {
            binding.jumperCategoryText.translationY =
                -(binding.categoryHopperFrame.y - (view.height - insetBottom)) +
                binding.libraryGridRecycler.recycler.translationY
        } else {
            binding.jumperCategoryText.translationY = binding.libraryGridRecycler.recycler.translationY
        }
    }

    fun resetHopperY() {
        hopperOffset = 0f
    }

    fun hideHopper(hide: Boolean) {
        binding.categoryHopperFrame.isVisible = !singleCategory && !hide && !isTabbedMode
        binding.jumperCategoryText.isVisible = !hide
    }

    fun jumpToNextCategory(next: Boolean): Boolean {
        val category = getVisibleHeader() ?: return false
        if (presenter.showAllCategories) {
            if (!next) {
                val fPosition = binding.libraryGridRecycler.recycler.findFirstVisibleItemPosition()
                if (fPosition > adapter.currentItems.indexOf(category)) {
                    scrollToHeader(category.category.order)
                    return true
                }
            }
            val newOffset = adapter.headerItems.indexOf(category) + (if (next) 1 else -1)
            return if (if (!next) newOffset > -1 else newOffset < adapter.headerItems.size) {
                val newCategory = (adapter.headerItems[newOffset] as LibraryHeaderItem).category
                val newOrder = newCategory.order
                scrollToHeader(newOrder)
                showCategoryText(newCategory.name)
                true
            } else {
                binding.libraryGridRecycler.recycler.scrollToPosition(if (next) adapter.itemCount - 1 else 0)
                true
            }
        } else {
            val newOffset =
                presenter.categories.indexOfFirst { presenter.currentCategoryId == it.id } +
                    (if (next) 1 else -1)
            if (if (!next) {
                newOffset > -1
            } else {
                    newOffset < presenter.categories.size
                }
            ) {
                val newCategory = presenter.categories[newOffset]
                val newOrder = newCategory.order
                scrollToHeader(newOrder)
                showCategoryText(newCategory.name)
                hopperAnimation?.cancel()
                hopperOffset = 0f
                updateHopperY()
                return true
            }
        }
        return false
    }

    fun visibleHeaderHolder(): LibraryHeaderHolder? {
        return adapter.getHeaderPositions().firstOrNull()?.let {
            binding.libraryGridRecycler.recycler.findViewHolderForAdapterPosition(it) as? LibraryHeaderHolder
        }
    }

    private fun getHeader(firstCompletelyVisible: Boolean = false): LibraryHeaderItem? {
        val position = if (firstCompletelyVisible) {
            binding.libraryGridRecycler.recycler.findFirstCompletelyVisibleItemPosition()
        } else {
            -1
        }
        if (position > 0) {
            when (val item = adapter.getItem(position)) {
                is LibraryHeaderItem -> return item
                is LibraryItem -> return item.header
            }
        } else {
            val fPosition = binding.libraryGridRecycler.recycler.findFirstVisibleItemPosition()
            when (val item = adapter.getItem(fPosition)) {
                is LibraryHeaderItem -> return item
                is LibraryItem -> return item.header
            }
        }
        return null
    }

    private fun getVisibleHeader(): LibraryHeaderItem? {
        val fPosition = binding.libraryGridRecycler.recycler.findFirstVisibleItemPosition()
        when (val item = adapter.getItem(fPosition)) {
            is LibraryHeaderItem -> return item
            is LibraryItem -> return item.header
        }
        return adapter.headerItems.firstOrNull() as? LibraryHeaderItem
    }

    private fun anchorView(): View {
        return if (binding.categoryHopperFrame.isVisible) {
            binding.categoryHopperFrame
        } else {
            binding.filterBottomSheet.filterBottomSheet
        }
    }

    private fun updateLibrary(category: Category? = null) {
        val view = view ?: return
        LibraryUpdateJob.startNow(view.context, category)
        snack = view.snack(MR.strings.updating_library) {
            anchorView = anchorView()
            this.view.elevation = 15f.dpToPx
            setAction(MR.strings.cancel) {
                LibraryUpdateJob.stop(context)
                viewScope.launchUI {
                    NotificationReceiver.dismissNotification(
                        context,
                        Notifications.ID_LIBRARY_PROGRESS,
                    )
                }
            }
        }
    }

    private fun setRecyclerLayout() {
        with(binding.libraryGridRecycler.recycler) {
            val bottomNav = if (isSubClass) null else activityBinding?.bottomNav
            viewScope.launchUI {
                updatePaddingRelative(
                    bottom = 50.dpToPx + (bottomNav?.height ?: 0),
                )
            }
            useStaggered(preferences, uiPreferences)
            if (libraryLayout == LibraryItem.LAYOUT_LIST) {
                spanCount = 1
                updatePaddingRelative(
                    start = 0,
                    end = 0,
                )
            } else {
                setGridSize(preferences)
                updatePaddingRelative(
                    start = 5.dpToPx,
                    end = 5.dpToPx,
                )
            }
            (manager as? GridLayoutManager)?.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    if (libraryLayout == LibraryItem.LAYOUT_LIST) return managerSpanCount
                    val item = this@LibraryController.mAdapter?.getItem(position)
                    return if (item is LibraryHeaderItem || item is SearchGlobalItem || item is LibraryPlaceholderItem) {
                        managerSpanCount
                    } else {
                        1
                    }
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setPreferenceFlows() {
        listOf(
            preferences.libraryLayout(),
            uiPreferences.uniformGrid(),
            preferences.gridSize(),
            preferences.useStaggeredGrid(),
        ).forEach {
            it.changes()
                .drop(1)
                .onEach {
                    reattachAdapter()
                }
                .launchIn(viewScope)
        }
        preferences.libraryDisplayMode().changes()
            .drop(1)
            .onEach {
                reconcileDisplaySurface()
                presenter.updateLibrary()
            }
            .launchIn(viewScope)
        preferences.librarySearchAcrossTabs().changes()
            .drop(1)
            .onEach { applyTabbedSearchVisibility() }
            .launchIn(viewScope)
        preferences.hideStartReadingButton().register()
        uiPreferences.outlineOnCovers().register { adapter.showOutline = it }
        // Pushes to mAdapter (continuous) via register's notifyDataSetChanged; also fan out to every
        // live pager page and refresh the tab strip so the badge honors the toggle in tabbed mode.
        preferences.categoryNumberOfItems().register {
            adapter.showNumber = it
            pagerAdapter?.forEachPage { pageAdapter, _ ->
                pageAdapter.showNumber = it
                pageAdapter.notifyDataSetChanged()
            }
            if (isTabbedMode) refreshTabStrip()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun <T> Preference<T>.register(onChanged: ((T) -> Unit)? = null) {
        changes()
            .drop(1)
            .onEach {
                onChanged?.invoke(it)
                adapter.notifyDataSetChanged()
            }
            .launchIn(viewScope)
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        android.os.Trace.beginSection(
            if (type.isEnter) "Hayai/LibraryController.onChangeStarted.enter"
            else "Hayai/LibraryController.onChangeStarted.exit",
        )
        try {
            onChangeStartedInner(handler, type)
        } finally {
            android.os.Trace.endSection()
        }
    }

    private fun onChangeStartedInner(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        when (type) {
            ControllerChangeType.PUSH_ENTER -> {
                // Initial creation; selectTab follows up with onTabActivated → onSetupLocalChrome.
            }
            ControllerChangeType.POP_ENTER -> {
                // Pop back from MangaDetails etc. Refresh library data, then re-wire the
                // local chrome via onTabActivated.
                presenter.updateLibrary()
                isPoppingIn = true
                onTabActivated()
            }
            ControllerChangeType.PUSH_EXIT, ControllerChangeType.POP_EXIT -> {
                // Pushed-over: drop out of menu dispatch so our items don't stack on top of
                // the pushed controller's. Each ported controller wires its own local
                // appBar via onSetupLocalChrome; we don't touch chrome here.
                setOptionsMenuHidden(true)
                saveStaggeredState()
                updateFilterSheetY()
                closeTip()
                if (binding.filterBottomSheet.filterBottomSheet.sheetBehavior.isHidden()) {
                    binding.filterBottomSheet.filterBottomSheet.isInvisible = true
                }
            }
            else -> Unit
        }
    }

    override fun onChangeEnded(
        changeHandler: ControllerChangeHandler,
        changeType: ControllerChangeType,
    ) {
        super.onChangeEnded(changeHandler, changeType)
        if (isPoppingIn) {
            isPoppingIn = false
            tempItems?.let { onNextLibraryUpdate(it) }
            tempItems = null
        }
    }

    /**
     * Called when the user swaps to the Library tab via the bottom nav. Wires up the
     * local appBar via [onSetupLocalChrome] and flips the view tree to match the display
     * mode. Mirrors `onChangeStarted(PUSH_ENTER)` for tab swaps, which bypass Conductor's
     * lifecycle because the controller view stays attached across swaps.
     */
    override fun onTabActivated() {
        if (!isBindingInitialized) return
        binding.filterBottomSheet.filterBottomSheet.isVisible = true
        binding.recyclerCover.isClickable = false
        binding.recyclerCover.isFocusable = false
        singleCategory = presenter.categories.size <= 1
        if (binding.libraryGridRecycler.recycler.manager is StaggeredGridLayoutManager && staggeredBundle != null) {
            binding.libraryGridRecycler.recycler.manager.onRestoreInstanceState(staggeredBundle)
            staggeredBundle = null
        }
        reconcileDisplaySurface()
        // BaseController.onChangeStarted fires onSetupLocalChrome on push/pop, but tab
        // swaps go through RootTabsController.selectTab → onTabActivated and bypass
        // Conductor's lifecycle. Wire chrome explicitly here.
        onSetupLocalChrome()
    }

    /**
     * Called when the user swaps away from the Library tab. Release Library-internal
     * state (filter sheet, staggered scroll). The incoming tab's [onSetupLocalChrome]
     * sets up its own appBar — nothing for us to undo.
     */
    override fun onTabDeactivated() {
        if (!isBindingInitialized) return
        saveStaggeredState()
        updateFilterSheetY()
        closeTip()
        if (binding.filterBottomSheet.filterBottomSheet.sheetBehavior.isHidden()) {
            binding.filterBottomSheet.filterBottomSheet.isInvisible = true
        }
    }

    /**
     * Pager-page-settle callback for tabbed mode. Updates the active category, records
     * the last-used category preference, and snaps the appbar to the new page's scroll
     * offset so the user lands at the top of the freshly-selected tab.
     */
    private fun onLibraryPageSelected(position: Int, visibleCats: List<Category>) {
        val target = visibleCats.getOrNull(position) ?: return
        if (target.order != activeCategory) {
            activeCategory = target.order
            setActiveCategory()
            if (!isSubClass) preferences.lastUsedCategory().set(target.order)
        }
        pagerAdapter?.adapterForPosition(position)?.let(::applySelectionStateTo)
        pagerAdapter?.recyclerForPosition(position)?.let { pageRecycler ->
            pageRecycler.stopScroll()
            appBar()?.let { appBar ->
                val offset = pageRecycler.computeVerticalScrollOffset()
                if (offset == 0) {
                    appBar.y = 0f
                    appBar.updateAppBarAfterY(pageRecycler)
                    pageRecycler.post {
                        if (activeCategory == target.order) {
                            appBar.updateAppBarAfterY(pageRecycler)
                        }
                    }
                    setPageToolbarElevated(false)
                } else {
                    val pinnedY = -appBar.height.toFloat() +
                        appBar.attrToolbarHeight + appBar.paddingTop + 48.dpToPx
                    appBar.y = pinnedY
                    appBar.updateAppBarAfterY(pageRecycler)
                    pageRecycler.post {
                        if (activeCategory == target.order) {
                            appBar.updateAppBarAfterY(pageRecycler)
                        }
                    }
                    setPageToolbarElevated(true)
                }
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        if (!isBindingInitialized) return
        updateFilterSheetY()
        if (observeLater) {
            presenter.updateLibrary()
        }
        reconcileDisplaySurface()
    }

    override fun onActivityPaused(activity: Activity) {
        super.onActivityPaused(activity)
        observeLater = true
    }

    override fun onDestroyView(view: View) {
        destroyActionModeIfNeeded()
        if (isBindingInitialized) {
            binding.libraryGridRecycler.recycler.removeOnScrollListener(scrollListener)
            binding.fastScroller.controller = null
            // appBar owns the pager listener installed via applyTabs and detaches it on
            // clearTabs / next applyTabs; no manual detach needed here.
            binding.libraryPager.adapter = null
        }
        pagerAdapter = null
        displaySheet?.dismissSafely()
        displaySheet = null
        mAdapter = null
        // Force the next reconcile to reapply the tree: a recreated view resets the recyclers to
        // their XML default visibility, so a stale tracker would skip the flip they now need.
        currentDisplaySurface = null
        saveStaggeredState()

        showAllCategoriesView?.let {
            (searchToolbar()?.searchView as? MiniSearchView)?.removeSearchModifierIcon(it)
        }
        super.onDestroyView(view)
    }

    open fun onNextLibraryUpdate(mangaMap: List<LibraryItem>, freshStart: Boolean = false) {
        if (isPoppingIn) {
            tempItems = mangaMap
            return
        }
        view ?: return
        destroyActionModeIfNeeded()
        if (mangaMap.isNotEmpty()) {
            if (!binding.progress.isVisible) {
                (activity as? MainActivity)?.showNotificationPermissionPrompt()
            }
            binding.emptyView.hide()
        } else {
            binding.emptyView.show(
                Icons.Filled.HeartBroken,
                if (hasActiveFilters) {
                    MR.strings.no_matches_for_filters
                } else {
                    MR.strings.library_is_empty_add_from_browse
                },
                if (!hasActiveFilters) {
                    listOf(
                        EmptyView.Action(MR.strings.getting_started_guide) {
                            activity?.openInBrowser("https://mihon.app/docs/guides/getting-started#_2-adding-sources")
                        },
                    )
                } else {
                    emptyList()
                },
            )
        }
        // When the tabbed pager is the target surface (pref tabbed, >1 category, not flattened or
        // in sub-class picker), the per-tab pager adapters own the data; mAdapter (continuous) MUST
        // stay empty so its hidden recycler can't leak categories+headers through during conductor's
        // hardware-accelerated push animation. Gate on the PREFERENCE-derived target, not the live
        // `libraryPager.isVisible` flag: an update interleaved with a resume/tab-activate could land
        // while the pager's visibility is mid-flip, and the live flag would momentarily read false,
        // populating + showing the continuous recycler in tabbed mode — that was the lib-flash.
        // targetDisplaySurface already folds in single-category, flatten-on-search, and sub-class.
        adapter.setItems(if (targetDisplaySurface == DisplaySurface.TABBED) emptyList() else mangaMap)
        if (binding.libraryGridRecycler.recycler.translationX != 0f) {
            val time = binding.root.resources.getInteger(
                AR.integer.config_shortAnimTime,
            ).toLong()
            viewScope.launchUI {
                delay(time / 2)
                binding.libraryGridRecycler.recycler.translationX = 0f
            }
        }
        singleCategory = presenter.categories.size <= 1

        binding.progress.isVisible = false
        (activity as? MainActivity)?.releaseSplash()

        if (!freshStart) {
            justStarted = false
        } // else binding.recyclerLayout.alpha = 1f
        if (binding.recyclerLayout.alpha == 0f) {
            binding.recyclerLayout.animate().alpha(1f).setDuration(500).start()
        }
        if (justStarted && freshStart && !isSubClass) {
            val activeC = activeCategory
            scrollToHeader(activeCategory)
            binding.libraryGridRecycler.recycler.post {
                if (isControllerVisible) {
                    appBar()?.y = 0f
                    appBar()?.updateAppBarAfterY(binding.libraryGridRecycler.recycler)
                    if (activeC > 0) {
                        appBar()?.useSearchToolbarForMenu(true)
                    }
                }
            }

            if (binding.libraryGridRecycler.recycler.manager is StaggeredGridLayoutManager && isControllerVisible) {
                staggeredObserver = ViewTreeObserver.OnGlobalLayoutListener {
                    binding.libraryGridRecycler.recycler.postOnAnimation {
                        if (!isControllerVisible) return@postOnAnimation
                        scrollToHeader(activeC, false)
                        appBar()?.y = 0f
                        appBar()?.updateAppBarAfterY(binding.libraryGridRecycler.recycler)
                        if (activeC > 0) {
                            appBar()?.useSearchToolbarForMenu(true)
                        }
                    }
                }
                binding.libraryGridRecycler.recycler.viewTreeObserver.addOnGlobalLayoutListener(staggeredObserver)
                viewScope.launchUI {
                    delay(500)
                    removeStaggeredObserver()
                    if (!isControllerVisible) return@launchUI
                    if (activeC > 0) {
                        appBar()?.useSearchToolbarForMenu(true)
                    }
                }
            }
        }
        if (isControllerVisible) {
            appBar()?.lockYPos = false
        }
        binding.libraryGridRecycler.recycler.post {
            elevateAppBar(binding.libraryGridRecycler.recycler.canScrollVertically(-1))
            setActiveCategory()
        }

        binding.categoryHopperFrame.isVisible = !singleCategory && !preferences.hideHopper().get() && !isTabbedMode
        if (isTabbedMode) {
            val visibleCats = visibleTabCategories()
            // Compare ids so reorder/add/remove forces a full view-tree rebuild — refreshAll
            // does NOT update pagerAdapter.categories, so its captured indices would map to the
            // wrong categories after a reorder.
            val currentCats = pagerAdapter?.categories.orEmpty()
            val categoriesChanged = currentCats.map { it.id } != visibleCats.map { it.id }
            if (visibleCats.size <= 1 || presenter.forceShowAllCategories || categoriesChanged) {
                // Force the tree to reapply: a category reorder/add/remove keeps the surface TABBED,
                // so the surface tracker alone would skip the pager rebind it needs.
                reconcileDisplaySurface(forceRebuild = true)
            } else {
                pagerAdapter?.refreshAll()
                // Refresh the tab strip so labels + count badges reflect the new data.
                refreshTabStrip()
            }
        }
        adapter.isLongPressDragEnabled = canDrag()
        binding.categoryRecycler.setCategories(
            presenter.categories,
            if (adapter.showNumber) {
                adapter.itemsPerCategory
            } else {
                emptyMap()
            },
        )
        with(binding.filterBottomSheet.root) {
            viewScope.launch {
                checkForManhwa(presenter.sourceManager)
            }
            updateGroupTypeButton(presenter.groupType)
            setExpandText(canCollapseOrExpandCategory())
        }
        if (shouldScrollToTop) {
            binding.libraryGridRecycler.recycler.scrollToPosition(0)
            shouldScrollToTop = false
        }
        if (isControllerVisible) {
            binding.headerTitle.setOnClickListener {
                val recycler = binding.libraryGridRecycler.recycler
                if (!singleCategory) {
                    showCategories(recycler.translationY == 0f)
                }
            }
            if (!hasMovedHopper && isAnimatingHopper == null) {
                showSlideAnimation()
            }
            setSubtitle()
            showMiniBar()
        }
        updateHopperAlpha()
        val isSingleCategory = !presenter.showAllCategories && !presenter.forceShowAllCategories
        val context = binding.roundedCategoryHopper.root.context
        binding.roundedCategoryHopper.upCategory.setImageDrawable(
            context.contextCompatDrawable(
                if (isSingleCategory) {
                    R.drawable.ic_arrow_start_24dp
                } else {
                    R.drawable.ic_expand_less_24dp
                },
            ),
        )
        binding.roundedCategoryHopper.downCategory.setImageDrawable(
            context.contextCompatDrawable(
                if (isSingleCategory) {
                    R.drawable.ic_arrow_end_24dp
                } else {
                    R.drawable.ic_expand_more_24dp
                },
            ),
        )
        binding.roundedCategoryHopper.categoryButton.setImageDrawable(
            context.contextCompatDrawable(
                LibraryGroup.groupTypeDrawableRes(presenter.groupType),
            ),
        )
    }

    private fun showSlideAnimation() {
        isAnimatingHopper = true
        val slide = 25f.dpToPx
        val animatorSet = AnimatorSet()
        this.animatorSet = animatorSet
        val animations = listOf(
            slideAnimation(0f, slide, 200),
            slideAnimation(slide, -slide),
            slideAnimation(-slide, slide),
            slideAnimation(slide, -slide),
            slideAnimation(-slide, 0f, 233),
        )
        animatorSet.playSequentially(animations)
        animatorSet.startDelay = 1250
        animatorSet.doOnEnd {
            binding.categoryHopperFrame.translationX = 0f
            isAnimatingHopper = false
            this.animatorSet = null
        }
        animatorSet.start()
    }

    private fun slideAnimation(from: Float, to: Float, duration: Long = 400): ObjectAnimator {
        return ObjectAnimator.ofFloat(binding.categoryHopperFrame, View.TRANSLATION_X, from, to)
            .setDuration(duration)
    }

    open fun showCategories(show: Boolean, closeSearch: Boolean = false, category: Int = -1) {
        binding.recyclerCover.isClickable = show
        binding.recyclerCover.isFocusable = show
        // Operate on this controller's own local appBar.
        val localAppBar = binding.appBar
        if (localAppBar != null) {
            (activity as? MainActivity)?.reEnableBackPressedCallBack()
            if (show && !localAppBar.compactSearchMode && localAppBar.useLargeToolbar) {
                localAppBar.compactSearchMode = localAppBar.useLargeToolbar && show
                if (localAppBar.compactSearchMode) {
                    mainRecycler.requestApplyInsets()
                    localAppBar.y = 0f
                    localAppBar.updateAppBarAfterY(mainRecycler)
                }
            } else if (!show && localAppBar.compactSearchMode && localAppBar.useLargeToolbar &&
                (resources?.configuration?.screenHeightDp ?: 0) >= 600
            ) {
                localAppBar.compactSearchMode = false
                mainRecycler.requestApplyInsets()
            }
        }
        if (closeSearch) {
            searchToolbar()?.searchItem?.collapseActionView()
        }
        val full = binding.categoryRecycler.height.toFloat() + binding.categoryRecycler.marginTop
        val translateY = if (show) full else 0f
        binding.libraryGridRecycler.recycler.animate().translationY(translateY).apply {
            setUpdateListener {
                appBar()?.updateAppBarAfterY(binding.libraryGridRecycler.recycler)
                updateHopperY()
            }
        }.start()
        // In tabbed mode the libraryGridRecycler is hidden; the visible content is the
        // libraryPager. Without translating the pager too, the all-categories overlay
        // would be invisible (Z-order: pager sits on top of category_recycler in the
        // FrameLayout) AND its scroll wouldn't work because the pager would intercept
        // touches across the whole screen. Animate both — only the visible one shifts
        // visually but updating both keeps the two layouts consistent.
        if (binding.libraryPager.isVisible) {
            binding.libraryPager.animate().translationY(translateY).start()
        }
        binding.recyclerShadow.animate().translationY(translateY - 8.dpToPx).start()
        binding.recyclerCover.animate().translationY(translateY).start()
        binding.recyclerCover.animate().alpha(if (show) 0.75f else 0f).start()
        appBar()?.updateAppBarAfterY(binding.libraryGridRecycler.recycler)
        binding.swipeRefresh.isEnabled = !show
        setSubtitle()
        binding.categoryRecycler.isInvisible = !show
        if (show) {
            // Pull the category overlay above the pager / grid recycler / cover so its
            // child views actually receive scroll/click touches. bringToFront only
            // mutates Z-order within the FrameLayout, so the appbar still sits above.
            binding.categoryRecycler.bringToFront()
            binding.recyclerCover.bringToFront()
            binding.categoryRecycler.post {
                binding.categoryRecycler.scrollToCategory(activeCategory)
            }
            binding.fastScroller.hideScrollbar()
            elevateAppBar(false)
            binding.filterBottomSheet.filterBottomSheet.sheetBehavior?.hide()
        } else {
            val notAtTop = binding.libraryGridRecycler.recycler.canScrollVertically(-1)
            elevateAppBar((notAtTop || category > 0) && category != 0)
        }
    }

    fun scrollToCategory(category: Category?) {
        if (category != null && activeCategory != category.order) {
            scrollToHeader(category.order)
        }
    }

    private fun scrollToHeader(pos: Int, removeObserver: Boolean = true) {
        if (removeObserver) {
            removeStaggeredObserver()
        }
        if (!presenter.showAllCategories) {
            shouldScrollToTop = true
            presenter.switchSection(pos)
            activeCategory = pos
            setActiveCategory()
            return
        }
        val headerPosition = mAdapter?.indexOf(pos) ?: return
        if (headerPosition > -1) {
            val activityBinding = activityBinding ?: return
            val index = adapter.headerItems.indexOf(adapter.getItem(headerPosition))
            val appbarOffset = if (index <= 0) 0 else -fullAppBarHeight!! + activityBinding.cardFrame.height
            val previousHeader = adapter.headerItems.getOrNull(index - 1) as? LibraryHeaderItem
            binding.libraryGridRecycler.recycler.scrollToPositionWithOffset(
                headerPosition,
                (
                    when {
                        headerPosition == 0 -> 0
                        previousHeader?.category?.isHidden == true -> (-3).dpToPx
                        else -> (-30).dpToPx
                    }
                    ) + appbarOffset,
            )
            (adapter.getItem(headerPosition) as? LibraryHeaderItem)?.category?.let {
                saveActiveCategory(it)
            }
            activeCategory = pos
            if (!isSubClass) {
                preferences.lastUsedCategory().set(pos)
            }
            binding.libraryGridRecycler.recycler.post {
                if (isControllerVisible) {
                    appBar()!!.y = 0f
                    appBar()!!.updateAppBarAfterY(binding.libraryGridRecycler.recycler)
                }
            }
        }
    }

    private fun onRefresh() {
        showCategories(false)
        presenter.updateLibrary()
        destroyActionModeIfNeeded()
    }

    /**
     * Called when a filter is changed.
     */
    private fun onFilterChanged() {
        presenter.requestFilterUpdate()
        destroyActionModeIfNeeded()
    }

    private fun reattachAdapter() {
        libraryLayout = preferences.libraryLayout().get()
        setRecyclerLayout()
        val position = binding.libraryGridRecycler.recycler.findFirstVisibleItemPosition()
        binding.libraryGridRecycler.recycler.adapter = adapter
        binding.libraryGridRecycler.recycler.scrollToPositionWithOffset(position, 0)
        if (isTabbedMode) pagerAdapter?.reattachAll()
    }

    fun search(query: String?): Boolean {
        val q = query ?: ""
        val singleMode = isInSingleCategoryMode
        val previous = this.query
        this.query = q

        // forceShowAllCategories transitions only fire on the blank ↔ non-blank edge.
        if (q.isNotBlank() && previous.isBlank() && singleMode) {
            presenter.forceShowAllCategories = if (isTabbedMode) {
                preferences.librarySearchAcrossTabs().get()
            } else {
                preferences.showAllCategoriesWhenSearchingSingleCategory().get()
            }
            presenter.updateLibrary()
        } else if (q.isBlank() && previous.isNotBlank() && singleMode) {
            if (!isSubClass && !isTabbedMode) {
                preferences.showAllCategoriesWhenSearchingSingleCategory()
                    .set(presenter.forceShowAllCategories)
            }
            presenter.forceShowAllCategories = false
            presenter.updateLibrary()
        }

        // Set the filter on every live adapter BEFORE any code path that can launch a
        // filter coroutine (applyTabbedSearchVisibility → setItems → adapter.launchFilter).
        // In tabbed mode without flatten the visible adapters are the per-tab page adapters
        // (pagerAdapter), not mAdapter — only writing to mAdapter would leave the visible
        // tabs unfiltered and make search appear to do nothing.
        forEachLibraryAdapter { a, _ -> a.setFilter(q) }
        if (q.isNotBlank()) {
            searchItem.string = q
            if (adapter.scrollableHeaders.isEmpty() && !isSubClass) {
                adapter.addScrollableHeader(searchItem)
            }
        } else if (adapter.scrollableHeaders.isNotEmpty()) {
            adapter.removeAllScrollableHeaders()
        }
        showAllCategoriesView?.isGone =
            isTabbedMode || preferences.showAllCategories().get() || presenter.groupType != BY_DEFAULT || q.isBlank()
        showAllCategoriesView?.isSelected = presenter.forceShowAllCategories

        if (q != previous && q.isNotBlank()) {
            binding.libraryGridRecycler.recycler.scrollToPosition(0)
        }

        // Defer the view-tree switch to the next frame so onQueryTextChange returns first
        // and any in-flight FlexibleAdapter layout settles before applyTabbedViewTree's
        // setItems(emptyList()) fires. Without this, a fast clear-and-collapse on back
        // could land an updateDataSet mid-layout → RecyclerView "Inconsistency detected".
        if (isTabbedMode) view?.post { applyTabbedSearchVisibility() }

        if (presenter.currentLibraryItems.isNotEmpty()) {
            forEachLibraryAdapter { a, _ -> a.requestFilter() }
        }
        return true
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onDestroyActionMode(mode: ActionMode?) {
        selectedMangas.clear()
        actionMode = null
        lastClickPosition = -1
        forEachLibraryAdapter { ad, _ ->
            ad.mode = SelectableAdapter.Mode.SINGLE
            ad.clearSelection()
            ad.notifyDataSetChanged()
            ad.isLongPressDragEnabled = canDrag()
        }
    }

    private fun setSelection(manga: Manga, selected: Boolean) {
        val activeAdapter = currentLibraryAdapter ?: return
        val previousMode = activeAdapter.mode
        val changed = if (selected) selectedMangas.add(manga) else selectedMangas.remove(manga)
        if (!changed) return

        if (!selected) lastClickPosition = -1
        val targetMode = if (selectedMangas.isEmpty()) {
            SelectableAdapter.Mode.SINGLE
        } else {
            SelectableAdapter.Mode.MULTI
        }
        forEachLibraryAdapter { ad, recycler ->
            if (ad.mode != targetMode) ad.mode = targetMode
            ad.allIndexOf(manga).forEach { position ->
                if (selected) ad.addSelection(position) else ad.removeSelection(position)
                (recycler.findViewHolderForAdapterPosition(position) as? LibraryHolder)?.toggleActivation()
            }
        }
        if (selected) {
            launchUI {
                delay(100)
                forEachLibraryAdapter { ad, _ -> ad.isLongPressDragEnabled = false }
            }
        } else if (selectedMangas.isEmpty()) {
            forEachLibraryAdapter { ad, _ -> ad.isLongPressDragEnabled = canDrag() }
        }
        updateHeaders(previousMode != activeAdapter.mode)
    }

    private fun updateHeaders(changedMode: Boolean = false) {
        val headerPositions = adapter.getHeaderPositions()
        headerPositions.forEach {
            if (changedMode) {
                adapter.notifyItemChanged(it)
            } else {
                (binding.libraryGridRecycler.recycler.findViewHolderForAdapterPosition(it) as? LibraryHeaderHolder)?.setSelection()
            }
        }
    }

    override fun startReading(position: Int, view: View?) {
        val activeAdapter = currentLibraryAdapter ?: return
        if (activeAdapter.mode == SelectableAdapter.Mode.MULTI) {
            toggleSelection(position)
            return
        }
        val manga = (activeAdapter.getItem(position) as? LibraryMangaItem)?.manga?.manga ?: return
        val activity = activity ?: return
        viewScope.launch {
            val chapter = presenter.getFirstUnread(manga) ?: return@launch
            activity.apply {
                if (view != null) {
                    val (intent, bundle) = ReaderActivity
                        .newIntentWithTransitionOptions(activity, manga, chapter, view)
                    startActivity(intent, bundle)
                } else {
                    startActivity(ReaderActivity.newIntent(activity, manga, chapter))
                }
            }
        }
        destroyActionModeIfNeeded()
    }

    private fun toggleSelection(position: Int) {
        val activeAdapter = currentLibraryAdapter ?: return
        val item = activeAdapter.getItem(position) as? LibraryMangaItem ?: return
        setSelection(item.manga.manga, !activeAdapter.isSelected(position))
        invalidateActionMode()
    }

    override fun canDrag(): Boolean {
        val filterOff = !hasActiveFilters && presenter.groupType == BY_DEFAULT
        if (isTabbedMode) return false
        return filterOff && (currentLibraryAdapter?.mode ?: SelectableAdapter.Mode.SINGLE) != SelectableAdapter.Mode.MULTI
    }

    /**
     * Called when a manga is clicked.
     *
     * @param position the position of the element clicked.
     * @return true if the item should be selected, false otherwise.
     */
    override fun onItemClick(view: View?, position: Int): Boolean {
        val activeAdapter = currentLibraryAdapter ?: return false
        val item = activeAdapter.getItem(position) as? LibraryMangaItem ?: return false
        return if (activeAdapter.mode == SelectableAdapter.Mode.MULTI) {
            snack?.dismiss()
            lastClickPosition = position
            toggleSelection(position)
            false
        } else {
            openManga(item.manga.manga)
            false
        }
    }

    private fun saveStaggeredState() {
        if (binding.libraryGridRecycler.recycler.manager is StaggeredGridLayoutManager) {
            staggeredBundle = binding.libraryGridRecycler.recycler.manager.onSaveInstanceState()
        }
    }

    private fun openManga(manga: Manga) {
        router.pushController(MangaDetailsController(manga).withFadeTransaction())
    }

    /**
     * Called when a manga is long clicked.
     *
     * @param position the position of the element clicked.
     */
    override fun onItemLongClick(position: Int) {
        val activeAdapter = currentLibraryAdapter ?: return
        val item = activeAdapter.getItem(position)
        if (item !is LibraryMangaItem) return
        snack?.dismiss()
        if (libraryLayout == LibraryItem.LAYOUT_COVER_ONLY_GRID && actionMode == null) {
            snack = view?.snack(item.manga.manga.title) {
                anchorView = activityBinding?.bottomNav
                view.elevation = 15f.dpToPx
            }
        }
        createActionModeIfNeeded()
        when {
            lastClickPosition == -1 -> setSelection(position)
            lastClickPosition > position -> for (i in position until lastClickPosition) setSelection(
                i,
            )
            lastClickPosition < position -> for (i in lastClickPosition + 1..position) setSelection(
                i,
            )
            else -> setSelection(position)
        }
        lastClickPosition = position
    }

    override fun globalSearch(query: String) {
        router.pushController(GlobalSearchController(query).withFadeTransaction())
    }

    override fun onActionStateChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        val position = viewHolder?.bindingAdapterPosition ?: return
        binding.swipeRefresh.isEnabled = actionState != ItemTouchHelper.ACTION_STATE_DRAG
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            if (lastItemPosition != null &&
                position != lastItemPosition &&
                lastItem == adapter.getItem(position)
            ) {
                // because for whatever reason you can repeatedly tap on a currently dragging manga
                adapter.removeSelection(position)
                (binding.libraryGridRecycler.recycler.findViewHolderForAdapterPosition(position) as? LibraryHolder)?.toggleActivation()
                adapter.moveItem(position, lastItemPosition!!)
            } else {
                isDragging = true
                lastItem = adapter.getItem(position)
                lastItemPosition = position
                onItemLongClick(position)
            }
        }
    }

    private fun onUpdateManga(mangaId: Long?) {
        if (mangaId == LibraryUpdateJob.STARTING_UPDATE_SOURCE) return
        if (mangaId == null) {
            adapter.getHeaderPositions().forEach { adapter.notifyItemChanged(it) }
        } else {
            presenter.updateLibrary()
        }
    }

    private fun setSelection(position: Int, selected: Boolean = true) {
        val activeAdapter = currentLibraryAdapter ?: return
        val item = activeAdapter.getItem(position) as? LibraryMangaItem ?: return

        setSelection(item.manga.manga, selected)
        invalidateActionMode()
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int) {
        // Because padding a recycler causes it to scroll up we have to scroll it back down... wild
        val fromItem = adapter.getItem(fromPosition)
        val toItem = adapter.getItem(toPosition)
        if (binding.libraryGridRecycler.recycler.layoutManager !is StaggeredGridLayoutManager && (
            (fromItem is LibraryItem && toItem is LibraryItem) || fromItem == null
            )
        ) {
            binding.libraryGridRecycler.recycler.scrollBy(
                0,
                binding.libraryGridRecycler.recycler.paddingTop,
            )
        }
        if (lastItemPosition == toPosition) {
            lastItemPosition = null
        } else if (lastItemPosition == null) lastItemPosition = fromPosition
    }

    override fun shouldMoveItem(fromPosition: Int, toPosition: Int): Boolean {
        if (adapter.isSelected(fromPosition)) toggleSelection(fromPosition)
        val item = adapter.getItem(fromPosition) as? LibraryMangaItem ?: return false
        val newHeader = adapter.getSectionHeader(toPosition) as? LibraryHeaderItem
        if (toPosition < 1) return false
        return (adapter.getItem(toPosition) !is LibraryHeaderItem) && (
            newHeader?.category?.id == item.manga.category || !presenter.mangaIsInCategory(
                item.manga,
                newHeader?.category?.id,
            )
            )
    }

    override fun onItemReleased(position: Int) {
        lastItem = null
        isDragging = false
        binding.swipeRefresh.isEnabled = true
        if (mAdapter == null || adapter.selectedItemCount > 0) {
            lastItemPosition = null
            return
        }
        destroyActionModeIfNeeded()
        // if nothing moved
        if (lastItemPosition == null) return
        val item = adapter.getItem(position) as? LibraryMangaItem ?: return
        val newHeader = adapter.getSectionHeader(position) as? LibraryHeaderItem
        val libraryItems = getSectionItems(adapter.getSectionHeader(position), item)
            .filterIsInstance<LibraryMangaItem>()
        val mangaIds = libraryItems.mapNotNull { (it as? LibraryMangaItem)?.manga?.manga?.id }
        if (newHeader?.category?.id == item.manga.category) {
            presenter.rearrangeCategory(item.manga.category, mangaIds)
        } else {
            if (presenter.mangaIsInCategory(item.manga, newHeader?.category?.id)) {
                adapter.moveItem(position, lastItemPosition!!)
                snack = view?.snack(MR.strings.already_in_category) {
                    anchorView = anchorView()
                    view.elevation = 15f.dpToPx
                }
                return
            }
            if (newHeader?.category != null) {
                moveMangaToCategory(
                    item.manga,
                    newHeader.category,
                    mangaIds,
                )
            }
        }
        lastItemPosition = null
    }

    private fun getSectionItems(header: IHeader<*>, skipItem: ISectionable<*, *>): List<ISectionable<*, *>> {
        val sectionItems: MutableList<ISectionable<*, *>> = ArrayList()
        var startPosition: Int = adapter.getGlobalPositionOf(header)
        var item = adapter.getItem(++startPosition) as? LibraryItem
        while (item?.header == header || item == skipItem) {
            sectionItems.add(item as ISectionable<*, *>)
            item = adapter.getItem(++startPosition) as? LibraryItem
        }
        return sectionItems
    }

    private fun moveMangaToCategory(
        manga: LibraryManga,
        category: Category?,
        mangaIds: List<Long>,
    ) {
        if (category?.id == null) return
        val oldCatId = manga.category
        presenter.moveMangaToCategory(manga, category.id, mangaIds)
        snack?.dismiss()
        snack = view?.snack(
            view!!.context!!.getString(MR.strings.moved_to_, category.name),
        ) {
            anchorView = anchorView()
            view.elevation = 15f.dpToPx
            setAction(MR.strings.undo) {
                manga.category = category.id!!
                presenter.moveMangaToCategory(manga, oldCatId, mangaIds)
            }
        }
    }

    override fun updateCategory(position: Int): Boolean {
        val category = (adapter.getItem(position) as? LibraryHeaderItem)?.category ?: return false
        val inQueue = LibraryUpdateJob.categoryInQueue(category.id)
        snack?.dismiss()
        snack = view?.snack(
            view!!.context!!.getString(
                when {
                    inQueue -> MR.strings._already_in_queue
                    LibraryUpdateJob.isRunning(view!!.context) -> MR.strings.adding_category_to_queue
                    else -> MR.strings.updating_
                },
                category.name,
            ),
            Snackbar.LENGTH_LONG,
        ) {
            anchorView = anchorView()
            view.elevation = 15f.dpToPx
            setAction(MR.strings.cancel) {
                LibraryUpdateJob.stop(context)
                viewScope.launchUI {
                    NotificationReceiver.dismissNotification(
                        context,
                        Notifications.ID_LIBRARY_PROGRESS,
                    )
                }
            }
        }
        if (!inQueue) {
            LibraryUpdateJob.startNow(
                view!!.context,
                category,
                mangaToUse = if (category.isDynamic) {
                    presenter.getMangaInCategories(category.id)
                } else {
                    null
                },
            )
        }
        return true
    }

    override fun toggleCategoryVisibility(position: Int) {
        if (!presenter.showAllCategories) {
            showCategories(true)
            return
        }
        val catId = (adapter.getItem(position) as? LibraryHeaderItem)?.category?.id ?: return
        presenter.toggleCategoryVisibility(catId)
    }

    /**
     * Nullable Boolean to tell is all is collapsed/expanded/applicable
     * true = all categories are expanded
     * false = all or some categories are collapsed
     * null = is in single category mode
     */
    fun canCollapseOrExpandCategory(): Boolean? {
        if (singleCategory || !presenter.showAllCategories || isSubClass) {
            return null
        }
        return presenter.allCategoriesExpanded()
    }

    override fun manageCategory(position: Int) {
        val category = (adapter.getItem(position) as? LibraryHeaderItem)?.category ?: return
        if (!category.isDynamic) {
            ManageCategoryDialog(category) {
                presenter.updateLibrary()
            }.showDialog(router)
        }
    }

    override fun sortCategory(catId: Int, sortBy: Char) {
        val category = presenter.categories.find { it.id == catId }
        if (category?.isDynamic == false && sortBy == LibrarySort.DragAndDrop.categoryValue) {
            val item = adapter.findCategoryHeader(catId) ?: return
            val libraryItems = adapter.getSectionItems(item)
                .filterIsInstance<LibraryMangaItem>()
            val mangaIds = libraryItems.mapNotNull { (it as? LibraryMangaItem)?.manga?.manga?.id }
            presenter.rearrangeCategory(catId, mangaIds)
        } else {
            presenter.sortCategory(catId, sortBy)
        }
    }

    override fun selectAll(position: Int) {
        val header = adapter.getSectionHeader(position) ?: return
        val items = adapter.getSectionItemPositions(header)
        val allSelected = allSelected(position)
        for (i in items) setSelection(i, !allSelected)
    }

    override fun allSelected(position: Int): Boolean {
        val header = adapter.getSectionHeader(position) ?: return false
        val items = adapter.getSectionItemPositions(header)
        return items.all { adapter.isSelected(it) }
    }

    //region sheet methods
    override fun showSheet() {
        closeTip()
        val sheetBehavior = binding.filterBottomSheet.filterBottomSheet.sheetBehavior
        when {
            sheetBehavior.isHidden() -> sheetBehavior?.collapse()
            !sheetBehavior.isExpanded() -> sheetBehavior?.expand()
            else -> showDisplayOptions()
        }
    }

    override fun hideSheet() {
        val sheetBehavior = binding.filterBottomSheet.filterBottomSheet.sheetBehavior
        when {
            sheetBehavior.isExpanded() -> sheetBehavior?.collapse()
            !sheetBehavior.isHidden() -> binding.filterBottomSheet.filterBottomSheet.sheetBehavior?.hide()
        }
    }

    override fun toggleSheet() {
        closeTip()
        when {
            binding.filterBottomSheet.filterBottomSheet.sheetBehavior.isHidden() -> binding.filterBottomSheet.filterBottomSheet.sheetBehavior?.collapse()
            !binding.filterBottomSheet.filterBottomSheet.sheetBehavior.isExpanded() -> binding.filterBottomSheet.filterBottomSheet.sheetBehavior?.expand()
            else -> binding.filterBottomSheet.filterBottomSheet.sheetBehavior?.hide()
        }
    }

    override fun canStillGoBack(): Boolean {
        return isBindingInitialized && (
            binding.recyclerCover.isClickable ||
                binding.filterBottomSheet.filterBottomSheet.sheetBehavior.isExpanded()
            )
    }

    override fun handleBack(): Boolean {
        if (binding.recyclerCover.isClickable) {
            showCategories(false)
            return true
        }
        if (binding.filterBottomSheet.filterBottomSheet.sheetBehavior.isExpanded()) {
            binding.filterBottomSheet.filterBottomSheet.sheetBehavior?.collapse()
            return true
        }
        return false
    }
    //endregion

    //region Toolbar options methods
    override fun onActionViewExpand(item: MenuItem?) {
        if (!isTabbedMode && !binding.recyclerCover.isClickable && query.isBlank() &&
            !singleCategory && presenter.showAllCategories
        ) {
            showCategories(true)
        }
    }

    override fun onActionViewCollapse(item: MenuItem?) {
        if (binding.recyclerCover.isClickable) {
            showCategories(false)
        }
    }

    override fun onSearchActionViewLongClickQuery(): String? {
        if (preferences.showLibrarySearchSuggestions().get()) {
            val suggestion = preferences.librarySearchSuggestion().get().takeIf { it.isNotBlank() }
            return suggestion?.removeSuffix("…")
        }
        return null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> expandActionViewFromInteraction = true
            R.id.action_filter -> {
                hasExpanded = true
                val sheetBehavior = binding.filterBottomSheet.filterBottomSheet.sheetBehavior
                if (!sheetBehavior.isExpanded() && !sheetBehavior.isSettling()) {
                    sheetBehavior?.expand()
                } else {
                    showDisplayOptions()
                }
            }
            R.id.action_more -> {
                val activity = activity as? MainActivity
                    ?: return super.onOptionsItemSelected(item)
                activity.showOverflowDialog(
                    showUpdateLibrary = true,
                    onUpdateLibrary = {
                        if (!LibraryUpdateJob.isRunning(activity)) {
                            updateLibrary()
                            destroyActionModeIfNeeded()
                        }
                    },
                )
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
    //endregion

    //region Action Mode Methods
    /**
     * Creates the action mode if it's not created already.
     */
    private fun createActionModeIfNeeded() {
        if (actionMode == null) {
            actionMode = (activity as AppCompatActivity).startSupportActionMode(this)
            val view = activity?.window?.currentFocus ?: return
            val imm =
                activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    ?: return
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    fun showCategoriesController() {
        router.pushController(CategoryController().withFadeTransaction())
        displaySheet?.dismiss()
    }

    /**
     * Destroys the action mode.
     */
    private fun destroyActionModeIfNeeded() {
        actionMode?.finish()
    }

    /**
     * Invalidates the action mode, forcing it to refresh its content.
     */
    private fun invalidateActionMode() {
        actionMode?.invalidate()
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.library_selection, menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val count = selectedMangas.size
        // Destroy action mode if there are no items selected.
        val migrationItem = menu.findItem(R.id.action_migrate)
        val shareItem = menu.findItem(R.id.action_share)
        val categoryItem = menu.findItem(R.id.action_move_to_category)
        categoryItem.isVisible = presenter.isCategoryMoreThanOne()
        migrationItem.isVisible = selectedMangas.any { it.source != LocalSource.ID }
        shareItem.isVisible = migrationItem.isVisible
        if (count == 0) {
            destroyActionModeIfNeeded()
        } else {
            mode.title = view?.context?.getString(MR.strings.selected_, count)
        }
        return false
    }
    //endregion

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_move_to_category -> showChangeMangaCategoriesSheet()
            R.id.action_share -> shareManga()
            R.id.action_delete -> {
                val options = arrayOf(
                    MR.strings.remove_downloads,
                    MR.strings.remove_from_library,
                )
                    .map { activity!!.getString(it) }
                activity!!.materialAlertDialog()
                    .setTitle(MR.strings.remove)
                    .setMultiChoiceItems(
                        options.toTypedArray(),
                        options.map { true }.toBooleanArray(),
                    ) { dialog, position, _ ->
                        if (position == 0) {
                            val listView = (dialog as AlertDialog).listView
                            listView.setItemChecked(position, true)
                        }
                    }
                    .setPositiveButton(MR.strings.remove) { dialog, _ ->
                        val listView = (dialog as AlertDialog).listView
                        if (listView.isItemChecked(1)) {
                            deleteMangasFromLibrary()
                        } else {
                            val mangas = selectedMangas.toList()
                            presenter.confirmDeletion(mangas, false)
                        }
                    }
                    .setNegativeButton(AR.string.cancel, null)
                    .show().apply {
                        disableItems(arrayOf(options.first()))
                    }
            }
            R.id.action_download_unread -> {
                presenter.downloadUnread(selectedMangas.toList())
            }
            R.id.action_mark_as_read -> {
                activity!!.materialAlertDialog()
                    .setMessage(MR.strings.mark_all_chapters_as_read)
                    .setPositiveButton(MR.strings.mark_as_read) { _, _ ->
                        markReadStatus(MR.strings.marked_as_read, true)
                    }
                    .setNegativeButton(AR.string.cancel, null)
                    .show()
            }
            R.id.action_mark_as_unread -> {
                activity!!.materialAlertDialog()
                    .setMessage(MR.strings.mark_all_chapters_as_unread)
                    .setPositiveButton(MR.strings.mark_as_unread) { _, _ ->
                        markReadStatus(MR.strings.marked_as_unread, false)
                    }
                    .setNegativeButton(AR.string.cancel, null)
                    .show()
            }
            R.id.action_migrate -> {
                val skipPre = preferences.skipPreMigration().get()
                PreMigrationController.navigateToMigration(
                    skipPre,
                    router,
                    selectedMangas.filter { !it.isLocal() }.mapNotNull { it.id },
                )
                destroyActionModeIfNeeded()
            }
            R.id.action_select_all -> selectAllInActiveCategory()
            else -> return false
        }
        return true
    }

    private fun selectAllInActiveCategory() {
        if (isTabbedMode) {
            val pageAdapter = currentLibraryAdapter ?: return
            val mangas = (0 until pageAdapter.itemCount).mapNotNull {
                (pageAdapter.getItem(it) as? LibraryMangaItem)?.manga?.manga
            }
            if (mangas.isEmpty()) return
            val allAlready = mangas.all { it in selectedMangas }
            mangas.forEach { setSelection(it, !allAlready) }
            invalidateActionMode()
        } else {
            val headerPos = mAdapter?.indexOf(activeCategory) ?: -1
            if (headerPos >= 0) selectAll(headerPos)
        }
    }

    private fun markReadStatus(resource: StringResource, markRead: Boolean) {
        val mapMangaChapters = presenter.markReadStatus(selectedMangas.toList(), markRead)
        destroyActionModeIfNeeded()
        snack?.dismiss()
        snack = view?.snack(resource, Snackbar.LENGTH_INDEFINITE) {
            anchorView = anchorView()
            view.elevation = 15f.dpToPx
            var undoing = false
            setAction(MR.strings.undo) {
                presenter.undoMarkReadStatus(mapMangaChapters)
                undoing = true
            }
            addCallback(
                object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onDismissed(
                        transientBottomBar: Snackbar?,
                        event: Int,
                    ) {
                        super.onDismissed(transientBottomBar, event)
                        if (!undoing) {
                            presenter.confirmMarkReadStatus(
                                mapMangaChapters,
                                markRead,
                            )
                        }
                    }
                },
            )
        }
        (activity as? MainActivity)?.setUndoSnackBar(snack)
    }

    private fun shareManga() {
        val context = view?.context ?: return
        val mangas = selectedMangas.toList()
        val urlList = presenter.getMangaUrls(mangas)
        if (urlList.isEmpty()) return
        val urls = presenter.getMangaUrls(mangas).joinToString("\n")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/*"
            putExtra(Intent.EXTRA_TEXT, urls)
        }
        startActivity(Intent.createChooser(intent, context.getString(MR.strings.share)))
    }

    open fun deleteMangasFromLibrary() {
        val mangas = selectedMangas.toList()
        presenter.removeMangaFromLibrary(mangas)
        destroyActionModeIfNeeded()
        snack?.dismiss()
        snack = view?.snack(
            activity?.getString(MR.strings.removed_from_library) ?: "",
            Snackbar.LENGTH_INDEFINITE,
        ) {
            anchorView = anchorView()
            view.elevation = 15f.dpToPx
            var undoing = false
            setAction(MR.strings.undo) {
                presenter.reAddMangas(mangas)
                undoing = true
            }
            addCallback(
                object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        super.onDismissed(transientBottomBar, event)
                        if (!undoing) presenter.confirmDeletion(mangas)
                    }
                },
            )
        }
        (activity as? MainActivity)?.setUndoSnackBar(snack)
    }

    /**
     * Move the selected manga to a list of categories.
     */
    private fun showChangeMangaCategoriesSheet() {
        val activity = activity ?: return
        viewScope.launchIO {
            selectedMangas.toList().moveCategories(activity) {
                presenter.updateLibrary()
                destroyActionModeIfNeeded()
            }
        }
    }

    companion object {
        // Shared across LibraryController lifetimes so manga_grid_item / header holders
        // recycled on one entry are reused on the next. Survives controller destruction.
        private val persistentLibraryPool = RecyclerView.RecycledViewPool()
    }
}
