package eu.kanade.tachiyomi.ui.base.controller

import android.app.Activity
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import co.touchlab.kermit.Logger
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.view.BackHandlerControllerInterface
import eu.kanade.tachiyomi.util.view.isControllerVisible
import eu.kanade.tachiyomi.util.view.removeQueryListener
import eu.kanade.tachiyomi.util.view.searchToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.koin.core.component.KoinComponent

abstract class BaseController(bundle: Bundle? = null) :
    Controller(bundle), BackHandlerControllerInterface, BaseControllerPreferenceControllerCommonInterface, KoinComponent {

    abstract val shouldHideLegacyAppBar: Boolean

    /**
     * Controllers that host their own [eu.kanade.tachiyomi.ui.base.ExpandedAppBarLayout]
     * inside their layout (implementing [eu.kanade.tachiyomi.ui.base.LocalAppBarOwner]
     * so `appBar()` routes to that local instance) override this to `true`. When true:
     *   - [activateLocalChrome] configures the local chrome on every enter.
     *   - The activity hides its (legacy / detail-screen) appBar while this controller
     *     is visible, see [eu.kanade.tachiyomi.ui.main.MainActivity.syncActivityAppBarVisibility].
     *   - [setHasOptionsMenu] is forced to false — menu inflation lives on the local
     *     toolbar via `Toolbar.inflateMenu` inside [onSetupLocalChrome].
     */
    open val hostsOwnAppBar: Boolean = false

    /**
     * Override to configure the controller-local appBar on each `PUSH_ENTER` /
     * `POP_ENTER`. Apply toolbar mode, tabs, alpha, menu state directly on
     * `binding.appBar`. Idempotent — runs on every enter; menu inflation should
     * be gated on `toolbar.menu.size() == 0`.
     */
    open fun onSetupLocalChrome() { }

    internal fun activateLocalChrome() {
        if (!hostsOwnAppBar) return

        removeQueryListener()
        (this as? eu.kanade.tachiyomi.ui.base.LocalAppBarOwner)
            ?.localAppBar()
            ?.let { appBar ->
                appBar.resetForActivation()
                wireDefaultLocalChrome(appBar)
            }
        setOptionsMenuHidden(false)
        onSetupLocalChrome()
        (activity as? MainActivity)?.syncActivityAppBarVisibility(this)
    }

    lateinit var viewScope: CoroutineScope
    var isDragging = false

    init {
        addLifecycleListener(
            object : LifecycleListener() {
                override fun postCreateView(controller: Controller, view: View) {
                    onViewCreated(view)
                }

                override fun preCreateView(controller: Controller) {
                    viewScope = MainScope()
                    Logger.v { "Create view for ${controller.instance()}" }
                }

                override fun preAttach(controller: Controller, view: View) {
                    Logger.v { "Attach view for ${controller.instance()}" }
                }

                override fun preDetach(controller: Controller, view: View) {
                    Logger.v { "Detach view for ${controller.instance()}" }
                }

                override fun preDestroyView(controller: Controller, view: View) {
                    viewScope.cancel()
                    Logger.v { "Destroy view for ${controller.instance()}" }
                }
            },
        )
    }

    open fun onViewCreated(view: View) { }

    internal fun setAppBarVisibility() {
        if (shouldHideLegacyAppBar) hideLegacyAppBar() else showLegacyAppBar()
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        if (type.isEnter && !isControllerVisible) {
            view?.alpha = 0f
        } else if (!type.isEnter || !hostsOwnAppBar) {
            removeQueryListener()
        }
        // When re-entering, snap the view to fully-opaque immediately. The change handler
        // would normally animate alpha 0→1, but that animator can be cancelled mid-flight
        // if the user launched another Activity (e.g., ReaderActivity) and came back —
        // leaving this controller's view stuck at alpha=0 ("blank screen") on the next
        // pop. Forcing alpha=1 at start of Enter guarantees visibility regardless of
        // whether the animator survives.
        if (type.isEnter && isControllerVisible) {
            view?.alpha = 1f
        }
        // Controllers that host their own appBar inflate their menu onto a local
        // CenteredToolbar via Toolbar.inflateMenu — skip the activity's setHasOptionsMenu
        // plumbing, which would otherwise inflate a redundant menu onto the (now hidden)
        // activity-global toolbar.
        setHasOptionsMenu(type.isEnter && isControllerVisible && !hostsOwnAppBar)
        // Hoisted chrome setup on PUSH_ENTER / POP_ENTER for controllers that host
        // their own appBar.
        if (type.isEnter && isControllerVisible) {
            if (hostsOwnAppBar) {
                activateLocalChrome()
            } else {
                (activity as? MainActivity)?.syncActivityAppBarVisibility(this)
            }
        }
        super.onChangeStarted(handler, type)
    }

    /**
     * Defaults applied to a `hostsOwnAppBar` controller's local appBar before its own
     * [onSetupLocalChrome] runs:
     *  - **Back arrow + click**: shown when this controller is not the router root, or
     *    when hosted by [eu.kanade.tachiyomi.ui.main.SearchActivity]. Routes taps
     *    through the activity's back-pressed dispatcher.
     *  - **Title**: copied from the activity's `title` (which Conductor's
     *    [Controller.setTitle] / [getTitle] mechanism keeps in sync) onto the local
     *    `CenteredToolbar`. Without this, ported controllers' titles would be invisible
     *    because the activity action bar (the original title host) is hidden.
     *  - **Floating search pill**: hidden unless the controller implements
     *    [eu.kanade.tachiyomi.ui.main.FloatingSearchInterface] — non-search screens
     *    (manga details, settings, info) would otherwise show an empty pill overlaying
     *    the main toolbar, intercepting its menu-item clicks.
     *  - **Incognito badge**: applied from the incognito preference so the local
     *    toolbars match the activity's incognito visual state. Previously this only
     *    fired on the activity-global toolbars via [MainActivity]'s preference observer.
     *
     * Controllers that need custom behavior can override any of these in their own
     * [onSetupLocalChrome] — it runs after this method.
     */
    private fun wireDefaultLocalChrome(appBar: eu.kanade.tachiyomi.ui.base.ExpandedAppBarLayout) {
        val act = activity as? MainActivity ?: return
        val toolbar = appBar.mainToolbar ?: return

        val onRoot = router.backstackSize == 1 && act !is eu.kanade.tachiyomi.ui.main.SearchActivity
        if (onRoot) {
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
        } else {
            toolbar.navigationIcon = androidx.core.content.ContextCompat.getDrawable(
                act,
                R.drawable.ic_arrow_back_24dp,
            )
            toolbar.setNavigationOnClickListener { act.onBackPressedDispatcher.onBackPressed() }
        }

        // Title (both the big_title view and the small toolbar.title). Conductor's
        // setTitle drives activity.title; relay that onto BOTH the local big title and
        // the small toolbar.title so it shows in expanded AND collapsed modes.
        // Without setBigTitle=true, the big_title TextView stays empty and the user
        // sees "no title" in the expanded state.
        appBar.setTitle(act.title, setBigTitle = true)

        // Search pill: only show for FloatingSearchInterface controllers. The card_view
        // is also given a sensible default title (placeholder) so the pill isn't empty
        // until the controller's own setupToolbarMenu runs.
        val floatingSearch = this as? eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
        val wantsFloatingSearch = floatingSearch?.showFloatingBar() == true
        appBar.cardFrame?.isVisible = wantsFloatingSearch
        if (wantsFloatingSearch) {
            appBar.searchToolbar?.let { pill ->
                pill.title = (this as? eu.kanade.tachiyomi.ui.base.controller.BaseLegacyController<*>)
                    ?.getSearchTitle()
                    ?: act.searchTitle
                    ?: act.title
                wireLocalSearchToolbar(pill, act)
            }
        }

        // Incognito state: BaseToolbar.setIncognitoMode updates the toolbar's
        // compound-drawable badge (CenteredToolbar) or the pill's incog ImageView
        // (FloatingToolbar). Read from the activity's current value.
        val incognito = act.preferences.incognitoMode().get()
        toolbar.setIncognitoMode(incognito)
        appBar.searchToolbar?.setIncognitoMode(incognito)
    }

    /**
     * Wire the controller-local [eu.kanade.tachiyomi.ui.base.FloatingToolbar] (search pill)
     * with the click/expand/menu-item handlers that previously lived only on the activity-
     * global pill in [MainActivity.onCreate]. Without this:
     *   - Tapping the pill does nothing (no expand-action-view trigger).
     *   - The navigation icon doesn't open search.
     *   - Items installed via the controller's [setupToolbarMenu] don't dispatch back
     *     into [onOptionsItemSelected].
     *
     * Idempotent — listeners overwrite, not stack. Safe to call repeatedly on each
     * controller re-entry.
     */
    private fun wireLocalSearchToolbar(
        pill: eu.kanade.tachiyomi.ui.base.FloatingToolbar,
        act: MainActivity,
    ) {
        // Tap anywhere on the pill body → expand the SearchView action.
        pill.setOnClickListener {
            pill.menu.findItem(R.id.action_search)?.expandActionView()
        }

        // Long-press the pill body → expand search, then prefill with the controller's
        // suggested query (e.g. random library entry) or clipboard contents. Mirrors
        // [MainActivity.setSearchTBLongClick] for the activity-global pill — needed
        // separately because [LocalAppBarOwner] controllers route [searchToolbar] to a
        // different instance the activity's listener never sees.
        pill.setOnLongClickListener {
            pill.menu.findItem(R.id.action_search)?.expandActionView()
            val longClickQuery = (this@BaseController as? BaseLegacyController<*>)
                ?.onSearchActionViewLongClickQuery()
            if (longClickQuery != null) {
                pill.searchView?.setQuery(longClickQuery, true)
                return@setOnLongClickListener true
            }
            val clipboard = androidx.core.content.ContextCompat
                .getSystemService(act, android.content.ClipboardManager::class.java)
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                clipboard.primaryClip?.getItemAt(0)?.text?.let { text ->
                    pill.searchView?.setQuery(text, true)
                }
            }
            true
        }

        // Pill's navigation icon (magnifying glass when collapsed, back-arrow when
        // expanded). For RootSearchInterface controllers it expands search; otherwise
        // it triggers the back stack so users can dismiss search-on-detail screens.
        pill.setNavigationOnClickListener {
            val isRootSearch = this@BaseController is eu.kanade.tachiyomi.ui.main.RootSearchInterface
            if (isRootSearch && pill.menu.findItem(R.id.action_search)?.isActionViewExpanded != true) {
                pill.menu.findItem(R.id.action_search)?.expandActionView()
            } else {
                act.onBackPressedDispatcher.onBackPressed()
            }
        }

        // Menu-item dispatch on the pill is set by [installLocalMenu] (which fires
        // *after* this method during onSetupLocalChrome). We deliberately don't wire
        // a placeholder listener here — it'd just be overwritten anyway.

        // Pill action-item visibility is owned by [ExpandedAppBarLayout.useSearchToolbarForMenu]
        // (it lifts items from the main toolbar when the bar collapses to compact mode
        // and hides them otherwise). No per-item expand listener needed here — when the
        // SearchView expands, AppCompat handles the layout reshape automatically.
    }

    open fun canStillGoBack(): Boolean { return false }

    open val mainRecycler: RecyclerView?
        get() = null

    override fun onActivityPaused(activity: Activity) {
        super.onActivityPaused(activity)
        removeQueryListener(false)
    }

    private fun Controller.instance(): String {
        return "${javaClass.simpleName}@${Integer.toHexString(hashCode())}"
    }

    /**
     * Workaround for buggy menu item layout after expanding/collapsing an expandable item like a SearchView.
     * This method should be removed when fixed upstream.
     * Issue link: https://issuetracker.google.com/issues/37657375
     */
    var expandActionViewFromInteraction = false
    fun MenuItem.fixExpand(onExpand: ((MenuItem) -> Boolean)? = null, onCollapse: ((MenuItem) -> Boolean)? = null) {
        setOnActionExpandListener(
            object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                    hideItemsIfExpanded(item, searchToolbar()?.menu, true)
                    return onExpand?.invoke(item) ?: true
                }

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    activity?.invalidateOptionsMenu()

                    return onCollapse?.invoke(item) ?: true
                }
            },
        )

        if (expandActionViewFromInteraction) {
            expandActionViewFromInteraction = false
            expandActionView()
        }
    }

    open fun onSearchActionViewLongClickQuery(): String? = null

    fun hideItemsIfExpanded(searchItem: MenuItem?, menu: Menu?, isExpanded: Boolean = false) {
        menu ?: return
        searchItem ?: return
        if (searchItem.isActionViewExpanded || isExpanded) {
            menu.forEach { it.isVisible = false }
        }
    }

    fun MenuItem.fixExpandInvalidate() {
        fixExpand { invalidateMenuOnExpand() }
    }

    /**
     * Workaround for menu items not disappearing when expanding an expandable item like a SearchView.
     * [expandActionViewFromInteraction] should be set to true in [onOptionsItemSelected] when the expandable item is selected
     * This method should be called as part of [MenuItem.OnActionExpandListener.onMenuItemActionExpand]
     */
    fun invalidateMenuOnExpand(): Boolean {
        return if (expandActionViewFromInteraction) {
            activity?.invalidateOptionsMenu()
            false
        } else {
            true
        }
    }

    fun hideLegacyAppBar() {
        (activity as? AppCompatActivity)?.findViewById<View>(R.id.app_bar)?.isVisible = false
    }

    fun showLegacyAppBar() {
        (activity as? AppCompatActivity)?.findViewById<View>(R.id.app_bar)?.isVisible = true
    }
}

interface BaseControllerPreferenceControllerCommonInterface {
    fun onActionViewExpand(item: MenuItem?) { }
    fun onActionViewCollapse(item: MenuItem?) { }
}
