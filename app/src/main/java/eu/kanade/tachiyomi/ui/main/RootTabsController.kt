package eu.kanade.tachiyomi.ui.main

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.SimpleSwapChangeHandler
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.library.compose.LibraryComposeController
import eu.kanade.tachiyomi.ui.recents.RecentsController
import eu.kanade.tachiyomi.ui.source.BrowseController
import yokai.util.koin.injectLazy
import yokai.domain.base.BasePreferences
import yokai.presentation.theme.ReducedMotion

/**
 * Contract implemented by controllers that can sit at the root of a [RootTabsController]
 * tab. The hooks fire on tab swap — when the user taps a different bottom-nav item — and
 * give the outgoing/incoming controllers a chance to release/acquire ownership of the
 * shared activity chrome (toolbar mode, app-bar Y, tab strip binding, scroll wiring).
 *
 * These hooks are NOT Conductor lifecycle events: nothing is pushed or popped. The view
 * stays attached and the controller is never destroyed by a tab swap. For real Conductor
 * pushes within a tab (e.g. MangaDetails on top of Library), the controller's own
 * `onChangeStarted(PUSH_ENTER/EXIT)` continues to handle them — those are independent of
 * tab activation.
 */
interface RootTabContent {
    /** This controller has just become the visible tab. */
    fun onTabActivated() {}

    /** This controller is no longer the visible tab (another tab took over). */
    fun onTabDeactivated() {}
}

/**
 * Single persistent root controller that hosts Library / Recents / Browse simultaneously
 * in sibling FrameLayouts. Tab swaps just toggle container visibility — the child
 * controllers are never destroyed by switching tabs, so subsequent visits skip the
 * inflate + layout work entirely. Inflation is deferred per tab: the child router for a
 * given tab is only initialised the first time the user actually selects it.
 *
 * Per-tab back-stack: pushing MangaDetails (or any controller) from inside a tab pushes
 * onto that tab's child router. Switching tabs and returning preserves the pushed stack.
 *
 * Tab activation is communicated to the root controllers via [RootTabContent] — no
 * synthetic Conductor events are dispatched.
 */
class RootTabsController : Controller() {

    private val basePreferences: BasePreferences by injectLazy()

    private val containers = mutableMapOf<Int, FrameLayout>()

