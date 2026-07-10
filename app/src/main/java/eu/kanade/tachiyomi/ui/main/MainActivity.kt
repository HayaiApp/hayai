package eu.kanade.tachiyomi.ui.main

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.assist.AssistContent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.IdRes
import androidx.appcompat.view.ActionMode
import androidx.appcompat.view.menu.ActionMenuItemView
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.core.animation.doOnEnd
import androidx.core.content.getSystemService
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.children
import androidx.core.view.forEach
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import co.touchlab.kermit.Logger
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.SimpleSwapChangeHandler
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetView
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import eu.kanade.tachiyomi.BuildConfig
import com.google.android.material.R as materialR
import androidx.appcompat.R as appcompatR
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.changesIn
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.data.updater.AppUpdateNotifier
import eu.kanade.tachiyomi.data.updater.AppUpdateResult
import eu.kanade.tachiyomi.data.updater.RELEASE_URL
import eu.kanade.tachiyomi.databinding.MainActivityBinding
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.ui.base.MaterialMenuSheet
import eu.kanade.tachiyomi.ui.base.SmallToolbarInterface
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.base.controller.BaseController
import eu.kanade.tachiyomi.ui.base.controller.BaseLegacyController
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.library.compose.LibraryComposeController
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.more.AboutController
import eu.kanade.tachiyomi.ui.more.OverflowDialog
import eu.kanade.tachiyomi.ui.more.stats.StatsController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.recents.RecentsController
import eu.kanade.tachiyomi.ui.recents.RecentsViewType
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.controllers.SettingsMainController
import eu.kanade.tachiyomi.ui.source.BrowseController
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.util.manga.MangaCoverMetadata
import eu.kanade.tachiyomi.util.manga.MangaShortcutManager
import eu.kanade.tachiyomi.util.showNotificationPermissionPrompt
import eu.kanade.tachiyomi.util.system.contextCompatDrawable
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.e
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.hasSideNavBar
import eu.kanade.tachiyomi.util.system.ignoredSystemInsets
import eu.kanade.tachiyomi.util.system.isBottomTappable
import eu.kanade.tachiyomi.util.system.isInNightMode
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.prepareSideNavContext
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.tryTakePersistableUriPermission
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.view.BackHandlerControllerInterface
import eu.kanade.tachiyomi.util.view.backgroundColor
import eu.kanade.tachiyomi.util.view.canStillGoBack
import eu.kanade.tachiyomi.util.view.dismissSafely
import eu.kanade.tachiyomi.util.view.doOnApplyWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.findChild
import eu.kanade.tachiyomi.util.view.getItemView
import eu.kanade.tachiyomi.util.view.isCompose
import eu.kanade.tachiyomi.util.view.mainRecyclerView
import eu.kanade.tachiyomi.util.view.setAction
import eu.kanade.tachiyomi.util.view.setMessage
import eu.kanade.tachiyomi.util.view.setTitle
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.withFadeInTransaction
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlin.collections.set
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import yokai.util.koin.injectLazy
import yokai.core.migration.Migrator
import yokai.domain.base.BasePreferences
import yokai.domain.manga.interactor.GetLibraryManga
import yokai.domain.recents.interactor.GetRecents
import yokai.i18n.MR
import yokai.presentation.core.Constants
import yokai.presentation.extension.repo.ExtensionRepoController
import yokai.presentation.onboarding.OnboardingController
import yokai.util.lang.getString
import android.R as AR

@SuppressLint("ResourceType")
open class MainActivity : BaseActivity<MainActivityBinding>() {

    /**
     * The top-level Conductor router. On the standard launch path it holds a single
     * persistent [RootTabsController] which in turn owns one child router per bottom-nav
     * tab. SearchActivity opts out and pushes controllers onto [topRouter] directly.
     *
     * Use [topRouter] only when you specifically need the activity-level backstack —
     * for example to push a controller (onboarding, dialogs) that must occlude all tabs.
     * Most code should use [router], which transparently targets the active tab.
     */
    protected lateinit var topRouter: Router

    /**
     * The router callers should target. When [RootTabsController] is installed this is the
     * active tab's child router; pushes, backstack lookups, and pops all operate on the
     * visible tab. Falls back to [topRouter] when there is no [RootTabsController]
     * (SearchActivity).
     */
    val router: Router
        get() {
            check(this::topRouter.isInitialized) { "router accessed before Conductor.attachRouter" }
            return if (rootTabsController != null && topRouter.backstackSize == 1) {
                rootTabsController!!.activeChildRouter() ?: topRouter
            } else {
                topRouter
            }
        }

    /** The [RootTabsController] hosting the bottom-nav tabs, if installed. */
    protected val rootTabsController: RootTabsController?
        get() = if (this::topRouter.isInitialized) {
            topRouter.backstack.firstOrNull()?.controller as? RootTabsController
        } else {
            null
        }

    /**
     * The controller that currently owns the shared activity chrome (toolbar, app bar,
     * tabs, search bar). This is the SINGLE SOURCE OF TRUTH for "what is visible to the
     * user" — referenced by [eu.kanade.tachiyomi.util.view.isControllerVisible] so that
     * dormant root tabs (alive but not currently selected) correctly report `false`.
     *
     * For [RootTabsController]: the top of the active child router's backstack.
     * For SearchActivity / no RootTabsController: the top of [topRouter]'s backstack.
     */
    val activeRootController: Controller?
        get() = if (this::topRouter.isInitialized) router.backstack.lastOrNull()?.controller else null

    protected val searchDrawable by lazy { contextCompatDrawable(R.drawable.ic_search_24dp) }
    protected val backDrawable by lazy { contextCompatDrawable(R.drawable.ic_arrow_back_24dp) }
    private var gestureDetector: GestureDetector? = null

    private var snackBar: Snackbar? = null
    private var extraViewForUndo: View? = null
    private var canDismissSnackBar = false

    // Search query handed off from another screen (e.g. manga-details "Search library") to be
    // applied to the Library tab's search bar once it becomes the active controller. Consumed
    // exactly once by LibraryController.consumePendingLibrarySearch(), which clears it.
    var pendingLibrarySearch: String? = null

    private var animationSet: AnimatorSet? = null
    private val downloadManager: DownloadManager by injectLazy()
    private val mangaShortcutManager: MangaShortcutManager by injectLazy()
    private val extensionManager: ExtensionManager by injectLazy()

    private val getRecents: GetRecents by injectLazy()

    private val hideBottomNav
        get() = router.backstackSize > 1 && router.backstack[1].controller !is DialogController

    private val updateChecker by lazy { AppUpdateChecker() }
    private val isUpdaterEnabled = BuildConfig.INCLUDE_UPDATER
    private var searchBarAnimation: ValueAnimator? = null
    private var searchTBLongClickSet = false
    private var overflowDialog: Dialog? = null

    /**
     * True from the start of any controller change until one frame after it ends. The shared
     * search action view is collapsed as a side effect of navigation (the outgoing screen's menu
     * teardown), and that collapse is dispatched to whoever is now top. Acting on it clears the
     * incoming controller's search (e.g. wiping Browse's query + results on back). We suppress the
     * controller-level collapse dispatch while this is set so only genuine user collapses act.
     */
    private var controllerChangeInProgress = false
    private val clearControllerChangeFlag = Runnable { controllerChangeInProgress = false }
    var currentToolbar: Toolbar? = null
    var ogWidth: Int = Int.MAX_VALUE
    var hingeGapSize = 0
        private set

    val velocityTracker: VelocityTracker by lazy { VelocityTracker.obtain() }
    private val actionButtonSize: Pair<Int, Int> by lazy {
        val attrs = intArrayOf(AR.attr.minWidth, AR.attr.minHeight)
        val ta = obtainStyledAttributes(androidx.appcompat.R.style.Widget_AppCompat_ActionButton, attrs)
        val dimenW = ta.getDimensionPixelSize(0, 0.dpToPx)
        val dimenH = ta.getDimensionPixelSize(1, 0.dpToPx)
        ta.recycle()
        dimenW to dimenH
    }

