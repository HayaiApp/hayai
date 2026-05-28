package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.assist.AssistContent
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.TextUtils
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.transition.addListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type.statusBars
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.children
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import co.touchlab.kermit.Logger
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import com.google.android.material.R as materialR
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.preference.toggle
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.orientationType
import eu.kanade.tachiyomi.data.database.models.readingModeType
import eu.kanade.tachiyomi.data.preference.changesIn
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.base.MaterialMenuSheet
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.SearchActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.AddToLibraryFirst
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Error
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Success
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import hayai.novel.reader.tts.NovelTtsController
import hayai.novel.reader.tts.TtsCommand
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.settings.OrientationType
import eu.kanade.tachiyomi.ui.reader.settings.PageLayout
import eu.kanade.tachiyomi.ui.reader.settings.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.settings.ReadingModeType
import eu.kanade.tachiyomi.ui.reader.settings.TabbedReaderSettingsSheet
import eu.kanade.tachiyomi.ui.reader.viewer.BaseViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.DeviceUtil.LegacyCutoutMode
import eu.kanade.tachiyomi.util.system.ThemeUtil
import eu.kanade.tachiyomi.util.system.contextCompatColor
import eu.kanade.tachiyomi.util.system.contextCompatDrawable
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.e
import eu.kanade.tachiyomi.util.system.getBottomGestureInsets
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.hasSideNavBar
import eu.kanade.tachiyomi.util.system.ignoredSystemInsets
import eu.kanade.tachiyomi.util.system.isBottomTappable
import eu.kanade.tachiyomi.util.system.isInNightMode
import eu.kanade.tachiyomi.util.system.isLTR
import eu.kanade.tachiyomi.util.system.isTablet
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchNonCancellableIO
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.system.spToPx
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.view.collapse
import eu.kanade.tachiyomi.util.view.compatToolTipText
import eu.kanade.tachiyomi.util.view.dismissSafely
import eu.kanade.tachiyomi.util.view.doOnApplyWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.hide
import eu.kanade.tachiyomi.util.view.isCollapsed
import eu.kanade.tachiyomi.util.view.isExpanded
import eu.kanade.tachiyomi.util.view.popupMenu
import eu.kanade.tachiyomi.util.view.setAction
import eu.kanade.tachiyomi.util.view.setMessage
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.widget.doOnEnd
import eu.kanade.tachiyomi.widget.doOnStart
import java.io.ByteArrayOutputStream
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Collections
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yokai.util.koin.injectLazy
import yokai.domain.base.BasePreferences
import yokai.domain.ui.settings.ReaderPreferences
import yokai.presentation.theme.ReducedMotion
import yokai.domain.ui.settings.ReaderPreferences.LandscapeCutoutBehaviour
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

/**
 * Activity containing the reader of Tachiyomi. This activity is mostly a container of the
 * viewers, to which calls from the view model or UI events are delegated.
 */
class ReaderActivity : BaseActivity<ReaderActivityBinding>() {

    val viewModel by viewModels<ReaderViewModel>()

    val scope = lifecycleScope

    /**
     * Viewer used to display the pages (pager, webtoon, ...).
     */
    var viewer: BaseViewer? = null
        private set

    /**
     * Whether the menu is currently visible.
     */
    var menuVisible = false
        private set

    /**
     * Whether the menu should stay visible.
     */
    private var menuTemporarilyVisible = false

    private var coroutine: Job? = null

    private var fromUrl = false

    /**
     * Configuration at reader level, like background color or forced orientation.
     */
    private var config: ReaderConfig? = null

    /**
     * Current Bottom Sheet on display, used to dismiss
     */
    private var bottomSheet: BottomSheetDialog? = null

    var sheetManageNavColor = false

    private val wic by lazy { WindowInsetsControllerCompat(window, binding.root) }
    private var lastVis = false

    private var snackbar: Snackbar? = null

    private var intentPageNumber: Int? = null

    /**
     * Compose overlay mounted when the active viewer is a [NovelViewer] / [NovelWebViewViewer]
     * to expose novel-only actions (TTS toggle, auto-scroll, scroll-to-top, settings) that the
     * manga XML bars never carried. Lifecycle is tied to the viewer-swap path in [setManga];
     * visibility is driven by [menuVisible] via [novelActionBarVisibleState].
     */
    internal var novelActionBarComposeView: androidx.compose.ui.platform.ComposeView? = null
    private val novelActionBarVisibleState = androidx.compose.runtime.mutableStateOf(false)
    private val novelActionBarTickState = androidx.compose.runtime.mutableStateOf(0)

    /**
     * Bound-service connection to [hayai.novel.reader.service.NovelTtsPlaybackService]. When
     * the binder lands, [ttsController] is set and the active novel viewer is attached as
     * the controller's [hayai.novel.reader.tts.TtsSource].
     */
    private var ttsServiceConnection: ServiceConnection? = null

    /**
     * The [NovelTtsController] hosted by the bound TTS service. `null` until [onCreate]'s
     * `bindService` call resolves; null-safe everywhere because the action bar / viewer
     * can issue play/pause taps before the bind completes.
     */
    var ttsController: NovelTtsController? = null
        private set

    var isLoading = false

    private var lastShiftDoubleState: Boolean? = null
    private var indexPageToShift: Int? = null
    private var indexChapterToShift: Long? = null

    private var lastCropRes = 0
    var manuallyShiftedPages = false
        private set

    val isSplitScreen: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode

    private var didTransitionFromChapter = false
    private var visibleChapterRange = longArrayOf()
    private var backPressedCallback: OnBackPressedCallback? = null

    var isScrollingThroughPagesOrChapters = false
    private var lastUiModeMask = -1
    private var hingeGapSize = 0
        set(value) {
            field = value
            (viewer as? PagerViewer)?.config?.hingeGapSize = value
        }

    private val readerPreferences: ReaderPreferences by injectLazy()
    private val basePreferences: BasePreferences by injectLazy()

    companion object {

        const val SHIFT_DOUBLE_PAGES = "shiftingDoublePages"
        const val SHIFTED_PAGE_INDEX = "shiftedPageIndex"
        const val SHIFTED_CHAP_INDEX = "shiftedChapterIndex"

        const val TRANSITION_NAME = "${BuildConfig.APPLICATION_ID}.TRANSITION_NAME"
        const val VISIBLE_CHAPTERS = "${BuildConfig.APPLICATION_ID}.VISIBLE_CHAPTERS"

        fun newIntent(context: Context, manga: Manga, chapter: Chapter, page: Int = -1): Intent {
            MainActivity.chapterIdToExitTo = 0L
            val intent = Intent(context, ReaderActivity::class.java)
            intent.putExtra("manga", manga.id)
            intent.putExtra("chapter", chapter.id)
            if (page >= 0) intent.putExtra("page", page)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return intent
        }

        /**
         * Lightweight overload used by the novel TTS notification to deep-link back into the
         * active reader. Accepts raw IDs instead of full [Manga]/[Chapter] objects so the service
         * doesn't have to resolve them out of the database just to build a tap intent.
         */
        fun newIntent(context: Context, mangaId: Long?, chapterId: Long?): Intent {
            MainActivity.chapterIdToExitTo = 0L
            val intent = Intent(context, ReaderActivity::class.java)
            mangaId?.let { intent.putExtra("manga", it) }
            chapterId?.let { intent.putExtra("chapter", it) }
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return intent
        }

        fun newIntentWithTransitionOptions(activity: Activity, manga: Manga, chapter: Chapter, sharedElement: View): Pair<Intent, Bundle?> {
            MainActivity.chapterIdToExitTo = 0L
            val intent = newIntent(activity, manga, chapter)
            intent.putExtra(TRANSITION_NAME, sharedElement.transitionName)
            val activityOptions = ActivityOptions.makeSceneTransitionAnimation(
                activity,
                sharedElement,
                sharedElement.transitionName,
            )
            return intent to activityOptions.toBundle()
        }
    }

