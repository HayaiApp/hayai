package eu.kanade.tachiyomi.util.view

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.BackEventCompat
import androidx.annotation.CallSuper
import androidx.annotation.MainThread
import androidx.appcompat.widget.SearchView
import androidx.cardview.widget.CardView
import androidx.core.content.getSystemService
import androidx.core.graphics.ColorUtils
import androidx.core.math.MathUtils
import androidx.core.net.toUri
import androidx.core.view.ScrollingView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type.ime
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.doOnLayout
import androidx.core.view.forEach
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.SimpleSwapChangeHandler
import com.google.android.material.R as materialR
import com.google.android.material.snackbar.Snackbar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.MainActivityBinding
import eu.kanade.tachiyomi.ui.base.ExpandedAppBarLayout
import eu.kanade.tachiyomi.ui.base.FloatingToolbar
import eu.kanade.tachiyomi.ui.base.LocalAppBarOwner
import eu.kanade.tachiyomi.ui.base.SmallToolbarInterface
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.ui.base.controller.BaseController
import eu.kanade.tachiyomi.ui.base.controller.BaseLegacyController
import eu.kanade.tachiyomi.ui.base.controller.CrossFadeChangeHandler
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.FadeChangeHandler
import eu.kanade.tachiyomi.ui.base.controller.OneWayFadeChangeHandler
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.TabbedInterface
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.ignoredSystemInsets
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.system.toInt
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.widget.StatefulNestedScrollView
import yokai.util.koin.injectLazy
import yokai.i18n.MR
import yokai.util.lang.getString
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import android.R as AR

fun Controller.setOnQueryTextChangeListener(
    searchView: SearchView?,
    onlyOnSubmit: Boolean = false,
    hideKbOnSubmit: Boolean = true,
    f: (text: String?) -> Boolean,
) {
    searchView?.setOnQueryTextListener(
        object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String?): Boolean {
                if (!onlyOnSubmit && router.backstack.lastOrNull()
                    ?.controller == this@setOnQueryTextChangeListener
                ) {
                    return f(newText)
                }
                return false
            }

            override fun onQueryTextSubmit(query: String?): Boolean {
                if (isControllerVisible) {
                    if (hideKbOnSubmit) {
                        val imm =
                            activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                ?: return f(query)
                        imm.hideSoftInputFromWindow(searchView.windowToken, 0)
                    }
                    return f(query)
                }
                return true
            }
        },
    )
}

/**
 * Install [menuRes] on the controller's local [appBar]. The controller's overflow
 * items (filter / 3-dot / etc.) are inflated onto the **main toolbar only** — the
 * search pill's menu is kept lean with just its intrinsic [R.id.action_search]
 * (the SearchView action item).
 *
 * When the user scrolls and [ExpandedAppBarLayout.useSearchToolbarForMenu] collapses
 * the bar to compact mode, the same menu items are lifted onto the pill so they
 * stay reachable while the main toolbar is hidden. This mirrors the activity-global
 * pattern in [MainActivity.setupSearchTBMenu] and is the difference between "actions
 * appear once in the right place" and "actions appear twice stacked on top of each
 * other" (the second was a regression that briefly shipped).
 *
 * Click dispatch is wired toolbar-level (so it survives menu rebuilds): the controller's
 * own [Controller.onOptionsItemSelected] gets the first crack, with a fallback to
 * [MainActivity.onOptionsItemSelected] for items the controller doesn't claim
 * (notably [R.id.action_more], which the activity owns the global overflow dialog for).
 */
fun Controller.installLocalMenu(menuRes: Int) {
    val appBar = appBar() ?: return
    val main = appBar.mainToolbar ?: return

    if (main.menu.size() == 0) {
        main.inflateMenu(menuRes)
    }
    val dispatch: (MenuItem) -> Boolean = { item ->
        onOptionsItemSelected(item) || (activity as? MainActivity)?.onOptionsItemSelected(item) == true
    }
    main.setOnMenuItemClickListener(dispatch)
    // Pre-wire the pill's dispatch too. The pill receives its menu items dynamically
    // from [ExpandedAppBarLayout.useSearchToolbarForMenu] on scroll-collapse; this
    // listener has to be in place ahead of time so taps work the moment items appear.
    // BaseController.wireLocalSearchToolbar sets the toolbar-level click handler;
    // re-set it here to guarantee ordering (wireDefaultLocalChrome runs before
    // controller's onSetupLocalChrome which calls installLocalMenu).
    appBar.searchToolbar?.setOnMenuItemClickListener(dispatch)
}