    @Deprecated("Create contract directly from Composable")
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                materialAlertDialog()
                    .setTitle(MR.strings.warning)
                    .setMessage(MR.strings.allow_notifications_recommended)
                    .setPositiveButton(AR.string.ok, null)
                    .show()
            }
        }

    private val basePreferences: BasePreferences by injectLazy()
    private val getLibraryManga: GetLibraryManga by injectLazy()

    // Ideally we want this to be inside the controller itself, but Conductor doesn't support the new ActivityResult API
    // Should be fine once we moved completely to Compose..... someday....
    // REF: https://github.com/bluelinelabs/Conductor/issues/612
    private val requestColourProfile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                applicationContext.tryTakePersistableUriPermission(uri, flags)
                basePreferences.displayProfile().set(uri.toString())
            }
        }

    fun setUndoSnackBar(snackBar: Snackbar?, extraViewToCheck: View? = null) {
        this.snackBar = snackBar
        canDismissSnackBar = false
        launchUI {
            delay(1000)
            if (this@MainActivity.snackBar == snackBar) {
                canDismissSnackBar = true
            }
        }
        extraViewForUndo = extraViewToCheck
    }

    override fun attachBaseContext(newBase: Context?) {
        ogWidth = min(newBase?.resources?.configuration?.screenWidthDp ?: Int.MAX_VALUE, ogWidth)
        super.attachBaseContext(newBase?.prepareSideNavContext())
    }

    val toolbarHeight: Int
        get() = maxOf(binding.toolbar.height, binding.cardFrame.height, binding.appBar.attrToolbarHeight)

    private var actionMode: ActionMode? = null
    private var backPressedCallback: OnBackPressedCallback? = null
    private val backCallback = {
        pressingBack()
        reEnableBackPressedCallBack()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the splash BEFORE super.onCreate(): AndroidX requires it, and it lets
        // BaseActivity.onCreate's setThemeByPref re-apply the user's chosen theme on top of
        // the library's postSplashScreenTheme swap. (See KDoc on installSplash.)
        val splashScreen = installSplash(savedInstanceState)

        // Window features must be requested before super.onCreate() adds any content.
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        setExitSharedElementCallback(
            object : MaterialContainerTransformSharedElementCallback() {
                override fun onMapSharedElements(
                    names: MutableList<String>,
                    sharedElements: MutableMap<String, View>,
                ) {
                    val mangaController =
                        router.backstack.lastOrNull()?.controller as? MangaDetailsController
                    if (mangaController == null || chapterIdToExitTo == 0L) {
                        super.onMapSharedElements(names, sharedElements)
                        return
                    }
                    val recyclerView = mangaController.binding.recycler
                    val selectedViewHolder =
                        recyclerView.findViewHolderForItemId(chapterIdToExitTo) ?: return
                    sharedElements[names[0]] = selectedViewHolder.itemView
                    chapterIdToExitTo = 0L
                }
            },
        )
        window.sharedElementsUseOverlay = false

        super.onCreate(savedInstanceState)

        // Arm the keep-on-screen predicate AFTER super.onCreate (decor view ready) and
        // AFTER requestFeature (window features must come first). The 5 s SPLASH_MAX_DURATION
        // cap is the unconditional backstop; root controllers call releaseSplash() once their
        // first content is rendered for the success path.
        splashScreen?.configure()

        backPressedCallback = object : OnBackPressedCallback(enabled = true) {
            var startTime: Long = 0
            var lastX: Float = 0f
            var lastY: Float = 0f
            var controllerHandlesBackPress = false
            override fun handleOnBackPressed() {
                if (controllerHandlesBackPress &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    lastX != 0f && lastY != 0f
                ) {
                    val motionEvent = MotionEvent.obtain(
                        startTime,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP,
                        lastX,
                        lastY,
                        0,
                    )
                    velocityTracker.addMovement(motionEvent)
                    motionEvent.recycle()
                    velocityTracker.computeCurrentVelocity(1, 5f)
                    backVelocity =
                        maxOf(0.5f, abs(velocityTracker.getAxisVelocity(MotionEvent.AXIS_X)) * 0.5f)
                }
                lastX = 0f
                lastY = 0f
                backCallback()
            }

            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                controllerHandlesBackPress = false
                val controller by lazy { router.backstack.lastOrNull()?.controller }
                if (!(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ViewCompat.getRootWindowInsets(window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    ) &&
                    actionMode == null &&
                    !(
                        binding.searchToolbar.hasExpandedActionView() && binding.cardFrame.isVisible &&
                            controller !is SearchControllerInterface
                        )
                ) {
                    controllerHandlesBackPress = true
                }
                if (controllerHandlesBackPress) {
                    startTime = SystemClock.uptimeMillis()
                    velocityTracker.clear()
                    val motionEvent = MotionEvent.obtain(startTime, startTime, MotionEvent.ACTION_DOWN, backEvent.touchX, backEvent.touchY, 0)
                    velocityTracker.addMovement(motionEvent)
                    motionEvent.recycle()
                    (controller as? BackHandlerControllerInterface)?.handleOnBackStarted(backEvent)
                }
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (controllerHandlesBackPress) {
                    val motionEvent = MotionEvent.obtain(startTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, backEvent.touchX, backEvent.touchY, 0)
                    lastX = backEvent.touchX
                    lastY = backEvent.touchY
                    velocityTracker.addMovement(motionEvent)
                    motionEvent.recycle()
                    val controller = router.backstack.lastOrNull()?.controller as? BackHandlerControllerInterface
                    controller?.handleOnBackProgressed(backEvent)
                }
            }

            override fun handleOnBackCancelled() {
                if (controllerHandlesBackPress) {
                    val controller = router.backstack.lastOrNull()?.controller as? BackHandlerControllerInterface
                    controller?.handleOnBackCancelled()
                }
            }
        }
        onBackPressedDispatcher.addCallback(backPressedCallback!!)
        // Do not let the launcher create a new activity http://stackoverflow.com/questions/16283079
        if (!isTaskRoot && this !is SearchActivity) {
            finish()
            return
        }
        gestureDetector = GestureDetector(this, GestureListener())
        binding = MainActivityBinding.inflate(layoutInflater)

        setContentView(binding.root)

        binding.toolbar.overflowIcon?.setTint(getResourceColor(R.attr.actionBarTintColor))

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        }
        var continueSwitchingTabs = false
        nav.getItemView(R.id.nav_library)?.setOnLongClickListener {
            if (nav.selectedItemId != R.id.nav_library) {
                nav.selectedItemId = R.id.nav_library
            }
            runLibraryNavAction(basePreferences.longTapLibraryNavBehaviour().get(), showSheet = true)
            true
        }
        nav.getItemView(R.id.nav_recents)?.setOnLongClickListener {
            if (nav.selectedItemId != R.id.nav_recents) {
                nav.selectedItemId = R.id.nav_recents
            }
            runRecentsNavAction(basePreferences.longTapRecentsNavBehaviour().get(), showSheet = true)
            true
        }
        nav.getItemView(R.id.nav_browse)?.setOnLongClickListener {
            if (nav.selectedItemId != R.id.nav_browse) {
                nav.selectedItemId = R.id.nav_browse
            }
            runBrowseNavAction(basePreferences.longTapBrowseNavBehaviour().get(), showSheet = true)
            true
        }

        val container: ViewGroup = binding.controllerContainer

        val content: ViewGroup = binding.mainContent

        // Splash is released by the root controller (LibraryController / RecentsController /
        // BrowseController) once its first content is rendered, so the user sees splash → content
        // with no empty MainActivity in between. The 5 s SPLASH_MAX_DURATION cap armed by
        // splashScreen?.configure() above is the backstop if a controller never signals ready.
        // SearchActivity overrides this and calls releaseSplash() in its own onCreate because its
        // pushed controllers don't carry the release hook.

        if (savedInstanceState == null && this !is SearchActivity) {
            // Reset Incognito Mode on relaunch
            preferences.incognitoMode().set(false)

            // Show "What's new" once migrations finish, but don't gate the splash on them.
            lifecycleScope.launchUI {
                val didMigration = Migrator.await()
                Migrator.release()
                if (didMigration && !BuildConfig.DEBUG) {
                    content.post {
                        whatsNewSheet().show()
                    }
                }
            }
        }

        combine(
            downloadManager.isDownloaderRunning,
            downloadManager.queueState,
        ) { isDownloading, queueState -> isDownloading to queueState.size }
            // queueState ticks per item; dedupe so the badge only re-renders when the pair changes.
            .distinctUntilChanged()
            .onEach { downloadStatusChanged(it.first, it.second) }
            .launchIn(lifecycleScope)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowCustomEnabled(true)

        setNavBarColor(content.rootWindowInsetsCompat)
        nav.isVisible = false
        content.doOnApplyWindowInsetsCompat { v, insets, _ ->
            setNavBarColor(insets)
            val systemInsets = insets.ignoredSystemInsets
            val contextView = window?.decorView?.findViewById<View>(appcompatR.id.action_mode_bar)
            contextView?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = systemInsets.left
                rightMargin = systemInsets.right
                // Edge-to-edge lays the contextual ActionMode bar from y=0; push it below the
                // status bar/cutout so its solid background doesn't paint over the notch.
                topMargin = systemInsets.top
            }
            // Consume any horizontal insets and pad all content in. There's not much we can do
            // with horizontal insets
            v.updatePadding(
                left = systemInsets.left,
                right = systemInsets.right,
            )
            // Top inset for binding.appBar is consumed inside ExpandedAppBarLayout's own
            // OnApplyWindowInsetsListener (see ExpandedAppBarLayout.init) so the widget
            // works identically whether it's the activity-global instance or a
            // controller-local one.
            binding.bottomNav?.updatePadding(
                bottom = systemInsets.bottom,
            )
            binding.sideNav?.updatePadding(
                left = 0,
                right = 0,
                bottom = systemInsets.bottom,
                top = systemInsets.top,
            )
            binding.bottomView?.isVisible = systemInsets.bottom > 0
            binding.bottomView?.updateLayoutParams<ViewGroup.LayoutParams> {
                height = systemInsets.bottom
            }
        }
        // Set this as nav view will try to set its own insets and they're hilariously bad
        ViewCompat.setOnApplyWindowInsetsListener(nav) { _, insets -> insets }

        topRouter = Conductor.attachRouter(this, container, savedInstanceState)

        // Install the persistent RootTabsController as the only top-level controller.
        // SearchActivity bypasses this (see SearchActivity.usesRootTabsController) and
        // keeps the legacy direct-pushed-controller pattern.
        if (usesRootTabsController() && !topRouter.hasRootController()) {
            topRouter.setRoot(
                RouterTransaction.with(RootTabsController())
                    .pushChangeHandler(SimpleSwapChangeHandler(false))
                    .popChangeHandler(SimpleSwapChangeHandler(false)),
            )
        }

        arrayOf(binding.toolbar, binding.searchToolbar).forEach { toolbar ->
            toolbar.setNavigationIconTint(getResourceColor(R.attr.actionBarTintColor))
            toolbar.router = router
        }
        rootTabsController?.let { rt ->
            // On config restore, currentTabId carries the previously visible tab. On
            // first launch it's -1 (sentinel) and we'll fall through to goToStartingTab.
            if (rt.currentTabId != -1) {
                nav.selectedItemId = rt.currentTabId
                rt.selectTab(rt.currentTabId)
            }
        } ?: run {
            if (topRouter.hasRootController()) {
                nav.selectedItemId = when (topRouter.backstack.firstOrNull()?.controller) {
                    is RecentsController -> R.id.nav_recents
                    is BrowseController -> R.id.nav_browse
                    else -> R.id.nav_library
                }
            }
        }

        nav.setOnItemSelectedListener { item ->
            val id = item.itemId
            val currentController = router.backstack.lastOrNull()?.controller
            if (!continueSwitchingTabs && currentController is BottomNavBarInterface) {
                if (!currentController.canChangeTabs {
                    continueSwitchingTabs = true
                    this@MainActivity.nav.selectedItemId = id
                }
                ) {
                    return@setOnItemSelectedListener false
                }
            }
            continueSwitchingTabs = false
            val rootTabs = rootTabsController
            if (rootTabs != null) {
                val sameTab = (rootTabs.currentTabId == id) &&
                    (rootTabs.activeChildRouter()?.hasRootController() == true)
                if (!sameTab) {
                    rootTabs.selectTab(id)
                } else if (router.backstackSize == 1) {
                    runDoubleTapAction(id)
                }
            } else {
                val currentRoot = topRouter.backstack.firstOrNull()
                if (currentRoot?.tag()?.toIntOrNull() != id) {
                    setRoot(
                        when (id) {
                            R.id.nav_library -> if (basePreferences.composeLibrary().get()) LibraryComposeController() else LibraryController()
                            R.id.nav_recents -> RecentsController()
                            else -> BrowseController()
                        },
                        id,
                    )
                } else if (currentRoot.tag()?.toIntOrNull() == id && topRouter.backstackSize == 1) {
                    runDoubleTapAction(id)
                }
            }
            true
        }

        val needsStartingTab = rootTabsController?.let { it.currentTabId == -1 }
            ?: !topRouter.hasRootController()
        if (needsStartingTab) {
            // Set start screen
            if (!handleIntentAction(intent)) {
                val hasOnboarding = topRouter.backstack.any { it.controller is OnboardingController }
                if (!hasOnboarding) {
                    goToStartingTab()
                    if (!basePreferences.hasShownOnboarding().get()) {
                        val hasLibraryManga = runBlocking { getLibraryManga.await().isNotEmpty() }
                        if (hasLibraryManga) {
                            basePreferences.hasShownOnboarding().set(true)
                        } else {
                            // Push onto topRouter (not router which forwards to active child
                            // router) so onboarding occludes all tabs — otherwise the user could
                            // tap another tab to bypass it.
                            topRouter.pushController(OnboardingController().withFadeInTransaction())
                        }
                    }
                }
            }
        }

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.searchToolbar.setNavigationOnClickListener {
            val rootSearchController = router.backstack.lastOrNull()?.controller
            if ((
                rootSearchController is RootSearchInterface ||
                    (currentToolbar != binding.searchToolbar && binding.appBar.useLargeToolbar)
                ) &&
                rootSearchController !is SmallToolbarInterface
            ) {
                binding.searchToolbar.menu.findItem(R.id.action_search)?.expandActionView()
            } else {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        binding.searchToolbar.searchItem?.setOnActionExpandListener(
            object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                    val controller = router.backstack.lastOrNull()?.controller
                    binding.appBar.compactSearchMode =
                        binding.appBar.useLargeToolbar && resources.configuration.screenHeightDp < 600
                    if (binding.appBar.compactSearchMode) {
                        setFloatingToolbar(true)
                        controller?.mainRecyclerView?.requestApplyInsets()
                        binding.appBar.y = 0f
                        binding.appBar.updateAppBarAfterY(controller?.mainRecyclerView)
                    }
                    binding.searchToolbar.menu.forEach { it.isVisible = false }
                    lifecycleScope.launchUI {
                        // A pushed LocalAppBarOwner (e.g. GlobalSearchController) drives its OWN
                        // local pill; this activity-global pill's events are not its events. Root
                        // tabs (backstackSize 1) are left as-is to preserve their existing dispatch.
                        if (!controller.isPushedLocalAppBarOwner()) {
                            (controller as? BaseLegacyController<*>)?.onActionViewExpand(item)
                            (controller as? SettingsLegacyController)?.onActionViewExpand(item)
                        }
                        reEnableBackPressedCallBack()
                    }
                    return true
                }

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    val controller = router.backstack.lastOrNull()?.controller
                    // A collapse during a controller change is the outgoing screen's menu being
                    // torn down, not the user clearing search — don't dispatch it to the incoming
                    // controller, or it wipes that controller's active query/results.
                    val isNavigationArtifact = controllerChangeInProgress
                    binding.appBar.compactSearchMode = false
                    controller?.mainRecyclerView?.requestApplyInsets()
                    setupSearchTBMenu(binding.toolbar.menu, true)
                    lifecycleScope.launchUI {
                        // A pushed LocalAppBarOwner (e.g. GlobalSearchController launched from a
                        // manga title) expands/collapses its OWN local pill, not this activity-global
                        // one. Dispatching this collapse to it fires its onActionViewCollapse →
                        // popCurrentController, tearing the just-pushed search controller off the
                        // stack and dropping the user back toward the root. Root tabs keep their
                        // existing dispatch.
                        if (!isNavigationArtifact && !controller.isPushedLocalAppBarOwner()) {
                            (controller as? BaseLegacyController<*>)?.onActionViewCollapse(item)
                            (controller as? SettingsLegacyController)?.onActionViewCollapse(item)
                        }
                        reEnableBackPressedCallBack()
                    }
                    return true
                }
            },
        )

        binding.appBar.alpha = 1f

        binding.searchToolbar.setOnClickListener {
            binding.searchToolbar.menu.findItem(R.id.action_search)?.expandActionView()
        }

        binding.searchToolbar.setOnMenuItemClickListener {
            if (router.backstack.lastOrNull()?.controller?.onOptionsItemSelected(it) == true) {
                return@setOnMenuItemClickListener true
            } else {
                return@setOnMenuItemClickListener onOptionsItemSelected(it)
            }
        }

        nav.isVisible = !hideBottomNav
        updateControllersWithSideNavChanges()
        binding.bottomView?.visibility = if (hideBottomNav) View.GONE else binding.bottomView?.visibility ?: View.GONE
        nav.alpha = if (hideBottomNav) 0f else 1f
        // Register the change listener on every router that can host visible controllers.
        // Without this, pushes inside Recents / Browse child routers wouldn't fire
        // syncActivityViewWithController, leaving the toolbar / nav icon stale.
        registerControllerChangeListener(topRouter)
        rootTabsController?.allChildRouters()?.forEach { registerControllerChangeListener(it) }

        syncActivityViewWithController(router.backstack.lastOrNull()?.controller)

        val navIcon = if (router.backstackSize > 1) backDrawable else null
        binding.toolbar.navigationIcon = navIcon
        (router.backstack.lastOrNull()?.controller as? BaseLegacyController<*>)?.setTitle()
        (router.backstack.lastOrNull()?.controller as? SettingsLegacyController)?.setTitle()

        lifecycleScope.launchIO {
            extensionManager.getExtensionUpdates(true)
        }

        // Pay root-tab XML parse + classloader cost on IO. View construction remains on main,
        // but first Recents/Browse attach and delayed Library rows skip binary XML decoding.
        eu.kanade.tachiyomi.ui.source.RootTabWarmup.primeAsync(resources)

        preferences.extensionUpdatesCount()
            .changesIn(lifecycleScope) {
                setExtensionsBadge()
            }
        preferences.incognitoMode()
            .changesIn(lifecycleScope) {
                binding.toolbar.setIncognitoMode(it)
                binding.searchToolbar.setIncognitoMode(it)
                // Also push into the active LocalAppBarOwner's toolbars — the activity-
                // global toolbars are hidden when a ported controller is visible, so this
                // observer used to silently no-op for Library / Recents / Browse.
                val active = activeRootController ?: router.backstack.lastOrNull()?.controller
                val localAppBar = (active as? eu.kanade.tachiyomi.ui.base.LocalAppBarOwner)?.localAppBar()
                localAppBar?.mainToolbar?.setIncognitoMode(it)
                localAppBar?.searchToolbar?.setIncognitoMode(it)
                SecureActivityDelegate.setSecure(this)
            }
        preferences.sideNavIconAlignment()
            .changesIn(lifecycleScope) {
                binding.sideNav?.menuGravity = when (it) {
                    1 -> Gravity.CENTER
                    2 -> Gravity.BOTTOM
                    else -> Gravity.TOP
                }
            }
        setFloatingToolbar(canShowFloatingToolbar(router.backstack.lastOrNull()?.controller), changeBG = false)

        lifecycleScope.launchUI {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity)
                    .collect { newLayoutInfo ->
                        hingeGapSize = 0
                        for (displayFeature: DisplayFeature in newLayoutInfo.displayFeatures) {
                            if (displayFeature is FoldingFeature && displayFeature.occlusionType == FoldingFeature.OcclusionType.FULL &&
                                displayFeature.isSeparating && displayFeature.orientation == FoldingFeature.Orientation.VERTICAL
                            ) {
                                hingeGapSize = displayFeature.bounds.width()
                            }
                        }
                        if (hingeGapSize > 0) {
                            (router.backstack.lastOrNull()?.controller as? HingeSupportedController)?.updateForHinge()
                        }
                    }
            }
        }
    }

    fun reEnableBackPressedCallBack() {
        val returnToStart = preferences.backReturnsToStart().get() && this !is SearchActivity
        backPressedCallback?.isEnabled = actionMode != null ||
            (binding.searchToolbar.hasExpandedActionView() && binding.cardFrame.isVisible) ||
            router.canStillGoBack() || (returnToStart && startingTab() != nav.selectedItemId)
    }

    override fun onTitleChanged(title: CharSequence?, color: Int) {
        super.onTitleChanged(title, color)
        binding.searchToolbar.title = searchTitle
        val onExpandedController = if (this::topRouter.isInitialized) router.backstack.lastOrNull()?.controller !is SmallToolbarInterface else false
        binding.appBar.setTitle(title, onExpandedController)
        // Also forward the title to the active controller's local appBar — Conductor's
        // setTitle drives activity.title which fires this callback, but the activity-
        // global appBar is hidden when a LocalAppBarOwner is active so the user never
        // sees that update. Without this, the big_title view of the local appBar stays
        // empty after every controller swap.
        val active = activeRootController ?: router.backstack.lastOrNull()?.controller
        val localAppBar = (active as? eu.kanade.tachiyomi.ui.base.LocalAppBarOwner)?.localAppBar()
        if (localAppBar != null) {
            localAppBar.setTitle(title, onExpandedController)
            localAppBar.searchToolbar?.title = searchTitle
        }
    }

    var searchTitle: String?
        get() {
            return try {
                (router.backstack.lastOrNull()?.controller as? BaseLegacyController<*>)?.getSearchTitle()
                    ?: (router.backstack.lastOrNull()?.controller as? SettingsLegacyController)?.getSearchTitle()
            } catch (_: Exception) {
                binding.searchToolbar.title?.toString()
            }
        }
        set(title) {
            binding.searchToolbar.title = title
            // Mirror onto the active LocalAppBarOwner's pill so the placeholder text
            // tracks for ported controllers too.
            val active = activeRootController ?: router.backstack.lastOrNull()?.controller
            (active as? eu.kanade.tachiyomi.ui.base.LocalAppBarOwner)?.localAppBar()
                ?.searchToolbar?.title = title
        }

    open fun setFloatingToolbar(show: Boolean, solidBG: Boolean = false, changeBG: Boolean = true, showSearchAnyway: Boolean = false) {
        Trace.beginSection("Hayai/setFloatingToolbar")
        try {
            setFloatingToolbarInner(show, solidBG, changeBG, showSearchAnyway)
        } finally {
            Trace.endSection()
        }
    }

    private fun setFloatingToolbarInner(show: Boolean, solidBG: Boolean, changeBG: Boolean, showSearchAnyway: Boolean) {
        val controller = if (this::topRouter.isInitialized) router.backstack.lastOrNull()?.controller else null
        val useLargeTB = binding.appBar.useLargeToolbar
        val onSearchController = canShowFloatingToolbar(controller)
        val onSmallerController = controller is SmallToolbarInterface || !useLargeTB
        currentToolbar = if (show && ((showSearchAnyway && onSearchController) || onSmallerController)) {
            binding.searchToolbar
        } else {
            binding.toolbar
        }
        binding.toolbar.isVisible = !(onSmallerController && onSearchController)
        // setSearchTBLongClick re-attaches the SAME listener on every controller change;
        // attach once and skip thereafter.
        if (!searchTBLongClickSet) {
            setSearchTBLongClick()
            searchTBLongClickSet = true
        }
        val showSearchBar = (show || showSearchAnyway) && onSearchController
        val isAppBarVisible = binding.appBar.isVisible
        val needsAnim = if (showSearchBar) {
            !binding.cardFrame.isVisible || binding.cardFrame.alpha < 1f
        } else {
            binding.cardFrame.isVisible || binding.cardFrame.alpha > 0f
        }
        if (this::topRouter.isInitialized && needsAnim && binding.appBar.useLargeToolbar && !onSmallerController &&
            (showSearchAnyway || isAppBarVisible)
        ) {
            binding.appBar.background = null
            searchBarAnimation?.cancel()
            if (showSearchBar && !binding.cardFrame.isVisible) {
                binding.cardFrame.alpha = 0f
                binding.cardFrame.isVisible = true
            }
            val endValue = if (showSearchBar) 1f else 0f
            val tA = ValueAnimator.ofFloat(binding.cardFrame.alpha, endValue)
            tA.addUpdateListener { binding.cardFrame.alpha = it.animatedValue as Float }
            tA.doOnEnd { binding.cardFrame.isVisible = showSearchBar }
            tA.duration = (abs(binding.cardFrame.alpha - endValue) * 150).roundToLong()
            searchBarAnimation = tA
            tA.start()
        } else if (this::topRouter.isInitialized &&
            (!binding.appBar.useLargeToolbar || onSmallerController || !isAppBarVisible)
        ) {
            binding.cardFrame.alpha = 1f
            binding.cardFrame.isVisible = showSearchBar
        }
        val bgColor = binding.appBar.backgroundColor ?: Color.TRANSPARENT
        if (changeBG && (if (solidBG) bgColor == Color.TRANSPARENT else false)) {
            binding.appBar.setBackgroundColor(
                if (show && !solidBG) Color.TRANSPARENT else getResourceColor(materialR.attr.colorSurface),
            )
        }
        // Defer the menu rebuild past the current frame so it doesn't run mid-fade.
        // setupSearchTBMenu diffs the current toolbar menu against the new controller's
        // menu, addOrUpdates/removes items, and may trigger requestLayout on actionMenuView.
        // Doing this synchronously during onChangeStarted blocks the Conductor crossfade
        // (200ms) and was a major source of the 250–700ms frames during root nav swaps.
        binding.toolbar.post {
            Trace.beginSection("Hayai/setupSearchTBMenu")
            try {
                setupSearchTBMenu(binding.toolbar.menu)
            } finally {
                Trace.endSection()
            }
        }
        if (currentToolbar != binding.searchToolbar) {
            binding.searchToolbar.menu?.children?.toList()?.forEach {
                it.isVisible = false
            }
        }
        val onRoot = !this::topRouter.isInitialized || router.backstackSize == 1
        if (!useLargeTB) {
            binding.searchToolbar.navigationIcon = if (onRoot) searchDrawable else backDrawable
        } else if (showSearchAnyway) {
            binding.searchToolbar.navigationIcon = if (!show || onRoot) searchDrawable else backDrawable
        }
        binding.searchToolbar.title = searchTitle
    }

    private fun setSearchTBLongClick() {
        binding.searchToolbar.setOnLongClickListener {
            binding.searchToolbar.menu.findItem(R.id.action_search)?.expandActionView()
            val visibleController = router.backstack.lastOrNull()?.controller as? BaseLegacyController<*>
            val longClickQuery = visibleController?.onSearchActionViewLongClickQuery()
            if (longClickQuery != null) {
                binding.searchToolbar.searchView?.setQuery(longClickQuery, true)
                return@setOnLongClickListener true
            }
            val clipboard: ClipboardManager? = getSystemService()
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                clipboard.primaryClip?.getItemAt(0)?.text?.let { text ->
                    binding.searchToolbar.searchView?.setQuery(text, true)
                }
            }
            true
        }
    }

    private fun setNavBarColor(insets: WindowInsetsCompat?) {
        if (insets == null) return
        window.navigationBarColor = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 -> {
                // basically if in landscape on a phone, solid black bar
                // otherwise translucent dark theme or black if light theme
                when {
                    insets.hasSideNavBar() -> Color.BLACK
                    isInNightMode() -> ColorUtils.setAlphaComponent(
                        getResourceColor(materialR.attr.colorPrimaryVariant),
                        179,
                    )
                    else -> Color.argb(179, 0, 0, 0)
                }
            }
            // if the android q+ device has gesture nav, transparent nav bar
            // this is here in case some crazy with a notch uses landscape
            insets.isBottomTappable() -> {
                getColor(AR.color.transparent)
            }
            // if in landscape with 2/3 button mode, fully opaque nav bar
            insets.hasSideNavBar() -> {
                getResourceColor(materialR.attr.colorPrimaryVariant)
            }
            // if in portrait with 2/3 button mode, translucent nav bar
            else -> {
                ColorUtils.setAlphaComponent(
                    getResourceColor(materialR.attr.colorPrimaryVariant),
                    179,
                )
            }
        }
    }

    override fun startSupportActionMode(callback: ActionMode.Callback): ActionMode? {
        window?.statusBarColor = getResourceColor(materialR.attr.colorPrimaryVariant)
        actionMode = super.startSupportActionMode(callback)
        reEnableBackPressedCallBack()
        return actionMode
    }

    override fun onSupportActionModeFinished(mode: ActionMode) {
        actionMode = null
        reEnableBackPressedCallBack()
        launchUI {
            val scale = Settings.Global.getFloat(
                contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f,
            )
            val duration = resources.getInteger(AR.integer.config_mediumAnimTime) * scale
            delay(duration.toLong())
            delay(100)
            if (Color.alpha(window?.statusBarColor ?: Color.BLACK) >= 255) {
                window?.statusBarColor =
                    getResourceColor(
                        AR.attr.statusBarColor,
                    )
            }
        }
        super.onSupportActionModeFinished(mode)
    }

    fun setStatusBarColorTransparent(show: Boolean) {
        window?.statusBarColor = if (show) {
            ColorUtils.setAlphaComponent(window?.statusBarColor ?: Color.TRANSPARENT, 0)
        } else {
            val color = getResourceColor(AR.attr.statusBarColor)
            ColorUtils.setAlphaComponent(window?.statusBarColor ?: color, Color.alpha(color))
        }
    }

    private fun runLibraryNavAction(action: BasePreferences.LibraryNavAction, showSheet: Boolean) {
        when (action) {
            BasePreferences.LibraryNavAction.UPDATE_LIBRARY -> startLibraryUpdate()
            BasePreferences.LibraryNavAction.FILTERS -> toggleRootBottomSheet(R.id.nav_library, showSheet)
        }
    }

    private fun runRecentsNavAction(action: BasePreferences.RecentsNavAction, showSheet: Boolean) {
        when (action) {
            BasePreferences.RecentsNavAction.DOWNLOAD_QUEUE -> toggleRootBottomSheet(R.id.nav_recents, showSheet)
            BasePreferences.RecentsNavAction.LAST_READ -> openLastReadChapter()
        }
    }

    private fun runBrowseNavAction(action: BasePreferences.BrowseNavAction, showSheet: Boolean) {
        when (action) {
            BasePreferences.BrowseNavAction.SOURCES_SHEET -> toggleRootBottomSheet(R.id.nav_browse, showSheet)
            BasePreferences.BrowseNavAction.GLOBAL_SEARCH ->
                router.pushController(GlobalSearchController().withFadeTransaction())
        }
    }

    private fun toggleRootBottomSheet(navId: Int, showOnly: Boolean) {
        nav.post {
            val controller = router.backstack.firstOrNull()?.controller as? BottomSheetController
                ?: return@post
            if (showOnly) controller.showSheet() else controller.toggleSheet()
        }
    }

    private fun startLibraryUpdate() {
        if (LibraryUpdateJob.isRunning(this)) return
        LibraryUpdateJob.startNow(this)
        binding.mainContent.snack(MR.strings.updating_library) {
            anchorView = binding.bottomNav
            setAction(MR.strings.cancel) {
                LibraryUpdateJob.stop(context)
                lifecycleScope.launchUI {
                    NotificationReceiver.dismissNotification(
                        context,
                        Notifications.ID_LIBRARY_PROGRESS,
                    )
                }
            }
        }
    }

    private fun openLastReadChapter() {
        lifecycleScope.launchUI {
            val lastReadChapter = getRecents.awaitUngrouped(
                filterScanlators = true,
                isResuming = true,
                search = "",
                offset = 0,
            ).maxByOrNull { it.history.last_read } ?: return@launchUI

            startActivity(
                ReaderActivity.newIntent(this@MainActivity, lastReadChapter.manga, lastReadChapter.chapter),
            )
        }
    }

    private fun setExtensionsBadge() {
        val updates = preferences.extensionUpdatesCount().get()
        if (updates > 0) {
            val badge = nav.getOrCreateBadge(R.id.nav_browse)
            badge.number = updates
        } else {
            nav.removeBadge(R.id.nav_browse)
        }
    }

    override fun onResume() {
        super.onResume()
        checkForAppUpdates()
        lifecycleScope.launchIO {
            extensionManager.getExtensionUpdates(false)
        }
        setExtensionsBadge()
        showDLQueueTutorial()
        reEnableBackPressedCallBack()
        // Restore visibility for the active controller after returning from another
        // Activity (e.g. ReaderActivity). The Conductor change-listener hack at
        // [onChangeCompleted] sets the outgoing controller's view alpha to 0 — but if
        // an Activity launch interrupted the next transition's animator, that alpha-0
        // can stick and the page renders blank. Hard-reset the active root controller
        // (and the top of the visible router) so the next user-visible frame is correct.
        if (isBindingInitialized && this::topRouter.isInitialized) {
            val active = activeRootController ?: router.backstack.lastOrNull()?.controller
            active?.view?.alpha = 1f
            rootTabsController?.view?.alpha = 1f
            rootTabsController?.resetActiveTabAlpha()
            restoreRootTabsAlphas()
            // Re-sync chrome visibility in case the activity-global appBar got into a
            // stale state across the activity transition.
            syncActivityAppBarVisibility(active)
            // Also defensively snap the local appBar of any LocalAppBarOwner back to a
            // visible baseline — the same defensive reset wireDefaultLocalChrome does on
            // every controller enter. Activity resume doesn't fire a Conductor change,
            // so without this nudge the local appBar can stay at translationY = -height
            // (off-screen) from before the pause.
            val localAppBar = (active as? eu.kanade.tachiyomi.ui.base.LocalAppBarOwner)?.localAppBar()
            localAppBar?.let { bar ->
                bar.alpha = 1f
                bar.isInvisible = false
                bar.lockYPos = false
                bar.translationY = 0f
                bar.y = 0f
            }
        }
    }

    fun restoreRootTabsAlphas() {
        rootTabsController?.allChildRouters()?.forEach { childRouter ->
            childRouter.backstack.lastOrNull()?.controller?.view?.alpha = 1f
        }
    }

    private fun showDLQueueTutorial() {
        if (router.backstackSize == 1 && this !is SearchActivity &&
            downloadManager.hasQueue() && !preferences.shownDownloadQueueTutorial().get()
        ) {
            if (!isBindingInitialized) return
            val recentsItem = nav.getItemView(R.id.nav_recents) ?: return
            preferences.shownDownloadQueueTutorial().set(true)
            TapTargetView.showFor(
                this,
                TapTarget.forView(
                    recentsItem,
                    getString(MR.strings.manage_whats_downloading),
                    getString(MR.strings.visit_recents_for_download_queue),
                ).outerCircleColorInt(getResourceColor(materialR.attr.colorSecondary)).outerCircleAlpha(0.95f)
                    .titleTextSize(
                        20,
                    )
                    .titleTextColorInt(getResourceColor(R.attr.colorOnSecondary)).descriptionTextSize(16)
                    .descriptionTextColorInt(getResourceColor(R.attr.colorOnSecondary))
                    .icon(contextCompatDrawable(R.drawable.ic_recent_read_32dp))
                    .targetCircleColor(AR.color.white)
                    .targetRadius(45),
                object : TapTargetView.Listener() {
                    override fun onTargetClick(view: TapTargetView) {
                        super.onTargetClick(view)
                        nav.selectedItemId = R.id.nav_recents
                    }
                },
            )
        }
    }

    override fun onPause() {
        super.onPause()
        snackBar?.dismiss()
        setStartingTab()
        saveExtras()
    }

    private fun saveExtras() {
        mangaShortcutManager.updateShortcuts(this)
        MangaCoverMetadata.savePrefs()
    }

    private fun checkForAppUpdates() {
        if (isUpdaterEnabled && router.backstack.lastOrNull()?.controller !is AboutController) {
            lifecycleScope.launchIO {
                try {
                    val result = updateChecker.checkForUpdate(this@MainActivity, isUserPrompt = true)
                    if (result is AppUpdateResult.NewUpdate) {
                        val body = result.release.info
                        val url = result.release.downloadLink
                        val isBeta = result.release.preRelease == true

                        // Create confirmation window
                        withUIContext {
                            showNotificationPermissionPrompt()
                            AppUpdateNotifier.releasePageUrl = result.release.releaseLink
                            AboutController.NewUpdateDialogController(body, url, isBeta).showDialog(router)
                        }
                    }
                } catch (error: Exception) {
                    Logger.e(error)
                }
            }
        }
    }

    fun showNotificationPermissionPrompt(showAnyway: Boolean = false) =
        this.showNotificationPermissionPrompt(
            requestNotificationPermissionLauncher,
            showAnyway,
            preferences,
        )

    fun showColourProfilePicker() {
        requestColourProfile.launch(arrayOf("*/*"))
    }

    @SuppressLint("MissingSuperCall")
    override fun onNewIntent(intent: Intent) {
        // The splash is long gone by the time onNewIntent fires (the activity is already
        // resumed). No splash state to touch here.
        if (!handleIntentAction(intent)) {
            super.onNewIntent(intent)
        }
    }

    protected open fun handleIntentAction(intent: Intent): Boolean {
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId > -1) {
            NotificationReceiver.dismissNotification(
                applicationContext,
                notificationId,
                intent.getIntExtra("groupId", 0),
            )
        }
        when (intent.action) {
            SHORTCUT_LIBRARY -> ensureRootTabSelected(R.id.nav_library)
            SHORTCUT_RECENTLY_UPDATED, SHORTCUT_RECENTLY_READ, Constants.SHORTCUT_RECENTS -> {
                val wasOnRecents = isRootTabSelected(R.id.nav_recents)
                ensureRootTabSelected(R.id.nav_recents)
                if (wasOnRecents) {
                    router.popToRoot()
                }
                if (intent.action == Constants.SHORTCUT_RECENTS) return true
                nav.post {
                    val controller =
                        router.backstack.firstOrNull()?.controller as? RecentsController
                    controller?.tempJumpTo(
                        when (intent.action) {
                            SHORTCUT_RECENTLY_UPDATED -> RecentsViewType.Updates
                            else -> RecentsViewType.History
                        },
                    )
                }
            }
            SHORTCUT_BROWSE -> ensureRootTabSelected(R.id.nav_browse)
            SHORTCUT_EXTENSIONS -> {
                val wasOnBrowse = isRootTabSelected(R.id.nav_browse)
                ensureRootTabSelected(R.id.nav_browse)
                if (wasOnBrowse) {
                    router.popToRoot()
                }
                nav.post {
                    val controller =
                        router.backstack.firstOrNull()?.controller as? BrowseController
                    controller?.showSheet()
                }
            }
            Constants.SHORTCUT_MANGA -> {
                val extras = intent.extras ?: return false
                ensureRootTabReady(R.id.nav_library)
                router.pushController(MangaDetailsController(extras).withFadeTransaction())
            }
            SHORTCUT_UPDATE_NOTES -> {
                val extras = intent.extras ?: return false
                ensureRootTabReady(R.id.nav_library)
                if (
                    router.backstack.lastOrNull()?.controller !is AboutController.NewUpdateDialogController &&
                    // FIXME: Show Compose version of NewUpdateDialog for AboutController
                    router.backstack.lastOrNull()?.controller !is AboutController
                ) {
                    AboutController.NewUpdateDialogController(extras).showDialog(router)
                }
            }
            SHORTCUT_SOURCE -> {
                val extras = intent.extras ?: return false
                ensureRootTabReady(R.id.nav_library)
                router.pushController(BrowseSourceController(extras).withFadeTransaction())
            }
            SHORTCUT_DOWNLOADS -> {
                ensureRootTabSelected(R.id.nav_recents)
                router.popToRoot()
                nav.post {
                    val controller =
                        router.backstack.firstOrNull()?.controller as? RecentsController
                    controller?.showSheet()
                }
            }
            SHORTCUT_LIBRARY_UPDATE_REPORT -> {
                ensureRootTabReady(R.id.nav_library)
                val tabName = intent.getStringExtra(EXTRA_LIBRARY_UPDATE_REPORT_TAB)
                val tab = tabName?.let {
                    runCatching {
                        yokai.presentation.library.update.LibraryUpdateReportScreenModel.ReportTab.valueOf(it)
                    }.getOrNull()
                } ?: yokai.presentation.library.update.LibraryUpdateReportScreenModel.ReportTab.ERRORS
                router.pushController(
                    eu.kanade.tachiyomi.ui.library.update.LibraryUpdateReportController(tab)
                        .withFadeTransaction(),
                )
            }
            Intent.ACTION_VIEW -> {
                if (intent.scheme == "tachiyomi" && intent.data?.host == "add-repo") {
                    intent.data?.getQueryParameter("url")?.let { repoUrl ->
                        ensureRootTabReady(R.id.nav_browse)
                        router.popToRoot()
                        router.pushController(ExtensionRepoController(repoUrl).withFadeTransaction())
                    }
                }
            }
            else -> return false
        }

        return true
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        when (val controller = router.backstack.lastOrNull()?.controller) {
            is MangaDetailsController -> {
                val url = try {
                    controller.presenter.source.getMangaUrl(controller.presenter.manga)
                } catch (e: Exception) {
                    return
                } ?: return
                outContent.webUri = Uri.parse(url)
            }
            is BrowseSourceController -> {
                val url = controller.presenter.source.webViewUrl ?: return
                outContent.webUri = Uri.parse(url)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overflowDialog?.dismissSafely()
        overflowDialog = null
        if (isBindingInitialized) {
            binding.toolbar.setNavigationOnClickListener(null)
            binding.searchToolbar.setNavigationOnClickListener(null)
        }
    }

    private fun pressingBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ViewCompat.getRootWindowInsets(window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        ) {
            WindowInsetsControllerCompat(window, binding.root).hide(WindowInsetsCompat.Type.ime())
        } else if (actionMode != null) {
            actionMode?.finish()
        } else if (binding.searchToolbar.hasExpandedActionView() && binding.cardFrame.isVisible) {
            binding.searchToolbar.collapseActionView()
        } else {
            backPress()
        }
    }

    override fun finish() {
        if (!preferences.backReturnsToStart().get() && this !is SearchActivity) {
            setStartingTab()
        }
        if (this !is SearchActivity) {
            SecureActivityDelegate.locked = true
        }
        saveExtras()
        super.finish()
    }

    protected open fun backPress() {
        val controller = router.backstack.lastOrNull()?.controller
        if (if (router.backstackSize == 1) controller?.handleBack() != true else !router.handleBack()) {
            if (preferences.backReturnsToStart().get() && this !is SearchActivity &&
                startingTab() != nav.selectedItemId
            ) {
                goToStartingTab()
            }
        }
    }

    protected val nav: NavigationBarView
        get() = binding.bottomNav ?: binding.sideNav!!

    private fun setStartingTab() {
        if (this is SearchActivity || !isBindingInitialized) return
        if (nav.selectedItemId != R.id.nav_browse &&
            preferences.startingTab().get() >= 0
        ) {
            preferences.startingTab().set(
                when (nav.selectedItemId) {
                    R.id.nav_library -> 0
                    else -> 1
                },
            )
        }
    }

    @IdRes
    private fun startingTab(): Int {
        return when (preferences.startingTab().get()) {
            0, -1 -> R.id.nav_library
            1, -2 -> R.id.nav_recents
            -3 -> R.id.nav_browse
            else -> R.id.nav_library
        }
    }

    private fun goToStartingTab() {
        ensureRootTabSelected(startingTab())
    }

    fun goToTab(@IdRes id: Int) {
        ensureRootTabSelected(id)
    }

    private fun isRootTabSelected(@IdRes id: Int): Boolean {
        val rootTabs = rootTabsController
        return if (rootTabs != null) {
            rootTabs.currentTabId == id
        } else {
            nav.selectedItemId == id
        }
    }

    private fun ensureRootTabSelected(@IdRes id: Int = startingTab()) {
        rootTabsController?.let { rootTabs ->
            if (nav.selectedItemId != id) {
                nav.selectedItemId = id
            }
            rootTabs.selectTab(id)
            return
        }
        if (router.backstack.isEmpty()) {
            nav.selectedItemId = id
        }
    }

    private fun ensureRootTabReady(@IdRes fallbackId: Int = startingTab()) {
        val rootTabs = rootTabsController
        if (rootTabs == null) {
            if (router.backstack.isEmpty()) {
                nav.selectedItemId = fallbackId
            }
            return
        }

        if (rootTabs.currentTabId == -1 ||
            rootTabs.activeChildRouter()?.hasRootController() != true
        ) {
            ensureRootTabSelected(fallbackId)
        }
    }

    /**
     * Route a query into the existing Library tab's search bar (used by manga-details
     * "Search library"). Pops the current router to root so any pushed manga-details
     * controller is gone, switches to the Library tab, then drains the pending query.
     * Works from Library, Recents, or Browse: when the switch lands on a freshly-activated
     * Library tab, [LibraryController.consumePendingLibrarySearch] runs via onTabActivated;
     * when we were already on Library (no tab change fires), the direct call below drains it.
     */
    fun goToLibraryAndSearch(query: String) {
        pendingLibrarySearch = query
        router.popToRoot()
        nav.selectedItemId = R.id.nav_library
        (activeRootController as? LibraryController)?.consumePendingLibrarySearch()
    }

    private fun setRoot(controller: Controller, id: Int) {
        router.setRoot(controller.withFadeInTransaction().tag(id.toString()))
    }

    private val registeredRouters = mutableSetOf<Router>()

    /**
     * Attach the toolbar / nav / app-bar resync listener to [targetRouter]. Safe to call
     * multiple times — repeats are a no-op via [registeredRouters].
     *
     * Called from MainActivity.onCreate for the top router + every initial child router,
     * and from [RootTabsController.ensureChildRoot] for any tab whose child router is
     * materialised lazily later (e.g. user finally taps the third tab).
     */
    fun registerControllerChangeListener(targetRouter: Router) {
        if (!registeredRouters.add(targetRouter)) return
        targetRouter.addChangeListener(createMainChangeListener(targetRouter))
    }

    /**
     * Whether [boundRouter] is the router currently responsible for the visible top
     * controller. Used by [createMainChangeListener] to gate shared activity chrome
     * work: pushes/pops happening in a dormant tab's child router (e.g. a click handler
     * fires after the user already swapped tabs) must NOT rebind the appBar / tabs /
     * nav icon / soft-input mode that the visible tab owns, or chrome from the inactive
     * tab leaks across.
     *
     * The visible router is:
     *  - [topRouter] when an occluding overlay (OnboardingController, etc.) is pushed
     *    on top of RootTabsController — [topRouter.backstackSize] > 1.
     *  - The active tab's child router otherwise.
     *
     * When a child router fires while another tab is active, the listener still applies
     * per-controller view tweaks (`to.view.alpha = 1f`, `from.view.alpha = 0f`) since
     * those are scoped to the controllers' own views. The active tab's chrome will
     * resync via [onActiveTabChanged] when the user eventually swaps to that tab.
     */
    private fun isVisibleChange(boundRouter: Router): Boolean {
        // During a config-change relaunch, attachRouter() restores RootTabsController,
        // which materialises its child routers and registers this listener on them —
        // all before `topRouter = attachRouter(...)` returns. The first synchronous
        // change event then crashes on the lateinit. Treat that pre-init window as
        // "not visible" so chrome sync is skipped; the active tab will resync via
        // onActiveTabChanged once onCreate finishes wiring everything up.
        if (!::topRouter.isInitialized) return false
        if (boundRouter === topRouter) return true
        val rt = rootTabsController ?: return false
        return topRouter.backstackSize == 1 && boundRouter === rt.activeChildRouter()
    }

    private fun createMainChangeListener(boundRouter: Router) =
        object : ControllerChangeHandler.ControllerChangeListener {
            override fun onChangeStarted(
                to: Controller?,
                from: Controller?,
                isPush: Boolean,
                container: ViewGroup,
                handler: ControllerChangeHandler,
            ) {
                Trace.beginSection("Hayai/MainActivity.onChangeStarted")
                // Hold the collapse-suppression flag up for the whole change (see field doc).
                controllerChangeInProgress = true
                binding.root.removeCallbacks(clearControllerChangeFlag)
                // Per-controller view tweak — scoped to its own view, safe across tabs.
                to?.view?.alpha = 1f
                if (to is RootTabsController) {
                    restoreRootTabsAlphas()
                }
                if (!isVisibleChange(boundRouter)) {
                    Trace.endSection()
                    return
                }
                syncActivityAppBarVisibility(to)
                Trace.beginSection("Hayai/syncActivityViewWithController")
                syncActivityViewWithController(to, from, isPush)
                Trace.endSection()
                binding.appBar.alpha = 1f
                if (binding.backShadow.isVisible && !isPush) {
                    val bA = ObjectAnimator.ofFloat(binding.backShadow, View.ALPHA, 0f)
                    from?.view?.let { view ->
                        bA.addUpdateListener {
                            binding.backShadow.x = view.x - binding.backShadow.width
                            if (boundRouter.backstackSize == 1) {
                                to?.view?.let { toView -> nav.x = toView.x }
                            }
                        }
                    }
                    bA.doOnEnd {
                        binding.backShadow.alpha = 0.25f
                        binding.backShadow.isVisible = false
                        nav.x = 0f
                    }
                    bA.duration = 150
                    bA.interpolator = DecelerateInterpolator(backVelocity.takeIf { it != 0f } ?: 1f)
                    bA.start()
                }
                if (!isPush || boundRouter.backstackSize == 1) {
                    nav.translationY = 0f
                }
                snackBar?.dismiss()
                Trace.endSection()
            }

            override fun onChangeCompleted(
                to: Controller?,
                from: Controller?,
                isPush: Boolean,
                container: ViewGroup,
                handler: ControllerChangeHandler,
            ) {
                // Clear the collapse-suppression flag one frame after the change settles, so a
                // menu-teardown collapse posted during the change is still treated as an artifact.
                binding.root.removeCallbacks(clearControllerChangeFlag)
                binding.root.post(clearControllerChangeFlag)
                // Per-controller view tweaks — scoped to the controllers' own views,
                // safe across tabs. (The from-alpha=0 is the Tachiyomi legacy hack that
                // hides the outgoing controller behind the incoming one once the change
                // animation has settled; it gets restored to 1 when the controller is
                // the `to` of a subsequent change.)
                to?.view?.x = 0f
                if (!(from is DialogController || to is DialogController) && from != null) {
                    from.view?.alpha = 0f
                }
                if (to is RootTabsController) {
                    restoreRootTabsAlphas()
                }
                if (!isVisibleChange(boundRouter)) return
                nav.translationY = 0f
                backVelocity = 0f
                showDLQueueTutorial()
                if (boundRouter.backstackSize == 1) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !isPush) {
                        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
                    }
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION")
                    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
            }
        }

    /**
     * True when this Activity should install [RootTabsController] as the persistent root.
     * SearchActivity overrides to false: it pushes single controllers directly.
     */
    protected open fun usesRootTabsController(): Boolean = true

    private fun runDoubleTapAction(@IdRes id: Int) {
        when (id) {
            R.id.nav_library -> runLibraryNavAction(
                basePreferences.doubleTapLibraryNavBehaviour().get(),
                showSheet = false,
            )
            R.id.nav_recents -> runRecentsNavAction(
                basePreferences.doubleTapRecentsNavBehaviour().get(),
                showSheet = false,
            )
            R.id.nav_browse -> runBrowseNavAction(
                basePreferences.doubleTapBrowseNavBehaviour().get(),
                showSheet = false,
            )
        }
    }

    /**
     * Called by [RootTabsController] after a tab swap. By the time we get here the outgoing
     * tab's controller has been notified via [RootTabContent.onTabDeactivated] and the
     * incoming via [RootTabContent.onTabActivated] — both have already wired/unwired their
     * own local appBars and toggled their [Controller.setOptionsMenuHidden] participation.
     *
     * Our job here is to sync the activity-global appBar visibility (hidden when the
     * incoming tab hosts its own) and trigger a fresh menu walk via [invalidateOptionsMenu]
     * so the activity-global toolbar (used by un-ported detail screens) shows only the
     * new active controller's items.
     */
    fun onActiveTabChanged(@Suppress("UNUSED_PARAMETER") fromTabId: Int, @Suppress("UNUSED_PARAMETER") toTabId: Int) {
        val active = activeRootController
        active?.view?.alpha = 1f
        syncActivityViewWithController(active)
        syncActivityAppBarVisibility(active)
        setFloatingToolbar(canShowFloatingToolbar(active), changeBG = false)
        // Rebuild the toolbar menu from scratch against the new active controller only —
        // dormant tabs have setOptionsMenuHidden(true) so Conductor's menu dispatch skips
        // them. Without invalidateOptionsMenu the prior tab's items would persist.
        invalidateOptionsMenu()
        binding.searchToolbar.title = searchTitle
        (active as? BaseLegacyController<*>)?.setTitle()
        (active as? SettingsLegacyController)?.setTitle()
    }

    /**
     * Show/hide the activity-global [binding.appBar] based on the visible controller.
     * Pass the actual [Controller] (not [router.backstack.lastOrNull]) to avoid a race
     * during PUSH_ENTER when the backstack hasn't yet been mutated. Called by
     * [BaseController.onChangeStarted] on every push/pop enter, by [onActiveTabChanged]
     * on every tab swap, and by the activity-level Conductor change listener.
     */
    fun syncActivityAppBarVisibility(active: Controller?) {
        val hostsOwn = (active as? BaseController)?.hostsOwnAppBar == true
        val composeRoute = active is eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
        // Defensive reset of the shared activity-global appBar before the incoming
        // controller configures it. Without this, slots the outgoing controller left
        // populated (search pill, secondary tabs row, lifted menu, scroll offset) bleed
        // through to the next screen — manifesting as a floating search pill or phantom
        // tab-strip padding on manga details / settings / any deep-nested screen.
        binding.appBar.resetToBaseline()
        binding.appBar.isVisible = !(hostsOwn || composeRoute)
    }

    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean {
        val prepare = super.onPreparePanel(featureId, view, menu)
        if (canShowFloatingToolbar(router.backstack.lastOrNull()?.controller)) {
            val searchItem = menu.findItem(R.id.action_search)
            searchItem?.isVisible = false
        }
        setupSearchTBMenu(menu)
        return prepare
    }

    fun setSearchTBMenuIfInvalid() = setupSearchTBMenu(binding.toolbar.menu)

    private fun setupSearchTBMenu(menu: Menu?, showAnyway: Boolean = false) {
        val toolbar = binding.searchToolbar
        val currentItemsId = toolbar.menu.children.toList().map { it.itemId }
        val newMenuIds = menu?.children?.toList()?.map { it.itemId }.orEmpty()
        menu?.children?.toList()?.let { menuItems ->
            val searchActive = toolbar.isSearchExpanded
            menuItems.forEachIndexed { index, oldMenuItem ->
                if (oldMenuItem.itemId == R.id.action_search) return@forEachIndexed
                val isVisible = oldMenuItem.isVisible &&
                    (currentToolbar == toolbar || !binding.appBar.useLargeToolbar) && (!searchActive || showAnyway)
                addOrUpdateMenuItem(oldMenuItem, toolbar.menu, isVisible, currentItemsId, index)
            }
        }
        toolbar.menu.children.toList().forEach {
            if (it.itemId != R.id.action_search && !newMenuIds.contains(it.itemId)) {
                toolbar.menu.removeItem(it.itemId)
            }
        }

        // Done because sometimes ActionMenuItemViews have a width/height of 0 and never update
        val actionMenuView = toolbar.findChild<ActionMenuView>()
        if (binding.appBar.isVisible && toolbar.isVisible &&
            toolbar.width > 0 && actionMenuView?.children?.any { it.width == 0 } == true
        ) {
            actionMenuView.children.forEach {
                if (it !is ActionMenuItemView) return@forEach
                it.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = actionButtonSize.first
                    height = actionButtonSize.second
                }
            }
            actionMenuView.requestLayout()
        }

        val controller = if (this::topRouter.isInitialized) router.backstack.lastOrNull()?.controller else null
        if (canShowFloatingToolbar(controller)) {
            binding.toolbar.menu.removeItem(R.id.action_search)
        }
    }

    private fun addOrUpdateMenuItem(oldMenuItem: MenuItem, menu: Menu, isVisible: Boolean, currentItemsId: List<Int>, index: Int) {
        if (currentItemsId.contains(oldMenuItem.itemId)) {
            val newItem = menu.findItem(oldMenuItem.itemId) ?: return
            if (newItem.icon != oldMenuItem.icon) {
                newItem.icon = oldMenuItem.icon
            }
            if (newItem.isVisible != isVisible) {
                newItem.isVisible = isVisible
            }
            updateSubMenu(oldMenuItem, newItem)
            return
        }
        val menuItem = if (oldMenuItem.hasSubMenu()) {
            menu.addSubMenu(
                oldMenuItem.groupId,
                oldMenuItem.itemId,
                index,
                oldMenuItem.title,
            ).item
        } else {
            menu.add(
                oldMenuItem.groupId,
                oldMenuItem.itemId,
                index,
                oldMenuItem.title,
            )
        }
        menuItem.isVisible = isVisible
        menuItem.actionView = oldMenuItem.actionView
        menuItem.icon = oldMenuItem.icon
        menuItem.isChecked = oldMenuItem.isChecked
        updateSubMenu(oldMenuItem, menuItem)
        menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    @SuppressLint("RestrictedApi")
    private fun updateSubMenu(oldMenuItem: MenuItem, menuItem: MenuItem) {
        if (oldMenuItem.hasSubMenu()) {
            val oldSubMenu = oldMenuItem.subMenu ?: return
            val newMenuIds = oldSubMenu.children.toList().map { it.itemId }
            val currentItemsId = menuItem.subMenu?.children?.toList()?.map { it.itemId } ?: return
            var isExclusiveCheckable = false
            var isCheckable = false
            oldSubMenu.children.toList().forEachIndexed { index, oldSubMenuItem ->
                val isSubVisible = oldSubMenuItem.isVisible
                addOrUpdateMenuItem(oldSubMenuItem, menuItem.subMenu!!, isSubVisible, currentItemsId, index)
                if (!isExclusiveCheckable) {
                    isExclusiveCheckable = (oldSubMenuItem as? MenuItemImpl)?.isExclusiveCheckable ?: false
                }
                if (!isCheckable) {
                    isCheckable = oldSubMenuItem.isCheckable
                }
            }
            menuItem.subMenu?.setGroupCheckable(oldSubMenu.children.first().groupId, isCheckable, isExclusiveCheckable)
            menuItem.subMenu?.children?.toList()?.forEach {
                if (!newMenuIds.contains(it.itemId)) {
                    menuItem.subMenu?.removeItem(it.itemId)
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // Initialize option to open catalogue settings.
            R.id.action_more -> {
                // Android's ActionBar dispatch runs Activity.onOptionsItemSelected before
                // Conductor's fragment-backed dispatch reaches the active controller, so we have
                // to consult the controller ourselves — otherwise [LibraryController]'s handler
                // (which flags showUpdateLibrary=true) loses the race against the bare dialog
                // shown here. The floating searchToolbar's setOnMenuItemClickListener already
                // does this same first-crack-then-fallback dance, this just brings the regular
                // appbar toolbar path in line with it.
                val controller = if (this::topRouter.isInitialized) {
                    router.backstack.lastOrNull()?.controller
                } else {
                    null
                }
                if (controller?.onOptionsItemSelected(item) != true) {
                    showOverflowDialog()
                }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * Shows the global overflow dialog. Optionally prepends an "Update library" entry — used by
     * [eu.kanade.tachiyomi.ui.library.LibraryController] so the library 3-dot menu can surface
     * library refresh without stacking a second popup on top. Blur is intentionally omitted.
     */
    fun showOverflowDialog(
        showUpdateLibrary: Boolean = false,
        onUpdateLibrary: () -> Unit = {},
    ) {
        if (overflowDialog != null) return
        val dialog = OverflowDialog(this, showUpdateLibrary, onUpdateLibrary)
        this.overflowDialog = dialog
        dialog.setOnDismissListener { this.overflowDialog = null }
        dialog.show()
    }

    fun showSettings() {
        router.pushController(SettingsMainController().withFadeTransaction())
    }

    fun showAbout() {
        router.pushController(AboutController().withFadeTransaction())
    }

    fun showStats() {
        router.pushController(StatsController().withFadeTransaction())
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            gestureDetector?.onTouchEvent(it)
            (router.backstack.lastOrNull()?.controller as? LibraryController)?.handleGeneralEvent(it)
        }
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            if (snackBar != null && snackBar!!.isShown) {
                val sRect = Rect()
                snackBar!!.view.getGlobalVisibleRect(sRect)

                val extRect: Rect? = if (extraViewForUndo != null) Rect() else null
                extraViewForUndo?.getGlobalVisibleRect(extRect)
                // This way the snackbar will only be dismissed if
                // the user clicks outside it.
                if (canDismissSnackBar &&
                    !sRect.contains(ev.x.toInt(), ev.y.toInt()) &&
                    (extRect == null || !extRect.contains(ev.x.toInt(), ev.y.toInt()))
                ) {
                    snackBar?.dismiss()
                    snackBar = null
                    extraViewForUndo = null
                }
            } else if (snackBar != null) {
                snackBar = null
                extraViewForUndo = null
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    protected fun canShowFloatingToolbar(controller: Controller?) =
        (controller is FloatingSearchInterface && controller.showFloatingBar())

    /**
     * True for a [LocalAppBarOwner] that was pushed on top of a root tab (its own pill, not the
     * activity-global one, hosts its search). Root tabs (backstackSize 1) return false so their
     * existing activity-global search dispatch is untouched. Used to stop the activity-global
     * pill's collapse/expand from cross-firing onto a pushed search controller.
     */
    private fun Controller?.isPushedLocalAppBarOwner(): Boolean =
        this is eu.kanade.tachiyomi.ui.base.LocalAppBarOwner && this.router.backstackSize > 1

    protected open fun syncActivityViewWithController(
        to: Controller?,
        from: Controller? = null,
        isPush: Boolean = false,
    ) {
        if (from is DialogController || to is DialogController) {
            return
        }
        reEnableBackPressedCallBack()
        setFloatingToolbar(canShowFloatingToolbar(to))
        val onRoot = router.backstackSize == 1
        val navIcon = if (onRoot) searchDrawable else backDrawable
        binding.toolbar.navigationIcon = if (onRoot) null else backDrawable
        binding.searchToolbar.navigationIcon = if (binding.appBar.useLargeToolbar) searchDrawable else navIcon
        binding.searchToolbar.subtitle = null

        nav.visibility = if (!hideBottomNav) View.VISIBLE else nav.visibility
        if (nav == binding.sideNav) {
            nav.isVisible = !hideBottomNav
            updateControllersWithSideNavChanges(from)
            nav.alpha = 1f
        } else {
            val targetAlpha = if (hideBottomNav) 0f else 1f
            // Skip the 150ms ValueAnimator entirely when nav alpha won't change. Common
            // path for root <-> root swaps (Library/Recents/Browse) where both keep the
            // bottom nav visible — the animation was 150ms of wasted per-frame invalidates.
            if (nav.alpha == targetAlpha) {
                nav.isVisible = !hideBottomNav
                binding.bottomView?.visibility = if (hideBottomNav) {
                    View.GONE
                } else {
                    binding.bottomView?.visibility ?: View.GONE
                }
            } else {
                animationSet?.cancel()
                animationSet = AnimatorSet()
                val alphaAnimation = ValueAnimator.ofFloat(nav.alpha, targetAlpha)
                alphaAnimation.addUpdateListener { valueAnimator ->
                    nav.alpha = valueAnimator.animatedValue as Float
                }
                alphaAnimation.doOnEnd {
                    nav.isVisible = !hideBottomNav
                    binding.bottomView?.visibility =
                        if (hideBottomNav) {
                            View.GONE
                        } else {
                            binding.bottomView?.visibility
                                ?: View.GONE
                        }
                }
                alphaAnimation.duration = 150
                animationSet?.playTogether(alphaAnimation)
                animationSet?.start()
            }
        }
    }

    private fun updateControllersWithSideNavChanges(extraController: Controller? = null) {
        if (!isBindingInitialized || !this::topRouter.isInitialized || this is SearchActivity) return
        binding.sideNav?.let { sideNav ->
            val controllers = (router.backstack.map { it?.controller } + extraController)
                .filterNotNull()
                .distinct()
            val navWidth = sideNav.width.takeIf { it != 0 } ?: 80.dpToPx
            controllers.forEach { controller ->
                val isRootController = controller is RootSearchInterface
                if (controller.view?.layoutParams !is ViewGroup.MarginLayoutParams) return@forEach
                controller.view?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    marginStart = if (sideNav.isVisible) {
                        if (isRootController) 0 else -navWidth
                    } else {
                        if (isRootController) navWidth else 0
                    }
                }
            }
        }
    }

    private fun BadgeDrawable.updateQueueSize(queueSize: Int) {
        number = queueSize
    }

    private fun downloadStatusChanged(downloading: Boolean, queueSize: Int) {
        lifecycleScope.launchUI {
            val hasQueue = downloading || queueSize > 0
            if (hasQueue) {
                val badge = nav.getOrCreateBadge(R.id.nav_recents)
                badge.updateQueueSize(queueSize)
                badge.backgroundColor = if (downloading) getResourceColor(appcompatR.attr.colorError) else Color.GRAY
                showDLQueueTutorial()
            } else {
                nav.removeBadge(R.id.nav_recents)
            }
        }
    }

    private fun whatsNewSheet() = MaterialMenuSheet(
        this,
        listOf(
            MaterialMenuSheet.MenuSheetItem(
                0,
                textRes = MR.strings.whats_new_this_release,
                drawable = R.drawable.ic_new_releases_outline_24dp,
            ),
            MaterialMenuSheet.MenuSheetItem(
                1,
                textRes = MR.strings.close,
                drawable = R.drawable.ic_close_24dp,
            ),
        ),
        title = getString(MR.strings.updated_to_, BuildConfig.VERSION_NAME),
        showDivider = true,
        selectedId = 0,
        onMenuItemClicked = { _, item ->
            if (item == 0) {
                try {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        RELEASE_URL.toUri(),
                    )
                    startActivity(intent)
                } catch (e: Throwable) {
                    toast(e.message)
                }
            }
            true
        },
    )

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        private var startingX = 0f
        private var startingY = 0f
        override fun onDown(e: MotionEvent): Boolean {
            startingX = e.x
            startingY = e.y
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            var result = false
            val diffY = e2.y - startingY
            val diffX = e2.x - startingX
            if (abs(diffX) <= abs(diffY)) {
                val sheetRect = Rect()
                nav.getGlobalVisibleRect(sheetRect)
                if (sheetRect.contains(startingX.toInt(), startingY.toInt()) &&
                    abs(diffY) > Companion.SWIPE_THRESHOLD &&
                    abs(velocityY) > Companion.SWIPE_VELOCITY_THRESHOLD &&
                    diffY <= 0
                ) {
                    val bottomSheetController =
                        router.backstack.lastOrNull()?.controller as? BottomSheetController
                    bottomSheetController?.showSheet()
                } else if (nav == binding.sideNav &&
                    sheetRect.contains(startingX.toInt(), startingY.toInt()) &&
                    abs(diffY) > Companion.SWIPE_THRESHOLD &&
                    abs(velocityY) > Companion.SWIPE_VELOCITY_THRESHOLD &&
                    diffY > 0
                ) {
                    val bottomSheetController =
                        router.backstack.lastOrNull()?.controller as? BottomSheetController
                    bottomSheetController?.hideSheet()
                }
                result = true
            }
            return result
        }
    }

    companion object {

        private const val SWIPE_THRESHOLD = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 100

        // Shortcut actions
        const val SHORTCUT_LIBRARY = "eu.kanade.tachiyomi.SHOW_LIBRARY"
        const val SHORTCUT_RECENTLY_UPDATED = "eu.kanade.tachiyomi.SHOW_RECENTLY_UPDATED"
        const val SHORTCUT_RECENTLY_READ = "eu.kanade.tachiyomi.SHOW_RECENTLY_READ"
        const val SHORTCUT_BROWSE = "eu.kanade.tachiyomi.SHOW_BROWSE"
        const val SHORTCUT_DOWNLOADS = "eu.kanade.tachiyomi.SHOW_DOWNLOADS"
        const val SHORTCUT_UPDATE_NOTES = "eu.kanade.tachiyomi.SHOW_UPDATE_NOTES"
        const val SHORTCUT_SOURCE = "eu.kanade.tachiyomi.SHOW_SOURCE"
        const val SHORTCUT_READER_SETTINGS = "eu.kanade.tachiyomi.READER_SETTINGS"
        const val SHORTCUT_EXTENSIONS = "eu.kanade.tachiyomi.EXTENSIONS"
        const val SHORTCUT_LIBRARY_UPDATE_REPORT = "eu.kanade.tachiyomi.SHOW_LIBRARY_UPDATE_REPORT"
        const val EXTRA_LIBRARY_UPDATE_REPORT_TAB = "library_update_report_tab"

        const val INTENT_SEARCH = "eu.kanade.tachiyomi.SEARCH"
        const val INTENT_SEARCH_QUERY = "query"
        const val INTENT_SEARCH_FILTER = "filter"

        var chapterIdToExitTo = 0L
        var backVelocity = 0f
    }
}

interface BottomNavBarInterface {
    fun canChangeTabs(block: () -> Unit): Boolean
}

interface RootSearchInterface {
    fun expandSearch() {
        if (this is Controller) {
            val mainActivity = activity as? MainActivity ?: return
            mainActivity.binding.searchToolbar.menu.findItem(R.id.action_search)?.expandActionView()
        }
    }
}

interface TabbedInterface {
    /**
     * Whether the activity tab bar is currently in use by this controller. Defaults to true so
     * always-tabbed controllers (recents) keep working without overriding. Conditionally-tabbed
     * controllers (library when display mode is tabbed) override this to a getter that reads
     * their current mode, so [scrollViewWith] / [fullAppBarHeight] and [colorToolbar]'s
     * floating-bar branch react to the live state.
     */
    val showActivityTabs: Boolean get() = true
}

interface HingeSupportedController {
    fun updateForHinge()
}

interface SearchControllerInterface : FloatingSearchInterface, SmallToolbarInterface

interface FloatingSearchInterface {
    fun searchTitle(title: String?): String? {
        if (this is Controller) {
            return activity?.getString(MR.strings.search_, title ?: "")
        }
        return title
    }

    fun showFloatingBar() = true
}

interface BottomSheetController {
    fun showSheet()
    fun hideSheet()
    fun toggleSheet()
}