    /**
     * Called when the activity is created. Initializes the view model and configuration.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the splash BEFORE super.onCreate() so the AndroidX library's postSplashScreenTheme
        // swap is followed by BaseActivity.onCreate's setThemeByPref re-applying the user's theme.
        val splashScreen = installSplash(savedInstanceState)

        // Window features (FEATURE_ACTIVITY_TRANSITIONS) must be requested before super.onCreate
        // adds content.
        if (intent.extras?.getString(TRANSITION_NAME) != null) {
            window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
            findViewById<View>(AR.id.content)?.let { contentView ->
                MainActivity.chapterIdToExitTo = 0L
                contentView.transitionName = intent.extras?.getString(TRANSITION_NAME)
                visibleChapterRange = intent.extras?.getLongArray(VISIBLE_CHAPTERS) ?: longArrayOf()
                didTransitionFromChapter = contentView.transitionName.contains("details chapter")
                setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
                window.sharedElementEnterTransition = buildContainerTransform(true)
                window.sharedElementReturnTransition = buildContainerTransform(false)
                // Postpone custom transition until manga ready
                postponeEnterTransition()
            }
        }

        super.onCreate(savedInstanceState)

        lastUiModeMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        binding = ReaderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val a = obtainStyledAttributes(intArrayOf(AR.attr.windowLightStatusBar))
        val lightStatusBar = a.getBoolean(0, false)
        a.recycle()
        setCutoutMode()

        // Arm the keep-on-screen predicate after super.onCreate, requestFeature, and
        // setContentView. Then immediately release: by this point the reader window is
        // inflated and the activity is visually ready behind the splash; chapter decode
        // continues asynchronously with the reader's own loading indicator. SPLASH_MIN_DURATION
        // (500 ms) still gates the dismissal so we don't flicker on instant cold starts.
        // Without this, direct cold-launches into the reader (notification / widget deep-links)
        // would hold the splash for the full SPLASH_MAX_DURATION (5 s) cap.
        splashScreen?.configure()
        releaseSplash()

        wic.isAppearanceLightStatusBars = lightStatusBar
        wic.isAppearanceLightNavigationBars = lightStatusBar

        binding.appBar.setBackgroundColor(contextCompatColor(R.color.surface_alpha))
        ViewCompat.setBackgroundTintList(
            binding.readerNav.root,
            ColorStateList.valueOf(contextCompatColor(R.color.surface_alpha)),
        )

        backPressedCallback = object : OnBackPressedCallback(enabled = true) {
            override fun handleOnBackPressed() {
                if (binding.chaptersSheet.root.sheetBehavior.isExpanded()) {
                    binding.chaptersSheet.root.lastScale = binding.chaptersSheet.root.scaleX
                    binding.chaptersSheet.root.sheetBehavior?.collapse()
                }
                reEnableBackPressedCallBack()
            }

            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (binding.chaptersSheet.root.sheetBehavior.isExpanded()) {
                    binding.chaptersSheet.root.sheetBehavior?.startBackProgress(backEvent)
                }
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (binding.chaptersSheet.root.sheetBehavior.isExpanded()) {
                    binding.chaptersSheet.root.sheetBehavior?.updateBackProgress(backEvent)
                }
            }

            override fun handleOnBackCancelled() {
                if (binding.chaptersSheet.root.sheetBehavior.isExpanded()) {
                    binding.chaptersSheet.root.sheetBehavior?.cancelBackProgress()
                }
            }
        }
        onBackPressedDispatcher.addCallback(backPressedCallback!!)
        if (viewModel.needsInit()) {
            fromUrl = handleIntentAction(intent)
            if (!fromUrl) {
                val manga = intent.extras?.getLong("manga", -1L) ?: -1L
                var chapter = intent.extras?.getLong("chapter", -1L) ?: -1L
                val page = intent.extras?.getInt("page", -1) ?: -1
                if (savedInstanceState != null) {
                    val savedChapter = savedInstanceState.getLong("saved_chapter_id", -1L)
                    if (savedChapter != -1L) {
                        chapter = savedChapter
                    }
                }
                if (manga == -1L || chapter == -1L) {
                    finish()
                    return
                }
                val startingPage = page.takeIf { it >= 0 }
                lifecycleScope.launchNonCancellableIO {
                    val initResult = viewModel.init(manga, chapter, startingPage)
                    if (!initResult.getOrDefault(false)) {
                        val exception = initResult.exceptionOrNull()
                        // Source-not-ready is handled by the SourceNotReady event collector,
                        // which renders an inline retry overlay instead of finish()ing the
                        // activity. Skip the generic error path here so we don't close the
                        // reader before the user has a chance to retry (issue #9).
                        if (exception !is SourceNotReadyException) {
                            val toShow = exception ?: IllegalStateException("Unknown err")
                            withUIContext {
                                setInitialChapterError(toShow)
                            }
                        }
                    }
                }
            } else {
                binding.pleaseWait.isVisible = true
            }
        }

        if (savedInstanceState != null) {
            menuVisible = savedInstanceState.getBoolean(::menuVisible.name)
            lastShiftDoubleState = savedInstanceState.getBoolean(SHIFT_DOUBLE_PAGES)
                .takeIf { savedInstanceState.containsKey(SHIFT_DOUBLE_PAGES) }
            indexPageToShift = savedInstanceState.getInt(SHIFTED_PAGE_INDEX, Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE }
            indexChapterToShift = savedInstanceState.getLong(SHIFTED_CHAP_INDEX, Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE }
            binding.readerNav.root.isInvisible = !menuVisible
        } else {
            binding.readerNav.root.isInvisible = true
        }

        binding.chaptersSheet.chaptersBottomSheet.setup(this)
        config = ReaderConfig()
        initializeMenu()

        // Bind to the TTS service so we can reach `NovelTtsController` directly. The
        // service is created on first bind; the controller becomes non-null when
        // `onServiceConnected` fires, and from then on the notification's Pause/Stop
        // action buttons route into the controller via the service itself — no broadcast
        // receiver needed on the activity side.
        bindTtsService()

        preferences.incognitoMode()
            .changesIn(lifecycleScope) {
                SecureActivityDelegate.setSecure(this)
            }
        reEnableBackPressedCallBack()

        viewModel.state
            .map { it.isLoadingAdjacentChapter }
            .distinctUntilChanged()
            .onEach(::setProgressDialog)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.manga }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setManga)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.viewerChapters }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setChapters)
            .launchIn(lifecycleScope)

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    ReaderViewModel.Event.ReloadMangaAndChapters -> {
                        viewModel.manga?.let(::setManga)
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                    }
                    ReaderViewModel.Event.ReloadViewerChapters -> {
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                    }
                    is ReaderViewModel.Event.SetOrientation -> {
                        setOrientation(event.orientation)
                    }
                    is ReaderViewModel.Event.SavedImage -> {
                        onSaveImageResult(event.result)
                    }
                    is ReaderViewModel.Event.ShareImage -> {
                        onShareImageResult(event.file, event.page)
                    }
                    is ReaderViewModel.Event.SetCoverResult -> {
                        onSetAsCoverResult(event.result)
                    }
                    is ReaderViewModel.Event.ShareTrackingError -> {
                        showTrackingError(event.errors)
                    }
                    is ReaderViewModel.Event.NovelVisibleChapterChanged -> {
                        val chapterId = event.chapter.chapter.id
                        if (chapterId != null) {
                            intent.putExtra("chapter", chapterId)
                        }
                        updateNovelChapterUi(event.chapter)
                    }
                    is ReaderViewModel.Event.SourceNotReady -> {
                        showSourceNotReadyOverlay(event.sourceId)
                    }
                }
            }
            .launchIn(lifecycleScope)

        lifecycleScope.launchUI {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@ReaderActivity).windowLayoutInfo(this@ReaderActivity)
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
                            binding.navLayout.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                                gravity = Gravity.TOP or Gravity.CENTER
                                anchorGravity = Gravity.TOP or Gravity.CENTER
                                width = (binding.root.width - hingeGapSize) / 2 - 24.dpToPx
                            }
                            binding.chaptersSheet.root.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                                gravity = Gravity.END
                                width = (binding.root.width - hingeGapSize) / 2
                            }
                            binding.pleaseWait.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                                marginStart = binding.root.width / 2 + hingeGapSize
                            }
                        }
                    }
            }
        }
    }

    /**
     * Called when the activity is destroyed. Cleans up the viewer, configuration and any view.
     */
    override fun onDestroy() {
        // Cleanup must happen BEFORE super.onDestroy(): unregisterReceiver and the
        // foreground-service stop both rely on Activity context state that super tears down.
        // This mirrors Tsundoku's `ReaderActivity.onDestroy` ordering exactly.
        removeNovelActionBar()
        // Detach the viewer from the controller before we tear the viewer down, so the
        // controller doesn't hold a dangling reference into a destroyed WebView.
        (viewer as? eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer)?.let { v ->
            ttsController?.detachSource(v)
        }
        unbindTtsService()
        // If the user disabled background playback, stop the service when the reader closes;
        // otherwise leave it running so audio continues from the persistent notification.
        if (!readerPreferences.novelTtsBackgroundPlayback.get()) {
            stopBackgroundTtsIfRunning()
        }
        super.onDestroy()
        viewer?.destroy()
        binding.chaptersSheet.chaptersBottomSheet.adapter = null
        viewer = null
        config = null
        bottomSheet?.dismissSafely()
        bottomSheet = null
        snackbar?.dismiss()
        snackbar = null
    }