fun Controller.removeQueryListener(includeSearchTB: Boolean = true) {
    val noop = object : SearchView.OnQueryTextListener {
        override fun onQueryTextSubmit(query: String?) = true
        override fun onQueryTextChange(newText: String?) = true
    }
    val pillSearch = searchToolbar()?.menu?.findItem(R.id.action_search)?.actionView as? SearchView
    // Controllers that host their own appBar inflate their menu onto appBar.mainToolbar;
    // clear that listener too so a swiped-away controller can't keep receiving query events.
    val mainSearch = appBar()?.mainToolbar?.menu?.findItem(R.id.action_search)?.actionView as? SearchView
    val activitySearch = activityBinding?.toolbar?.menu?.findItem(R.id.action_search)?.actionView as? SearchView
    if (includeSearchTB) {
        pillSearch?.setOnQueryTextListener(noop)
    }
    mainSearch?.setOnQueryTextListener(noop)
    activitySearch?.setOnQueryTextListener(noop)
}

fun <T> Controller.liftAppbarWith(
    recyclerOrNested: T,
    padView: Boolean = false,
    changeMarginsInstead: Boolean = false,
    liftOnScroll: ((Boolean) -> Unit)? = null,
) {
    val recycler = recyclerOrNested as? RecyclerView ?: recyclerOrNested as? NestedScrollView ?: return
    if (padView) {
        var appBarHeight = (
            if ((fullAppBarHeight ?: 0) > 0) {
                fullAppBarHeight!!
            } else {
                appBar()?.attrToolbarHeight ?: 0
            }
            )
        appBar()?.mainToolbar?.post {
            if (fullAppBarHeight!! > 0) {
                appBarHeight = fullAppBarHeight!!
                recycler.requestApplyInsets()
            }
        }
        val initialToolbarY = appBar()?.mainToolbar?.y?.toInt() ?: 0
        recycler.updatePaddingRelative(
            top = if (changeMarginsInstead) 0 else initialToolbarY + appBarHeight,
            bottom = recycler.rootWindowInsetsCompat?.getInsets(systemBars())?.bottom ?: 0,
        )
        if (changeMarginsInstead) {
            recycler.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = initialToolbarY + appBarHeight
            }
        }
        recycler.applyBottomAnimatedInsets(setPadding = true) { view, insets ->
            val headerHeight = insets.getInsets(systemBars()).top + appBarHeight
            if (changeMarginsInstead) {
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = headerHeight
                }
            } else {
                view.updatePaddingRelative(top = headerHeight)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            recycler.doOnApplyWindowInsetsCompat { view, insets, _ ->
                val headerHeight = insets.getInsets(systemBars()).top + appBarHeight
                view.updatePaddingRelative(
                    top = if (changeMarginsInstead) 0 else headerHeight,
                    bottom = insets.getInsets(ime() or systemBars()).bottom,
                )
                if (changeMarginsInstead) {
                    view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        topMargin = headerHeight
                    }
                }
            }
        }
    } else {
        view?.applyWindowInsetsForController(appBar()?.attrToolbarHeight ?: 0)
        recycler.setOnApplyWindowInsetsListener(RecyclerWindowInsetsListener)
    }

    var toolbarColorAnim: ValueAnimator? = null
    var isToolbarColored = false

    val colorToolbar: (Boolean) -> Unit = f@{ isColored ->
        isToolbarColored = isColored
        toolbarColorAnim?.cancel()
        liftOnScroll?.invoke(isColored)
        val floatingBar =
            !(activityBinding?.toolbar?.isVisible == true || activityBinding?.tabsFrameLayout?.isVisible == true)
        val percent = ImageUtil.getPercentOfColor(
            appBar()!!.backgroundColor ?: Color.TRANSPARENT,
            activity!!.getResourceColor(materialR.attr.colorSurface),
            activity!!.getResourceColor(materialR.attr.colorPrimaryVariant),
        )
        if (floatingBar) {
            setAppBarBG(0f)
            return@f
        }
        toolbarColorAnim = ValueAnimator.ofFloat(percent, isColored.toInt().toFloat())
        toolbarColorAnim?.addUpdateListener { valueAnimator ->
            setAppBarBG(valueAnimator.animatedValue as Float)
        }
        toolbarColorAnim?.start()
    }

    val floatingBar =
        !(activityBinding?.toolbar?.isVisible == true || activityBinding?.tabsFrameLayout?.isVisible == true)
    if (floatingBar) {
        setAppBarBG(0f)
    }

    appBar()?.setToolbarModeBy(this)
    appBar()?.hideBigView(true)
    appBar()?.y = 0f
    appBar()?.updateAppBarAfterY(recycler)

    setAppBarBG(0f)
    (recycler as? NestedScrollView)?.setOnScrollChangeListener { _, _, _, _, _ ->
        if (router?.backstack?.lastOrNull()
            ?.controller == this@liftAppbarWith && activity != null
        ) {
            val notAtTop = recycler.canScrollVertically(-1)
            if (notAtTop != isToolbarColored) colorToolbar(notAtTop)
        }
    }
    (recycler as? RecyclerView)?.addOnScrollListener(
        object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (router?.backstack?.lastOrNull()
                    ?.controller == this@liftAppbarWith && activity != null
                ) {
                    val notAtTop = recycler.canScrollVertically(-1)
                    if (notAtTop != isToolbarColored) colorToolbar(notAtTop)
                }
            }
        },
    )
    addLifecycleListener(
        object : Controller.LifecycleListener() {
            override fun onChangeStart(
                controller: Controller,
                changeHandler: ControllerChangeHandler,
                changeType: ControllerChangeType,
            ) {
                super.onChangeStart(controller, changeHandler, changeType)
                if (changeType.isEnter) {
                    appBar()?.hideBigView(
                        true,
                        setTitleAlpha = this@liftAppbarWith !is MangaDetailsController,
                    )
                    appBar()?.setToolbarModeBy(this@liftAppbarWith)
                    // useTabsInPreLayout is owned by the controller's onSetupLocalChrome.
                    colorToolbar(isToolbarColored)
                    appBar()?.updateAppBarAfterY(recycler)
                }
            }
        },
    )
}