    // -1 sentinel until selectTab() is first called. Prevents premature visibility
    // assignment in onCreateView from showing an empty Library container before the host
    // activity has decided which startingTab to use.
    var currentTabId: Int = -1
        private set

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup,
        savedViewState: Bundle?,
    ): View {
        val ctx = activity!!
        val root = FrameLayout(ctx)
        if (savedViewState != null) {
            currentTabId = savedViewState.getInt(KEY_CURRENT_TAB, currentTabId)
        }
        for (tabId in TAB_IDS) {
            val tabContainer = FrameLayout(ctx).apply {
                id = tabContainerId(tabId)
                isVisible = (currentTabId == tabId)
            }
            root.addView(
                tabContainer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            containers[tabId] = tabContainer
        }

        // Rebind child routers to their new containers
        for (tabId in TAB_IDS) {
            if (tabId == currentTabId) {
                ensureChildRoot(tabId)
            } else {
                val tabContainer = containers[tabId]!!
                val childRouter = getChildRouter(tabContainer, childRouterTag(tabId))
                (activity as? MainActivity)?.registerControllerChangeListener(childRouter)
            }
        }

        return root
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        currentTabId = savedInstanceState.getInt(KEY_CURRENT_TAB, currentTabId)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, currentTabId)
    }

    override fun onDestroyView(view: View) {
        cancelSwapAnimations()
        containers.clear()
        super.onDestroyView(view)
    }

    /**
     * Make [tabId] the visible tab. Lazy-creates the tab's child router and root
     * controller on first selection. Idempotent — no-op when already on [tabId].
     *
     * Sequence:
     *  1. Notify the outgoing tab's top controller via [RootTabContent.onTabDeactivated] (if any).
     *  2. Hide the outgoing container, show the incoming.
     *  3. Materialise the incoming child router (first selection only — Conductor's
     *     PUSH_ENTER fires synchronously here on the controller).
     *  4. Notify the incoming controller via [RootTabContent.onTabActivated]. The controller
     *     calls its own [BaseController.onSetupLocalChrome] to wire its local appBar — this fires
     *     for both initial creation AND tab swap, since the chrome state has to be applied
     *     either way.
     *  5. Ask [MainActivity] to resync any remaining cross-cutting state (title, etc.).
     */
    fun selectTab(tabId: Int) {
        if (!containers.containsKey(tabId)) return
        val prev = currentTabId
        if (tabId == prev && containers[tabId]?.isVisible == true) {
            // Already visible — make sure the child router has a root in case the activity
            // recreated us empty (defensive).
            ensureChildRoot(tabId)
            return
        }
        val outgoing = if (prev != -1 && prev != tabId) {
            childRouterFor(prev)?.backstack?.lastOrNull()?.controller
        } else {
            null
        }
        (outgoing as? RootTabContent)?.onTabDeactivated()

        val outgoingContainer = if (prev != -1) containers[prev] else null
        val target = containers[tabId] ?: return
        currentTabId = tabId
        ensureChildRoot(tabId)

        val incoming = childRouterFor(tabId)?.backstack?.lastOrNull()?.controller
        (incoming as? RootTabContent)?.onTabActivated()

        (activity as? MainActivity)?.onActiveTabChanged(prev, tabId)
        animateSwap(prev, outgoingContainer, target)
    }

    /**
     * Cross-fade between two tab containers: incoming alpha 0→1, outgoing alpha 1→0,
     * then outgoing visibility=GONE so its view tree drops out of measure/layout passes.
     *
     * Honors [ReducedMotion.isEnabled] — snaps to the end state without an animator when
     * the user has opted into reduced motion (Appearance → Motion preference).
     *
     * Cancels any prior in-flight swap so rapid taps don't stack animators.
     */
    private fun animateSwap(previousTabId: Int, outgoing: FrameLayout?, incoming: FrameLayout) {
        cancelSwapAnimations()

        if (ReducedMotion.isEnabled() || outgoing == null) {
            // No previous tab to fade out from (initial selection) or reduced motion — snap.
            settleSwap()
            return
        }

        containers.values.forEach { container ->
            if (container !== outgoing && container !== incoming) {
                container.isVisible = false
                container.alpha = 0f
                container.translationX = 0f
            }
        }

        val incomingWasVisible = incoming.isVisible
        incoming.isVisible = true
        outgoing.isVisible = true

        // Animate render properties through ViewPropertyAnimator so the RenderThread can
        // drive the transition. The previous ValueAnimator ran a Kotlin callback on the UI
        // thread every frame and invalidated two complete RecyclerView/Compose trees.
        val rtlMultiplier = if (incoming.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
        val movingForward = TAB_IDS.indexOf(currentTabId) >= TAB_IDS.indexOf(previousTabId)
        val direction = (if (movingForward) 1f else -1f) * rtlMultiplier
        val travel = TAB_SWAP_TRANSLATION_DP * incoming.resources.displayMetrics.density
        if (!incomingWasVisible) {
            incoming.alpha = 0f
            incoming.translationX = direction * travel
        }

        val generation = ++swapGeneration
        activeOutgoing = outgoing
        activeIncoming = incoming

        outgoingAnimator = outgoing.animate()
            .alpha(0f)
            .translationX(-direction * travel * OUTGOING_TRANSLATION_FRACTION)
            .setDuration(TAB_SWAP_DURATION_MS)
            .setInterpolator(tabSwapInterpolator)
            .withLayer()

        incomingAnimator = incoming.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(TAB_SWAP_DURATION_MS)
            .setInterpolator(tabSwapInterpolator)
            .withLayer()
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != swapGeneration) return
                    settleSwap()
                }
            })

        outgoingAnimator?.start()
        incomingAnimator?.start()
    }

    private fun cancelSwapAnimations() {
        ++swapGeneration
        outgoingAnimator?.setListener(null)?.cancel()
        incomingAnimator?.setListener(null)?.cancel()
        activeOutgoing?.translationX = 0f
        activeIncoming?.translationX = 0f
        outgoingAnimator = null
        incomingAnimator = null
        activeOutgoing = null
        activeIncoming = null
    }

    private fun settleSwap() {
        containers.forEach { (tabId, container) ->
            val selected = tabId == currentTabId
            container.animate().setListener(null).cancel()
            container.alpha = if (selected) 1f else 0f
            container.translationX = 0f
            container.isVisible = selected
        }
        outgoingAnimator = null
        incomingAnimator = null
        activeOutgoing = null
        activeIncoming = null
        (activity as? MainActivity)?.onRootTabTransitionSettled()
    }

    private var swapGeneration = 0
    private var outgoingAnimator: ViewPropertyAnimator? = null
    private var incomingAnimator: ViewPropertyAnimator? = null
    private var activeOutgoing: FrameLayout? = null
    private var activeIncoming: FrameLayout? = null
    private val tabSwapInterpolator = FastOutSlowInInterpolator()

    private fun childRouterFor(tabId: Int): Router? {
        val container = containers[tabId] ?: return null
        return getChildRouter(container, childRouterTag(tabId))
    }

    /**
     * The child router for the currently visible tab. Returns null only if the view tree
     * is not yet created.
     */
    fun activeChildRouter(): Router? {
        val container = containers[currentTabId] ?: return null
        return getChildRouter(container, childRouterTag(currentTabId))
    }

    private fun ensureChildRoot(tabId: Int) {
        val container = containers[tabId] ?: return
        val childRouter = getChildRouter(container, childRouterTag(tabId))
        // Wire the host activity's change listener onto this child router so that pushes
        // and pops within this tab fire syncActivityViewWithController, nav-icon updates,
        // bottom-nav alpha, etc. Idempotent in the host.
        (activity as? MainActivity)?.registerControllerChangeListener(childRouter)
        if (!childRouter.hasRootController()) {
            val controller = controllerForTab(tabId)
            childRouter.setRoot(
                RouterTransaction.with(controller)
                    .pushChangeHandler(SimpleSwapChangeHandler(false))
                    .popChangeHandler(SimpleSwapChangeHandler(false))
                    .tag(tabId.toString()),
            )
        }
    }

    /**
     * All currently-materialised child routers (one per tab the user has visited).
     * Used by [MainActivity] to attach its change listener on startup for tabs already
     * restored from saved state.
     */
    fun allChildRouters(): List<Router> = TAB_IDS.mapNotNull { childRouterFor(it) }

    private fun controllerForTab(id: Int): Controller = when (id) {
        R.id.nav_library ->
            if (basePreferences.composeLibrary().get()) LibraryComposeController() else LibraryController()
        R.id.nav_recents -> RecentsController()
        else -> BrowseController()
    }

    fun resetActiveTabAlpha() {
        containers[currentTabId]?.let { container ->
            container.alpha = 1f
            container.isVisible = true
        }
    }

    override fun handleBack(): Boolean = activeChildRouter()?.handleBack() == true

    private fun childRouterTag(tabId: Int): String = "root_tabs_child_$tabId"

    private fun tabContainerId(navId: Int): Int = when (navId) {
        R.id.nav_library -> CONTAINER_ID_LIBRARY
        R.id.nav_recents -> CONTAINER_ID_RECENTS
        else -> CONTAINER_ID_BROWSE
    }

    companion object {
        /**
         * Duration of the cross-fade between tab containers. Tuned so the transition is
         * actually perceptible — Material's "between screens" guideline lands around
         * 300ms; 250ms feels intentional without dragging on rapid taps. Honors
         * [ReducedMotion] (snap-to-end) — see [animateSwap].
         */
        private const val TAB_SWAP_DURATION_MS = 250L
        private const val TAB_SWAP_TRANSLATION_DP = 12f
        private const val OUTGOING_TRANSLATION_FRACTION = 0.35f

        private const val KEY_CURRENT_TAB = "RootTabsController.currentTabId"
        // Stable container ids so Conductor's child router restores into the same FrameLayout
        // across config changes. Picked from the 0x7E_xx_xxxx custom-id range to avoid R clash.
        private const val CONTAINER_ID_LIBRARY = 0x7E001001
        private const val CONTAINER_ID_RECENTS = 0x7E001002
        private const val CONTAINER_ID_BROWSE = 0x7E001003
        private val TAB_IDS = listOf(R.id.nav_library, R.id.nav_recents, R.id.nav_browse)
    }
}
