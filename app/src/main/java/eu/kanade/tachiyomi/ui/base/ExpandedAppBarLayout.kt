package eu.kanade.tachiyomi.ui.base

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewPropertyAnimator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.math.MathUtils
import androidx.core.view.ScrollingView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.bluelinelabs.conductor.Controller
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import com.google.android.material.R as materialR
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.isTablet
import eu.kanade.tachiyomi.util.view.backgroundColor
import eu.kanade.tachiyomi.util.view.isControllerVisible
import eu.kanade.tachiyomi.util.view.setTextColorAlpha
import eu.kanade.tachiyomi.widget.StatefulNestedScrollView
import yokai.util.koin.injectLazy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.R as AR

class ExpandedAppBarLayout@JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppBarLayout(context, attrs) {

    init {
        // Self-consume the top system inset as padding so this widget can be hosted by
        // any layout (activity root or per-controller layout) without the host having
        // to dispatch insets manually. The activity used to set this on `binding.appBar`
        // directly; per-controller appBars need their own copy of the same behavior.
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = systemBars.top)
            insets
        }
    }

    var searchToolbar: FloatingToolbar? = null
    var cardFrame: FrameLayout? = null
    /**
     * The [com.google.android.material.card.MaterialCardView] inside [cardFrame] that
     * actually carries the search-pill's rounded background. [setAppBarBG] (in
     * ControllerExtensions) animates this color on scroll; for controllers hosting
     * their own appBar, this is the local instance — for the activity-global appBar,
     * it's the one inside `main_activity.xml`. Both forms expose the same id so the
     * lookup in [onFinishInflate] is uniform.
     */
    var cardView: com.google.android.material.card.MaterialCardView? = null
    var mainToolbar: CenteredToolbar? = null
    var bigTitleView: TextView? = null
    val preferences: PreferencesHelper by injectLazy()
    var bigView: View? = null
    var imageView: ImageView? = null
    var imageLayout: FrameLayout? = null
    private var tabsFrameLayout: FrameLayout? = null
    private var mainTabs: TabLayout? = null
    private var tabSelectedListener: TabLayout.OnTabSelectedListener? = null
    private var boundPager: ViewPager? = null
    private var pagerListener: ViewPager.OnPageChangeListener? = null
    private var appliedTabItems: List<TabItem> = emptyList()
    private var appliedTabMode: TabMode? = null
    private var onTabSelected: ((Int) -> Unit)? = null
    private var onTabReselected: ((Int) -> Unit)? = null
    private var onPagerPageSelected: ((Int) -> Unit)? = null
    private var suppressTabCallbacks = false

    /**
     * Which of [mainToolbar] / [searchToolbar] is the active "menu host" for this
     * appBar instance — tracked locally so the appBar can decide its own visual
     * state without reaching into [MainActivity.currentToolbar]. Set by
     * [useSearchToolbarForMenu] as the user scrolls / opens the search pill.
     */
    private var currentActiveToolbar: BaseToolbar? = null
    private var lastToolbarTitleAlpha = INVALID_TITLE_ALPHA
    private var isExtraSmall = false
    val useLargeToolbar: Boolean
        get() = preferences.useLargeToolbar().get() && !isExtraSmall

    var compactSearchMode = false
        set(value) {
            if (field != value) invalidatePreLayoutHeight()
            field = value
        }

    /** Defines how the toolbar layout should be */
    private var toolbarMode = ToolbarState.EXPANDED
        set(value) {
            if (field != value) invalidatePreLayoutHeight()
            field = value
            if (value == ToolbarState.SEARCH_ONLY) {
                mainToolbar?.isGone = true
            } else if (value == ToolbarState.COMPACT) {
                mainToolbar?.alpha = 1f
                mainToolbar?.isVisible = true
            }
            if (value != ToolbarState.EXPANDED) {
                mainToolbar?.translationY = 0f
                y = 0f
            }
        }
    var useTabsInPreLayout = false
        set(value) {
            if (field != value) invalidatePreLayoutHeight()
            field = value
        }
    var yAnimator: ViewPropertyAnimator? = null

    /**
     * used to ignore updates to y
     *
     * use only on controller.onViewCreated that asynchronously loads the first set of items
     * and make false once the recycler has items
     */
    var lockYPos = false

    /** A value used to determine the offset needed for a recycler to land just under the smaller toolbar */
    val toolbarDistanceToTop: Int
        get() {
            val tabHeight = if (tabsFrameLayout?.isVisible == true) 48.dpToPx else 0
            return paddingTop - (mainToolbar?.height ?: 0) - tabHeight
        }

    /** A value used to determine the offset needed for a appbar's y to show only the smaller toolbar */
    val yNeededForSmallToolbar: Int
        get() {
            if (toolbarMode != ToolbarState.EXPANDED) return 0
            val tabHeight = if (tabsFrameLayout?.isVisible == true) 48.dpToPx else 0
            return -preLayoutHeight + (mainToolbar?.height ?: 0) + tabHeight
        }

    val attrToolbarHeight: Int = let {
        val attrsArray = intArrayOf(R.attr.mainActionBarSize)
        val array = it.context.obtainStyledAttributes(attrsArray)
        val height = array.getDimensionPixelSize(0, 0)
        array.recycle()
        height
    }

    // Scroll background updates used to resolve the same theme attributes through a
    // TypedArray on every frame. An app bar is recreated when its themed Context changes,
    // so these values are safe to resolve once per instance.
    internal val surfaceColor: Int by lazy(LazyThreadSafetyMode.NONE) {
        context.getResourceColor(materialR.attr.colorSurface)
    }
    internal val elevatedSurfaceColor: Int by lazy(LazyThreadSafetyMode.NONE) {
        context.getResourceColor(materialR.attr.colorPrimaryVariant)
    }
    internal val themedStatusBarColor: Int by lazy(LazyThreadSafetyMode.NONE) {
        context.getResourceColor(AR.attr.statusBarColor)
    }

    val preLayoutHeight: Int
        get() = getEstimatedLayout(
            cardFrame?.isVisible == true && toolbarMode == ToolbarState.EXPANDED,
            useTabsInPreLayout,
            toolbarMode == ToolbarState.EXPANDED,
        )

    private val preLayoutHeightWhileSearching: Int
        get() = getEstimatedLayout(
            cardFrame?.isVisible == true && toolbarMode == ToolbarState.EXPANDED,
            useTabsInPreLayout,
            toolbarMode == ToolbarState.EXPANDED,
            true,
        )

    private var dontFullyHideToolbar = false
    private var collapsedBackgroundVisible = false

    /** Small toolbar height + top system insets, same size as a collapsed appbar */
    private val compactAppBarHeight: Float
        get() {
            val appBarHeight = if (mainToolbar?.height ?: 0 > 0) {
                mainToolbar?.height ?: 0
            } else {
                attrToolbarHeight
            }
            return (appBarHeight + paddingTop).toFloat()
        }

    /** Used to restrain how far up the app bar can go up. Tablets stop at the smaller toolbar */
    private val minTabletHeight: Int
        get() {
            val tabHeight = if (tabsFrameLayout?.isVisible == true) 48.dpToPx else 0
            return if (context.isTablet() || (compactSearchMode && toolbarMode == ToolbarState.EXPANDED)) {
                (mainToolbar?.height ?: 0) + paddingTop + tabHeight
            } else {
                0
            }
        }

    enum class ToolbarState {
        EXPANDED,
        COMPACT,
        SEARCH_ONLY,
    }

    fun setToolbarModeBy(controller: Controller?, useSmall: Boolean? = null) {
        toolbarMode = if (useSmall ?: !useLargeToolbar) {
            when {
                controller is FloatingSearchInterface && controller.showFloatingBar() -> {
                    ToolbarState.SEARCH_ONLY
                }
                else -> ToolbarState.COMPACT
            }
        } else {
            when (controller) {
                is SmallToolbarInterface -> {
                    if (controller is FloatingSearchInterface && controller.showFloatingBar()) {
                        ToolbarState.SEARCH_ONLY
                    } else {
                        ToolbarState.COMPACT
                    }
                }
                else -> ToolbarState.EXPANDED
            }
        }
    }

    fun hideBigView(useSmall: Boolean, force: Boolean? = null, setTitleAlpha: Boolean = true) {
        val useSmallAnyway = force ?: (useSmall || !useLargeToolbar)
        bigView?.isGone = useSmallAnyway
        if (useSmallAnyway) {
            mainToolbar?.backgroundColor = null
            if (!setTitleAlpha) return
            setToolbarTitleAlpha(255)
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        bigTitleView = findViewById(R.id.big_title)
        searchToolbar = findViewById(R.id.search_toolbar)
        mainToolbar = findViewById(R.id.toolbar)
        bigView = findViewById(R.id.big_toolbar)
        cardFrame = findViewById(R.id.card_frame)
        cardView = findViewById(R.id.card_view)
        tabsFrameLayout = findViewById(R.id.tabs_frame_layout)
        mainTabs = findViewById(R.id.main_tabs)
        imageView = findViewById(R.id.big_icon)
        imageLayout = findViewById(R.id.big_icon_layout)
        shrinkAppBarIfNeeded(resources.configuration)
    }

    /**
     * Populate the tab strip with [items], selecting [selectedIndex] without firing
     * the [onSelected] callback. [onReselected] fires when the user taps the already-
     * selected tab. If [pagerSync] is non-null, a two-way listener pair is installed
     * so the [TabLayout] and the [ViewPager] stay in lockstep.
     *
     * Detaches any previously-installed listeners before re-populating, so this is
     * safe to call repeatedly without leaking the prior owner's callbacks.
     */
    fun applyTabs(
        items: List<TabItem>,
        selectedIndex: Int,
        mode: TabMode = TabMode.Fixed,
        onSelected: (Int) -> Unit,
        onReselected: ((Int) -> Unit)? = null,
        pagerSync: TabsPagerSync? = null,
    ) {
        val tabLayout = mainTabs ?: return
        val tabsFrame = tabsFrameLayout ?: return
        if (items.isEmpty()) {
            clearTabs()
            return
        }

        val safeIndex = selectedIndex.coerceIn(0, items.lastIndex)
        this.onTabSelected = onSelected
        this.onTabReselected = onReselected
        onPagerPageSelected = pagerSync?.onPageSelected

        // Root tab controllers keep their local app bars alive. Re-entering one therefore
        // must not remove/reinflate every tab and badge: that forces TabLayout to remeasure
        // the entire strip during the navigation frame. Reuse the existing views whenever
        // the item structure is compatible and only update changed text/count values.
        val canReuseTabs = appliedTabItems.size == items.size &&
            appliedTabItems.zip(items).all { (old, new) ->
                (old is TabItem.Label && new is TabItem.Label) ||
                    (old is TabItem.Badged && new is TabItem.Badged)
            }

        if (!canReuseTabs) {
            clearTabs()
            this.onTabSelected = onSelected
            this.onTabReselected = onReselected
            onPagerPageSelected = pagerSync?.onPageSelected

            tabLayout.tabMode = mode.tabLayoutMode
            tabLayout.tabGravity = TabLayout.GRAVITY_FILL
            val inflater = LayoutInflater.from(tabLayout.context)
            items.forEachIndexed { index, item ->
                val tab = tabLayout.newTab()
                bindTab(tab, item, inflater)
                tabLayout.addTab(tab, index == safeIndex)
            }

            val tabListener = object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    tab ?: return
                    if (!suppressTabCallbacks) onTabSelected?.invoke(tab.position)
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {
                    tab ?: return
                    if (!suppressTabCallbacks) onTabReselected?.invoke(tab.position)
                }
            }
            tabLayout.addOnTabSelectedListener(tabListener)
            tabSelectedListener = tabListener
        } else {
            if (appliedTabMode != mode) tabLayout.tabMode = mode.tabLayoutMode
            val inflater = LayoutInflater.from(tabLayout.context)
            items.forEachIndexed { index, item ->
                tabLayout.getTabAt(index)?.let { bindTab(it, item, inflater) }
            }
            if (tabLayout.selectedTabPosition != safeIndex) {
                suppressTabCallbacks = true
                tabLayout.getTabAt(safeIndex)?.select()
                suppressTabCallbacks = false
            }
        }

        val nextPager = pagerSync?.pager
        if (boundPager !== nextPager) {
            boundPager?.let { pager -> pagerListener?.let(pager::removeOnPageChangeListener) }
            boundPager = null
            pagerListener = null
        }
        if (nextPager != null && boundPager == null) {
            val listener = object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int,
                ) {
                    tabLayout.setScrollPosition(position, positionOffset, /* updateSelectedTabView = */ false)
                }
                override fun onPageSelected(position: Int) {
                    if (tabLayout.selectedTabPosition != position) {
                        tabLayout.getTabAt(position)?.select()
                    }
                    onPagerPageSelected?.invoke(position)
                }
            }
            nextPager.addOnPageChangeListener(listener)
            boundPager = nextPager
            pagerListener = listener
        }

        appliedTabItems = items.toList()
        appliedTabMode = mode
        useTabsInPreLayout = true
        tabsFrame.alpha = 1f
        tabsFrame.isVisible = true
    }

    private fun bindTab(tab: TabLayout.Tab, item: TabItem, inflater: LayoutInflater) {
        when (item) {
            is TabItem.Label -> {
                if (tab.customView != null) tab.customView = null
                if (tab.text != item.text) tab.setText(item.text)
            }
            is TabItem.Badged -> {
                val customView = tab.customView
                    ?: inflater.inflate(R.layout.chrome_tab_with_count, mainTabs, false).also {
                        tab.customView = it
                    }
                customView.findViewById<TextView>(R.id.tab_label).apply {
                    if (text != item.text) text = item.text
                }
                customView.findViewById<TextView>(R.id.tab_count).apply {
                    val shouldHide = item.count == null
                    if (isGone != shouldHide) isGone = shouldHide
                    item.count?.let { count ->
                        val countText = count.toString()
                        if (text != countText) text = countText
                    }
                }
            }
        }
    }

    /**
     * Detach tab + pager listeners and clear the strip. Symmetric counterpart to
     * [applyTabs]; safe to call repeatedly.
     */
    fun clearTabs() {
        boundPager?.let { pager -> pagerListener?.let(pager::removeOnPageChangeListener) }
        boundPager = null
        pagerListener = null
        onPagerPageSelected = null
        val tabLayout = mainTabs
        val tabsFrame = tabsFrameLayout
        if (tabLayout != null) {
            tabSelectedListener?.let(tabLayout::removeOnTabSelectedListener)
            tabSelectedListener = null
            tabLayout.removeAllTabs()
        }
        if (tabsFrame != null && tabsFrame.isVisible) {
            tabsFrame.isVisible = false
            tabsFrame.alpha = 0f
        }
        appliedTabItems = emptyList()
        appliedTabMode = null
        onTabSelected = null
        onTabReselected = null
        suppressTabCallbacks = false
        useTabsInPreLayout = false
    }

    /**
     * Defensive reset to a known-clean baseline. Idempotent. Called by the activity on
     * every controller-change before the incoming controller's chrome wiring runs, so
     * any slots the previous controller left populated (tabs row, search pill, lifted
     * menu, scroll-offset state) are vacated. This is the single invariant enforcement
     * point — controllers no longer have to clean up after themselves.
     */
    fun resetToBaseline() {
        clearTabs()
        cardFrame?.isVisible = false
        cardFrame?.alpha = 0f
        dropLiftedPillMenu()
        currentActiveToolbar = null
        lastToolbarTitleAlpha = INVALID_TITLE_ALPHA
        searchToolbar?.searchItem?.collapseActionView()
        // mainToolbar may have been made invisible / gone by a SEARCH_ONLY or scroll-
        // collapsed previous controller — restore to fully-visible so the next
        // controller's chrome renders correctly even if it doesn't call scrollViewWith.
        mainToolbar?.isGone = false
        mainToolbar?.isInvisible = false
        mainToolbar?.alpha = 1f
        mainToolbar?.translationY = 0f
        lockYPos = false
        translationY = 0f
        y = 0f
        alpha = 1f
        isInvisible = false
        useTabsInPreLayout = false
        compactSearchMode = false
    }

    fun setTitle(title: CharSequence?, setBigTitle: Boolean) {
        if (setBigTitle) {
            if (bigTitleView?.text != title) invalidatePreLayoutHeight()
            bigTitleView?.text = title
        }
        mainToolbar?.title = title
    }

    override fun setTranslationY(translationY: Float) {
        if (lockYPos) return
        val realHeight = (preLayoutHeightWhileSearching + paddingTop).toFloat()
        val newY = if (dontFullyHideToolbar && !useLargeToolbar) {
            0f
        } else {
            MathUtils.clamp(
                translationY,
                -realHeight + (if (context.isTablet()) minTabletHeight else 0),
                if (compactSearchMode && toolbarMode == ToolbarState.EXPANDED) -realHeight + top + minTabletHeight else 0f,
            )
        }
        super.setTranslationY(newY)
    }

    // Cached big-title height. getEstimatedLayout runs on every scroll frame (via
    // preLayoutHeightWhileSearching in updateAppBarAfterY/setTranslationY); measuring the
    // bigTitleView there is per-frame jank. Cache the measure and invalidate only when an
    // input to it changes (layout pass, config change, title text, tab/search-mode toggle).
    private var cachedBigTitleHeight: Int = INVALID_TITLE_HEIGHT

    /** Drop the cached [bigTitleView] height so the next [getEstimatedLayout] re-measures. */
    fun invalidatePreLayoutHeight() {
        cachedBigTitleHeight = INVALID_TITLE_HEIGHT
    }

    private fun bigTitleHeight(): Int {
        cachedBigTitleHeight.takeIf { it != INVALID_TITLE_HEIGHT }?.let { return it }
        val widthMeasureSpec = MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels, MeasureSpec.AT_MOST)
        val heightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        bigTitleView?.measure(widthMeasureSpec, heightMeasureSpec)
        val textHeight = max(bigTitleView?.height ?: 0, bigTitleView?.measuredHeight ?: 0) +
            (bigTitleView?.marginTop?.plus(bigView?.paddingBottom ?: 0) ?: 0)
        cachedBigTitleHeight = textHeight
        return textHeight
    }

    fun getEstimatedLayout(includeSearchToolbar: Boolean, includeTabs: Boolean, includeLargeToolbar: Boolean, ignoreSearch: Boolean = false): Int {
        val hasLargeToolbar = includeLargeToolbar && useLargeToolbar && (!compactSearchMode || ignoreSearch)
        val appBarHeight = attrToolbarHeight * (if (includeSearchToolbar && hasLargeToolbar) 2 else 1)
        return appBarHeight + (if (hasLargeToolbar) bigTitleHeight() else 0) +
            if (includeTabs) 48.dpToPx else 0
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        // Config change alters big-title text appearance and the measure width.
        invalidatePreLayoutHeight()
        shrinkAppBarIfNeeded(newConfig)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // A real layout pass resolves the big-title's actual height; drop the estimate so the
        // next read reflects it.
        if (changed) invalidatePreLayoutHeight()
    }

    /**
     * For smaller devices, update the big view (with the large title) to be a smaller font and
     * less padding
     */
    private fun shrinkAppBarIfNeeded(config: Configuration?) {
        config ?: return
        dontFullyHideToolbar = config.smallestScreenWidthDp > 600
        isExtraSmall = false
        if (config.screenHeightDp < 600) {
            val bigTitleView = bigTitleView ?: return
            isExtraSmall = config.screenWidthDp < 720
            if (isExtraSmall) {
                setToolbarModeBy(null, true)
                return
            }
            val attrs = intArrayOf(materialR.attr.textAppearanceHeadlineMedium)
            val ta = context.obtainStyledAttributes(attrs)
            val resId = ta.getResourceId(0, 0)
            ta.recycle()
            TextViewCompat.setTextAppearance(bigTitleView, resId)
            bigTitleView.setTextColor(context.getResourceColor(R.attr.actionBarTintColor))
            bigTitleView.updateLayoutParams<MarginLayoutParams> {
                topMargin = 12.dpToPx
            }
            imageView?.updateLayoutParams<MarginLayoutParams> {
                height = 48.dpToPx
                width = 48.dpToPx
            }
            imageLayout?.updateLayoutParams<MarginLayoutParams> {
                height = 48.dpToPx
            }
        }
    }

    /**
     * Update the views in appbar based on its current Y position
     *
     * @param recyclerOrNested used to determine how far it has scrolled down, if it has not scrolled
     * past the app bar's height, match the Y to the recyclerView's offset
     * @param cancelAnim if true, cancel the current snap animation
     */
    fun updateAppBarAfterY(scrollView: ScrollingView?, cancelAnim: Boolean = true) {
        if (cancelAnim) {
            yAnimator?.cancel()
        }
        if (lockYPos) return
        val offset = scrollView?.computeVerticalScrollOffset() ?: 0
        val bigHeight = bigView?.height ?: 0
        val realHeight = preLayoutHeightWhileSearching + paddingTop
        val tabHeight = if (tabsFrameLayout?.isVisible == true) 48.dpToPx else 0
        val shortH = if (toolbarMode != ToolbarState.EXPANDED || compactSearchMode) 0f else compactAppBarHeight
        val smallHeight = -realHeight + shortH + tabHeight
        val newY = when {
            // for smaller devices, when search is active, we want to shrink the app bar and never
            // extend it pass the compact state
            toolbarMode == ToolbarState.EXPANDED && compactSearchMode -> {
                MathUtils.clamp(
                    translationY,
                    -realHeight.toFloat() + top + if (context.isTablet()) minTabletHeight else 0,
                    -realHeight.toFloat() + top + minTabletHeight,
                )
            }
            // for regular compact modes, no need to clamp, setTranslationY will take care of it
            toolbarMode != ToolbarState.EXPANDED -> {
                translationY
            }
            // if the recycler hasn't scrolled past the app bars height...
            offset < realHeight - shortH - tabHeight -> {
                -offset.toFloat()
            }
            else -> {
                MathUtils.clamp(
                    translationY,
                    -realHeight.toFloat() + top + minTabletHeight,
                    max(
                        smallHeight,
                        if (offset > realHeight - shortH - tabHeight) {
                            smallHeight
                        } else {
                            min(
                                -offset.toFloat(),
                                0f,
                            )
                        },
                    ) + top.toFloat(),
                )
            }
        }

        translationY = newY
        mainToolbar?.let { mainToolbar ->
            mainToolbar.translationY = when {
                toolbarMode != ToolbarState.EXPANDED -> 0f
                -newY <= bigHeight -> max(-newY, 0f)
                else -> bigHeight.toFloat()
            }
        }
        if (toolbarMode != ToolbarState.EXPANDED || compactSearchMode) {
            if (compactSearchMode && toolbarMode == ToolbarState.EXPANDED) {
                bigView?.alpha = 0f
                mainToolbar?.alpha = 0f
                cardFrame?.backgroundColor = null
            } else {
                mainToolbar?.alpha = 1f
            }
            useSearchToolbarForMenu(compactSearchMode || offset > realHeight - shortH - tabHeight)
            return
        }
        // If toolbar is expanded, we want to fade out the big view, then later the main toolbar
        val alpha =
            (bigHeight + newY * 2) / (bigHeight) + 0.45f // (realHeight.toFloat() + newY * 5) / realHeight.toFloat() + .33f
        bigView?.alpha = MathUtils.clamp(if (alpha.isNaN()) 1f else alpha, 0f, 1f)
        if (mainToolbar?.toolbarTitle == null) return
        setToolbarTitleAlpha(
            (
                MathUtils.clamp(
                    (1 - ((if (alpha.isNaN()) 1f else alpha) + 0.95f)) * 2,
                    0f,
                    1f,
                ) * 255
                ).roundToInt(),
        )
        val mainToolbar = mainToolbar ?: return
        mainToolbar.alpha = MathUtils.clamp(
            (mainToolbar.bottom + mainToolbar.translationY + y - paddingTop) / mainToolbar.height,
            0f,
            1f,
        )
        val useSearchToolbar = mainToolbar.alpha <= 0.025f
        val idle = RecyclerView.SCROLL_STATE_IDLE
        val state = when (scrollView) {
            is RecyclerView -> scrollView.scrollState
            is StatefulNestedScrollView -> if (scrollView.hasStopped) idle else RecyclerView.SCROLL_STATE_DRAGGING
            else -> idle
        }
        if (if (useSearchToolbar) {
            -y >= height || (state <= idle) || context.isTablet()
        } else {
                currentActiveToolbar == searchToolbar
            }
        ) {
            useSearchToolbarForMenu(useSearchToolbar)
        }
    }

    /**
     * Snap Appbar to hide the entire appbar or show the smaller toolbar
     *
     * Only snaps if the [scrollView] has scrolled farther than the current app bar's height
     * @param callback closure updates along with snapping the appbar, use if something needs to
     * update alongside the appbar
     */
    fun snapAppBarY(controller: Controller?, scrollView: ScrollingView, callback: (() -> Unit)?): Float {
        val halfWay = compactAppBarHeight / 2
        val shortAnimationDuration = resources?.getInteger(
            if (toolbarMode != ToolbarState.EXPANDED) {
                AR.integer.config_shortAnimTime
            } else {
                AR.integer.config_longAnimTime
            },
        ) ?: 0
        val realHeight = preLayoutHeightWhileSearching + paddingTop
        val closerToTop = abs(y) > realHeight - halfWay
        val atTop = !(scrollView as View).canScrollVertically(-1)
        val shortH =
            if (toolbarMode != ToolbarState.EXPANDED || compactSearchMode) 0f else compactAppBarHeight
        val lastY = if (closerToTop && !atTop) {
            -height.toFloat()
        } else {
            shortH
        }

        val onFirstItem = scrollView.computeVerticalScrollOffset() < realHeight - shortH

        return if (!onFirstItem) {
            yAnimator = animate().y(lastY)
                .setDuration(shortAnimationDuration.toLong())
            yAnimator?.setUpdateListener {
                if (controller?.isControllerVisible == true) {
                    updateAppBarAfterY(scrollView, false)
                    callback?.invoke()
                }
            }
            yAnimator?.start()
            useSearchToolbarForMenu(true)
            lastY
        } else {
            useSearchToolbarForMenu((mainToolbar?.alpha ?: 0f) <= 0f)
            y
        }
    }

    /**
     * Background intensity for the current appbar position.
     *
     * Expanded appbars keep the surface background until the large area has fully collapsed.
     * This matches Recents: the list may be scrolled, but the chrome should not switch to its
     * elevated background while the large title/search area is still visible.
     */
    fun backgroundProgressForScroll(notAtTop: Boolean): Float {
        if (!notAtTop) {
            collapsedBackgroundVisible = false
            return 0f
        }

        val collapsedY = yNeededForSmallToolbar.toFloat()
        if (toolbarMode == ToolbarState.EXPANDED && collapsedY >= 0f) {
            collapsedBackgroundVisible = false
            return 0f
        }
        return if (collapsedY < 0f) {
            val collapseEdge = collapsedY
            val expandResetEdge = collapsedY + 12.dpToPx
            when {
                y <= collapseEdge -> collapsedBackgroundVisible = true
                y > expandResetEdge -> collapsedBackgroundVisible = false
            }
            if (collapsedBackgroundVisible) 1f else 0f
        } else {
            1f
        }
    }

    /**
     * Swap the appBar's "active toolbar" — either [mainToolbar] or [searchToolbar].
     * Driven by scroll position and search-pill state; the appBar updates its own
     * visibility/background per the chosen toolbar and lifts the main toolbar's
     * action items onto the pill (or drops them) so they're reachable in whichever
     * mode is active. State is local to this instance; there is no longer an
     * activity-level `currentToolbar` coordinator.
     */
    fun useSearchToolbarForMenu(showCardTB: Boolean) {
        if (lockYPos) return
        val main = mainToolbar
        val search = searchToolbar
        val card = cardFrame
        val wantSearchTB = (showCardTB || toolbarMode == ToolbarState.SEARCH_ONLY) && card?.isVisible == true
        if (wantSearchTB && search != null) {
            if (currentActiveToolbar === search) return
            currentActiveToolbar = search
            if (toolbarMode == ToolbarState.EXPANDED) {
                main?.isInvisible = true
            }
            main?.backgroundColor = null
            card.backgroundColor = null
            liftMenuToPill()
        } else {
            if (currentActiveToolbar === main) return
            currentActiveToolbar = main
            if (toolbarMode == ToolbarState.EXPANDED) {
                main?.isInvisible = false
            }
            card?.backgroundColor = if (tabsFrameLayout?.isVisible == false) {
                context.getResourceColor(materialR.attr.colorSurface)
            } else {
                null
            }
            dropLiftedPillMenu()
        }
    }

    /**
     * Mirror the [mainToolbar]'s non-search action items onto the [searchToolbar]
     * (the pill). Called when the appBar collapses to compact mode so the controller's
     * filter / overflow / etc. stay reachable while the main toolbar is hidden.
     *
     * Skips [R.id.action_search] — the pill already has its own from
     * [FloatingToolbar.onFinishInflate], and that one carries the SearchView action view.
     *
     * Idempotent: items already present (matched by itemId) are left alone, so calling
     * this repeatedly during scroll won't churn the menu state.
     */
    private fun liftMenuToPill() {
        val source = mainToolbar?.menu ?: return
        val dest = searchToolbar?.menu ?: return
        var i = 0
        val sourceCount = source.size()
        while (i < sourceCount) {
            val item = source.getItem(i)
            i++
            if (item.itemId == R.id.action_search) continue
            if (dest.findItem(item.itemId) != null) continue
            val added = dest.add(item.groupId, item.itemId, item.order, item.title)
            added.icon = item.icon
            added.isVisible = item.isVisible
            added.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
    }

    /** Re-copy newly inflated main-toolbar actions when the pill is already active. */
    internal fun refreshLiftedPillMenu() {
        if (currentActiveToolbar === searchToolbar) liftMenuToPill()
    }

    /**
     * Inverse of [liftMenuToPill] — removes the lifted action items from the pill so
     * they don't double-up with the now-visible [mainToolbar] in expanded mode.
     * Leaves [R.id.action_search] in place (intrinsic to the pill).
     */
    internal fun dropLiftedPillMenu() {
        val menu = searchToolbar?.menu ?: return
        // Remove backwards so the menu can be mutated in place without allocating a
        // temporary list. This method is part of scroll/chrome transitions.
        var i = menu.size() - 1
        while (i >= 0) {
            val id = menu.getItem(i).itemId
            if (id != R.id.action_search) menu.removeItem(id)
            i--
        }
    }

    private fun setToolbarTitleAlpha(alpha: Int) {
        if (lastToolbarTitleAlpha == alpha) return
        lastToolbarTitleAlpha = alpha
        mainToolbar?.toolbarTitle?.setTextColorAlpha(alpha)
    }

    companion object {
        private const val INVALID_TITLE_HEIGHT = -1
        private const val INVALID_TITLE_ALPHA = -1
    }
}

interface SmallToolbarInterface