fun Controller.scrollViewWith(
    recycler: ScrollingView,
    padBottom: Boolean = false,
    customPadding: Boolean = false,
    ignoreInsetVisibility: Boolean = false,
    swipeRefreshLayout: SwipeRefreshLayout? = null,
    afterInsets: ((WindowInsetsCompat) -> Unit)? = null,
    liftOnScroll: ((Boolean) -> Unit)? = null,
    onLeavingController: (() -> Unit)? = null,
    onBottomNavUpdate: (() -> Unit)? = null,
): ((Boolean) -> Unit) {
    if (recycler !is View) return { _ -> }
    val appBar = appBar() ?: return { _ -> }
    var statusBarHeight = -1
    val tabBarHeight = 48.dpToPx
    appBar.lockYPos = false
    appBar.y = 0f
    val includeTabView: () -> Boolean = {
        (this@scrollViewWith as? TabbedInterface)?.showActivityTabs == true
    }
    // useTabsInPreLayout is owned by the controller's onSetupLocalChrome.
    appBar.setToolbarModeBy(this@scrollViewWith)
    var appBarHeight = (
        if ((fullAppBarHeight ?: 0) > 0) {
            fullAppBarHeight!!
        } else {
            appBar.preLayoutHeight
        }
        )
    swipeRefreshLayout?.setDistanceToTriggerSync(150.dpToPx)
    val swipeCircle = swipeRefreshLayout?.findChild<ImageView>()
    appBar.doOnLayout {
        if ((fullAppBarHeight ?: 0) > 0 && isControllerVisible) {
            appBarHeight = fullAppBarHeight!!
            recycler.requestApplyInsets()
        }
    }
    val updateViewsNearBottom = {
        onBottomNavUpdate?.invoke()
        activityBinding?.bottomView?.translationY = activityBinding?.bottomNav?.translationY ?: 0f
    }
    recycler.post {
        updateViewsNearBottom()
    }

    (recycler as? RecyclerView)?.let { setItemAnimatorForAppBar(it) }

    val randomTag = Random.nextLong()
    var lastY = 0f
    // Small dead-zone before the large toolbar starts collapsing/translating. Recents
    // (liftAppbarWith) only reacts on real offset; the large-toolbar pipeline used to move
    // appBar.y from the very first pixel, so a 1px scroll visibly slid + recolored the bar.
    // Accumulate scroll while pinned at the top and only begin collapsing past this threshold.
    val collapseThreshold = 12.dpToPx
    var pinnedScrollAccum = 0
    var fakeToolbarView: View? = null
    val preferences: PreferencesHelper by injectLazy()
    var fakeBottomNavView: View? = null
    if (!customPadding) {
        recycler.updatePaddingRelative(
            top = (
                activity?.window?.decorView?.rootWindowInsetsCompat?.getInsets(systemBars())?.top
                    ?: 0
                ) + appBarHeight,
        )
    }
    val atTopOfRecyclerView: () -> Boolean = f@{
        if (this is SmallToolbarInterface || appBar.useLargeToolbar == false) {
            return@f !recycler.canScrollVertically(-1)
        }
        val activityBinding = activityBinding ?: return@f true
        return@f recycler.computeVerticalScrollOffset() - recycler.paddingTop <=
            0 - appBar.paddingTop -
            activityBinding.toolbar.height - if (includeTabView()) tabBarHeight else 0
    }
    recycler.doOnApplyWindowInsetsCompat { view, insets, _ ->
        appBarHeight = fullAppBarHeight ?: 0
        val systemInsets = if (ignoreInsetVisibility) insets.ignoredSystemInsets else insets.getInsets(systemBars())
        val headerHeight = systemInsets.top + appBarHeight
        if (!customPadding) {
            view.updatePaddingRelative(
                top = headerHeight,
                bottom = if (padBottom) systemInsets.bottom else view.paddingBottom,
            )
        }
        swipeRefreshLayout?.setProgressViewOffset(
            true,
            headerHeight + (-60).dpToPx,
            headerHeight + 10.dpToPx,
        )
        statusBarHeight = systemInsets.top
        afterInsets?.invoke(insets)
    }

    var toolbarColorAnim: ValueAnimator? = null
    var isToolbarColor = false
    var isInView = true
    val colorToolbar: (Boolean) -> Unit = f@{ isColored ->
        isToolbarColor = isColored
        if (liftOnScroll != null) {
            liftOnScroll.invoke(isColored)
        } else {
            toolbarColorAnim?.cancel()
            val floatingBar =
                (this as? FloatingSearchInterface)?.showFloatingBar() == true && !includeTabView()
            if (floatingBar) {
                setAppBarBG(isColored.toInt().toFloat(), false)
                return@f
            }
            val percent = ImageUtil.getPercentOfColor(
                appBar.backgroundColor ?: Color.TRANSPARENT,
                activity!!.getResourceColor(materialR.attr.colorSurface),
                activity!!.getResourceColor(materialR.attr.colorPrimaryVariant),
            )
            toolbarColorAnim = ValueAnimator.ofFloat(percent, isColored.toInt().toFloat())
            toolbarColorAnim?.addUpdateListener { valueAnimator ->
                setAppBarBG(valueAnimator.animatedValue as Float, includeTabView())
            }
            toolbarColorAnim?.start()
        }
    }
    if ((this as? FloatingSearchInterface)?.showFloatingBar() == true && !includeTabView()) {
        setAppBarBG(0f, false)
    }
    addLifecycleListener(
        object : Controller.LifecycleListener() {
            override fun onChangeEnd(
                controller: Controller,
                changeHandler: ControllerChangeHandler,
                changeType: ControllerChangeType,
            ) {
                super.onChangeEnd(controller, changeHandler, changeType)
                if (changeType.isEnter) {
                    // Reposition the appbar now that the transition is done — moved from
                    // onChangeStart so it doesn't snap mid-fade.
                    appBar.updateAppBarAfterY(recycler)
                    if (fakeToolbarView?.parent != null) {
                        val parent = fakeToolbarView?.parent as? ViewGroup ?: return
                        parent.removeView(fakeToolbarView)
                        fakeToolbarView = null
                    }
                    if (fakeBottomNavView?.parent != null) {
                        val parent = fakeBottomNavView?.parent as? ViewGroup ?: return
                        parent.removeView(fakeBottomNavView)
                        fakeBottomNavView = null
                    }
                }
            }

            override fun onChangeStart(
                controller: Controller,
                changeHandler: ControllerChangeHandler,
                changeType: ControllerChangeType,
            ) {
                super.onChangeStart(controller, changeHandler, changeType)
                isInView = changeType.isEnter
                if (changeType.isEnter) {
                    appBar.hideBigView(
                        this@scrollViewWith is SmallToolbarInterface,
                        setTitleAlpha = this@scrollViewWith !is MangaDetailsController,
                    )
                    appBar.setToolbarModeBy(this@scrollViewWith)
                    // useTabsInPreLayout is owned by the controller's onSetupLocalChrome.
                    colorToolbar(isToolbarColor)
                    lastY = 0f
                    // Don't reposition the activity appbar Y here — that snaps the bar from
                    // the outgoing controller's scroll position to the incoming controller's
                    // fresh-scroll position instantly at the start of the Conductor crossfade,
                    // which the user reads as "topbar snaps to the top without animating".
                    // Deferred to onChangeEnd so the swap is masked by the fade completion.
                    activityBinding?.toolbar?.tag = randomTag
                    activityBinding?.toolbar?.setOnClickListener {
                        if (recycler is RecyclerView) {
                            recycler.smoothScrollToTop()
                        } else if (recycler is NestedScrollView) {
                            recycler.smoothScrollTo(0, 0)
                        }
                    }
                } else {
                    if (!customPadding && lastY == 0f && (
                        (
                            this@scrollViewWith !is FloatingSearchInterface && router.backstack.lastOrNull()
                                ?.controller is MangaDetailsController
                            ) || includeTabView()
                        )
                    ) {
                        val parent = recycler.parent as? ViewGroup ?: return
                        val v = View(activity)
                        fakeToolbarView = v
                        parent.addView(v, parent.indexOfChild(recycler) + 1)
                        val params = fakeToolbarView?.layoutParams
                        params?.height = recycler.paddingTop
                        params?.width = MATCH_PARENT
                        v.setBackgroundColor(v.context.getResourceColor(materialR.attr.colorSurface))
                        v.layoutParams = params
                        onLeavingController?.invoke()
                    }
                    if (!customPadding && router.backstackSize == 2 && changeType == ControllerChangeType.PUSH_EXIT &&
                        router.backstack.lastOrNull()?.controller !is DialogController
                    ) {
                        val parent = recycler.parent as? ViewGroup ?: return
                        val bottomNav = activityBinding?.bottomNav ?: return
                        val v = View(activity)
                        fakeBottomNavView = v
                        parent.addView(v)
                        val params = fakeBottomNavView?.layoutParams
                        params?.height = bottomNav.height
                        (params as? FrameLayout.LayoutParams)?.gravity = Gravity.BOTTOM
                        fakeBottomNavView?.translationY = bottomNav.translationY
                        params?.width = MATCH_PARENT
                        v.setBackgroundColor(v.context.getResourceColor(materialR.attr.colorPrimaryVariant))
                        v.layoutParams = params
                    }
                    toolbarColorAnim?.cancel()
                    if (activityBinding?.toolbar?.tag == randomTag) {
                        activityBinding?.toolbar?.setOnClickListener(null)
                    }
                }
            }
        },
    )
    colorToolbar(!atTopOfRecyclerView())

    recycler.post {
        if (isControllerVisible) {
            appBar.updateAppBarAfterY(recycler)
            colorToolbar(!atTopOfRecyclerView())
        }
    }

    fun updateBottomNavOnScroll(dy: Int) {
        activityBinding?.bottomNav?.let { bottomNav ->
            if (bottomNav.isVisible && isInView) {
                if (preferences.hideBottomNavOnScroll().get()) {
                    bottomNav.translationY += dy
                    bottomNav.translationY = MathUtils.clamp(
                        bottomNav.translationY,
                        0f,
                        bottomNav.height.toFloat(),
                    )
                    updateViewsNearBottom()
                } else if (bottomNav.translationY != 0f) {
                    bottomNav.translationY = 0f
                    activityBinding!!.bottomView?.translationY = 0f
                }
            }
        }
    }

    fun onScrolled(dy: Int) {
        if (isControllerVisible && statusBarHeight > -1 &&
            (this@scrollViewWith as? BaseLegacyController<*>)?.isDragging != true &&
            activity != null && appBar.height > 0 &&
            recycler.translationY == 0f
        ) {
            if (!recycler.canScrollVertically(-1)) {
                val shortAnimationDuration = resources?.getInteger(
                    AR.integer.config_shortAnimTime,
                ) ?: 0
                appBar.y = 0f
                appBar.updateAppBarAfterY(recycler)
                if (router.backstackSize == 1 && isInView) {
                    activityBinding!!.bottomNav?.let {
                        val animator = it.animate()?.translationY(0f)
                            ?.setDuration(shortAnimationDuration.toLong())
                        animator?.setUpdateListener {
                            updateViewsNearBottom()
                        }
                        animator?.start()
                    }
                }
                lastY = 0f
                pinnedScrollAccum = 0
                if (isToolbarColor) colorToolbar(false)
            } else {
                // Dead-zone: while the large toolbar is still fully expanded, absorb the first few
                // pixels of a downward scroll instead of immediately sliding/recoloring it. Once
                // accumulated scroll crosses the threshold, collapse normally. Compact / small-
                // toolbar screens have no big view to slide, so they're unaffected.
                val useThreshold = this@scrollViewWith !is SmallToolbarInterface &&
                    appBar.useLargeToolbar && appBar.y >= 0f
                if (useThreshold && dy > 0 && pinnedScrollAccum < collapseThreshold) {
                    pinnedScrollAccum += dy
                    if (pinnedScrollAccum < collapseThreshold) {
                        appBar.y = 0f
                        appBar.updateAppBarAfterY(recycler)
                        // Track the bottom nav from the first pixel: the dead-zone only pins the
                        // appbar, it must not delay hide-on-scroll for the bottom nav.
                        updateBottomNavOnScroll(dy)
                        appBar.let {
                            swipeCircle?.translationY = max(it.y, -it.height + it.paddingTop.toFloat())
                        }
                        return
                    }
                }
                if (appBar.y >= 0f) pinnedScrollAccum = collapseThreshold
                appBar.y -= dy
                appBar.updateAppBarAfterY(recycler)
                updateBottomNavOnScroll(dy)

                // Re-arm the dead-zone once the bar scrolls back to fully expanded.
                if (appBar.y >= 0f && dy < 0) pinnedScrollAccum = 0
                if (!isToolbarColor && appBar.y <= -appBar.height.toFloat()) {
                    colorToolbar(true)
                }
                val notAtTop = !atTopOfRecyclerView()
                if (notAtTop != isToolbarColor) colorToolbar(notAtTop)
                lastY = appBar.y
            }
            appBar.let {
                swipeCircle?.translationY = max(it.y, -it.height + it.paddingTop.toFloat())
            }
        }
    }

    fun onScrollIdle() {
        val activityBinding = activityBinding ?: return
        if (isControllerVisible && statusBarHeight > -1 &&
            activity != null && appBar.height > 0 &&
            recycler.translationY == 0f
        ) {
            val halfWay = appBar.height.toFloat() / 2
            val shortAnimationDuration = resources?.getInteger(
                AR.integer.config_shortAnimTime,
            ) ?: 0
            val closerToTop = abs(appBar.y) > halfWay
            val halfWayBottom = (activityBinding.bottomNav?.height?.toFloat() ?: 0f) / 2
            val closerToBottom = (activityBinding.bottomNav?.translationY ?: 0f) > halfWayBottom
            val atTop = !recycler.canScrollVertically(-1)
            val closerToEdge =
                if (activityBinding.bottomNav?.isVisible == true &&
                    preferences.hideBottomNavOnScroll().get()
                ) {
                    closerToBottom
                } else {
                    closerToTop
                }
            lastY = appBar.snapAppBarY(this@scrollViewWith, recycler) {
                swipeCircle?.translationY = max(
                    appBar.y,
                    -appBar.height + appBar.paddingTop.toFloat(),
                )
            }
            if (activityBinding.bottomNav?.isVisible == true &&
                isInView && preferences.hideBottomNavOnScroll().get()
            ) {
                activityBinding.bottomNav.let {
                    val lastBottomY =
                        if (closerToEdge && !atTop) it.height.toFloat() else 0f
                    val animator = it.animate()?.translationY(lastBottomY)
                        ?.setDuration(shortAnimationDuration.toLong())
                    animator?.setUpdateListener {
                        updateViewsNearBottom()
                    }
                    animator?.start()
                }
            }
            val notAtTop = !atTopOfRecyclerView()
            if (notAtTop != isToolbarColor) colorToolbar(notAtTop)
        }
    }

    (recycler as? RecyclerView)?.addOnScrollListener(
        object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                onScrolled(dy)
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE &&
                    (this@scrollViewWith as? BaseLegacyController<*>)?.isDragging != true
                ) {
                    onScrollIdle()
                } else if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    val view = activity?.window?.currentFocus ?: return
                    val imm =
                        activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            ?: return
                    imm.hideSoftInputFromWindow(view.windowToken, 0)
                }
            }
        },
    )

    (recycler as? NestedScrollView)?.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
        onScrolled(scrollY - oldScrollY)
    }

    (recycler as? StatefulNestedScrollView)?.setScrollStoppedListener {
        if ((this@scrollViewWith as? BaseLegacyController<*>)?.isDragging != true) {
            onScrollIdle()
        }
    }
    return colorToolbar
}

