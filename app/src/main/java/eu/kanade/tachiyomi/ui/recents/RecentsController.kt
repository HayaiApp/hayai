package eu.kanade.tachiyomi.ui.recents

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import androidx.activity.BackEventCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.SearchOff
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionSet
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.SelectableAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.ChapterHistory
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.seriesType
import eu.kanade.tachiyomi.data.download.DownloadJob
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.databinding.RecentsControllerBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.base.SmallToolbarInterface
import eu.kanade.tachiyomi.ui.base.controller.BaseCoroutineController
import eu.kanade.tachiyomi.ui.main.BottomSheetController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import eu.kanade.tachiyomi.ui.main.TabbedInterface
import eu.kanade.tachiyomi.ui.base.TabItem
import eu.kanade.tachiyomi.ui.base.TabMode
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.recents.options.TabbedRecentsOptionsSheet
import eu.kanade.tachiyomi.ui.upcoming.UpcomingController
import eu.kanade.tachiyomi.ui.source.browse.ProgressItem
import eu.kanade.tachiyomi.util.chapter.updateTrackChapterMarkedAsRead
import eu.kanade.tachiyomi.util.system.addCheckBoxPrompt
import eu.kanade.tachiyomi.util.system.dpToPx
import yokai.domain.recents.models.RecentsHidden
import eu.kanade.tachiyomi.util.system.getBottomGestureInsets
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.ignoredSystemInsets
import eu.kanade.tachiyomi.util.system.isLTR
import eu.kanade.tachiyomi.util.system.isPromptChecked
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.system.setCustomTitleAndMessage
import eu.kanade.tachiyomi.util.system.spToPx
import eu.kanade.tachiyomi.util.system.toInt
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.appBar
import eu.kanade.tachiyomi.util.view.collapse
import eu.kanade.tachiyomi.util.view.dismissSafely
import eu.kanade.tachiyomi.util.view.expand
import eu.kanade.tachiyomi.util.view.fullAppBarHeight
import eu.kanade.tachiyomi.util.view.hide
import eu.kanade.tachiyomi.util.view.installLocalMenu
import eu.kanade.tachiyomi.util.view.isCollapsed
import eu.kanade.tachiyomi.util.view.isControllerVisible
import eu.kanade.tachiyomi.util.view.isExpanded
import eu.kanade.tachiyomi.util.view.isHidden
import eu.kanade.tachiyomi.util.view.moveRecyclerViewUp
import eu.kanade.tachiyomi.util.view.onAnimationsFinished
import eu.kanade.tachiyomi.util.view.scrollViewWith
import eu.kanade.tachiyomi.util.view.searchToolbar
import eu.kanade.tachiyomi.util.view.setAction
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.setPositiveButton
import eu.kanade.tachiyomi.util.view.setStyle
import eu.kanade.tachiyomi.util.view.smoothScrollToTop
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.updateGradiantBGRadius
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.widget.LinearLayoutManagerAccurateOffset
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.launch
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

/**
 * Fragment that shows recently read manga.
 * Uses R.layout.fragment_recently_read.
 * UI related actions should be called from here.
 */