    /**
     * Bind to [hayai.novel.reader.service.NovelTtsPlaybackService]. The service is created
     * on first bind (`BIND_AUTO_CREATE`) and stays alive as long as either this activity
     * holds the binding OR the user has background-playback enabled and the service is
     * also started in the foreground.
     */
    private fun bindTtsService() {
        if (ttsServiceConnection != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? hayai.novel.reader.service.NovelTtsPlaybackService.LocalBinder
                ttsController = binder?.service?.controller
                attachNovelViewerToTtsController()
                observeTtsControllerState()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                ttsController = null
            }
        }
        ttsServiceConnection = connection
        bindService(
            Intent(this, hayai.novel.reader.service.NovelTtsPlaybackService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private var ttsStateObserverJob: Job? = null

    /**
     * Bump the novel action-bar's tick state on every controller-state emission so the
     * Compose TTS icon (play / pause / loading) reflects the engine in real time. This
     * is the bridge that lets us delete the legacy 750 ms polling — state changes drive
     * the UI directly.
     */
    private fun observeTtsControllerState() {
        ttsStateObserverJob?.cancel()
        val controller = ttsController ?: return
        ttsStateObserverJob = lifecycleScope.launch {
            controller.state.collect {
                novelActionBarTickState.value = novelActionBarTickState.value + 1
            }
        }
    }

    private fun unbindTtsService() {
        val connection = ttsServiceConnection ?: return
        try {
            unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Service already unbound (e.g. died) — nothing to do.
        }
        ttsServiceConnection = null
        ttsController = null
    }

    /**
     * Wires the current novel viewer as the [hayai.novel.reader.tts.TtsSource] the
     * controller calls back into. Safe to call repeatedly — `attachSource` overwrites the
     * previous reference, and the controller only ever has one viewer at a time.
     */
    internal fun attachNovelViewerToTtsController() {
        val controller = ttsController ?: return
        val novelViewer = viewer as? eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer
        if (novelViewer != null) controller.attachSource(novelViewer)
    }

    /**
     * Makes the TTS service "started" in addition to "bound" so it survives this
     * activity going away — keeping audio alive in the background notification. The
     * service is already foreground from `onCreate`, so this is just a sticky-lifecycle
     * promotion. The state listener wired in the service drives the notification; the
     * activity no longer needs to polling-sync anything.
     */
    private fun startBackgroundTtsIfEnabled() {
        if (readerPreferences.novelTtsBackgroundPlayback.get()) {
            hayai.novel.reader.service.NovelTtsPlaybackService.start(this)
        }
    }

    private fun stopBackgroundTtsIfRunning() {
        hayai.novel.reader.service.NovelTtsPlaybackService.stop(this)
    }

    /**
     * Called when the activity is saving instance state. Current progress is persisted if this
     * activity isn't changing configurations.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(::menuVisible.name, menuVisible)
        viewModel.state.value.viewerChapters?.currChapter?.chapter?.id?.let { currChapterId ->
            outState.putLong("saved_chapter_id", currChapterId)
        }
        (viewer as? PagerViewer)?.let { pViewer ->
            val config = pViewer.config
            if (config.doublePages) {
                outState.putBoolean(SHIFT_DOUBLE_PAGES, config.shiftDoublePage)
            }
            if (config.shiftDoublePage && config.doublePages) {
                pViewer.getShiftedPage()?.let {
                    outState.putInt(SHIFTED_PAGE_INDEX, it.index)
                    outState.putLong(SHIFTED_CHAP_INDEX, it.chapter.chapter.id ?: 0L)
                }
            }
        }
        super.onSaveInstanceState(outState)
    }

    /**
     * Called when the options menu of the binding.toolbar is being created. It adds our custom menu.
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val splitItem = menu.findItem(R.id.action_shift_double_page)
        splitItem?.isVisible = ((viewer as? PagerViewer)?.config?.doublePages ?: false) && !canShowSplitAtBottom()
        binding.chaptersSheet.shiftPageButton.isVisible = ((viewer as? PagerViewer)?.config?.doublePages ?: false) && canShowSplitAtBottom()
        (viewer as? PagerViewer)?.config?.let { config ->
            val icon = ContextCompat.getDrawable(
                this,
                if ((!config.shiftDoublePage).xor(viewer is R2LPagerViewer)) R.drawable.ic_page_previous_outline_24dp else R.drawable.ic_page_next_outline_24dp,
            )
            splitItem?.icon = icon
            binding.chaptersSheet.shiftPageButton.setImageDrawable(icon)
        }
        setBottomNavButtons(preferences.pageLayout().get())
        (binding.toolbar.background as? LayerDrawable)?.let { layerDrawable ->
            val isDoublePage = splitItem?.isVisible ?: false
            // Shout out to Google for not fixing setVisible https://issuetracker.google.com/issues/127538945
            layerDrawable.findDrawableByLayerId(R.id.layer_full_width).alpha = if (!isDoublePage) 255 else 0
            layerDrawable.findDrawableByLayerId(R.id.layer_one_item).alpha = if (isDoublePage) 255 else 0
        }
        return super.onPrepareOptionsMenu(menu)
    }

    private fun canShowSplitAtBottom(): Boolean {
        return if (!preferences.readerBottomButtons().isSet()) {
            isTablet()
        } else {
            ReaderBottomButton.ShiftDoublePage.isIn(preferences.readerBottomButtons().get())
        }
    }

    fun setBottomNavButtons(pageLayout: Int) {
        val isDoublePage = pageLayout == PageLayout.DOUBLE_PAGES.value ||
            (pageLayout == PageLayout.AUTOMATIC.value && (viewer as? PagerViewer)?.config?.doublePages ?: false)
        binding.chaptersSheet.doublePage.setImageDrawable(
            ContextCompat.getDrawable(
                this,
                when {
                    isDoublePage -> R.drawable.ic_book_open_variant_24dp
                    (viewer as? PagerViewer)?.config?.splitPages == true -> R.drawable.ic_book_open_split_24dp
                    else -> R.drawable.ic_single_page_24dp
                },
            ),
        )
        with(binding.readerNav) {
            listOf(leftPageText, rightPageText).forEach {
                it.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    val isCurrent = (viewer is R2LPagerViewer).xor(it === leftPageText)
                    width = if (isDoublePage && isCurrent) 48.spToPx else 32.spToPx
                }
            }
        }
    }

    private fun updateOrientationShortcut(preference: Int) {
        val orientation = OrientationType.fromPreference(preference)
        binding.chaptersSheet.rotationSheetButton.setImageResource(orientation.iconRes)
    }

    private fun updateCropBordersShortcut() {
        val isNovel = viewer is eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer
        val isPagerType = viewer is PagerViewer || (viewer as? WebtoonViewer)?.hasMargins == true
        val enabled = when {
            isNovel -> readerPreferences.novelMarginsCropped.get()
            isPagerType -> preferences.cropBorders().get()
            else -> preferences.cropBordersWebtoon().get()
        }

        with(binding.chaptersSheet.cropBordersSheetButton) {
            val drawableRes = if (enabled) {
                R.drawable.anim_free_to_crop
            } else {
                R.drawable.anim_crop_to_free
            }
            if (lastCropRes != drawableRes) {
                val drawable = AnimatedVectorDrawableCompat.create(context, drawableRes)
                setImageDrawable(drawable)
                drawable?.start()
                lastCropRes = drawableRes
            }
            compatToolTipText =
                getString(
                    if (enabled) {
                        MR.strings.remove_crop
                    } else {
                        MR.strings.crop_borders
                    },
                )
        }
    }

    private fun updateBottomShortcuts() {
        val enabledButtons = preferences.readerBottomButtons().get()
        with(binding.chaptersSheet) {
            readingMode.isVisible = ReaderBottomButton.ReadingMode.isIn(enabledButtons)
            rotationSheetButton.isVisible =
                ReaderBottomButton.Rotation.isIn(enabledButtons)
            doublePage.isVisible = viewer is PagerViewer &&
                ReaderBottomButton.PageLayout.isIn(enabledButtons)
            cropBordersSheetButton.isVisible = when {
                viewer is eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer -> true
                viewer is PagerViewer ->
                    ReaderBottomButton.CropBordersPaged.isIn(enabledButtons)
                else -> ReaderBottomButton.CropBordersWebtoon.isIn(enabledButtons)
            }
            webviewButton.isVisible =
                ReaderBottomButton.WebView.isIn(enabledButtons)
            chaptersButton.isVisible =
                ReaderBottomButton.ViewChapters.isIn(enabledButtons)
            shiftPageButton.isVisible =
                ((viewer as? PagerViewer)?.config?.doublePages ?: false) && canShowSplitAtBottom()
            binding.toolbar.menu.findItem(R.id.action_shift_double_page)?.isVisible =
                ((viewer as? PagerViewer)?.config?.doublePages ?: false) && !canShowSplitAtBottom()
        }
    }

    /**
     * Called when an item of the options menu was clicked. Used to handle clicks on our menu
     * entries.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_shift_double_page -> {
                shiftDoublePages()
                manuallyShiftedPages = true
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    fun shiftDoublePages(forceShift: Boolean? = null, page: ReaderPage? = null) {
        (viewer as? PagerViewer)?.let { pViewer ->
            if (forceShift == pViewer.config.shiftDoublePage) return
            pViewer.config.shiftDoublePage = !pViewer.config.shiftDoublePage
            viewModel.state.value.viewerChapters?.let {
                pViewer.updateShifting(page)
                pViewer.setChaptersDoubleShift(it)
                invalidateOptionsMenu()
            }
        }
    }

    private fun popToMain() {
        if (fromUrl) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finishAfterTransition()
        } else {
            backPressedCallback?.isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    fun reEnableBackPressedCallBack() {
        backPressedCallback?.isEnabled = binding.chaptersSheet.chaptersBottomSheet.sheetBehavior.isExpanded()
    }

    override fun finishAfterTransition() {
        if (didTransitionFromChapter && visibleChapterRange.isNotEmpty() && MainActivity.chapterIdToExitTo !in visibleChapterRange) {
            finish()
        } else {
            viewModel.onBackPressed()
            super.finishAfterTransition()
        }
    }

    override fun finish() {
        viewModel.onBackPressed()
        super.finish()
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = viewer?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_N -> {
                if (viewer is R2LPagerViewer) {
                    binding.readerNav.leftChapter.performClick()
                } else {
                    binding.readerNav.rightChapter.performClick()
                }
                return true
            }
            KeyEvent.KEYCODE_P -> {
                if (viewer !is R2LPagerViewer) {
                    binding.readerNav.leftChapter.performClick()
                } else {
                    binding.readerNav.rightChapter.performClick()
                }
                return true
            }
            KeyEvent.KEYCODE_L -> {
                binding.readerNav.leftChapter.performClick()
                return true
            }
            KeyEvent.KEYCODE_R -> {
                binding.readerNav.rightChapter.performClick()
                return true
            }
            KeyEvent.KEYCODE_E -> {
                viewer?.moveToNext()
                return true
            }
            KeyEvent.KEYCODE_Q -> {
                viewer?.moveToPrevious()
                return true
            }
            else -> return super.onKeyUp(keyCode, event)
        }
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    private fun buildContainerTransform(entering: Boolean): MaterialContainerTransform {
        return MaterialContainerTransform(this, entering).apply {
            duration = (
                resources?.getInteger(
                    if (entering) {
                        AR.integer.config_longAnimTime
                    } else {
                        AR.integer.config_mediumAnimTime
                    },
                ) ?: 500
                ).toLong()
            addTarget(AR.id.content)
        }
    }

    /**
     * Initializes the reader menu. It sets up click listeners and the initial visibility.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeMenu() {
        // Set binding.toolbar
        setSupportActionBar(binding.toolbar)
        val primaryColor = ColorUtils.setAlphaComponent(
            getResourceColor(materialR.attr.colorSurface),
            200,
        )
        binding.appBar.setBackgroundColor(primaryColor)
        window.statusBarColor = Color.TRANSPARENT
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.navigationIcon?.setTint(getResourceColor(R.attr.actionBarTintColor))
        binding.toolbar.setNavigationOnClickListener {
            popToMain()
        }

        binding.toolbar.setOnClickListener {
            viewModel.manga?.id?.let { id ->
                val intent = SearchActivity.openMangaIntent(this, id)
                startActivity(intent)
            }
        }

        with(binding.chaptersSheet) {
            with(doublePage) {
                compatToolTipText = getString(MR.strings.page_layout)
                setOnClickListener {
                    if (preferences.pageLayout().get() == PageLayout.AUTOMATIC.value) {
                        (viewer as? PagerViewer)?.config?.let { config ->
                            config.doublePages = !config.doublePages
                            reloadChapters(config.doublePages, true)
                        }
                    } else {
                        showPageLayoutMenu()
                    }
                }
                setOnLongClickListener {
                    showPageLayoutMenu()
                    true
                }
            }
            cropBordersSheetButton.setOnClickListener {
                val isNovel = viewer is eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer
                val pref = when {
                    isNovel -> readerPreferences.novelMarginsCropped
                    (viewer as? WebtoonViewer)?.hasMargins == true || viewer is PagerViewer ->
                        preferences.cropBorders()
                    else -> preferences.cropBordersWebtoon()
                }
                pref.toggle()
            }

            with(rotationSheetButton) {
                compatToolTipText = getString(MR.strings.rotation)

                setOnClickListener {
                    // Highlight whichever orientation we'd actually apply if the popup were
                    // dismissed unchanged. getMangaOrientationType() resolves the per-series
                    // override → type-appropriate default fallback (novel vs manga) for us.
                    popupMenu(
                        items = OrientationType.entries.map { it.flagValue to it.stringRes },
                        selectedItemId = viewModel.getMangaOrientationType(),
                    ) {
                        val newOrientation = OrientationType.fromPreference(itemId)

                        viewModel.setMangaOrientationType(newOrientation.flagValue)

                        updateOrientationShortcut(newOrientation.flagValue)
                    }
                }
            }

            webviewButton.setOnClickListener {
                openMangaInBrowser()
            }

            displayOptions.setOnClickListener {
                if (viewer is eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer) {
                    eu.kanade.tachiyomi.ui.reader.settings.TabbedNovelReaderSettingsSheet(this@ReaderActivity).show()
                } else {
                    TabbedReaderSettingsSheet(this@ReaderActivity).show()
                }
            }

            displayOptions.setOnLongClickListener {
                if (viewer is eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer) {
                    eu.kanade.tachiyomi.ui.reader.settings.TabbedNovelReaderSettingsSheet(this@ReaderActivity).show()
                } else {
                    TabbedReaderSettingsSheet(this@ReaderActivity, true).show()
                }
                true
            }

            readingMode.setOnClickListener { readingMode ->
                readingMode.popupMenu(
                    items = ReadingModeType.entries.map { it.flagValue to it.stringRes },
                    selectedItemId = viewModel.manga?.readingModeType,
                ) {
                    viewModel.setMangaReadingMode(itemId)
                }
            }
        }

        listOf(
            preferences.cropBorders(),
            preferences.cropBordersWebtoon(),
            readerPreferences.novelMarginsCropped,
        ).forEach { pref ->
            pref.changes()
                .onEach { updateCropBordersShortcut() }
                .launchIn(scope)
        }

        binding.chaptersSheet.shiftPageButton.setOnClickListener {
            shiftDoublePages()
            manuallyShiftedPages = true
        }

        binding.readerNav.leftChapter.setOnClickListener { loadAdjacentChapter(false) }
        binding.readerNav.rightChapter.setOnClickListener { loadAdjacentChapter(true) }

        binding.touchView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (binding.chaptersSheet.chaptersBottomSheet.sheetBehavior.isExpanded()) {
                    binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.collapse()
                }
            }
            false
        }
        val readerNavGestureDetector = ReaderNavGestureDetector(this)
        val gestureDetector = GestureDetector(this, readerNavGestureDetector)
        with(binding.readerNav) {
            binding.readerNav.pageSeekbar.addOnSliderTouchListener(
                object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) {
                        readerNavGestureDetector.lockVertical = false
                        readerNavGestureDetector.hasScrollHorizontal = true
                        isScrollingThroughPagesOrChapters = true
                    }

                    override fun onStopTrackingTouch(slider: Slider) {
                        isScrollingThroughPagesOrChapters = false
                    }
                },
            )
            listOf(root, leftChapter, rightChapter, pageSeekbar).forEach {
                it.setOnTouchListener { _, event ->
                    val result = gestureDetector.onTouchEvent(event)
                    if (event?.action == MotionEvent.ACTION_UP) {
                        if (!result) {
                            val sheetBehavior = binding.chaptersSheet.root.sheetBehavior
                            if (sheetBehavior?.state != BottomSheetBehavior.STATE_SETTLING && !sheetBehavior.isCollapsed()) {
                                sheetBehavior?.collapse()
                            }
                        }
                        if (readerNavGestureDetector.lockVertical) {
                            return@setOnTouchListener true
                        }
                    } else if ((event?.action != MotionEvent.ACTION_UP || event.action != MotionEvent.ACTION_DOWN) && result) {
                        event.action = MotionEvent.ACTION_CANCEL
                        return@setOnTouchListener false
                    }
                    if (it == pageSeekbar) {
                        readerNavGestureDetector.lockVertical
                    } else {
                        result
                    }
                }
            }
        }

        // Init listeners on bottom menu
        binding.readerNav.pageSeekbar.addOnChangeListener { _, value, fromUser ->
            if (viewer != null && fromUser) {
                val prevValue = (viewer as? PagerViewer)?.pager?.currentItem ?: -1
                when (val currentViewer = viewer) {
                    is eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer ->
                        currentViewer.setProgressPercent(value.roundToInt())
                    else -> moveToPageIndex(value.roundToInt())
                }
                val newValue = (viewer as? PagerViewer)?.pager?.currentItem ?: -1
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                    ((prevValue > -1 && newValue != prevValue) || viewer !is PagerViewer)
                ) {
                    binding.readerNav.pageSeekbar.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                }
            }
        }

        binding.readerNav.pageSeekbar.setLabelFormatter { value ->
            if (viewer is eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer) {
                return@setLabelFormatter "${value.roundToInt()}%"
            }

            val pageNumber = (value + 1).roundToInt()
            (viewer as? PagerViewer)?.let {
                if (it.config.doublePages || it.config.splitPages) {
                    if (it.hasExtraPage(value.roundToInt(), viewModel.getCurrentChapter())) {
                        val invertDoublePage = (viewer as? PagerViewer)?.config?.invertDoublePages ?: false
                        return@setLabelFormatter if (!binding.readerNav.pageSeekbar.isRTL.xor(invertDoublePage)) {
                            "$pageNumber-${pageNumber + 1}"
                        } else {
                            "${pageNumber + 1}-$pageNumber"
                        }
                    }
                }
            }
            pageNumber.toString()
        }

        // Set initial visibility
        setMenuVisibility(menuVisible, false)
        binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.isHideable = !menuVisible
        if (!menuVisible) binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.hide()
        binding.chaptersSheet.root.sheetBehavior?.isGestureInsetBottomIgnored = true
        val peek = 50.dpToPx
        lastVis = window.decorView.rootWindowInsetsCompat?.isVisible(statusBars()) ?: false
        var firstPass = true
        binding.readerLayout.doOnApplyWindowInsetsCompat { _, insets, _ ->
            setNavColor(insets)
            val systemInsets = insets.ignoredSystemInsets
            val currentOrientation = resources.configuration.orientation
            val isLandscapeFully = currentOrientation == Configuration.ORIENTATION_LANDSCAPE && readerPreferences.landscapeCutoutBehavior().get() == LandscapeCutoutBehaviour.DEFAULT
            val cutOutInsets = if (isLandscapeFully) insets.displayCutout else null
            val vis = insets.isVisible(statusBars())
            val fullscreen = preferences.fullscreen().get() && !isSplitScreen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!firstPass && lastVis != vis && fullscreen) {
                    onVisibilityChange(vis)
                }
                firstPass = false
                lastVis = vis
            }
            wic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            if (!fullscreen && sheetManageNavColor) {
                window.navigationBarColor = getResourceColor(materialR.attr.colorSurface)
            }
            binding.appBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = systemInsets.left
                rightMargin = systemInsets.right
            }
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemInsets.top
            }
            binding.chaptersSheet.chaptersBottomSheet.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = systemInsets.left
                rightMargin = systemInsets.right
                height = 280.dpToPx + systemInsets.bottom
            }
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = cutOutInsets?.safeInsetLeft ?: 0
                rightMargin = cutOutInsets?.safeInsetRight ?: 0
            }
            binding.chaptersSheet.topbarLayout.updatePadding(
                left = cutOutInsets?.safeInsetLeft ?: 0,
                right = cutOutInsets?.safeInsetRight ?: 0,
            )
            binding.chaptersSheet.chapterRecycler.updatePadding(
                left = cutOutInsets?.safeInsetLeft ?: 0,
                right = cutOutInsets?.safeInsetRight ?: 0,
            )
            binding.navLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = 12.dpToPx + max(systemInsets.left, cutOutInsets?.safeInsetLeft ?: 0)
                rightMargin = 12.dpToPx + max(systemInsets.right, cutOutInsets?.safeInsetRight ?: 0)
            }
            // Re-resolve the slider's vertical position whenever insets change. The "top"
            // mode pins the slider to `actionBarSize + status-bar-inset`; immersive toggles
            // and cutout transitions can change that inset mid-session, so we recompute on
            // every inset event. Idempotent for "center"/"bottom" — those branches don't
            // read the inset.
            applyNovelNavLayoutPosition()
            binding.chaptersSheet.root.sheetBehavior?.peekHeight =
                peek + if (fullscreen) {
                insets.getBottomGestureInsets()
            } else {
                val rootInsets = binding.root.rootWindowInsetsCompat ?: insets
                max(
                    0,
                    (rootInsets.getBottomGestureInsets()) -
                        rootInsets.getInsetsIgnoringVisibility(systemBars()).bottom,
                )
            }
            binding.chaptersSheet.chapterRecycler.updatePaddingRelative(bottom = systemInsets.bottom)
            binding.viewerContainer.requestLayout()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            binding.readerLayout.setOnSystemUiVisibilityChangeListener {
                if (preferences.fullscreen().get()) {
                    onVisibilityChange((it and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0)
                }
            }
        }
    }

    private fun loadAdjacentChapter(rightButton: Boolean) {
        if (isLoading) {
            return
        }
        isScrollingThroughPagesOrChapters = true
        lifecycleScope.launch {
            val getNextChapter = (viewer is R2LPagerViewer).xor(rightButton)
            val adjChapter = viewModel.adjacentChapter(getNextChapter)
            if (adjChapter != null) {
                if (rightButton) {
                    binding.readerNav.rightChapter.isInvisible = true
                    binding.readerNav.rightProgress.isVisible = true
                } else {
                    binding.readerNav.leftChapter.isInvisible = true
                    binding.readerNav.leftProgress.isVisible = true
                }
                loadChapter(adjChapter)
            } else {
                toast(
                    if (getNextChapter) {
                        MR.strings.theres_no_next_chapter
                    } else {
                        MR.strings.theres_no_previous_chapter
                    },
                )
            }
        }
    }

    suspend fun loadChapter(chapter: Chapter) {
        loadChapter(ReaderChapter(chapter))
    }

    private suspend fun loadChapter(chapter: ReaderChapter) {
        val lastPage = viewModel.loadChapter(chapter) ?: return
        scope.launchUI {
            moveToPageIndex(lastPage, false, chapterChange = true)
        }
        refreshChapters()
    }

    fun setNavColor(insets: WindowInsetsCompat) {
        sheetManageNavColor = when {
            isSplitScreen -> {
                window.statusBarColor = getResourceColor(materialR.attr.colorPrimaryVariant)
                window.navigationBarColor = getResourceColor(materialR.attr.colorPrimaryVariant)
                false
            }
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 -> {
                // basically if in landscape on a phone
                // For lollipop, draw opaque nav bar
                window.navigationBarColor = when {
                    insets.hasSideNavBar() -> Color.BLACK
                    isInNightMode() -> {
                        ColorUtils.setAlphaComponent(
                            getResourceColor(materialR.attr.colorPrimaryVariant),
                            179,
                        )
                    }
                    else -> Color.argb(179, 0, 0, 0)
                }
                !insets.hasSideNavBar()
            }
            insets.isBottomTappable() -> {
                window.navigationBarColor = Color.TRANSPARENT
                false
            }
            insets.hasSideNavBar() -> {
                window.navigationBarColor = getResourceColor(materialR.attr.colorSurface)
                false
            }
            // if in portrait with 2/3 button mode, translucent nav bar
            else -> {
                true
            }
        }
    }

    private fun showPageLayoutMenu() {
        with(binding.chaptersSheet.doublePage) {
            val config = (viewer as? PagerViewer)?.config
            val selectedId = when {
                config?.doublePages == true -> PageLayout.DOUBLE_PAGES
                config?.splitPages == true -> PageLayout.SPLIT_PAGES
                else -> PageLayout.SINGLE_PAGE
            }
            popupMenu(
                items = listOf(
                    PageLayout.SINGLE_PAGE,
                    PageLayout.DOUBLE_PAGES,
                    PageLayout.SPLIT_PAGES,
                ).map { it.value to it.stringRes },
                selectedItemId = selectedId.value,
            ) {
                val newLayout = PageLayout.fromPreference(itemId)

                if (preferences.pageLayout().get() == PageLayout.AUTOMATIC.value) {
                    (viewer as? PagerViewer)?.config?.let { config ->
                        config.doublePages = newLayout == PageLayout.DOUBLE_PAGES
                        if (newLayout == PageLayout.SINGLE_PAGE) {
                            preferences.automaticSplitsPage().set(false)
                        } else if (newLayout == PageLayout.SPLIT_PAGES) {
                            preferences.automaticSplitsPage().set(true)
                        }
                        reloadChapters(config.doublePages, true)
                    }
                } else {
                    preferences.pageLayout().set(newLayout.value)
                }
            }
        }
    }

    fun hideMenu() {
        if (menuVisible && !isScrollingThroughPagesOrChapters) {
            setMenuVisibility(false)
        }
    }

    /**
     * Sets the visibility of the menu according to [visible] and with an optional parameter to
     * [animate] the views.
     */
    private fun setMenuVisibility(visible: Boolean, animate: Boolean = true) {
        val oldVisibility = menuVisible
        menuVisible = visible
        if (visible) coroutine?.cancel()
        binding.viewerContainer.requestLayout()
        if (visible) {
            snackbar?.dismiss()
            wic.show(systemBars())
            binding.appBar.isVisible = true

            if (binding.chaptersSheet.chaptersBottomSheet.sheetBehavior.isExpanded()) {
                binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.isHideable = false
            }
            if (!binding.chaptersSheet.chaptersBottomSheet.sheetBehavior.isExpanded() && sheetManageNavColor) {
                window.navigationBarColor = Color.TRANSPARENT
            }
            if (animate && oldVisibility != menuVisible) {
                if (!menuTemporarilyVisible) {
                    if (ReducedMotion.isEnabled()) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                        delayTitleScroll()
                    } else {
                        val toolbarAnimation = AnimationUtils.loadAnimation(this, R.anim.enter_from_top)
                        toolbarAnimation.doOnStart {
                            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                        }
                        toolbarAnimation.doOnEnd { delayTitleScroll() }
                        binding.appBar.startAnimation(toolbarAnimation)
                    }
                } else {
                    delayTitleScroll()
                }
                binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.collapse()
            }
        } else {
            if (preferences.fullscreen().get()) {
                wic.hide(systemBars())
                wic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }

            if (animate && binding.appBar.isVisible) {
                if (ReducedMotion.isEnabled()) {
                    binding.appBar.isVisible = false
                    stopTitleScroll()
                } else {
                    val toolbarAnimation = AnimationUtils.loadAnimation(this, R.anim.exit_to_top)
                    toolbarAnimation.doOnEnd {
                        binding.appBar.isVisible = false
                        stopTitleScroll()
                    }
                    binding.appBar.startAnimation(toolbarAnimation)
                }
                binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.isHideable = true
                binding.chaptersSheet.chaptersBottomSheet.sheetBehavior?.hide()
            } else if (!animate) {
                binding.appBar.isVisible = false
                stopTitleScroll()
            }
        }
        menuTemporarilyVisible = false
        // Mirror visibility into the Compose overlay so the novel action bar fades in/out
        // alongside the existing manga app-bar / chapter-nav.
        novelActionBarVisibleState.value = visible
        if (visible) novelActionBarTickState.value = novelActionBarTickState.value + 1
    }

    /**
     * Lazily creates the [androidx.compose.ui.platform.ComposeView] hosting the novel-only
     * action bar and anchors it just above the existing chapter-nav layout. Re-entrant: a
     * second call when the view is already mounted is a no-op.
     */
    private fun mountNovelActionBarIfNeeded() {
        if (novelActionBarComposeView != null) return
        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            id = View.generateViewId()
            setContent {
                yokai.presentation.theme.YokaiTheme {
                    val visible = novelActionBarVisibleState.value
                    // Tick state forces a recomposition each time the bars become visible so
                    // the displayed TTS / auto-scroll booleans pick up out-of-band state.
                    @Suppress("UNUSED_VARIABLE")
                    val tick = novelActionBarTickState.value
                    val novelWeb = viewer as? eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer
                    val ttsActive = novelWeb?.isTtsSpeaking() == true || novelWeb?.isTtsPaused() == true
                    val ttsPaused = novelWeb?.isTtsPaused() == true
                    val autoScrolling = novelWeb?.isAutoScrollActive() == true
                    hayai.novel.reader.bars.NovelReaderActionBar(
                        visible = visible,
                        isTtsActive = ttsActive,
                        isTtsPaused = ttsPaused,
                        isAutoScrolling = autoScrolling,
                        onToggleTts = {
                            var startedFresh = false
                            if (novelWeb != null) {
                                when {
                                    novelWeb.isTtsPaused() -> novelWeb.resumeTts()
                                    novelWeb.isTtsSpeaking() -> novelWeb.pauseTts()
                                    else -> { novelWeb.startTts(); startedFresh = true }
                                }
                            }
                            if (startedFresh) startBackgroundTtsIfEnabled()
                            novelActionBarTickState.value = novelActionBarTickState.value + 1
                        },
                        onLongPressTts = {
                            novelWeb?.stopTts()
                            stopBackgroundTtsIfRunning()
                            novelActionBarTickState.value = novelActionBarTickState.value + 1
                        },
                        // onTtsStartFromViewport removed: the TTS_VIEWPORT button was
                        // consolidated into the main TTS toggle (which now starts from the
                        // top of the chapter on the first tap, then pauses/resumes).
                        onToggleAutoScroll = {
                            novelWeb?.toggleAutoScroll()
                            novelActionBarTickState.value = novelActionBarTickState.value + 1
                        },
                        onScrollToTop = {
                            novelWeb?.scrollToTop()
                        },
                        onClickFindReplace = {
                            hayai.novel.reader.settings.showNovelFindReplaceSheet(this@ReaderActivity)
                        },
                        onClickQuotes = {
                            val manga = viewModel.manga ?: return@NovelReaderActionBar
                            val novelId = manga.id ?: return@NovelReaderActionBar
                            val chapter = viewModel.state.value.viewerChapters?.currChapter?.chapter
                            hayai.novel.reader.quote.showQuotesSheet(
                                activity = this@ReaderActivity,
                                novelId = novelId,
                                novelName = manga.title,
                                chapterName = chapter?.name ?: "",
                            )
                        },
                    )
                }
            }
        }
        novelActionBarComposeView = composeView
        val twelveDp = (12 * resources.displayMetrics.density).toInt()
        val coordinatorParams = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.MATCH_PARENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(twelveDp, 0, twelveDp, 0)
            marginStart = twelveDp
            marginEnd = twelveDp
            anchorId = R.id.nav_layout
            anchorGravity = android.view.Gravity.TOP
            gravity = android.view.Gravity.TOP
        }
        binding.root.addView(composeView, coordinatorParams)
    }

    private fun removeNovelActionBar() {
        novelActionBarComposeView?.let {
            it.disposeComposition()
            binding.root.removeView(it)
        }
        novelActionBarComposeView = null
    }

    /**
     * Reposition the chapter-nav layout (slider + prev/next chapter) and the novel action bar
     * based on [ReaderPreferences.novelProgressSliderPosition], which is a 3x3 grid string
     * formatted "<v>-<h>" where v ∈ {top, center, bottom} and h ∈ {left, center, right}.
     *
     * Layout rules:
     *   - **bottom-center** (default): keeps the manga-reader layout exactly — nav_layout is
     *     anchored to `chapters_sheet`, full-width, with the action bar stacked just above.
     *   - any other **-center** (top-center, center-center): full-width stretch but anchored
     *     to a different vertical zone (under the toolbar / vertically centered).
     *   - any **-left or **-right**: compact pill (~360dp wide), anchored to that side via
     *     gravity. Inside the pill the slider's `layout_weight=1` fills the remaining space
     *     between the two prev/next buttons.
     *
     * Action bar (TTS / auto-scroll / find-replace) follows the slider only when slider is
     * at bottom-center; in every other mode it re-anchors to `chapters_sheet` so the
     * controls stay at the bottom-of-screen for thumb access.
     *
     * Backward-compat: the legacy preference values "top"/"center"/"bottom" (without a
     * horizontal axis) map to their *-center equivalents so existing user prefs still work.
     *
     * Foldables: the hinge-gap branch (init block, line ~537) overrides nav width on dual-pane
     * layouts before this runs once per pref change. Re-running this from a hinge update would
     * stomp that width — currently the foldable path only fires on layout-info changes, which
     * happen rarely enough that we don't bother re-merging.
     */
    private fun applyNovelNavLayoutPosition() {
        val isNovelViewer = viewer is eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer
        val rawPosition = if (isNovelViewer) {
            readerPreferences.novelProgressSliderPosition.get()
        } else {
            "bottom-center"
        }
        // Legacy 1D values → 2D *-center equivalents.
        val position = when (rawPosition) {
            "top" -> "top-center"
            "center" -> "center-center"
            "bottom" -> "bottom-center"
            else -> rawPosition
        }
        val (vAxis, hAxis) = position.split("-").let {
            (it.getOrNull(0) ?: "bottom") to (it.getOrNull(1) ?: "center")
        }
        val twelveDp = 12.dpToPx
        // Compact pill width for the *-left / *-right corners. Capped at 360dp so it sits
        // comfortably in the gutter without crowding content; the inner slider's
        // layout_weight=1 fills the remaining space between the prev/next chapter buttons.
        val compactWidthPx = 360.dpToPx
        val isStretch = hAxis == "center"
        // ?attr/actionBarSize resolved at runtime; falls back to 56dp. Used by top-* to push
        // the slider below the toolbar; tracks live insets via the inset listener.
        val toolbarPx by lazy {
            val typed = TypedValue()
            if (theme.resolveAttribute(android.R.attr.actionBarSize, typed, true)) {
                TypedValue.complexToDimensionPixelSize(typed.data, resources.displayMetrics)
            } else {
                56.dpToPx
            }
        }
        val statusBarTop = binding.root.rootWindowInsetsCompat?.getInsets(systemBars())?.top ?: 0
        // Translate the v/h axis into Android Gravity flags. Note START/END (rather than
        // LEFT/RIGHT) so RTL locales mirror correctly.
        val verticalGravity = when (vAxis) {
            "top" -> Gravity.TOP
            "center" -> Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM
        }
        val horizontalGravity = when (hAxis) {
            "left" -> Gravity.START
            "right" -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        binding.navLayout.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            if (position == "bottom-center") {
                // Preserve the original anchored behaviour for the manga-equivalent default —
                // anchoring to chapters_sheet auto-handles sheet expansion (nav slides up
                // with the sheet) and keeps the rotation/foldable code paths working.
                anchorId = R.id.chapters_sheet
                anchorGravity = Gravity.TOP
                gravity = Gravity.TOP
                topMargin = 0
                bottomMargin = 0
                width = CoordinatorLayout.LayoutParams.MATCH_PARENT
            } else {
                // All other 8 positions: detach anchor and use absolute gravity. Manual
                // top/bottom margins compensate for toolbar/sheet so the slider doesn't
                // collide with either.
                anchorId = View.NO_ID
                gravity = verticalGravity or horizontalGravity
                topMargin = if (vAxis == "top") toolbarPx + statusBarTop else 0
                // For bottom-left / bottom-right, lift above the chapters_sheet's peek
                // height so the slider sits in the same band as bottom-center would.
                bottomMargin = if (vAxis == "bottom") {
                    (binding.chaptersSheet.root.sheetBehavior?.peekHeight ?: 50.dpToPx)
                } else {
                    0
                }
                width = if (isStretch) {
                    CoordinatorLayout.LayoutParams.MATCH_PARENT
                } else {
                    compactWidthPx
                }
            }
            // Preserve horizontal cutout/inset margins applied by the inset listener.
            marginStart = max(twelveDp, marginStart)
            marginEnd = max(twelveDp, marginEnd)
        }
        // Action bar: only follow the slider when it's at bottom-center (so the action bar
        // sits naturally above the slider, where the user expects controls). For every
        // other slider position, re-anchor the action bar to chapters_sheet so TTS /
        // auto-scroll / find-replace remain at thumb-reach at the bottom of the screen.
        novelActionBarComposeView?.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            anchorId = if (position == "bottom-center") R.id.nav_layout else R.id.chapters_sheet
            anchorGravity = Gravity.TOP
            gravity = Gravity.TOP
        }
    }

    /**
     * Called from the view model when a manga is ready. Used to instantiate the appropriate viewer
     * and the binding.toolbar title.
     */
    private fun setManga(manga: Manga) {
        val prevViewer = viewer
        val noDefault = manga.viewer_flags == -1
        val mangaViewer = viewModel.getMangaReadingMode()
        val newViewer = when (mangaViewer) {
            ReadingModeType.LEFT_TO_RIGHT.flagValue -> L2RPagerViewer(this)
            ReadingModeType.VERTICAL.flagValue -> VerticalPagerViewer(this)
            ReadingModeType.LONG_STRIP.flagValue -> WebtoonViewer(this)
            ReadingModeType.CONTINUOUS_VERTICAL.flagValue -> WebtoonViewer(this, hasMargins = true)
            // NOVEL -->
            // Novel reading is WebView-only as of the viewer consolidation. The legacy
            // native NovelViewer was deleted; the `novelRenderingMode` preference is gone.
            // Keeping the WHEN branch lets ReadingModeType.NOVEL stay distinct so manga
            // viewer flags don't accidentally route a novel through a Pager/Webtoon.
            ReadingModeType.NOVEL.flagValue ->
                eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer(this)
            // NOVEL <--
            else -> R2LPagerViewer(this)
        }

        if (noDefault && viewModel.manga?.readingModeType!! > 0 &&
            viewModel.manga?.readingModeType!! != preferences.defaultReadingMode().get()
        ) {
            snackbar = binding.readerLayout.snack(
                getString(
                    MR.strings.reading_,
                    getString(
                        when (mangaViewer) {
                            ReadingModeType.RIGHT_TO_LEFT.flagValue -> MR.strings.right_to_left_viewer
                            ReadingModeType.VERTICAL.flagValue -> MR.strings.vertical_viewer
                            ReadingModeType.LONG_STRIP.flagValue -> MR.strings.long_strip
                            else -> MR.strings.left_to_right_viewer
                        },
                    ).lowercase(Locale.getDefault()),
                ),
                4000,
            ) {
                setAction(MR.strings.use_default) {
                    viewModel.setMangaReadingMode(0)
                }
            }
        }

        if (window.sharedElementEnterTransition is MaterialContainerTransform &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        ) {
            // Wait until transition is complete to avoid crash on API 26
            window.sharedElementEnterTransition.addListener(
                onEnd = { setOrientation(viewModel.getMangaOrientationType()) },
            )
        } else {
            setOrientation(viewModel.getMangaOrientationType())
        }

        // Destroy previous viewer if there was one
        if (prevViewer != null) {
            prevViewer.destroy()
            binding.viewerContainer.removeAllViews()
        }
        viewer = newViewer
        binding.viewerContainer.addView(newViewer.getView())

        // Mount/dismount the Compose overlay that hosts the novel-only action bar.
        if (newViewer is eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer) {
            mountNovelActionBarIfNeeded()
            // The controller may already be bound from a prior session; re-attach the
            // freshly-created viewer so play/pause issued before bind-completion still
            // routes through this viewer once the controller fires.
            attachNovelViewerToTtsController()
        } else {
            removeNovelActionBar()
        }
        // Position the slider/nav (top/center/bottom) — must run after the action bar is
        // mounted so it can re-anchor that view too.
        applyNovelNavLayoutPosition()

        if (newViewer is R2LPagerViewer) {
            binding.readerNav.leftChapter.compatToolTipText = getString(MR.strings.next_chapter)
            binding.readerNav.rightChapter.compatToolTipText = getString(MR.strings.previous_chapter)
        } else {
            binding.readerNav.leftChapter.compatToolTipText = getString(MR.strings.previous_chapter)
            binding.readerNav.rightChapter.compatToolTipText = getString(MR.strings.next_chapter)
        }

        if (newViewer is PagerViewer) {
            newViewer.config.hingeGapSize = hingeGapSize
            if (preferences.pageLayout().get() == PageLayout.AUTOMATIC.value) {
                setDoublePageMode(newViewer)
            }
            lastShiftDoubleState?.let { newViewer.config.shiftDoublePage = it }
        }

        binding.navigationOverlay.isLTR = viewer !is R2LPagerViewer

        supportActionBar?.title = manga.title

        binding.readerNav.pageSeekbar.isRTL = newViewer is R2LPagerViewer

        binding.pleaseWait.isVisible = true
        if (!ReducedMotion.isEnabled()) {
            binding.pleaseWait.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in_long))
        }
        invalidateOptionsMenu()
        updateCropBordersShortcut()
        updateBottomShortcuts()
        val viewerMode = ReadingModeType.fromPreference(viewModel.state.value.manga?.readingModeType ?: 0)
        binding.chaptersSheet.readingMode.setImageResource(viewerMode.iconRes)
        startPostponedEnterTransition()
    }

    override fun onPause() {
        // Flush the novel viewer's latest scroll percent to its chapter's `last_page_read`
        // before the read-timer write, so process-restore lands at the right within-chapter
        // position. The chapter id itself is already in savedState via the VM (kept in sync
        // by `setNovelVisibleChapter`), so no separate "active chapter" write is needed.
        (viewer as? eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer)?.flushPersistentState()
        viewModel.flushReadTimer()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        viewModel.restartReadTimer()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val oldUiMode = lastUiModeMask
        val newUiMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        lastUiModeMask = newUiMode

        super.onConfigurationChanged(newConfig)

        if (oldUiMode != newUiMode) {
            // Theme tree caches light/dark; only a recreate keeps toolbar/sheet colors consistent.
            recreate()
            return
        }

        setOrientation(viewModel.getMangaOrientationType())
        setCutoutMode()
    }

    fun reloadChapters(doublePages: Boolean, force: Boolean = false) {
        val pViewer = viewer as? PagerViewer ?: return
        pViewer.updateShifting()
        if (!force && pViewer.config.autoDoublePages) {
            setDoublePageMode(pViewer)
        } else {
            pViewer.config.doublePages = doublePages
            if (pViewer.config.autoDoublePages) {
                pViewer.config.splitPages = preferences.automaticSplitsPage().get() && !pViewer.config.doublePages
            }
        }
        if (doublePages) {
            // If we're moving from single to double, we want the current page to be the first page
            val currentIndex = binding.readerNav.pageSeekbar.value.roundToInt()
            viewModel.getCurrentChapter()?.requestedPage = currentIndex
            pViewer.hasMoved = false
            pViewer.config.shiftDoublePage = shouldShiftDoublePages(currentIndex)
        }
        viewModel.state.value.viewerChapters?.let {
            pViewer.setChaptersDoubleShift(it)
        }
        invalidateOptionsMenu()
    }

    private fun shouldShiftDoublePages(currentIndex: Int): Boolean {
        val currentChapter = viewModel.getCurrentChapter()
        return (
            currentIndex +
                (currentChapter?.pages?.take(currentIndex)?.count { it.alonePage } ?: 0)
            ) % 2 != 0
    }

    /**
     * Called from the view model whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the binding.toolbar.
     */
    fun setChapters(viewerChapters: ViewerChapters) {
        val isNovelViewer = viewer is eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer

        binding.pleaseWait.clearAnimation()
        binding.pleaseWait.isVisible = false
        if (indexChapterToShift != null && indexPageToShift != null) {
            viewerChapters.currChapter.pages?.find { it.index == indexPageToShift && it.chapter.chapter.id == indexChapterToShift }?.let {
                (viewer as? PagerViewer)?.updateShifting(it)
            }
            indexChapterToShift = null
            indexPageToShift = null
        }
        val currentChapterPageCount = viewerChapters.currChapter.pages?.size ?: 1
        binding.readerNav.root.visibility = when {
            isNovelViewer && binding.chaptersSheet.root.sheetBehavior.isCollapsed() -> View.VISIBLE
            !isNovelViewer && currentChapterPageCount == 1 -> View.GONE
            binding.chaptersSheet.root.sheetBehavior.isCollapsed() -> View.VISIBLE
            else -> View.INVISIBLE
        }

        if (isNovelViewer) {
            // Honour the user's "Show progress slider" preference instead of force-showing.
            // Previously the slider was unconditionally pinned visible here, which fought
            // the preference: toggling the pref off had no effect because setChapters
            // re-set it to true on every chapter rebind.
            val showSlider = readerPreferences.novelShowProgressSlider.get()
            binding.readerNav.pageSeekbar.isVisible = showSlider
            binding.readerNav.pageSeekbar.isEnabled = showSlider
            binding.readerNav.leftPageText.isVisible = false
            binding.readerNav.rightPageText.isVisible = false
        } else {
            val showSeekbar = currentChapterPageCount > 1
            binding.readerNav.pageSeekbar.isVisible = showSeekbar
            binding.readerNav.pageSeekbar.isEnabled = showSeekbar
            binding.readerNav.leftPageText.isVisible = true
            binding.readerNav.rightPageText.isVisible = true
        }

        if (isNovelViewer) {
            updateNovelProgressUi(viewerChapters.currChapter.chapter.last_page_read.coerceIn(0, 100))
        }
        if (lastShiftDoubleState == null) {
            manuallyShiftedPages = false
        }
        lastShiftDoubleState = null
        viewer?.setChapters(viewerChapters)
        intentPageNumber?.let { moveToPageIndex(it) }
        intentPageNumber = null

        // Repopulate the chapters bottom sheet on (re)bind. The sheet's `setup(this)` runs
        // in onCreate before viewModel.init has resolved the manga, so its initial
        // refreshList() call returns an empty list. For paged viewers a subsequent
        // PagerViewer.firstLayout → onPageChange → activity.onPageSelected fires another
        // refreshList via the selectedChapterId-mismatch check downstream, which
        // backfills the sheet. NovelViewer.setChapters never goes through onPageSelected
        // on the initial load (it just renders the text into the scroll container), so
        // without a refresh here the sheet stays empty for the entire session. Force a
        // refresh whenever the selected chapter actually changes — cheap, idempotent,
        // and works for every viewer type.
        if (binding.chaptersSheet.chaptersBottomSheet.selectedChapterId != viewerChapters.currChapter.chapter.id) {
            binding.chaptersSheet.chaptersBottomSheet.refreshList()
        }
        val chapter = viewerChapters.currChapter.chapter
        binding.toolbar.subtitle = if (isNovelViewer) {
            formatNovelChapterTitle(chapter, viewModel.manga!!)
        } else {
            chapter.preferredChapterName(this, viewModel.manga!!, preferences)
        }

        updateToolbarMarquee()

        if (isNovelViewer) {
            binding.readerNav.leftChapter.isVisible = true
            binding.readerNav.rightChapter.isVisible = true
            binding.readerNav.leftChapter.alpha = if (viewerChapters.prevChapter != null) 1f else 0.5f
            binding.readerNav.rightChapter.alpha = if (viewerChapters.nextChapter != null) 1f else 0.5f
        } else if (viewerChapters.nextChapter == null && viewerChapters.prevChapter == null) {
            binding.readerNav.leftChapter.isVisible = false
            binding.readerNav.rightChapter.isVisible = false
        } else if (viewer is R2LPagerViewer) {
            binding.readerNav.leftChapter.isVisible = true
            binding.readerNav.rightChapter.isVisible = true
            binding.readerNav.leftChapter.alpha = if (viewerChapters.nextChapter != null) 1f else 0.5f
            binding.readerNav.rightChapter.alpha = if (viewerChapters.prevChapter != null) 1f else 0.5f
        } else {
            binding.readerNav.leftChapter.isVisible = true
            binding.readerNav.rightChapter.isVisible = true
            binding.readerNav.rightChapter.alpha = if (viewerChapters.nextChapter != null) 1f else 0.5f
            binding.readerNav.leftChapter.alpha = if (viewerChapters.prevChapter != null) 1f else 0.5f
        }
        if (didTransitionFromChapter) {
            MainActivity.chapterIdToExitTo = viewerChapters.currChapter.chapter.id ?: 0L
        }
    }

    private fun updateToolbarMarquee() {
        listOfNotNull(getTitleTextView(), getSubtitleTextView()).forEach { textView ->
            textView.ellipsize = TextUtils.TruncateAt.MARQUEE
            textView.marqueeRepeatLimit = -1
            textView.isSingleLine = true
            textView.isFocusable = true
            textView.isFocusableInTouchMode = true
            textView.isHorizontalFadingEdgeEnabled = true
            textView.setFadingEdgeLength(16.dpToPx)
            textView.setHorizontallyScrolling(true)
        }
    }

    private fun updateNovelChapterUi(readerChapter: ReaderChapter) {
        if (viewer !is eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer) return
        val manga = viewModel.manga ?: return
        val readerChapterId = readerChapter.chapter.id ?: return
        binding.toolbar.subtitle = formatNovelChapterTitle(readerChapter.chapter, manga)
        updateToolbarMarquee()
        if (didTransitionFromChapter) {
            MainActivity.chapterIdToExitTo = readerChapterId
        }
        binding.readerNav.leftChapter.alpha = if (viewModel.adjacentChapter(next = false) != null) 1f else 0.5f
        binding.readerNav.rightChapter.alpha = if (viewModel.adjacentChapter(next = true) != null) 1f else 0.5f
        if (binding.chaptersSheet.chaptersBottomSheet.selectedChapterId != readerChapterId) {
            binding.chaptersSheet.chaptersBottomSheet.refreshList()
        }
    }

    /**
     * Phase A #14: port of Tsundoku's `novelChapterTitleDisplay` when-block
     * (ReaderActivity.kt:684-719 in donor). Mode 0 = name only (default),
     * 1 = number only, 2 = both (`Ch. N: name`). Falls back to chapter name when
     * the number is missing.
     */
    private fun formatNovelChapterTitle(chapter: Chapter, manga: eu.kanade.tachiyomi.domain.manga.models.Manga): String {
        val mode = readerPreferences.novelChapterTitleDisplay.get()
        val number = chapter.chapter_number
        val numStr: String? = if (number >= 0f) {
            if (number == number.toLong().toFloat()) number.toLong().toString() else number.toString()
        } else null
        return when (mode) {
            1 -> numStr?.let { "Chapter $it" } ?: chapter.preferredChapterName(this, manga, preferences)
            2 -> numStr?.let { "Ch. $it: ${chapter.name}" } ?: chapter.preferredChapterName(this, manga, preferences)
            else -> chapter.preferredChapterName(this, manga, preferences)
        }
    }

    /**
     * Phase A #14: re-applies the toolbar subtitle using the current
     * `novelChapterTitleDisplay` setting. Called by the viewer's preference observer
     * so toggling the mode applies live without leaving the reader.
     */
    fun refreshNovelToolbarSubtitle() {
        if (viewer !is eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer) return
        val manga = viewModel.manga ?: return
        val chapter = viewModel.state.value.viewerChapters?.currChapter?.chapter ?: return
        binding.toolbar.subtitle = formatNovelChapterTitle(chapter, manga)
        updateToolbarMarquee()
    }

    private fun getTitleTextView(): TextView? = getTextViewsWithText(binding.toolbar.title)
    private fun getSubtitleTextView(): TextView? = getTextViewsWithText(binding.toolbar.subtitle)

    private fun getTextViewsWithText(text: CharSequence?): TextView? {
        if (text.isNullOrBlank()) return null
        val viewTopComparator = Comparator<View> { view1, view2 -> view1.top - view2.top }
        val textViews = binding.toolbar.children.filterIsInstance<TextView>()
            .filter { TextUtils.equals(it.text, text) }.toList()
        return if (textViews.isEmpty()) null else Collections.max(textViews, viewTopComparator)
    }

    private fun delayTitleScroll() {
        val list = listOfNotNull(getTitleTextView(), getSubtitleTextView())
        if (list.isNotEmpty()) {
            scope.launchUI {
                delay(1000)
                if (menuVisible) {
                    list.forEach { it.isSelected = true }
                }
            }
        }
    }

    private fun stopTitleScroll() =
        listOfNotNull(getTitleTextView(), getSubtitleTextView()).forEach { it.isSelected = false }

    /**
     * Called from the view model if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialChapterError(error: Throwable) {
        Logger.e(error)
        finish()
        toast(error.message)
    }

    /**
     * View handle for the inline overlay shown when the source isn't ready (issue #9).
     * Kept as a field so subsequent emissions can re-use / dismiss the same view rather
     * than stacking duplicates.
     */
    private var sourceNotReadyOverlay: View? = null

    /**
     * Inline overlay shown when [ReaderViewModel.init] gives up waiting for the source.
     * The user is offered a Retry button that re-runs init with the original intent
     * extras. We mount this on viewer_container (rather than finish()ing the activity)
     * so the user keeps their place in the navigation stack — they came here via History,
     * Continue Reading, or a recents tap and shouldn't be unceremoniously kicked back.
     */
    private fun showSourceNotReadyOverlay(sourceId: Long) {
        Logger.w { "ReaderActivity: showing SourceNotReady overlay for source $sourceId" }
        binding.pleaseWait.clearAnimation()
        binding.pleaseWait.isVisible = false
        // Idempotent: clobber any prior overlay so we don't stack views on rapid retries.
        sourceNotReadyOverlay?.let { binding.viewerContainer.removeView(it) }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48.dpToPx, 48.dpToPx, 48.dpToPx, 48.dpToPx)
            // Block taps from falling through to the (absent) viewer behind us.
            isClickable = true
            isFocusable = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val title = TextView(this).apply {
            text = getString(MR.strings.source_not_installed)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = getString(MR.strings.novel_source_not_ready, sourceId)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 16.dpToPx, 0, 24.dpToPx)
            alpha = 0.7f
        }
        val retry = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(MR.strings.retry)
            setOnClickListener {
                // Tear down the overlay before kicking off another init pass; if the
                // retry succeeds the viewer renders into the now-clean container.
                hideSourceNotReadyOverlay()
                retryInitAfterSourceNotReady()
            }
        }
        container.addView(title)
        container.addView(subtitle)
        container.addView(retry)
        binding.viewerContainer.addView(container)
        sourceNotReadyOverlay = container
    }

    private fun hideSourceNotReadyOverlay() {
        sourceNotReadyOverlay?.let { binding.viewerContainer.removeView(it) }
        sourceNotReadyOverlay = null
    }

    /**
     * Re-runs [ReaderViewModel.init] using the same intent extras the activity started
     * with. By this point the user has tapped Retry, so either (a) the plugin manager
     * has finished loading and awaitSource resolves immediately, or (b) it still hasn't
     * and we show the overlay again. Either path is fine — the user is in control.
     */
    private fun retryInitAfterSourceNotReady() {
        val manga = intent.extras?.getLong("manga", -1L) ?: -1L
        val chapter = intent.extras?.getLong("chapter", -1L) ?: -1L
        val page = intent.extras?.getInt("page", -1) ?: -1
        if (manga == -1L || chapter == -1L) {
            finish()
            return
        }
        val startingPage = page.takeIf { it >= 0 }
        binding.pleaseWait.isVisible = true
        lifecycleScope.launchNonCancellableIO {
            val initResult = viewModel.init(manga, chapter, startingPage)
            if (!initResult.getOrDefault(false)) {
                val exception = initResult.exceptionOrNull()
                if (exception !is SourceNotReadyException) {
                    val toShow = exception ?: IllegalStateException("Unknown err")
                    withUIContext {
                        setInitialChapterError(toShow)
                    }
                }
                // For SourceNotReady the Event collector will re-mount the overlay.
            }
        }
    }

    /**
     * Called from the view model whenever it's loading the next or previous chapter. It shows or
     * dismisses a non-cancellable dialog to prevent user interaction according to the value of
     * [show]. This is only used when the next/previous buttons on the binding.toolbar are clicked; the
     * other cases are handled with chapter transitions on the viewers and chapter preloading.
     */
    private fun setProgressDialog(show: Boolean) {
        if (!show) {
            binding.readerNav.leftChapter.isVisible = true
            binding.readerNav.rightChapter.isVisible = true

            binding.readerNav.leftProgress.isVisible = false
            binding.readerNav.rightProgress.isVisible = false
            binding.chaptersSheet.root.resetChapter()
        }
        if (show) {
            isLoading = true
        } else {
            scope.launchIO {
                delay(100)
                isLoading = false
            }
        }
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    private fun moveToPageIndex(index: Int, animated: Boolean = true, chapterChange: Boolean = false) {
        val viewer = viewer ?: return
        val currentChapter = viewModel.getCurrentChapter() ?: return
        val page = currentChapter.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page, animated)
        if (chapterChange) {
            isScrollingThroughPagesOrChapters = false
        }
    }

    private fun refreshChapters() {
        binding.chaptersSheet.chaptersBottomSheet.refreshList()
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the view model.
     */
    @SuppressLint("SetTextI18n")
    fun onPageSelected(page: ReaderPage, hasExtraPage: Boolean) {
        viewModel.onPageSelected(page, hasExtraPage)

        if (viewer is eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer) {
            if (binding.chaptersSheet.chaptersBottomSheet.selectedChapterId != page.chapter.chapter.id) {
                binding.chaptersSheet.chaptersBottomSheet.refreshList()
            }
            return
        }

        val pages = page.chapter.pages ?: return

        val currentPage = if (hasExtraPage) {
            val invertDoublePage = (viewer as? PagerViewer)?.config?.invertDoublePages ?: false
            if (!binding.readerNav.pageSeekbar.isRTL.xor(invertDoublePage)) {
                "${page.number}-${page.number + 1}"
            } else {
                "${page.number + 1}-${page.number}"
            }
        } else {
            "${page.number}${if (page.firstHalf == false) "*" else ""}"
        }

        val totalPages = pages.size.toString()
        if (hingeGapSize > 0) {
            binding.pageNumber.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                marginStart = (binding.root.width) / 2 + hingeGapSize
            }
        }
        binding.pageNumber.text = if (resources.isLTR) "$currentPage/$totalPages" else "$totalPages/$currentPage"
        if (viewer is R2LPagerViewer) {
            binding.readerNav.rightPageText.text = currentPage
            binding.readerNav.leftPageText.text = totalPages
        } else {
            binding.readerNav.leftPageText.text = currentPage
            binding.readerNav.rightPageText.text = totalPages
        }
        // Size labels to fit the widest possible text for this chapter so 1000+ pages don't line-wrap.
        val maxDigits = totalPages.length
        val isDoublePageMode = (viewer as? PagerViewer)?.config?.doublePages == true
        listOf(binding.readerNav.leftPageText, binding.readerNav.rightPageText).forEach { textView ->
            val isCurrent = (viewer is R2LPagerViewer).xor(textView === binding.readerNav.leftPageText)
            val chars = if (isDoublePageMode && isCurrent) maxDigits * 2 + 1 else maxDigits
            textView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = maxOf(chars * 10 + 4, 32).spToPx
            }
        }
        if (binding.chaptersSheet.chaptersBottomSheet.selectedChapterId != page.chapter.chapter.id) {
            binding.chaptersSheet.chaptersBottomSheet.refreshList()
        }
        // Set seekbar progress
        binding.readerNav.pageSeekbar.valueTo = max(pages.lastIndex.toFloat(), 1f)
        val progress = page.index + if (hasExtraPage) 1 else 0
        // For a double page, show the last 2 pages as if it was the final part of the seekbar
        binding.readerNav.pageSeekbar.value = (if (progress == pages.lastIndex) progress else page.index).toFloat()
    }

    fun onNovelProgressChanged(page: ReaderPage, progressPercent: Int) {
        viewModel.onNovelScrollProgress(page)
        updateNovelProgressUi(progressPercent.coerceIn(0, 100))
    }

    /**
     * Float overload called by the Tsundoku-derived NovelViewer when scroll progress changes.
     * Updates the progress slider in real-time.
     */
    fun onNovelProgressChanged(progress: Float) {
        val percentage = kotlin.math.round(progress * 100).toInt().coerceIn(0, 100)
        updateNovelProgressUi(percentage)
    }

    /**
     * Called from the novel viewer to save reading progress with a percentage.
     * Progress is stored as percentage (0-100) in last_page_read.
     */
    fun saveNovelProgress(page: ReaderPage, progressPercentage: Int, isTeardown: Boolean = false) {
        page.chapter.chapter.last_page_read = progressPercentage.coerceIn(0, 100)
        if (isTeardown) {
            // Teardown path: flush the percent but never flip the read flag.
            viewModel.onNovelTeardownProgress(page)
        } else {
            viewModel.onNovelScrollProgress(page)
        }
    }

    /**
     * Tells the view model to load the next chapter and mark it as active.
     */
    internal fun loadNextChapter() {
        lifecycleScope.launch {
            val next = viewModel.adjacentChapter(next = true) ?: return@launch
            viewModel.loadChapter(next)
        }
    }

    /**
     * Tells the view model to load the previous chapter and mark it as active.
     */
    internal fun loadPreviousChapter() {
        lifecycleScope.launch {
            val prev = viewModel.adjacentChapter(next = false) ?: return@launch
            viewModel.loadChapter(prev)
        }
    }

    /**
     * Check if translation mode is currently enabled.
     * Translation is out of scope for the initial port — always returns false.
     */
    fun isTranslationEnabled(): Boolean = false

    /**
     * Translate text content using the translation service.
     * Translation is out of scope for the initial port — returns content unchanged.
     */
    suspend fun translateContentIfEnabled(content: String): String = content

    /**
     * Called when the "Remember" action is triggered from the text-selection menu.
     * Quotes feature is out of scope for the initial port — no-op stub.
     */
    fun onRememberSelectedText() {
        // No-op — quotes feature ships in a later phase.
    }

    @SuppressLint("SetTextI18n")
    private fun updateNovelProgressUi(progressPercent: Int) {
        val progressLabel = "$progressPercent%"
        if (hingeGapSize > 0) {
            binding.pageNumber.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                marginStart = (binding.root.width) / 2 + hingeGapSize
            }
        }

        binding.readerNav.pageSeekbar.valueFrom = 0f
        binding.readerNav.pageSeekbar.valueTo = 100f
        if (binding.readerNav.pageSeekbar.value.roundToInt() != progressPercent) {
            binding.readerNav.pageSeekbar.value = progressPercent.toFloat()
        }

        binding.pageNumber.text = progressLabel
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(page: ReaderPage, extraPage: ReaderPage? = null) {
        val items = if (extraPage != null) {
            listOf(
                MaterialMenuSheet.MenuSheetItem(
                    3,
                    R.drawable.ic_outline_share_24dp,
                    MR.strings.share_second_page,
                ),
                MaterialMenuSheet.MenuSheetItem(
                    4,
                    R.drawable.ic_outline_save_24dp,
                    MR.strings.save_second_page,
                ),
                MaterialMenuSheet.MenuSheetItem(
                    5,
                    R.drawable.ic_outline_photo_24dp,
                    MR.strings.set_second_page_as_cover,
                ),
                MaterialMenuSheet.MenuSheetItem(
                    0,
                    R.drawable.ic_share_24dp,
                    MR.strings.share_first_page,
                ),
                MaterialMenuSheet.MenuSheetItem(
                    1,
                    R.drawable.ic_save_24dp,
                    MR.strings.save_first_page,
                ),
                MaterialMenuSheet.MenuSheetItem(
                    2,
                    R.drawable.ic_photo_24dp,
                    MR.strings.set_first_page_as_cover,
                ),
                MaterialMenuSheet.MenuSheetItem(
                    6,
                    R.drawable.ic_share_all_outline_24dp,
                    MR.strings.share_combined_pages,
                ),
                MaterialMenuSheet.MenuSheetItem(
                    7,
                    R.drawable.ic_save_all_outline_24dp,
                    MR.strings.save_combined_pages,
                ),
            )
        } else {
            listOf(
                MaterialMenuSheet.MenuSheetItem(
                    0,
                    R.drawable.ic_share_24dp,
                    MR.strings.share,
                ),
                MaterialMenuSheet.MenuSheetItem(
                    1,
                    R.drawable.ic_save_24dp,
                    MR.strings.save,
                ),
                MaterialMenuSheet.MenuSheetItem(
                    2,
                    R.drawable.ic_photo_24dp,
                    MR.strings.set_as_cover,
                ),
            )
        }
        MaterialMenuSheet(this, items) { _, item ->
            when (item) {
                0 -> shareImage(page)
                1 -> saveImage(page)
                2 -> showSetCoverPrompt(page)
                3 -> extraPage?.let { shareImage(it) }
                4 -> extraPage?.let { saveImage(it) }
                5 -> extraPage?.let { showSetCoverPrompt(it) }
                6, 7 -> extraPage?.let { secondPage ->
                    (viewer as? PagerViewer)?.let { viewer ->
                        val isLTR = (viewer !is R2LPagerViewer).xor(viewer.config.invertDoublePages)
                        val bg = ThemeUtil.readerBackgroundColor(viewer.config.readerTheme)
                        if (item == 6) {
                            viewModel.shareImages(page, secondPage, isLTR, bg)
                        } else {
                            viewModel.saveImages(page, secondPage, isLTR, bg)
                        }
                    }
                }
            }
            true
        }.show()
        if (binding.chaptersSheet.root.sheetBehavior.isExpanded()) {
            binding.chaptersSheet.root.sheetBehavior?.collapse()
        }
    }

    /**
     * Called from the viewer when the given [chapter] should be preloaded. It should be called when
     * the viewer is reaching the beginning or end of a chapter or the transition page is active.
     */
    fun requestPreloadChapter(chapter: ReaderChapter) {
        lifecycleScope.launch {
            viewModel.preloadChapter(chapter)
        }
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Called from the page sheet. It delegates the call to the view model to do some IO, which
     * will call [onShareImageResult] with the path the image was saved on when it's ready.
     */
    private fun shareImage(page: ReaderPage) {
        viewModel.shareImage(page)
    }

    private fun showSetCoverPrompt(page: ReaderPage) {
        if (page.status !is Page.State.Ready) return

        materialAlertDialog()
            .setMessage(MR.strings.use_image_as_cover)
            .setPositiveButton(AR.string.ok) { _, _ ->
                setAsCover(page)
            }
            .setNegativeButton(AR.string.cancel, null)
            .show()
    }

    /**
     * Called from the view model when a page is ready to be shared. It shows Android's default
     * sharing tool.
     */
    private fun onShareImageResult(file: UniFile, page: ReaderPage, secondPage: ReaderPage? = null) {
        val manga = viewModel.manga ?: return
        val chapter = page.chapter.chapter

        val decimalFormat =
            DecimalFormat("#.###", DecimalFormatSymbols().apply { decimalSeparator = '.' })

        val pageNumber = if (secondPage != null) {
            getString(MR.strings.pages_, if (resources.isLTR) "${page.number}-${page.number + 1}" else "${page.number + 1}-${page.number}")
        } else {
            getString(MR.strings.page_, page.number)
        }
        val text = "${manga.title}: ${if (chapter.isRecognizedNumber) {
            getString(MR.strings.chapter_, decimalFormat.format(chapter.chapter_number))
        } else {
            chapter.preferredChapterName(this, manga, preferences)
        }
        }, $pageNumber"

        val stream = file.uri.toFile().getUriCompat(this)
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_STREAM, stream)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            clipData = ClipData.newRawUri(null, stream)
            type = "image/*"
        }
        startActivity(Intent.createChooser(intent, getString(MR.strings.share)))
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        val chapterUrl = viewModel.getChapterUrl() ?: return
        outContent.webUri = Uri.parse(chapterUrl)
    }

    /**
     * Called from the page sheet. It delegates saving the image of the given [page] on external
     * storage to the viewModel.
     */
    private fun saveImage(page: ReaderPage) {
        viewModel.saveImage(page)
    }

    /**
     * Called from the view model when a page is saved or fails. It shows a message or logs the
     * event depending on the [result].
     */
    private fun onSaveImageResult(result: ReaderViewModel.SaveImageResult) {
        when (result) {
            is ReaderViewModel.SaveImageResult.Success -> {
                toast(MR.strings.picture_saved)
            }
            is ReaderViewModel.SaveImageResult.Error -> {
                Logger.e(result.error)
            }
        }
    }

    /**
     * Called from the page sheet. It delegates setting the image of the given [page] as the
     * cover to the viewModel.
     */
    private fun setAsCover(page: ReaderPage) {
        viewModel.setAsCover(page)
    }

    /**
     * Called from the view model when a page is set as cover or fails. It shows a different message
     * depending on the [result].
     */
    private fun onSetAsCoverResult(result: ReaderViewModel.SetAsCoverResult) {
        toast(
            when (result) {
                Success -> MR.strings.cover_updated
                AddToLibraryFirst -> MR.strings.must_be_in_library_to_edit
                Error -> MR.strings.failed_to_update_cover
            },
        )
    }

    private fun showTrackingError(errors: List<Pair<TrackService, String?>>) {
        if (errors.isEmpty()) return
        snackbar?.dismiss()
        val errorText = if (errors.size > 1) {
            getString(MR.strings.failed_to_update_, errors.joinToString(", ") { getString(it.first.nameRes()) })
        } else {
            val (service, errorMessage) = errors.first()
            buildSpannedString {
                if (errorMessage != null) {
                    val icon = contextCompatDrawable(service.getLogo())
                        ?.mutate()
                        ?.run {
                            (this as? BitmapDrawable)?.run {
                                val newBitmap = Bitmap.createBitmap(
                                    intrinsicWidth,
                                    intrinsicHeight,
                                    bitmap.config!!,
                                )
                                val canvas = Canvas(newBitmap)
                                val bgColor = ColorUtils.setAlphaComponent(service.getLogoColor(), 255)
                                canvas.drawColor(bgColor)
                                canvas.drawBitmap(bitmap, 0f, 0f, null)
                                BitmapDrawable(resources, newBitmap)
                            } ?: this
                        }?.apply {
                            val size =
                                resources.getDimension(com.google.android.material.R.dimen.design_snackbar_text_size)
                            val dRatio = intrinsicWidth / intrinsicHeight.toFloat()
                            setBounds(0, 0, (size * dRatio).roundToInt(), size.roundToInt())
                        } ?: return
                    val alignment =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DynamicDrawableSpan.ALIGN_CENTER else DynamicDrawableSpan.ALIGN_BASELINE
                    inSpans(ImageSpan(icon, alignment)) { append("image") }
                    append(" - $errorMessage")
                }
            }
        }
        snackbar = binding.readerLayout.snack(errorText, 5000)
    }

    private fun onVisibilityChange(visible: Boolean) {
        if (visible && !menuTemporarilyVisible && !menuVisible && !binding.appBar.isVisible) {
            menuTemporarilyVisible = true
            coroutine = scope.launchUI {
                delay(2000)
                if (window.decorView.rootWindowInsetsCompat?.isVisible(statusBars()) == true) {
                    menuTemporarilyVisible = false
                    setMenuVisibility(false)
                }
            }
            if (sheetManageNavColor) {
                window.navigationBarColor =
                    ColorUtils.setAlphaComponent(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 || isInNightMode()) {
                            getResourceColor(materialR.attr.colorSurface)
                        } else {
                            Color.BLACK
                        },
                        if (binding.root.rootWindowInsetsCompat?.hasSideNavBar() == true) {
                            255
                        } else {
                            179
                        },
                    )
            }
            binding.appBar.isVisible = true
            if (ReducedMotion.isEnabled()) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            } else {
                val toolbarAnimation = AnimationUtils.loadAnimation(this, R.anim.enter_from_top)
                toolbarAnimation.doOnStart {
                    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                }
                binding.appBar.startAnimation(toolbarAnimation)
            }
        } else if (!visible && (menuTemporarilyVisible || menuVisible)) {
            if (menuTemporarilyVisible && !menuVisible) {
                setMenuVisibility(false)
            }
            coroutine?.cancel()
        }
    }

    /**
     * Sets notch cutout mode to "NEVER", if mobile is in a landscape view
     */
    private fun setCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val currentOrientation = resources.configuration.orientation

            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode =
                when (currentOrientation) {
                    Configuration.ORIENTATION_LANDSCAPE -> {
                        if (readerPreferences.landscapeCutoutBehavior().get() == LandscapeCutoutBehaviour.HIDE) {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                        } else {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    }

                    else -> {
                        if (readerPreferences.cutoutShort().get()) {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        } else {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                        }
                    }
                }

            window.attributes = attributes
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val currentOrientation = resources.configuration.orientation
            DeviceUtil.setLegacyCutoutMode(
                window,
                when (currentOrientation) {
                    Configuration.ORIENTATION_LANDSCAPE -> {
                        if (readerPreferences.landscapeCutoutBehavior().get() == LandscapeCutoutBehaviour.HIDE)
                            LegacyCutoutMode.NEVER
                        else
                            LegacyCutoutMode.SHORT_EDGES
                    }
                    else -> {
                        if (readerPreferences.cutoutShort().get())
                            LegacyCutoutMode.SHORT_EDGES
                        else
                            LegacyCutoutMode.NEVER
                    }
                },
            )
        }
    }

    private fun setDoublePageMode(viewer: PagerViewer) {
        val currentOrientation = resources.configuration.orientation
        viewer.config.doublePages = (currentOrientation == Configuration.ORIENTATION_LANDSCAPE)
        if (viewer.config.autoDoublePages) {
            viewer.config.splitPages = preferences.automaticSplitsPage().get() && !viewer.config.doublePages
        }
    }

    private fun handleIntentAction(intent: Intent): Boolean {
        val uri = intent.data ?: return false
        if (!viewModel.canLoadUrl(uri)) {
            openInBrowser(intent.data!!.toString(), true)
            finishAfterTransition()
            return true
        }
        setMenuVisibility(visible = false, animate = true)
        scope.launch(Dispatchers.IO) {
            try {
                intentPageNumber = viewModel.intentPageNumber(uri)
                viewModel.loadChapterURL(uri)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setInitialChapterError(e)
                }
            }
        }
        return true
    }

    private fun openMangaInBrowser() {
        val source = viewModel.source ?: return
        val chapterUrl = viewModel.getChapterUrl() ?: return

        val intent = WebViewActivity.newIntent(
            applicationContext,
            chapterUrl,
            source.id,
            viewModel.manga!!.title,
        )
        startActivity(intent)
    }

    /**
     * Forces the user preferred [orientation] on the activity.
     */
    fun setOrientation(orientation: Int) {
        val newOrientation = OrientationType.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) {
            requestedOrientation = newOrientation.flag
        }
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        var showNewChapter = false

        /**
         * Initializes the reader subscriptions.
         */
        init {
            preferences.readerTheme().changesIn(scope) { theme ->
                    binding.viewerContainer.setBackgroundColor(
                        ThemeUtil.readerBackgroundColor(
                            theme,
                            getResourceColor(android.R.attr.colorBackground),
                        )
                    )
                }

            preferences.defaultOrientationType().changes()
                .drop(1)
                .onEach {
                    delay(250)
                    setOrientation(viewModel.getMangaOrientationType())
                }
                .launchIn(scope)

            // Novel reader has its own default-orientation pref so a user can lock novels to
            // portrait without dragging manga along. Observe it the same way as the manga
            // default; getMangaOrientationType() picks the right one based on the loaded series.
            readerPreferences.novelDefaultOrientationType.changes()
                .drop(1)
                .onEach {
                    delay(250)
                    setOrientation(viewModel.getMangaOrientationType())
                }
                .launchIn(scope)

            preferences.showPageNumber().changesIn(scope) { setPageNumberVisibility(it) }

            readerPreferences.cutoutShort().changesIn(scope) { setCutoutMode() }

            readerPreferences.landscapeCutoutBehavior().changes()
                .drop(1)
                .onEach { setCutoutMode() }
                .launchIn(scope)

            basePreferences.displayProfile().changesIn(scope) { setDisplayProfile(it) }

            preferences.fullscreen().changesIn(scope) { setFullscreen(it) }

            preferences.keepScreenOn().changesIn(scope) { setKeepScreenOn(it) }

            preferences.customBrightness().changesIn(scope) { setCustomBrightness(it) }

            preferences.colorFilter().changesIn(scope) { setColorFilter(it) }

            preferences.colorFilterMode().changesIn(scope) {
                setColorFilter(preferences.colorFilter().get())
            }

            merge(preferences.grayscale().changes(), preferences.invertedColors().changes())
                .onEach { setLayerPaint(preferences.grayscale().get(), preferences.invertedColors().get()) }
                .launchIn(lifecycleScope)

            preferences.alwaysShowChapterTransition().changesIn(scope) {
                showNewChapter = it
            }

            preferences.pageLayout().changesIn(scope) { setBottomNavButtons(it) }

            preferences.automaticSplitsPage().changes()
                .drop(1)
                .onEach {
                    val isPaused = !this@ReaderActivity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    if (isPaused) {
                        (viewer as? PagerViewer)?.config?.let { config ->
                            reloadChapters(config.doublePages, true)
                        }
                    }
                }
                .launchIn(scope)

            preferences.readerBottomButtons().changesIn(scope) { updateBottomShortcuts() }

            // React to live changes from the novel quick-settings sheet so the slider snaps
            // to the new position without re-creating the viewer.
            readerPreferences.novelProgressSliderPosition.changes().onEach {
                applyNovelNavLayoutPosition()
            }.launchIn(scope)
        }

        /**
         * Sets the visibility of the bottom page indicator according to [visible].
         */
        private fun setPageNumberVisibility(visible: Boolean) {
            binding.pageNumber.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }

        /**
         * Sets the display profile to [path].
         */
        private fun setDisplayProfile(path: String) {
            val file = UniFile.fromUri(baseContext, path.toUri())
            if (file != null && file.exists()) {
                val inputStream = file.openInputStream()
                val outputStream = ByteArrayOutputStream()
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                val data = outputStream.toByteArray()
                SubsamplingScaleImageView.setDisplayProfile(data)
                TachiyomiImageDecoder.displayProfile = data
            }
        }

        /**
         * Sets the fullscreen reading mode (immersive) according to [enabled].
         */
        private fun setFullscreen(enabled: Boolean) {
            WindowCompat.setDecorFitsSystemWindows(window, !enabled || isSplitScreen)
            wic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            binding.root.rootWindowInsetsCompat?.let { setNavColor(it) }
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        private fun setCustomBrightness(enabled: Boolean) {
            if (enabled) {
                preferences.customBrightnessValue().changes()
                    .sample(100)
                    .onEach { setCustomBrightnessValue(it) }
                    .launchIn(scope)
            } else {
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the color filter overlay according to [enabled].
         */
        private fun setColorFilter(enabled: Boolean) {
            if (enabled) {
                preferences.colorFilterValue().changes()
                    .sample(100)
                    .onEach { setColorFilterValue(it) }
                    .launchIn(scope)
            } else {
                binding.colorOverlay.isVisible = false
            }
        }

        private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint {
            return Paint().apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        if (grayscale) {
                            setSaturation(0f)
                        }
                        if (invertedColors) {
                            postConcat(
                                ColorMatrix(
                                    floatArrayOf(
                                        -1f, 0f, 0f, 0f, 255f,
                                        0f, -1f, 0f, 0f, 255f,
                                        0f, 0f, -1f, 0f, 255f,
                                        0f, 0f, 0f, 1f, 0f,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            // Calculate and set reader brightness.
            val readerBrightness = when {
                value > 0 -> {
                    value / 100f
                }
                value < 0 -> {
                    0.01f
                }
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }

            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

            // Set black overlay visibility.
            if (value < 0) {
                binding.brightnessOverlay.isVisible = true
                val alpha = (abs(value) * 2.56).toInt()
                binding.brightnessOverlay.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
            } else {
                binding.brightnessOverlay.isVisible = false
            }
        }

        /**
         * Sets the color filter [value].
         */
        private fun setColorFilterValue(value: Int) {
            binding.colorOverlay.isVisible = true
            binding.colorOverlay.setFilterColor(value, preferences.colorFilterMode().get())
        }

        private fun setLayerPaint(grayscale: Boolean, invertedColors: Boolean) {
            val paint = if (grayscale || invertedColors) getCombinedPaint(grayscale, invertedColors) else null
            binding.viewerContainer.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
    }
}