fun Controller.setItemAnimatorForAppBar(recycler: RecyclerView) {
    var itemAppBarAnimator: Animator? = null

    fun animateAppBar() {
        if (this !is SmallToolbarInterface && isControllerVisible) {
            itemAppBarAnimator?.cancel()
            val duration = (recycler.itemAnimator?.changeDuration ?: 250) * 2
            itemAppBarAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                addUpdateListener {
                    if (isControllerVisible) {
                        appBar()?.updateAppBarAfterY(recycler)
                    }
                }
            }
            itemAppBarAnimator?.duration = duration
            itemAppBarAnimator?.start()
        }
    }

    recycler.itemAnimator = object : DefaultItemAnimator() {
        override fun animateMove(
            holder: RecyclerView.ViewHolder?,
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
        ): Boolean {
            animateAppBar()
            return super.animateMove(holder, fromX, fromY, toX, toY)
        }

        override fun onAnimationFinished(viewHolder: RecyclerView.ViewHolder) {
            if (isControllerVisible) {
                appBar()?.updateAppBarAfterY(recycler)
            }
            super.onAnimationFinished(viewHolder)
        }

        override fun animateChange(
            oldHolder: RecyclerView.ViewHolder,
            newHolder: RecyclerView.ViewHolder,
            preInfo: ItemHolderInfo,
            postInfo: ItemHolderInfo,
        ): Boolean {
            animateAppBar()
            return super.animateChange(oldHolder, newHolder, preInfo, postInfo)
        }

        override fun animateChange(
            oldHolder: RecyclerView.ViewHolder?,
            newHolder: RecyclerView.ViewHolder?,
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
        ): Boolean {
            animateAppBar()
            return super.animateChange(oldHolder, newHolder, fromX, fromY, toX, toY)
        }
    }
}