class RecentsController(bundle: Bundle? = null) :
    BaseCoroutineController<RecentsControllerBinding, RecentsPresenter>(bundle),
    RecentMangaAdapter.RecentsInterface,
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    FlexibleAdapter.OnItemMoveListener,
    FlexibleAdapter.EndlessScrollListener,
    TabbedInterface,
    RootSearchInterface,
    FloatingSearchInterface,
    BottomSheetController,
    eu.kanade.tachiyomi.ui.base.LocalAppBarOwner,
    eu.kanade.tachiyomi.ui.main.RootTabContent,
    ActionMode.Callback {

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
        appBar.applyTabs(
            items = RecentsViewType.entries.map {
                TabItem.Label(activity?.getString(it.stringRes).orEmpty())
            },
            selectedIndex = presenter.viewType.mainValue,
            mode = TabMode.Fixed,
            onSelected = { idx -> setViewType(RecentsViewType.valueOf(idx)) },
            onReselected = { binding.recycler.smoothScrollToTop() },
            pagerSync = null,
        )
        appBar.y = 0f
        appBar.updateAppBarAfterY(binding.recycler)
        setupToolbarMenu()
    }

    /**
     * Install the recents overflow menu onto the local appBar (both main toolbar and
     * search pill — see [eu.kanade.tachiyomi.util.view.installLocalMenu]) and bind the
     * search-pill's query listener + placeholder text.
     */
    private fun setupToolbarMenu() {
        installLocalMenu(R.menu.recents)

        val pill = searchToolbar() ?: return
        pill.setQueryHint(view?.context?.getString(MR.strings.search_recents), !isSearching())
        if (isSearching()) {
            pill.searchItem?.expandActionView()
            pill.searchView?.setQuery(query, true)
            pill.searchView?.clearFocus()
        }
        setOnQueryTextChangeListener(pill.searchView) {
            if (query != it) {
                query = it ?: return@setOnQueryTextChangeListener false
                resetProgressItem()
                refresh()
            }
            true
        }
    }

    init {
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    /** Adapter containing the recent manga. */
    private lateinit var adapter: RecentMangaAdapter
    var displaySheet: TabbedRecentsOptionsSheet? = null

    private var progressItem: ProgressItem? = null
    override var presenter = RecentsPresenter()
    private var snack: Snackbar? = null
    private var lastChapterId: Long? = null
    private var showingDownloads = false
    private var headerHeight = 0
    private var ogRadius = 0f
    private var deviceRadius = 0f to 0f
    private var lastScale = 1f

    /**
     * Active contextual action mode toolbar, owned by the host activity. Non-null
     * iff the user is in multi-select on History/Updates.
     */
    private var actionMode: ActionMode? = null

    /**
     * Anchor for range select. Updated on every long-press; reset when
     * selection mode is destroyed or all items deselected.
     */
    private var lastClickPosition: Int = -1

    private var query = ""
        set(value) {
            field = value
            presenter.query = value
        }

    override val mainRecycler: RecyclerView
        get() = binding.recycler

    override fun getTitle(): String? {
        return view?.context?.getString(MR.strings.recents)
    }

    override fun getSearchTitle(): String? {
        return searchTitle(
            view?.context?.getString(
                when (presenter.viewType) {
                    RecentsViewType.History -> MR.strings.history
                    RecentsViewType.Updates -> MR.strings.updates
                    else -> MR.strings.updates_and_history
                },
            )?.lowercase(Locale.ROOT),
        )
    }

    override fun createBinding(inflater: LayoutInflater) =
        RecentsControllerBinding.inflate(inflater)

    /**
     * Called when view is created
     *
     * @param view created view
     */
    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        // Initialize adapter
        val isReturning = this::adapter.isInitialized
        adapter = RecentMangaAdapter(this)
        adapter.setPreferenceFlows()
        binding.recycler.adapter = adapter
        binding.recycler.layoutManager = LinearLayoutManagerAccurateOffset(view.context)
        binding.recycler.setHasFixedSize(true)
        binding.recycler.setItemViewCacheSize(8)
        binding.recycler.itemAnimator = null
        // Process-static pool so recent_manga_item holders are reused on every Recents
        // re-entry. Without it, root-nav back to Recents re-inflates rows + their
        // sub-chapter children (the dominant frame cost in perfetto).
        binding.recycler.setRecycledViewPool(persistentRecentsPool)
        persistentRecentsPool.setMaxRecycledViews(R.layout.recent_manga_item, 30)
        persistentRecentsPool.setMaxRecycledViews(R.layout.recent_chapters_section_item, 8)
        binding.recycler.addItemDecoration(RecentMangaDivider(view.context))
        binding.recycler.onAnimationsFinished {
            (activity as? MainActivity)?.releaseSplash()
        }
        adapter.isSwipeEnabled = true
        adapter.itemTouchHelperCallback.setSwipeFlags(
            if (view.resources.isLTR) ItemTouchHelper.LEFT else ItemTouchHelper.RIGHT,
        )
        binding.swipeRefresh.setStyle()
        scrollViewWith(
            binding.recycler,
            swipeRefreshLayout = binding.swipeRefresh,
            ignoreInsetVisibility = true,
            afterInsets = {
                val appBarHeight = appBar()?.attrToolbarHeight ?: 0
                val systemInsets = it.ignoredSystemInsets
                headerHeight = systemInsets.top + appBarHeight + 48.dpToPx
                binding.recycler.updatePaddingRelative(
                    bottom = activityBinding?.bottomNav?.height ?: systemInsets.bottom,
                )
                binding.downloadBottomSheet.sheetLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    height = appBarHeight + systemInsets.top
                }
                val bigToolbarHeight = fullAppBarHeight ?: 0

                binding.recentsEmptyView.updatePadding(
                    top = bigToolbarHeight + systemInsets.top,
                    bottom = activityBinding?.bottomNav?.height ?: systemInsets.bottom,
                )
                binding.progress.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = (bigToolbarHeight + systemInsets.top) / 2
                }
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

        if (!isReturning && adapter.itemCount == 0) {
            appBar()?.y = 0f
            appBar()?.updateAppBarAfterY(binding.recycler)
            appBar()?.lockYPos = true
        }
        viewScope.launchUI {
            val height =
                activityBinding?.bottomNav?.height ?: view.rootWindowInsetsCompat?.getInsets(
                    systemBars(),
                )?.bottom ?: 0
            binding.recycler.updatePaddingRelative(bottom = height)
            binding.downloadBottomSheet.dlRecycler.updatePaddingRelative(
                bottom = height,
            )
            val isExpanded = binding.downloadBottomSheet.root.sheetBehavior.isExpanded()
            binding.downloadBottomSheet.dlRecycler.alpha = isExpanded.toInt().toFloat()
            binding.downloadBottomSheet.titleText.alpha = (!isExpanded).toInt().toFloat()
            binding.downloadBottomSheet.sheetToolbar.alpha = isExpanded.toInt().toFloat()
            if (binding.downloadBottomSheet.root.sheetBehavior.isCollapsed()) {
                if (hasQueue()) {
                    binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.isHideable =
                        false
                } else {
                    binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.isHideable =
                        true
                    binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.state =
                        BottomSheetBehavior.STATE_HIDDEN
                }
            } else if (binding.downloadBottomSheet.root.sheetBehavior.isHidden()) {
                if (!hasQueue()) {
                    binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.skipCollapsed =
                        true
                } else {
                    binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.skipCollapsed =
                        false
                    binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.state =
                        BottomSheetBehavior.STATE_COLLAPSED
                }
            }
            updateTitleAndMenu()
        }

        if (presenter.recentItems.isNotEmpty()) {
            adapter.updateDataSet(presenter.recentItems)
        } else {
            binding.recentsFrameLayout.alpha = 0f
        }

        binding.downloadBottomSheet.dlBottomSheet.onCreate(this)

        binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.addBottomSheetCallback(
            object :
                BottomSheetBehavior.BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, progress: Float) {
                    val height =
                        binding.root.height - binding.downloadBottomSheet.dlRecycler.paddingTop
                    // Doing some fun math to hide the tab bar just as the title text of the
                    // dl sheet is under the toolbar
                    val cap = height * (1 / 12600f) + 479f / 700
                    binding.downloadBottomSheet.titleText.alpha = 1 - max(0f, progress / cap)
                    binding.downloadBottomSheet.sheetToolbar.alpha = max(0f, progress / cap)
                    binding.downloadBottomSheet.pill.alpha = binding.downloadBottomSheet.titleText.alpha * 0.25f
                    binding.downloadBottomSheet.dlRecycler.alpha = progress * 10
                    val oldShow = showingDownloads
                    showingDownloads = progress > 0.92f
                    if (!isControllerVisible) {
                        return
                    }
                    binding.downloadBottomSheet.root.apply {
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
                    if (isControllerVisible) {
                        appBar()?.alpha = (1 - progress * 3) + 0.5f
                    }
                    binding.downloadBottomSheet.root.updateGradiantBGRadius(
                        ogRadius,
                        deviceRadius,
                        progress,
                        binding.downloadBottomSheet.sheetLayout,
                    )
                    if (oldShow != showingDownloads) {
                        updateTitleAndMenu()
                        (activity as? MainActivity)?.reEnableBackPressedCallBack()
                    }
                }

                override fun onStateChanged(p0: View, state: Int) {
                    if (this@RecentsController.view == null) return
                    if (state == BottomSheetBehavior.STATE_EXPANDED || state == BottomSheetBehavior.STATE_COLLAPSED) {
                        showingDownloads = state == BottomSheetBehavior.STATE_EXPANDED
                        updateTitleAndMenu()
                    }

                    if (isControllerVisible) {
                        activityBinding?.tabsFrameLayout?.isVisible =
                            state != BottomSheetBehavior.STATE_EXPANDED
                    }
                    binding.downloadBottomSheet.dlBottomSheet.apply {
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
                        }
                    }

                    if (state == BottomSheetBehavior.STATE_COLLAPSED) {
                        if (hasQueue()) {
                            binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.isHideable =
                                false
                        } else {
                            binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.isHideable =
                                true
                            binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.state =
                                BottomSheetBehavior.STATE_HIDDEN
                        }
                    } else if (state == BottomSheetBehavior.STATE_HIDDEN) {
                        if (!hasQueue()) {
                            binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.skipCollapsed =
                                true
                        } else {
                            binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.skipCollapsed =
                                false
                            binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.state =
                                BottomSheetBehavior.STATE_COLLAPSED
                        }
                    }

                    if (presenter.downloadManager.hasQueue()) {
                        binding.downloadBottomSheet.downloadFab.alpha = 1f
                        if (state == BottomSheetBehavior.STATE_EXPANDED) {
                            binding.downloadBottomSheet.downloadFab.show()
                        } else {
                            binding.downloadBottomSheet.downloadFab.hide()
                        }
                    }

                    binding.downloadBottomSheet.sheetLayout.isClickable =
                        state == BottomSheetBehavior.STATE_COLLAPSED
                    binding.downloadBottomSheet.sheetLayout.isFocusable =
                        state == BottomSheetBehavior.STATE_COLLAPSED
                    setPadding(binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.isHideable == true)
                }
            },
        )
        viewScope.launch {
            LibraryUpdateJob.isRunningFlow(view.context).collect {
                binding.swipeRefresh.isRefreshing = it
            }
        }
        binding.swipeRefresh.setOnRefreshListener {
            if (!LibraryUpdateJob.isRunning(view.context)) {
                binding.swipeRefresh.isRefreshing = true
                snack?.dismiss()
                snack = view.snack(MR.strings.updating_library) {
                    anchorView =
                        if (binding.downloadBottomSheet.root.sheetBehavior.isCollapsed()) {
                            binding.downloadBottomSheet.root
                        } else {
                            activityBinding?.bottomNav ?: binding.downloadBottomSheet.root
                        }
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
                LibraryUpdateJob.startNow(view.context)
            }
        }
        ogRadius = view.resources.getDimension(R.dimen.rounded_radius)
        setSheetToolbar()
        if (showingDownloads) {
            binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.expand()
        }
        setPadding(binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.isHideable == true)

        binding.downloadBottomSheet.root.sheetBehavior?.isGestureInsetBottomIgnored = true
    }

    private fun setSheetToolbar() {
        binding.downloadBottomSheet.sheetToolbar.title = view?.context?.getString(MR.strings.download_queue)
        binding.downloadBottomSheet.sheetToolbar.overflowIcon?.setTint(view?.context?.getResourceColor(R.attr.actionBarTintColor) ?: Color.BLACK)
        binding.downloadBottomSheet.sheetToolbar.setOnMenuItemClickListener { item ->
            return@setOnMenuItemClickListener binding.downloadBottomSheet.dlBottomSheet.onOptionsItemSelected(item)
        }
        binding.downloadBottomSheet.sheetToolbar.setNavigationOnClickListener {
            if (binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.isHideable == true) {
                binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.hide()
            } else {
                binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.collapse()
            }
        }
    }

    fun updateTitleAndMenu() {
        if (isControllerVisible) {
            val activity = (activity as? MainActivity) ?: return
            appBar()?.isInvisible = showingDownloads
            (activity as? MainActivity)?.setStatusBarColorTransparent(showingDownloads)
            setTitle()
        }
    }

    private fun setBottomPadding() {
        val bottomBar = activityBinding?.bottomNav
        val pad = bottomBar?.translationY?.minus(bottomBar.height) ?: 0f
        val padding = max(
            (-pad).toInt(),
            view?.rootWindowInsetsCompat?.getBottomGestureInsets() ?: 0,
        )
        binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.peekHeight = 48.spToPx + padding
        binding.downloadBottomSheet.fastScroller.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = -pad.toInt()
        }
        binding.downloadBottomSheet.dlRecycler.updatePaddingRelative(
            bottom = max(
                -pad.toInt(),
                view?.rootWindowInsetsCompat?.getInsets(systemBars())?.bottom ?: 0,
            ) + binding.downloadBottomSheet.downloadFab.height + 20.dpToPx,
        )
        binding.downloadBottomSheet.downloadFab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = max(
                -pad.toInt(),
                view?.rootWindowInsetsCompat?.getInsets(systemBars())?.bottom ?: 0,
            ) + 16.dpToPx
        }
        setPadding(binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.isHideable == true)
    }

    fun setRefreshing(refresh: Boolean) {
        binding.swipeRefresh.isRefreshing = refresh
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int) { }

    override fun shouldMoveItem(fromPosition: Int, toPosition: Int) = true

    override fun onActionStateChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        binding.swipeRefresh.isEnabled = actionState != ItemTouchHelper.ACTION_STATE_SWIPE ||
            binding.swipeRefresh.isRefreshing
    }

    override fun canStillGoBack(): Boolean {
        return showingDownloads ||
            actionMode != null ||
            presenter.uiPreferences.recentsViewType().get() != presenter.viewType.mainValue
    }

    override fun handleOnBackStarted(backEvent: BackEventCompat) {
        if (showingDownloads) {
            binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.startBackProgress(backEvent)
        }
    }

    override fun handleOnBackProgressed(backEvent: BackEventCompat) {
        if (showingDownloads) {
            binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.updateBackProgress(backEvent)
        } else {
            super.handleOnBackProgressed(backEvent)
        }
    }

    override fun handleOnBackCancelled() {
        if (showingDownloads) {
            binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.cancelBackProgress()
        } else {
            super.handleOnBackCancelled()
        }
    }

    override fun handleBack(): Boolean {
        if (showingDownloads) {
            binding.downloadBottomSheet.dlBottomSheet.dismiss()
            return true
        }
        if (actionMode != null) {
            destroyActionModeIfNeeded()
            return true
        }
        val viewType = RecentsViewType.valueOf(presenter.uiPreferences.recentsViewType().get())
        if (viewType != presenter.viewType) {
            tempJumpTo(viewType)
            return true
        }
        return false
    }

    fun setPadding(sheetIsHidden: Boolean) {
        val peekHeight = binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.peekHeight ?: 0
        val cInsets = view?.rootWindowInsetsCompat ?: return
        binding.recycler.updatePaddingRelative(
            bottom = if (sheetIsHidden) {
                activityBinding?.bottomNav?.height ?: cInsets.getInsets(systemBars()).bottom
            } else {
                peekHeight
            },
        )
    }

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        if (!isBindingInitialized) return
        if (!presenter.isLoading) {
            refresh()
        }
        setBottomPadding()
        binding.downloadBottomSheet.dlBottomSheet.update(!presenter.downloadManager.isPaused())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBindingInitialized) {
            binding.downloadBottomSheet.root.onDestroy()
        }
        snack?.dismiss()
        snack = null
    }

    override fun onDestroyView(view: View) {
        super.onDestroyView(view)
        // Drop the action mode before the underlying view is torn down so we
        // don't leak the activity reference.
        destroyActionModeIfNeeded()
        displaySheet?.dismissSafely()
        displaySheet = null
    }

    fun refresh() = presenter.getRecents()

    fun showLists(
        recents: List<RecentMangaItem>,
        hasNewItems: Boolean,
        shouldMoveToTop: Boolean = false,
    ) {
        if (view == null) return
        if (!binding.progress.isVisible && recents.isNotEmpty()) {
            (activity as? MainActivity)?.showNotificationPermissionPrompt()
        }
        binding.progress.isVisible = false
        binding.recentsFrameLayout.alpha = 1f
        adapter.removeAllScrollableHeaders()
        adapter.updateDataSet(recents)
        adapter.onLoadMoreComplete(null)
        if (isControllerVisible) {
            appBar()?.lockYPos = false
        }
        if (!hasNewItems || presenter.viewType == RecentsViewType.GroupedAll ||
            recents.isEmpty()
        ) {
            loadNoMore()
        } else if (presenter.viewType != RecentsViewType.GroupedAll) {
            resetProgressItem()
        }
        if (recents.isEmpty()) {
            binding.recentsEmptyView.show(
                if (!isSearching()) {
                    Icons.Filled.HistoryToggleOff
                } else {
                    Icons.Filled.SearchOff
                },
                if (isSearching()) {
                    MR.strings.no_results_found
                } else {
                    when (presenter.viewType) {
                        RecentsViewType.Updates -> MR.strings.no_recent_chapters
                        RecentsViewType.History -> MR.strings.no_recently_read_manga
                        else -> MR.strings.no_recent_read_updated_manga
                    }
                },
            )
        } else {
            binding.recentsEmptyView.hide()
        }
        val isSearchExpanded = searchToolbar()?.isSearchExpanded == true
        if (shouldMoveToTop) {
            if (isSearchExpanded) {
                moveRecyclerViewUp(scrollUpAnyway = true)
            } else {
                (binding.recycler.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
            }
        }
        if (lastChapterId != null) {
            refreshItem(lastChapterId ?: 0L)
            lastChapterId = null
        }
    }

    fun updateChapterDownload(download: Download) {
        if (view == null || !this::adapter.isInitialized) return
        val id = download.chapter.id ?: return
        val item = adapter.getItemByChapterId(id) ?: return
        val holder = binding.recycler.findViewHolderForItemId(item.id!!) as? RecentMangaHolder ?: return
        if (item.id == id) {
            holder.notifyStatus(download.status, download.progress, download.chapter.read, true)
        } else {
            holder.notifySubStatus(
                download.chapter,
                download.status,
                download.progress,
                download.chapter.read,
                true,
            )
        }
    }

    fun updateDownloadStatus(isRunning: Boolean) {
        binding.downloadBottomSheet.dlBottomSheet.update(isRunning)
    }

    private fun refreshItem(chapterId: Long) {
        val recentItemPos = adapter.currentItems.indexOfFirst {
            it is RecentMangaItem &&
                it.mch.chapter.id == chapterId
        }
        if (recentItemPos > -1) adapter.notifyItemChanged(recentItemPos)
    }

    override fun downloadChapter(position: Int) {
        val view = view ?: return
        val item = adapter.getItem(position) as? RecentMangaItem ?: return
        val chapter = item.chapter
        val manga = item.mch.manga
        if (item.status != Download.State.NOT_DOWNLOADED && item.status != Download.State.ERROR) {
            presenter.deleteChapter(chapter, manga)
        } else {
            if (item.status == Download.State.ERROR) {
                DownloadJob.start(view.context)
            } else {
                presenter.downloadChapter(manga, chapter)
            }
        }
    }

    override fun startDownloadNow(position: Int) {
        val chapter = (adapter.getItem(position) as? RecentMangaItem)?.chapter ?: return
        presenter.startDownloadChapterNow(chapter)
    }

    override fun downloadChapter(position: Int, chapter: Chapter) {
        val view = view ?: return
        val item = adapter.getItem(position) as? RecentMangaItem ?: return
        val manga = item.mch.manga
        val status = item.downloadInfo.find { it.chapterId == chapter.id }?.status ?: return
        if (status != Download.State.NOT_DOWNLOADED && status != Download.State.ERROR) {
            presenter.deleteChapter(chapter, manga)
        } else {
            if (status == Download.State.ERROR) {
                DownloadJob.start(view.context)
            } else {
                presenter.downloadChapter(manga, chapter)
            }
        }
    }

    override fun startDownloadNow(position: Int, chapter: Chapter) {
        presenter.startDownloadChapterNow(chapter)
    }

    override fun onCoverClick(position: Int) {
        val manga = (adapter.getItem(position) as? RecentMangaItem)?.mch?.manga ?: return
        router.pushController(MangaDetailsController(manga).withFadeTransaction())
    }

    override fun onRemoveHistoryClicked(position: Int) {
        onItemLongClick(position)
    }

    override fun onSubChapterClicked(position: Int, chapter: Chapter, view: View) {
        val manga = (adapter.getItem(position) as? RecentMangaItem)?.mch?.manga ?: return
        openChapter(view, manga, chapter)
    }

    override fun areExtraChaptersExpanded(position: Int): Boolean {
        if (alwaysExpanded()) return true
        val item = (adapter.getItem(position) as? RecentMangaItem) ?: return false
        val date = presenter.dateFormat.format(item.mch.history.last_read)
        val invertDefault = !adapter.collapseGrouped
        return presenter.expandedSectionsMap["${item.mch.manga} - $date"]?.xor(invertDefault)
            ?: invertDefault
    }

    override fun updateExpandedExtraChapters(position: Int, expanded: Boolean) {
        if (alwaysExpanded()) return
        val item = (adapter.getItem(position) as? RecentMangaItem) ?: return
        val date = presenter.dateFormat.format(item.mch.history.last_read)
        val invertDefault = !adapter.collapseGrouped
        presenter.expandedSectionsMap["${item.mch.manga} - $date"] = expanded.xor(invertDefault)
    }

    fun tempJumpTo(viewType: RecentsViewType) {
        // Switching tabs in the middle of a multi-select would leave stale
        // selection positions hanging around; collapse it cleanly.
        destroyActionModeIfNeeded()
        presenter.toggleGroupRecents(viewType, false)
        activityBinding?.mainTabs?.run { selectTab(getTabAt(viewType.mainValue)) }
        (activity as? MainActivity)?.reEnableBackPressedCallBack()
        updateTitleAndMenu()
    }

    private fun setViewType(viewType: RecentsViewType) {
        if (viewType != presenter.viewType) {
            destroyActionModeIfNeeded()
            presenter.toggleGroupRecents(viewType)
            updateTitleAndMenu()
        }
    }

    override fun getViewType(): RecentsViewType = presenter.viewType

    override fun scope() = viewScope

    override fun onItemClick(view: View?, position: Int): Boolean {
        val item = adapter.getItem(position) ?: return false
        if (item is RecentMangaItem) {
            // While in multi-select, a tap toggles selection instead of
            // opening the reader. Footer rows (mch.manga.id == null) keep
            // their existing "jump to other tab" behaviour.
            if (adapter.isInSelectionMode && item.mch.manga.id != null) {
                snack?.dismiss()
                lastClickPosition = position
                toggleSelection(position)
                return false
            }
            if (item.mch.manga.id == null) {
                val headerItem = adapter.getHeaderOf(item) as? RecentMangaHeaderItem
                tempJumpTo(
                    when (headerItem?.recentsType) {
                        RecentMangaHeaderItem.NEW_CHAPTERS -> RecentsViewType.Updates
                        RecentMangaHeaderItem.CONTINUE_READING -> RecentsViewType.History
                        else -> return false
                    },
                )
            } else {
                if (activity == null) return false
                openChapter(view?.findViewById(R.id.main_view), item.mch.manga, item.chapter)
            }
        } else if (item is RecentMangaHeaderItem) return false
        return true
    }

    private fun openChapter(view: View?, manga: Manga, chapter: Chapter) {
        val activity = activity ?: return
        activity.apply {
            if (view != null) {
                val (intent, bundle) = ReaderActivity
                    .newIntentWithTransitionOptions(activity, manga, chapter, view)
                startActivity(intent, bundle)
            } else {
                val intent = ReaderActivity.newIntent(activity, manga, chapter)
                startActivity(intent)
            }
        }
    }

    override fun onItemLongClick(position: Int) {
        val item = adapter.getItem(position) as? RecentMangaItem ?: return
        if (item.mch.manga.id == null) return
        // History/Updates → enter (or extend) multi-select. GroupedAll keeps
        // the pre-existing single-item dialog flow.
        if (adapter.selectionEnabled) {
            handleSelectionLongClick(position)
            return
        }
        showRemoveHistoryDialog(item.mch.manga, item.mch.history, item.mch.chapter)
    }

    override fun onItemLongClick(position: Int, chapter: ChapterHistory): Boolean {
        val history = chapter.history ?: return false
        val item = adapter.getItem(position) as? RecentMangaItem ?: return false
        // Sub-chapter long-press is intentionally NOT a selection entry point.
        // If we're already in selection mode, swallow the gesture so the
        // toolbar stays visible; otherwise fall back to the existing dialog.
        if (adapter.isInSelectionMode) return true
        if (history.id != null) {
            showRemoveHistoryDialog(item.mch.manga, history, chapter)
        }
        return history.id != null
    }

    override fun onItemSelectionToggled(position: Int) {
        snack?.dismiss()
        lastClickPosition = position
        toggleSelection(position)
    }

    private fun showRemoveHistoryDialog(manga: Manga, history: History, chapter: Chapter) {
        val activity = activity ?: return
        if (history.id != null) {
            activity.materialAlertDialog()
                .setCustomTitleAndMessage(
                    MR.strings.reset_chapter_question,
                    activity.getString(
                        MR.strings.this_will_remove_the_read_date_for_x_question,
                        chapter.name,
                    ),
                )
                .addCheckBoxPrompt(
                    activity.getString(
                        MR.strings.reset_all_chapters_for_this_,
                        manga.seriesType(activity),
                    ),
                )
                .setNegativeButton(AR.string.cancel, null)
                .setPositiveButton(MR.strings.reset) { dialog, _ ->
                    removeHistory(manga, history, dialog.isPromptChecked)
                }
                .show()
        }
    }

    private fun removeHistory(manga: Manga, history: History, all: Boolean) {
        if (all) {
            // Reset last read of chapter to 0L
            presenter.removeAllFromHistory(manga.id!!)
        } else {
            // Remove all chapters belonging to manga from library
            presenter.removeFromHistory(history)
        }
    }

    override fun markAsRead(position: Int) {
        val preferences = presenter.preferences
        val item = adapter.getItem(position) as? RecentMangaItem ?: return
        val holder = binding.recycler.findViewHolderForAdapterPosition(position)
        val holderId = (holder as? RecentMangaHolder)?.chapterId
        adapter.notifyItemChanged(position)
        // Scope the mark-read fade to the marked row only. beginDelayedTransition on the whole
        // recycler stays pending across the async getRecents() -> updateDataSet reorder, fading
        // every moved row (the list-wide churn the user sees). Targeting just this itemView keeps
        // the intended single-row fade and leaves the bulk reorder un-animated (itemAnimator=null).
        holder?.itemView?.let { row ->
            val transition = TransitionSet().addTransition(androidx.transition.Fade())
            transition.addTarget(row)
            transition.duration = view!!.resources.getInteger(AR.integer.config_shortAnimTime)
                .toLong()
            androidx.transition.TransitionManager.beginDelayedTransition(binding.recycler, transition)
        }
        if (holderId == -1L) return
        val chapter = holderId?.let { item.mch.extraChapters.find { holderId == it.id } }
            ?: item.chapter
        val manga = item.mch.manga
        val lastRead = chapter.last_page_read
        val pagesLeft = chapter.pages_left
        lastChapterId = chapter.id
        val wasRead = chapter.read
        presenter.markChapterRead(chapter, !wasRead)
        snack = view?.snack(
            if (wasRead) {
                MR.strings.marked_as_unread
            } else {
                MR.strings.marked_as_read
            },
            Snackbar.LENGTH_INDEFINITE,
        ) {
            anchorView = activityBinding?.bottomNav
            var undoing = false
            setAction(MR.strings.undo) {
                presenter.markChapterRead(chapter, wasRead, lastRead, pagesLeft)
                undoing = true
            }
            addCallback(
                object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        super.onDismissed(transientBottomBar, event)
                        if (!undoing && !wasRead) {
                            if (preferences.removeAfterMarkedAsRead().get()) {
                                lastChapterId = chapter.id
                                presenter.deleteChapter(chapter, manga)
                            }
                            updateTrackChapterMarkedAsRead(preferences, chapter, manga.id) {
                                (router.backstack.lastOrNull()?.controller as? MangaDetailsController)?.presenter?.fetchTracks()
                            }
                        }
                    }
                },
            )
        }
        (activity as? MainActivity)?.setUndoSnackBar(snack)
    }

    private fun isSearching() = query.isNotEmpty()
    override fun alwaysExpanded() =
        query.isNotEmpty() || (presenter.viewType.isHistory && !presenter.groupHistory.isByTime)

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        android.os.Trace.beginSection(
            if (type.isEnter) "Hayai/RecentsController.onChangeStarted.enter"
            else "Hayai/RecentsController.onChangeStarted.exit",
        )
        try {
            super.onChangeStarted(handler, type)
            when (type) {
                ControllerChangeType.PUSH_ENTER -> {
                    // Initial creation. selectTab will follow up with onTabActivated which
                    // calls onSetupLocalChrome + UI setup — we don't duplicate it here.
                }
                ControllerChangeType.POP_ENTER -> {
                    // Returning from a pushed controller (e.g. MangaDetails). Refresh the
                    // presenter, then activation re-wires the local chrome.
                    presenter.onCreate()
                    onTabActivated()
                }
                ControllerChangeType.PUSH_EXIT, ControllerChangeType.POP_EXIT -> {
                    // Drop out of Conductor's menu dispatch while something is on top.
                    setOptionsMenuHidden(true)
                    snack?.dismiss()
                }
                else -> Unit
            }
            setBottomPadding()
        } finally {
            android.os.Trace.endSection()
        }
    }

    override fun onChangeEnded(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeEnded(handler, type)
        if (type == ControllerChangeType.POP_ENTER) {
            setBottomPadding()
        }
        if (type.isEnter && isControllerVisible) {
            updateTitleAndMenu()
        }
    }

    /**
     * Called when the user swaps to the Recents tab via the bottom nav. The view tree is
     * already attached; we just need to (re)take ownership of the shared activity chrome:
     * rewire the app bar to our recycler, rebind the History/Updates tabs, and bring the
     * tab strip back in.
     */
    override fun onTabActivated() {
        if (!isBindingInitialized) return
        binding.downloadBottomSheet.dlBottomSheet.dismiss()
        // Tab swap is not a Conductor event, so the base-controller hoist of
        // onSetupLocalChrome doesn't fire here — wire chrome explicitly.
        onSetupLocalChrome()
        setBottomPadding()
        updateTitleAndMenu()
    }

    /**
     * Called when the user swaps away from the Recents tab. The incoming tab's own
     * onSetupLocalChrome wires its appBar — we just dismiss any per-tab UI state
     * (snackbars).
     */
    override fun onTabDeactivated() {
        if (!isBindingInitialized) return
        snack?.dismiss()
        setBottomPadding()
    }

    fun hasQueue() = presenter.downloadManager.hasQueue()

    override fun showSheet() {
        if (!isBindingInitialized) return
        binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.expand()
    }

    override fun hideSheet() {
        if (!isBindingInitialized) return
        if (binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.isHideable == true) {
            binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.hide()
        } else {
            binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.collapse()
        }
    }

    override fun toggleSheet() {
        if (showingDownloads) {
            binding.downloadBottomSheet.dlBottomSheet.dismiss()
        } else {
            binding.downloadBottomSheet.dlBottomSheet.sheetBehavior?.expand()
        }
    }

    override fun expandSearch() {
        if (showingDownloads) {
            binding.downloadBottomSheet.dlBottomSheet.dismiss()
        } else {
            searchToolbar()?.menu?.findItem(R.id.action_search)?.expandActionView()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.display_options -> {
                displaySheet = TabbedRecentsOptionsSheet(
                    this,
                    presenter.viewType.mainValue.coerceIn(0, 2),
                )
                displaySheet?.show()
            }
            R.id.action_view_upcoming -> {
                router.pushController(UpcomingController().withFadeTransaction())
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Adds [sourceId] to the per-tab hidden-sources pref (History or Updates),
     * then refreshes the list. Invoked by [RecentMangaHeaderItem]'s source
     * header overflow ("Hide all from <source>"). Wave 2B (group-by-source)
     * owns this; Wave 2A does not touch it.
     */
    override fun onHideSourceClicked(sourceId: String) {
        val pref = when (presenter.viewType) {
            RecentsViewType.History -> presenter.recentsPreferences.hiddenSourcesInHistory()
            RecentsViewType.Updates -> presenter.recentsPreferences.hiddenSourcesInUpdates()
            else -> return
        }
        pref.set(pref.get() + sourceId)
        presenter.getRecents()
    }

    override fun noMoreLoad(newItemsSize: Int) {}

    override fun onLoadMore(lastPosition: Int, currentPage: Int) {
        val view = view ?: return
        if (presenter.finished ||
            BackupRestoreJob.isRunning(view.context.applicationContext) ||
            (presenter.viewType == RecentsViewType.GroupedAll && !isSearching())
        ) {
            loadNoMore()
            return
        }
        presenter.requestNext()
    }

    private fun loadNoMore() {
        adapter.onLoadMoreComplete(null)
    }

    /**
     * Sets a new progress item and reenables the scroll listener.
     */
    private fun resetProgressItem() {
        adapter.onLoadMoreComplete(null)
        progressItem = ProgressItem()
        adapter.setEndlessScrollListener(this, progressItem!!)
    }

    // region Selection mode (Wave 2A)

    /**
     * Pick the [RecentsHidden] tab id matching the active view type. Only
     * called from selection-mode flows, which only run on History/Updates.
     */
    private fun currentHiddenTab(): Int = when (presenter.viewType) {
        RecentsViewType.History -> RecentsHidden.TAB_HISTORY
        RecentsViewType.Updates -> RecentsHidden.TAB_UPDATES
        else -> RecentsHidden.TAB_HISTORY
    }

    /**
     * Toggle [position]'s selection and refresh the action mode UI.
     */
    private fun toggleSelection(position: Int) {
        adapter.toggleSelection(position)
        // The FlexibleAdapter only redraws the activation state; we still need
        // to repaint our custom row tint.
        (binding.recycler.findViewHolderForAdapterPosition(position) as? RecentMangaHolder)
            ?.updateSelectedBackground()
        invalidateActionMode()
    }

    /**
     * Long-click entry point used by both the top-level row and the cover.
     * Implements first-press anchor + second-press range select like the
     * library multi-select.
     */
    private fun handleSelectionLongClick(position: Int) {
        snack?.dismiss()
        createActionModeIfNeeded()
        when {
            lastClickPosition == -1 -> setSelected(position, true)
            lastClickPosition > position -> {
                for (i in position until lastClickPosition) setSelected(i, true)
            }
            lastClickPosition < position -> {
                for (i in lastClickPosition + 1..position) setSelected(i, true)
            }
            else -> setSelected(position, true)
        }
        lastClickPosition = position
        invalidateActionMode()
    }

    /**
     * Add or remove [position] from the selection without touching the action
     * mode UI. Used by range-select.
     */
    private fun setSelected(position: Int, selected: Boolean) {
        val item = adapter.getItem(position) as? RecentMangaItem ?: return
        if (!item.isSelectable) return
        val isSelected = adapter.isSelected(position)
        if (selected && !isSelected) {
            adapter.addSelection(position)
        } else if (!selected && isSelected) {
            adapter.removeSelection(position)
        }
        (binding.recycler.findViewHolderForAdapterPosition(position) as? RecentMangaHolder)
            ?.updateSelectedBackground()
    }

    private fun createActionModeIfNeeded() {
        if (actionMode == null) {
            adapter.mode = SelectableAdapter.Mode.MULTI
            adapter.isSwipeEnabled = false
            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this)
        }
    }

    private fun destroyActionModeIfNeeded() {
        actionMode?.finish()
    }

    private fun invalidateActionMode() {
        if (adapter.selectedItemCount == 0) {
            destroyActionModeIfNeeded()
            return
        }
        actionMode?.invalidate()
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.recents_selection, menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val count = adapter.selectedItemCount
        if (count == 0) {
            destroyActionModeIfNeeded()
            return false
        }
        mode.title = view?.context?.getString(MR.strings.selected_, count)
        // Erase (history reset) only makes sense on the History tab.
        menu.findItem(R.id.action_erase_history)?.isVisible = presenter.viewType.isHistory
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select_all -> {
                selectAllSelectable()
                true
            }
            R.id.action_mark_as_read -> {
                bulkMarkAsRead()
                true
            }
            R.id.action_hide -> {
                bulkHide()
                true
            }
            R.id.action_erase_history -> {
                confirmBulkEraseHistory()
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        actionMode = null
        lastClickPosition = -1
        adapter.mode = SelectableAdapter.Mode.IDLE
        adapter.clearSelection()
        adapter.isSwipeEnabled = true
        // Repaint visible rows so the selection tint clears.
        (0 until adapter.itemCount).forEach { pos ->
            (binding.recycler.findViewHolderForAdapterPosition(pos) as? RecentMangaHolder)
                ?.updateSelectedBackground()
        }
    }

    private fun selectAllSelectable() {
        var changed = false
        (0 until adapter.itemCount).forEach { pos ->
            val item = adapter.getItem(pos) as? RecentMangaItem ?: return@forEach
            if (!item.isSelectable) return@forEach
            if (!adapter.isSelected(pos)) {
                adapter.addSelection(pos)
                (binding.recycler.findViewHolderForAdapterPosition(pos) as? RecentMangaHolder)
                    ?.updateSelectedBackground()
                changed = true
            }
        }
        if (changed) {
            invalidateActionMode()
        }
    }

    /**
     * Mark every selected chapter as read (or all unread if every selection is
     * already read). Snapshot the previous state so the undo snackbar can
     * restore it in bulk.
     */
    private fun bulkMarkAsRead() {
        val view = view ?: return
        val items = adapter.selectedRecentItems()
        if (items.isEmpty()) return
        // Read-state to apply: if everything is already read, flip all to
        // unread; otherwise mark them all read. Mirrors LibraryController's
        // markReadStatus semantics.
        val targetRead = !items.all { it.chapter.read }
        val chapterIds = items.mapNotNull { it.chapter.id }
        val snapshot = presenter.snapshotReadState(chapterIds)
        presenter.bulkMarkRead(chapterIds, targetRead)
        destroyActionModeIfNeeded()
        snack?.dismiss()
        snack = view.snack(
            if (targetRead) MR.strings.marked_as_read else MR.strings.marked_as_unread,
            Snackbar.LENGTH_INDEFINITE,
        ) {
            anchorView = activityBinding?.bottomNav
            var undoing = false
            setAction(MR.strings.undo) {
                presenter.restoreReadState(snapshot)
                undoing = true
            }
            addCallback(
                object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        super.onDismissed(transientBottomBar, event)
                        if (!undoing && targetRead) {
                            // Mirror the single-item flow: update tracking
                            // after the user has accepted the read action.
                            val preferences = presenter.preferences
                            items.forEach { ri ->
                                updateTrackChapterMarkedAsRead(preferences, ri.chapter, ri.mch.manga.id) {}
                            }
                        }
                    }
                },
            )
        }
        (activity as? MainActivity)?.setUndoSnackBar(snack)
    }

    /**
     * Hide every selection from the current tab. SQL filters the rows on the
     * next refresh; undo just deletes the hidden rows again.
     */
    private fun bulkHide() {
        val view = view ?: return
        val tab = currentHiddenTab()
        val items = adapter.selectedRecentItems()
        if (items.isEmpty()) return
        val pairs = items.mapNotNull { ri ->
            val cId = ri.chapter.id ?: return@mapNotNull null
            val mId = ri.mch.manga.id ?: return@mapNotNull null
            cId to mId
        }
        if (pairs.isEmpty()) return
        presenter.hideItems(tab, pairs)
        destroyActionModeIfNeeded()
        snack?.dismiss()
        val count = pairs.size
        snack = view.snack(
            view.context.getString(
                MR.plurals.recents_n_items_hidden,
                count,
                count,
            ),
            Snackbar.LENGTH_LONG,
        ) {
            anchorView = activityBinding?.bottomNav
            setAction(MR.strings.undo) {
                presenter.unhideItems(tab, pairs.map { it.first })
            }
        }
        (activity as? MainActivity)?.setUndoSnackBar(snack)
    }

    /**
     * Show the existing reset-confirmation dialog before kicking off a bulk
     * history erase. On confirm, snapshot each row so undo can restore them.
     */
    private fun confirmBulkEraseHistory() {
        val activity = activity ?: return
        val items = adapter.selectedRecentItems()
        if (items.isEmpty()) return
        // Reuse the existing single-item confirmation copy; substitute either
        // the lone chapter name (1 item) or a "N selected items" summary so
        // the dialog reads naturally for both.
        val summary = if (items.size == 1) {
            items.first().chapter.name
        } else {
            activity.getString(MR.strings.selected_, items.size)
        }
        activity.materialAlertDialog()
            .setCustomTitleAndMessage(
                MR.strings.reset_chapter_question,
                activity.getString(
                    MR.strings.this_will_remove_the_read_date_for_x_question,
                    summary,
                ),
            )
            .setNegativeButton(AR.string.cancel, null)
            .setPositiveButton(MR.strings.reset) { _, _ ->
                performBulkEraseHistory(items)
            }
            .show()
    }

    private fun performBulkEraseHistory(items: List<RecentMangaItem>) {
        val view = view ?: return
        val historyIds = items.mapNotNull { it.mch.history.id }
        if (historyIds.isEmpty()) return
        val snapshot = presenter.snapshotHistory(historyIds)
        presenter.bulkRemoveHistory(historyIds)
        destroyActionModeIfNeeded()
        snack?.dismiss()
        snack = view.snack(MR.strings.marked_as_unread, Snackbar.LENGTH_LONG) {
            anchorView = activityBinding?.bottomNav
            setAction(MR.strings.undo) {
                presenter.restoreHistory(snapshot)
            }
        }
        (activity as? MainActivity)?.setUndoSnackBar(snack)
    }
    // endregion

    companion object {
        // Shared across RecentsController lifetimes so recent_manga_item holders are
        // reused on every Recents re-entry. Survives controller destruction.
        private val persistentRecentsPool = androidx.recyclerview.widget.RecyclerView.RecycledViewPool()
    }
}