val Controller.mainRecyclerView: RecyclerView?
    get() {
        (this as? SettingsLegacyController)?.let { return it.listView }
        val legacy = this as? BaseLegacyController<*> ?: return null
        // Conductor's onChangeStarted can fire before the incoming controller's onCreateView,
        // so subclass overrides that touch `binding.recycler` would NPE on a lateinit field.
        // Skip until the view is wired up; the caller's null-safe usage handles that case.
        if (!legacy.isBindingInitialized) return null
        return legacy.mainRecycler
    }

fun Controller.moveRecyclerViewUp(allTheWayUp: Boolean = false, scrollUpAnyway: Boolean = false) {
    if (activityBinding?.bigToolbar?.isVisible == false) return
    val recycler = mainRecyclerView ?: return
    val appBarOffset = appBar()!!.toolbarDistanceToTop
    if (allTheWayUp && recycler.computeVerticalScrollOffset() - recycler.paddingTop <=
        (fullAppBarHeight ?: appBar()!!.preLayoutHeight)
    ) {
        (recycler.layoutManager as? LinearLayoutManager)?.scrollToPosition(0)
        (recycler.layoutManager as? StaggeredGridLayoutManager)?.scrollToPosition(0)
        recycler.post {
            appBar()!!.updateAppBarAfterY(recycler)
            appBar()!!.useSearchToolbarForMenu(false)
        }
        return
    }
    if (scrollUpAnyway || recycler.computeVerticalScrollOffset() - recycler.paddingTop <= 0 - appBarOffset) {
        (recycler.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(0, appBar()!!.yNeededForSmallToolbar)
        (recycler.layoutManager as? StaggeredGridLayoutManager)
            ?.scrollToPositionWithOffset(0, appBar()!!.yNeededForSmallToolbar)
        recycler.post {
            appBar()!!.updateAppBarAfterY(recycler)
            appBar()!!.useSearchToolbarForMenu(recycler.computeVerticalScrollOffset() != 0)
        }
    }
}

fun Controller.setAppBarBG(value: Float, includeTabView: Boolean = false) {
    val context = view?.context ?: return
    val floatingBar =
        (this as? FloatingSearchInterface)?.showFloatingBar() == true && !includeTabView
    if (!isControllerVisible) return
    if (floatingBar) {
        (appBar()?.cardView as? CardView)?.setCardBackgroundColor(context.getResourceColor(materialR.attr.colorPrimaryVariant))
        if (this !is SmallToolbarInterface && appBar()?.useLargeToolbar == true &&
            appBar()?.compactSearchMode != true
        ) {
            val colorSurface = context.getResourceColor(materialR.attr.colorSurface)
            val color = ColorUtils.blendARGB(
                colorSurface,
                ColorUtils.setAlphaComponent(colorSurface, 0),
                value,
            )
            appBar()?.backgroundColor = color
        } else {
            appBar()?.backgroundColor = Color.TRANSPARENT
        }
        if (appBar()?.isInvisible != true) {
            activity?.window?.statusBarColor =
                context.getResourceColor(AR.attr.statusBarColor)
        }
    } else {
        val color = ColorUtils.blendARGB(
            context.getResourceColor(materialR.attr.colorSurface),
            context.getResourceColor(materialR.attr.colorPrimaryVariant),
            value,
        )
        appBar()?.setBackgroundColor(color)
        if (appBar()?.isInvisible != true) {
            activity?.window?.statusBarColor =
                ColorUtils.setAlphaComponent(color, (0.87f * 255).roundToInt())
        }
        if ((this as? FloatingSearchInterface)?.showFloatingBar() == true) {
            val invColor = ColorUtils.blendARGB(
                context.getResourceColor(materialR.attr.colorSurface),
                context.getResourceColor(materialR.attr.colorPrimaryVariant),
                1 - value,
            )
            (appBar()?.cardView as? CardView)?.setCardBackgroundColor(
                ColorStateList.valueOf(
                    invColor,
                ),
            )
        }
    }
}

fun Controller.withFadeTransaction(): RouterTransaction {
    return RouterTransaction.with(this)
        .pushChangeHandler(fadeTransactionHandler())
        .popChangeHandler(fadeTransactionHandler())
}

fun Controller.fadeTransactionHandler(): ControllerChangeHandler {
    // Reduce-motion: skip the animator pipeline entirely.
    if (yokai.presentation.theme.ReducedMotion.isEnabled()) {
        return SimpleSwapChangeHandler(false)
    }
    val isLowRam = activity?.getSystemService<ActivityManager>()?.isLowRamDevice == true
    return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || isLowRam) {
        FadeChangeHandler(isLowRam)
    } else {
        CrossFadeChangeHandler(false)
    }
}

fun Controller.withFadeInTransaction(): RouterTransaction {
    if (yokai.presentation.theme.ReducedMotion.isEnabled()) {
        return RouterTransaction.with(this)
            .pushChangeHandler(SimpleSwapChangeHandler(false))
            .popChangeHandler(SimpleSwapChangeHandler(false))
    }
    // Conductor's AnimatorChangeHandler default is 300ms — perceptibly slow on root nav
    // (Library/Recents/Browse). 150ms reads as "snappy" without losing the fade entirely.
    return RouterTransaction.with(this)
        .pushChangeHandler(FadeChangeHandler(ROOT_NAV_FADE_MS))
        .popChangeHandler(OneWayFadeChangeHandler(ROOT_NAV_FADE_MS))
}

private const val ROOT_NAV_FADE_MS = 150L

fun Controller.openInBrowser(url: String?) {
    if (url == null) {
        activity?.toast(MR.strings.open_in_browser_fail)
        return
    }

    try {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        startActivity(intent)
    } catch (e: Throwable) {
        activity?.toast(e.message)
    }
}

fun Controller.copyToClipboard(content: String, label: String?, useToast: Boolean): Snackbar? {
    if (content.isBlank()) return null

    val activity = activity ?: return null
    val view = view ?: return null

    val clipboard = activity.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, content))

    label ?: return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return null
    return if (useToast) {
        activity.toast(view.context.getString(MR.strings._copied_to_clipboard, label))
        null
    } else {
        view.snack(view.context.getString(MR.strings._copied_to_clipboard, label))
    }
}

val Controller.activityBinding: MainActivityBinding?
    get() = (activity as? MainActivity)?.binding

/**
 * The [ExpandedAppBarLayout] this controller currently uses for its chrome. By
 * default routes to the activity-global appBar in [MainActivity.binding.appBar].
 * Controllers that host their own layout-local appBar implement [LocalAppBarOwner]
 * to redirect this — anything that scrolls, mutates alpha, or queries the appBar
 * for its layout metrics should go through this single accessor so the routing is
 * correct in both cases.
 */
fun Controller.appBar(): ExpandedAppBarLayout? =
    // A LocalAppBarOwner must NEVER silently land on the activity-global appBar — that's
    // the channel by which chrome mutations (applyTabs, cardFrame.isVisible, scroll-y)
    // intended for the local appBar leak onto the shared global one during view-creation
    // / destruction races and bleed through to the next screen.
    if (this is LocalAppBarOwner) localAppBar() else activityBinding?.appBar

/**
 * The [FloatingToolbar] pill search owned by this controller's [appBar]. Same routing
 * contract as [appBar].
 */
fun Controller.searchToolbar(): FloatingToolbar? = appBar()?.searchToolbar

val Controller.toolbarHeight: Int?
    get() = (activity as? MainActivity)?.toolbarHeight

/**
 * Returns the expected height of the app bar - top insets, based on what the controller
 * needs. Routes through [appBar] so controllers that host their own get accurate metrics
 * for their local instance rather than the (hidden) activity-global one.
 */
val Controller.fullAppBarHeight: Int?
    get() {
        val local = appBar() ?: return null
        val includeSearchToolbar = (this as? FloatingSearchInterface)?.showFloatingBar() == true
        val includeTabs = (this as? TabbedInterface)?.showActivityTabs == true
        val includeLargeToolbar = this !is SmallToolbarInterface
        return if (!includeLargeToolbar || !local.useLargeToolbar) {
            local.attrToolbarHeight + if (includeTabs) 48.dpToPx else 0
        } else {
            local.getEstimatedLayout(includeSearchToolbar, includeTabs, includeLargeToolbar)
        }
    }

/**
 * True iff this controller currently owns the shared activity chrome (toolbar, app bar,
 * tab strip, search bar). The activity is the single source of truth — when [MainActivity]
 * hosts [RootTabsController], all three root tab controllers live concurrently in sibling
 * child routers, so the naive `router.backstack.lastOrNull() == this` check would report
 * every root tab as visible at once. For non-MainActivity hosts (e.g. tests) we fall back
 * to the router-local definition.
 */
val Controller.isControllerVisible: Boolean
    get() {
        val activity = activity as? eu.kanade.tachiyomi.ui.main.MainActivity
        return if (activity != null) {
            activity.activeRootController == this
        } else {
            router.backstack.lastOrNull()?.controller == this
        }
    }

val Controller.previousController: Controller?
    get() = router.backstack.getOrNull(router.backstackSize - 2)?.controller

@MainThread
fun Router.canStillGoBack(): Boolean {
    if (backstack.size > 1) return true
    (backstack.lastOrNull()?.controller as? BaseController)?.let { controller ->
        return controller.canStillGoBack()
    }
    return false
}

val Router.isCompose: Boolean
    get() = backstack.lastOrNull()?.controller is BaseComposeController

interface BackHandlerControllerInterface {
    fun handleOnBackStarted(backEvent: BackEventCompat) {
    }

    @CallSuper
    fun handleOnBackProgressed(backEvent: BackEventCompat) {
        if (this !is Controller) return
        if (router.backstackSize > 1 && isControllerVisible) {
            val progress = ((backEvent.progress.takeIf { it > 0.001f } ?: 0f) * 0.5f).pow(0.6f)
            view?.let { view ->
                view.alpha = 1f - progress
                view.x = progress * view.width * 0.15f

                activityBinding?.let { activityBinding ->
                    val container = activityBinding.controllerContainer
                    val backShadow = activityBinding.backShadow
                    if (container.indexOfChild(backShadow) != container.indexOfChild(view) - 1) {
                        container.removeView(backShadow)
                        container.addView(backShadow, container.indexOfChild(view))
                    }
                    if (!backShadow.isVisible) {
                        backShadow.isVisible = true
                    }
                    backShadow.x = view.x - backShadow.width
                    backShadow.alpha = 0.33f * view.alpha
                }

                router.backstack[router.backstackSize - 2].controller.view?.let { view2 ->
                    view2.alpha = progress
                    view2.x = view.x - view.width * 0.2f
                }
            }
        }
    }

    @CallSuper
    fun handleOnBackCancelled() {
        if (this !is Controller) return
        view?.alpha = 1f
        view?.x = 0f
        if (router.backstackSize > 1) {
            router.backstack[router.backstackSize - 2].controller.view?.let { view ->
                view.alpha = 0f
                view.x = 0f
            }
        }
        activityBinding?.backShadow?.isVisible = false
        activityBinding?.backShadow?.alpha = 0.25f
    }
}
