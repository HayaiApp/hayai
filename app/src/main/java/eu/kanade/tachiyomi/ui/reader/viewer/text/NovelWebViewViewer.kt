package eu.kanade.tachiyomi.ui.reader.viewer.text

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.Keep
import co.touchlab.kermit.Logger
import eu.kanade.presentation.reader.settings.CodeSnippet
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.BaseViewer
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterDifference
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import yokai.util.koin.injectLazy
import yokai.domain.library.LibraryPreferences
import yokai.domain.ui.settings.ReaderPreferences
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * NovelWebViewViewer renders novel content using a WebView for more flexibility.
 * Supports custom CSS and JavaScript injection.
 */
class NovelWebViewViewer(val activity: ReaderActivity) :
    BaseViewer,
    hayai.novel.reader.tts.TtsSource {

    private val container = FrameLayout(activity)
    private lateinit var webView: WebView
    private var loadingIndicator: ReaderProgressIndicator? = null
    // Compose `LoadingIndicator` overlay shown on top of the WebView while a chapter loads.
    private var loadingComposeOverlay: androidx.compose.ui.platform.ComposeView? = null
    private var loadingTimeoutJob: Job? = null
    private var overlayScrollbar: hayai.novel.reader.ui.NovelOverlayScrollbar? = null
    private var webViewScrollOffset: Int = 0
    private var webViewScrollRange: Int = 0
    private var webViewScrollExtent: Int = 0
    private val preferences: ReaderPreferences by injectLazy()
    // Translation feature is out of scope for the initial Hayai port; the donor's
    // TranslationPreferences class has no Hayai counterpart. realTimeTranslation calls
    // are stubbed to false at the call site (see displayContent).
    private val libraryPreferences: LibraryPreferences by injectLazy()
    // Used by buildTransitionCardHtml to swap the cloud icon for a check-circle when a
    // chapter is locally downloaded (mirrors the manga ChapterTransition widget).
    private val downloadManager: eu.kanade.tachiyomi.data.download.DownloadManager by injectLazy()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var currentPage: ReaderPage? = null
    private var currentChapters: ViewerChapters? = null

    // Track scroll progress
    private var lastSavedProgress = 0f

    // Infinite scroll state tracking
    private var isInfiniteScrollPrepend = false
    private var loadedChapterIds = mutableListOf<Long>()
    private var loadedChapters = mutableListOf<ReaderChapter>()
    private var isLoadingNext = false
    // Symmetric to isLoadingNext: gates the JS scroll-up trigger so we never fan out into
    // multiple parallel prepends on the rebound from a fling. Reset in
    // prependPreviousChapterIfAvailable's finally block.
    private var isLoadingPrev = false
    private var isDestroyed = false
    private var isEditingMode = false

    // Tracks the (chapter, page) pair the user can retry when the error overlay is showing.
    // Cleared once retry is initiated or content loads successfully.
    private var lastErrorRetryContext: Pair<ReaderChapter, ReaderPage>? = null

    // Auto-scroll state. Phase B #6: previously a Kotlin-side Choreographer callback drove
    // webView.scrollBy(0, dy) on every vsync, which stuttered because the IPC + WebView
    // layout pipeline both fought the user's gesture loop. The actual scroll now runs as
    // a `requestAnimationFrame` loop *inside* the WebView (see autoScrollRafScript in
    // injectCustomScript) — Kotlin only sets `window.__hayaiSetAutoScroll(on, speed)`
    // globals via evaluateJavascript. These flags stay in Kotlin for UI state queries
    // (button highlight, double-tap toggle, pause-on-touch).
    private var isAutoScrolling = false
    private var isAutoScrollPaused = false

    // Phase B #2: was DisabledNavigation() — its regions list is empty, so navigator.getAction
    // always resolved to MENU and the NEXT/PREV/LEFT/RIGHT branches in onSingleTapConfirmed
    // never fired (tap-to-scroll silently dead even with the pref ON). VerticalNavigation
    // splits the screen into top→PREV / middle→MENU / bottom→NEXT stripes so tap-to-scroll
    // actually reaches the scrollBy branches below.
    private var navigator: eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation = eu.kanade.tachiyomi.ui.reader.viewer.navigation.VerticalNavigation()

    // Per-chapter tagged paragraph list, populated in lockstep with every HTML-load path
    // (loadHtmlContent / appendHtmlContent / prependHtmlContent) via
    // NovelViewerTextUtils.normalizeAndTagContentForHtml. The DOM's `data-paragraph-index`
    // and the indices of this list share one coordinate system — a tap on paragraph N
    // drives chunk N with no \n-split heuristics or cross-chapter drift. This is the
    // single piece of state the TTS controller needs from the viewer; everything else
    // (engine instance, queue, current paragraph, audio focus) lives in the controller.
    private val chapterParagraphsById = LinkedHashMap<Long, List<String>>()

    /**
     * Emits a chapter's DB id each time its paragraph list is added to
     * [chapterParagraphsById]. The TTS controller awaits this flow in
     * [awaitNextChapterReady] / [awaitPreviousChapterReady] so chapter-advance never
     * relies on a hardcoded delay — it resolves exactly when the new chapter's tagged
     * paragraphs are available to speak.
     */
    private val chapterParagraphReadyFlow = kotlinx.coroutines.flow.MutableSharedFlow<Long>(
        replay = 1,
        extraBufferCapacity = 16,
    )

    private data class CustomStylePayload(
        val css: String,
        val hideChapterTitle: Boolean,
        val backgroundColor: Int,
    )

    var pendingSelectedText: String? = null

    private val gestureDetector = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {
            // Increased thresholds for less sensitive swipe detection
            private val SWIPE_THRESHOLD = 150
            private val SWIPE_VELOCITY_THRESHOLD = 200

            // Require horizontal swipe to be significantly more horizontal than vertical
            private val DIRECTION_RATIO = 1.5f

            override fun onDown(e: MotionEvent): Boolean = false

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (isEditingMode) return false
                if (!preferences.novelSwipeNavigation.get()) return false
                if (e1 == null) return false

                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                // Require horizontal swipe to be significantly more horizontal than vertical
                val absDiffX = kotlin.math.abs(diffX)
                val absDiffY = kotlin.math.abs(diffY)

                if (absDiffX > absDiffY * DIRECTION_RATIO) {
                    if (absDiffX > SWIPE_THRESHOLD && kotlin.math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            // Swipe right - go to previous chapter
                            activity.loadPreviousChapter()
                        } else {
                            // Swipe left - go to next chapter
                            activity.loadNextChapter()
                        }
                        return true
                    }
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isEditingMode) return false

                // While TTS is running (or paused) and the user has tap-to-start enabled,
                // let taps fall through to the WebView so the JS click handler installed in
                // `installSentenceClickHandler` can catch the paragraph and call
                // `Android.startTtsAtParagraph(chapId, paraIdx)`. Without this gate, the
                // navigator's MENU region (which covers the centre stripe — exactly where
                // the reading text lives) consumed every tap as `toggleMenu`, so the JS
                // handler never fired and tap-to-jump silently did nothing. The JS handler
                // is itself gated on `__novelTtsClickEnabled`, which only flips true once
                // TTS is actually speaking — so a casual tap when TTS is off still toggles
                // the menu as before.
                if (preferences.novelTtsTapToStart.get() && (isTtsSpeaking() || isTtsPaused())) {
                    return false
                }

                val pos = android.graphics.PointF(
                    e.x / container.width.toFloat(),
                    e.y / container.height.toFloat(),
                )

                val tapToScroll = preferences.novelTapToScroll.get()
                when (navigator.getAction(pos)) {
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.MENU -> {
                        activity.toggleMenu()
                    }
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.NEXT,
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.RIGHT -> {
                        if (tapToScroll) {
                            webView.evaluateJavascript("window.scrollBy(0, ${(container.height * 0.8).toInt()});", null)
                        } else {
                            return false
                        }
                    }
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.PREV,
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.LEFT -> {
                        if (tapToScroll) {
                            webView.evaluateJavascript("window.scrollBy(0, -${(container.height * 0.8).toInt()});", null)
                        } else {
                            return false
                        }
                    }
                }

                return true
            }

            // Phase B #16: double-tap dispatches through the same NovelTapAction enum used
            // for long-press. Default = TOGGLE_MENU, but the user can rebind to any action
            // (chapter nav, scroll, auto-scroll, TTS, define) from the in-reader settings.
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isEditingMode) return false
                val action = hayai.novel.reader.NovelTapAction.fromOrdinal(
                    preferences.novelDoubleTapAction.get(),
                )
                if (action == hayai.novel.reader.NovelTapAction.NONE) return false
                dispatchTapAction(action)
                return true
            }

            // Phase B #16: long-press path. Only intercepts when the user has bound an
            // action AND there is no live text selection — otherwise we forward to the
            // WebView so its native text-selection behaviour continues to work as before.
            override fun onLongPress(e: MotionEvent) {
                if (isEditingMode) return
                val action = hayai.novel.reader.NovelTapAction.fromOrdinal(
                    preferences.novelLongTapAction.get(),
                )
                if (action == hayai.novel.reader.NovelTapAction.NONE) return
                // If text selection is active, let the WebView keep its existing selection
                // toolbar — checked asynchronously via evaluateJavascript. The action only
                // fires when there's no selection.
                webView.evaluateJavascript(
                    "(function(){var s=window.getSelection();return s?s.toString():'';})();",
                ) { result ->
                    val selection = result?.trim('"')?.replace("\\\"", "\"") ?: ""
                    if (selection.isNotEmpty()) return@evaluateJavascript
                    activity.runOnUiThread { dispatchTapAction(action) }
                }
            }
        },
    ).apply {
        // Long-press is now intercepted by the listener above (gated on pref + no selection),
        // so re-enable detection. The listener forwards to WebView text-selection when the
        // pref is NONE / a selection is already active.
        setIsLongpressEnabled(true)
    }

    init {
        initWebView()
        observePreferences()
        // Defer TTS initialization until actually needed to avoid "not bound" errors
        // TTS will be initialized lazily when startTts() is called
    }

    /**
     * Paints the TTS highlight on the paragraph the controller reports as currently
     * speaking. The JS anchors on `data-chapter-id` + `data-paragraph-index` — the same
     * coordinate system the tagger emits at chapter-load time — so a tap on chapter A's
     * paragraph 5 correctly highlights A's paragraph 5 even with chapter B also loaded
     * in the infinite-scroll viewport.
     *
     * Implements [hayai.novel.reader.tts.TtsSource.paintHighlight].
     */
    override fun paintHighlight(chapterId: Long, paragraphIndex: Int) {
        if (!preferences.novelTtsEnableHighlight.get()) return

        val highlightColor = String.format("#%06X", 0xFFFFFF and preferences.novelTtsHighlightColor.get())
        val highlightTextColor = String.format("#%06X", 0xFFFFFF and preferences.novelTtsHighlightTextColor.get())
        val highlightStyle = preferences.novelTtsHighlightStyle.get()
        val keepInView = preferences.novelTtsKeepHighlightInView.get()

        val jsCode = """
            (function() {
                var state = window.__tdTtsState || (window.__tdTtsState = {});
                if (!state.styleEl) {
                    state.styleEl = document.createElement('style');
                    state.styleEl.id = 'td-tts-highlight-style';
                    state.styleEl.textContent =
                        '.td-tts-highlight-bg{background:var(--td-tts-highlight-bg)!important;color:var(--td-tts-highlight-text)!important;border-radius:6px;padding:0 .2em;}' +
                        '.td-tts-highlight-underline{text-decoration:underline 2px var(--td-tts-highlight-bg)!important;text-underline-offset:0.2em;}' +
                        '.td-tts-highlight-outline{outline:2px solid var(--td-tts-highlight-bg)!important;outline-offset:2px;border-radius:8px;padding:0 .2em;}' ;
                    document.head.appendChild(state.styleEl);
                }

                document.documentElement.style.setProperty('--td-tts-highlight-bg', '$highlightColor');
                document.documentElement.style.setProperty('--td-tts-highlight-text', '$highlightTextColor');

                var target = document.querySelector(
                    '[data-chapter-id="$chapterId"] [data-paragraph-index="$paragraphIndex"]'
                );

                if (state.currentEl && state.currentEl !== target) {
                    state.currentEl.classList.remove('td-tts-highlight-bg', 'td-tts-highlight-underline', 'td-tts-highlight-outline');
                }

                if (!target) {
                    state.currentEl = null;
                    return;
                }

                if ('$highlightStyle' === 'underline') {
                    target.classList.add('td-tts-highlight-underline');
                } else if ('$highlightStyle' === 'outline') {
                    target.classList.add('td-tts-highlight-outline');
                } else {
                    target.classList.add('td-tts-highlight-bg');
                }

                state.currentEl = target;
                if ($keepInView) {
                    target.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
                }
            })();
        """.trimIndent()

        evaluateJavascriptSafe(jsCode)
    }

    /** Implements [hayai.novel.reader.tts.TtsSource.clearHighlight]. */
    override fun clearHighlight() {
        evaluateJavascriptSafe(
            """
            (function() {
                var state = window.__tdTtsState;
                if (state && state.currentEl) {
                    state.currentEl.classList.remove('td-tts-highlight-bg', 'td-tts-highlight-underline', 'td-tts-highlight-outline');
                    state.currentEl = null;
                }
            })();
            """.trimIndent(),
        )
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun initWebView() {
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // Remove blocksDescendants from reader_activity.xml's viewer_container parent
                // so the WebView can actually receive text input focus.
                (container.parent as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })

        webView = object : WebView(activity) {
            override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
                if (!preferences.novelTextSelectable.get() || callback == null) {
                    return super.startActionMode(callback, type)
                }
                // Preserve Callback2 so the floating toolbar anchors correctly to the selection
                // The label is the user-visible string. Keep it descriptive ("Add quote")
                // and add to the front of the menu (`order = 0`) with SHOW_AS_ACTION_ALWAYS
                // so it's not hidden behind the overflow menu on the floating action mode
                // toolbar that often only has room for 2-3 visible items.
                val quoteLabel = activity.getString(MR.strings.novel_quote_add)
                // Phase B #15: the floating-toolbar Google Define shortcut wasn't reliably
                // present (depends on the WebView/Google Search build), so add an explicit
                // entry that fires ACTION_PROCESS_TEXT with the current selection.
                val defineLabel = activity.getString(MR.strings.novel_define)
                val translateLabel = activity.getString(MR.strings.novel_translate)
                val searchWebLabel = activity.getString(MR.strings.novel_web_search)
                val wrapped = if (callback is ActionMode.Callback2) {
                    object : ActionMode.Callback2() {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            val result = callback.onCreateActionMode(mode, menu)
                            menu.add(
                                Menu.NONE,
                                REMEMBER_MENU_ITEM_ID,
                                0,
                                quoteLabel,
                            )
                                .setIcon(android.R.drawable.ic_menu_save)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            menu.add(
                                Menu.NONE,
                                DEFINE_MENU_ITEM_ID,
                                1,
                                defineLabel,
                            )
                                .setIcon(android.R.drawable.ic_menu_search)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            menu.add(
                                Menu.NONE,
                                TRANSLATE_MENU_ITEM_ID,
                                2,
                                translateLabel,
                            )
                                .setIcon(android.R.drawable.ic_menu_send)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            menu.add(
                                Menu.NONE,
                                WEB_SEARCH_MENU_ITEM_ID,
                                3,
                                searchWebLabel,
                            )
                                .setIcon(android.R.drawable.ic_menu_search)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            return result
                        }
                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                            // The host action mode rebuilds the menu on prepare; re-add our item if it got dropped.
                            if (menu.findItem(REMEMBER_MENU_ITEM_ID) == null) {
                                menu.add(Menu.NONE, REMEMBER_MENU_ITEM_ID, 0, quoteLabel)
                                    .setIcon(android.R.drawable.ic_menu_save)
                                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            }
                            if (menu.findItem(DEFINE_MENU_ITEM_ID) == null) {
                                menu.add(Menu.NONE, DEFINE_MENU_ITEM_ID, 1, defineLabel)
                                    .setIcon(android.R.drawable.ic_menu_search)
                                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            }
                            if (menu.findItem(TRANSLATE_MENU_ITEM_ID) == null) {
                                menu.add(Menu.NONE, TRANSLATE_MENU_ITEM_ID, 2, translateLabel)
                                    .setIcon(android.R.drawable.ic_menu_send)
                                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            }
                            if (menu.findItem(WEB_SEARCH_MENU_ITEM_ID) == null) {
                                menu.add(Menu.NONE, WEB_SEARCH_MENU_ITEM_ID, 3, searchWebLabel)
                                    .setIcon(android.R.drawable.ic_menu_search)
                                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            }
                            return callback.onPrepareActionMode(mode, menu)
                        }
                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                            if (item.itemId == REMEMBER_MENU_ITEM_ID) {
                                onRememberSelectedText(mode) // pass mode in
                                return true
                            }
                            if (item.itemId == DEFINE_MENU_ITEM_ID) {
                                triggerDefineFromSelection(mode)
                                return true
                            }
                            if (item.itemId == TRANSLATE_MENU_ITEM_ID) {
                                triggerTranslateFromSelection(mode)
                                return true
                            }
                            if (item.itemId == WEB_SEARCH_MENU_ITEM_ID) {
                                triggerWebSearchFromSelection(mode)
                                return true
                            }
                            return callback.onActionItemClicked(mode, item)
                        }
                        override fun onDestroyActionMode(mode: ActionMode) =
                            callback.onDestroyActionMode(mode)

                        // Forward the content rect so the toolbar floats near the selection
                        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) =
                            callback.onGetContentRect(mode, view, outRect)
                    }
                } else {
                    object : ActionMode.Callback {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            val result = callback.onCreateActionMode(mode, menu)
                            menu.add(
                                Menu.NONE,
                                REMEMBER_MENU_ITEM_ID,
                                0,
                                quoteLabel,
                            )
                                .setIcon(android.R.drawable.ic_menu_save)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            menu.add(
                                Menu.NONE,
                                DEFINE_MENU_ITEM_ID,
                                1,
                                defineLabel,
                            )
                                .setIcon(android.R.drawable.ic_menu_search)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            menu.add(
                                Menu.NONE,
                                TRANSLATE_MENU_ITEM_ID,
                                2,
                                translateLabel,
                            )
                                .setIcon(android.R.drawable.ic_menu_send)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            menu.add(
                                Menu.NONE,
                                WEB_SEARCH_MENU_ITEM_ID,
                                3,
                                searchWebLabel,
                            )
                                .setIcon(android.R.drawable.ic_menu_search)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            return result
                        }
                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                            if (menu.findItem(REMEMBER_MENU_ITEM_ID) == null) {
                                menu.add(Menu.NONE, REMEMBER_MENU_ITEM_ID, 0, quoteLabel)
                                    .setIcon(android.R.drawable.ic_menu_save)
                                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            }
                            if (menu.findItem(DEFINE_MENU_ITEM_ID) == null) {
                                menu.add(Menu.NONE, DEFINE_MENU_ITEM_ID, 1, defineLabel)
                                    .setIcon(android.R.drawable.ic_menu_search)
                                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            }
                            if (menu.findItem(TRANSLATE_MENU_ITEM_ID) == null) {
                                menu.add(Menu.NONE, TRANSLATE_MENU_ITEM_ID, 2, translateLabel)
                                    .setIcon(android.R.drawable.ic_menu_send)
                                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            }
                            if (menu.findItem(WEB_SEARCH_MENU_ITEM_ID) == null) {
                                menu.add(Menu.NONE, WEB_SEARCH_MENU_ITEM_ID, 3, searchWebLabel)
                                    .setIcon(android.R.drawable.ic_menu_search)
                                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                            }
                            return callback.onPrepareActionMode(mode, menu)
                        }
                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                            if (item.itemId == REMEMBER_MENU_ITEM_ID) {
                                onRememberSelectedText(mode)
                                return true
                            }
                            if (item.itemId == DEFINE_MENU_ITEM_ID) {
                                triggerDefineFromSelection(mode)
                                return true
                            }
                            if (item.itemId == TRANSLATE_MENU_ITEM_ID) {
                                triggerTranslateFromSelection(mode)
                                return true
                            }
                            if (item.itemId == WEB_SEARCH_MENU_ITEM_ID) {
                                triggerWebSearchFromSelection(mode)
                                return true
                            }
                            return callback.onActionItemClicked(mode, item)
                        }
                        override fun onDestroyActionMode(mode: ActionMode) =
                            callback.onDestroyActionMode(mode)
                    }
                }
                return super.startActionMode(wrapped, type)
            }

            // computeVerticalScroll{Range,Extent,Offset} are protected on WebView, so push
            // them into outer-class fields on every scroll instead of trying to expose them.
            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt)
                webViewScrollOffset = computeVerticalScrollOffset()
                webViewScrollRange = computeVerticalScrollRange()
                webViewScrollExtent = computeVerticalScrollExtent()
                updateOverlayScrollbarFromMetrics()
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)
            }
            applyWebViewScrollbarSettings(this)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                // Block images/videos if preference is set
                val shouldBlock = preferences.novelBlockMedia.get()
                blockNetworkImage = shouldBlock
                loadsImagesAutomatically = !shouldBlock
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): android.webkit.WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (url.startsWith("hayai-novel-image://")) {
                        val imagePath = android.net.Uri.decode(url.removePrefix("hayai-novel-image://"))
                        val loader = activity.viewModel.state.value.viewerChapters?.currChapter?.pageLoader
                        if (loader != null) {
                            val stream = kotlinx.coroutines.runBlocking { loader.getPageDataStream(imagePath) }
                            if (stream != null) {
                                val mimeType = when (imagePath.substringAfterLast('.', "").lowercase()) {
                                    "png" -> "image/png"
                                    "jpg", "jpeg" -> "image/jpeg"
                                    "gif" -> "image/gif"
                                    "svg" -> "image/svg+xml"
                                    "webp" -> "image/webp"
                                    "avif" -> "image/avif"
                                    else -> "image/jpeg"
                                }
                                return android.webkit.WebResourceResponse(mimeType, "UTF-8", stream)
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    hideLoadingIndicator()
                    injectCustomScript()
                    injectScrollTracking()
                    restoreScrollPosition()
                    if (isEditingMode) {
                        toggleEditMode(true)
                    }
                }
            }

            // Add JavaScript interface for progress saving
            addJavascriptInterface(this@NovelWebViewViewer.WebViewInterface(), "Android")

            // Enable text selection via long press
            isLongClickable = true

            setOnTouchListener { _, event ->
                // Pause autoscroll while a finger is down so the user's drag/select isn't
                // fighting the scroll loop; resume on release/cancel.
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> pauseAutoScroll()
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> resumeAutoScroll()
                }
                gestureDetector.onTouchEvent(event)
                false
            }
        }

        // Initial setup for background to avoid white flashes
        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val (themeBgColor, _) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        webView.setBackgroundColor(finalBgColor)
        container.setBackgroundColor(finalBgColor)

        container.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        // Mount the custom scrollbar overlay on top of the WebView. Driven by
        // applyOverlayScrollbarConfig + the WebView's scroll change listener below.
        val bar = hayai.novel.reader.ui.NovelOverlayScrollbar(activity)
        overlayScrollbar = bar
        container.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        applyOverlayScrollbarConfig()
    }

    private fun updateOverlayScrollbarFromMetrics() {
        val bar = overlayScrollbar ?: return
        val range = webViewScrollRange.toFloat()
        val extent = webViewScrollExtent.toFloat()
        val offset = webViewScrollOffset.toFloat()
        if (range <= 0f) {
            bar.setVisibleFraction(1f)
            return
        }
        val fraction = (extent / range).coerceIn(0f, 1f)
        bar.setVisibleFraction(fraction)
        val maxOffset = (range - extent).coerceAtLeast(1f)
        bar.setProgress((offset / maxOffset).coerceIn(0f, 1f))
    }

    private fun observePreferences() {

        // Every novel pref the settings sheet exposes routes through this merged observer
        // (ported from Tsundoku's NovelWebViewPreferenceObserver) so changes apply live
        // instead of needing a chapter reopen. The callbacks below are wired to Hayai's
        // existing apply paths.
        NovelWebViewPreferenceObserver(
            preferences = preferences,
            scope = scope,
            onStyleChanged = { injectCustomStyles() },
            onScriptChanged = { injectCustomScript() },
            onChapterReloadRequested = { reloadCurrentChaptersFromScratch() },
            onBlockMediaChanged = { blockMedia ->
                webView.settings.apply {
                    blockNetworkImage = blockMedia
                    loadsImagesAutomatically = !blockMedia
                }
                // Reload from scratch so the content re-renders with media stripped/restored.
                reloadCurrentChaptersFromScratch()
            },
            onTtsSettingsChanged = {
                activity.ttsController?.dispatch(hayai.novel.reader.tts.TtsCommand.ReapplySettings)
            },
            onKeepScreenOnChanged = { keepOn ->
                activity.runOnUiThread {
                    if (keepOn) {
                        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            },
        ).observe()

        // Re-apply the overlay scrollbar config when any of its prefs change.
        scope.launch {
            merge(
                preferences.novelVerticalScrollbar.changes().drop(1),
                preferences.novelVerticalScrollbarPosition.changes().drop(1),
                preferences.novelVerticalProgressSliderSize.changes().drop(1),
            )
                .collect {
                    activity.runOnUiThread { applyOverlayScrollbarConfig() }
                }
        }

        // Re-evaluate sentence-tap-to-TTS when the master preference flips. We call the
        // gated refresh (not setSentenceTapToTtsEnabled directly) so the pref-on case
        // still respects the "TTS must currently be active" rule.
        scope.launch {
            preferences.novelTtsTapToStart.changes().drop(1).collect {
                activity.runOnUiThread { refreshSentenceTapToTtsState() }
            }
        }

        // Phase A #14: novelChapterTitleDisplay only affects the toolbar subtitle (per
        // Tsundoku — it does NOT synthesize a title into chapter content). The activity
        // owns the subtitle, so route the change through there.
        scope.launch {
            preferences.novelChapterTitleDisplay.changes()
                .drop(1)
                .collect {
                    activity.runOnUiThread { activity.refreshNovelToolbarSubtitle() }
                }
        }

        // Engine name change: stop any active playback so the controller's next start
        // picks up the new engine (NovelTtsEngine.ensureInitialized tears down the old
        // binding when the engine identity changes).
        scope.launch {
            preferences.novelTtsEngine.changes().drop(1).collect {
                activity.ttsController?.dispatch(hayai.novel.reader.tts.TtsCommand.Stop)
            }
        }

        // Phase B #6: when the user drags the auto-scroll speed slider mid-scroll, push
        // the new value into the JS loop so they get live feedback. The setter no-ops if
        // auto-scroll isn't running.
        scope.launch {
            preferences.novelAutoScrollSpeed.changes()
                .drop(1)
                .collect { newSpeed ->
                    if (isAutoScrolling && !isAutoScrollPaused) {
                        val clamped = newSpeed.coerceIn(1, 50)
                        evaluateJavascriptSafe(
                            "if(window.__hayaiSetAutoScroll){window.__hayaiSetAutoScroll(true, $clamped);}",
                        )
                    }
                }
        }
    }

    /**
     * Re-renders the current chapter(s) from scratch for prefs that change parsed CONTENT
     * (regex, lowercase, normalize, EPUB toggles, auto-split, raw HTML, hide-title strip,
     * media blocking). setChapters() early-returns when the chapter is already in
     * loadedChapterIds, so the cache MUST be cleared first or the stale DOM survives — this
     * restores the invariant the lone hideChapterTitle path previously special-cased.
     */
    private fun reloadCurrentChaptersFromScratch() {
        val chapters = currentChapters ?: return
        loadedChapterIds.clear()
        loadedChapters.clear()
        setChapters(chapters)
    }

    private fun applyWebViewScrollbarSettings(target: WebView = webView) {
        // Native scrollbar is permanently off — the overlay scrollbar handles all
        // visibility / position / size preferences (the WebView APIs don't).
        target.isVerticalScrollBarEnabled = false
        target.isHorizontalScrollBarEnabled = false
        target.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        target.layoutDirection = View.LAYOUT_DIRECTION_LTR
        applyOverlayScrollbarConfig()
    }

    private fun applyOverlayScrollbarConfig() {
        val bar = overlayScrollbar ?: return
        val visible = preferences.novelVerticalScrollbar.get()
        bar.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val side = if (preferences.novelVerticalScrollbarPosition.get() == "left") {
            hayai.novel.reader.ui.NovelOverlayScrollbar.Side.LEFT
        } else {
            hayai.novel.reader.ui.NovelOverlayScrollbar.Side.RIGHT
        }
        val track = if (preferences.novelVerticalProgressSliderSize.get() == "half") {
            hayai.novel.reader.ui.NovelOverlayScrollbar.Track.HALF
        } else {
            hayai.novel.reader.ui.NovelOverlayScrollbar.Track.FULL
        }
        // Tint the thumb to contrast with the chosen page color.
        val theme = preferences.novelTheme.get()
        val (_, themeText) = getThemeColors(theme)
        val customText = preferences.novelFontColor.get().takeIf { it != 0 } ?: themeText
        bar.configure(side = side, track = track, thumbColor = (customText and 0x00FFFFFF) or 0x66000000)
        // Briefly flash the bar in so the user gets visual feedback that the toggle
        // applied. Without this, side/size/visible changes are invisible until the next
        // scroll event because the bar's alpha sits at 0 in idle state. Refresh the
        // metrics first so the pulse reflects the current scroll position rather than
        // the stale visibleFraction=1 the bar starts with before any scroll fires.
        updateOverlayScrollbarFromMetrics()
        bar.pulse()
    }

    private fun buildCustomStylePayload(): CustomStylePayload {
        val fontSize = preferences.novelFontSize.get()
        val fontFamily = preferences.novelFontFamily.get()
        val lineHeight = preferences.novelLineHeight.get()
        // Crop-borders toggle (driven by the chapters-sheet button) zeroes out margins
        // without touching the underlying user-set values.
        val cropped = preferences.novelMarginsCropped.get()
        val marginLeft = if (cropped) 0 else preferences.novelMarginLeft.get()
        val marginRight = if (cropped) 0 else preferences.novelMarginRight.get()
        val marginTop = if (cropped) 0 else preferences.novelMarginTop.get()
        val marginBottom = if (cropped) 0 else preferences.novelMarginBottom.get()
        val fontColor = preferences.novelFontColor.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val paragraphIndent = preferences.novelParagraphIndent.get()
        val paragraphSpacing = preferences.novelParagraphSpacing.get()
        val textAlign = preferences.novelTextAlign.get()
        val theme = preferences.novelTheme.get()
        val hideChapterTitle = preferences.novelHideChapterTitle.get()

        val (themeBgColor, themeTextColor) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        val finalTextColor = if (theme == "custom" && fontColor != 0) fontColor else themeTextColor

        val bgColorHex = String.format("#%06X", 0xFFFFFF and finalBgColor)
        val textColorHex = String.format("#%06X", 0xFFFFFF and finalTextColor)

        val customCss = preferences.novelCustomCss.get()
        val useOriginalFonts = preferences.novelUseOriginalFonts.get()

        // Collect enabled CSS snippets
        val cssSnippetsJson = preferences.novelCustomCssSnippets.get()
        val enabledSnippetsCss = try {
            val snippets = Json.decodeFromString<List<CodeSnippet>>(cssSnippetsJson)
            snippets.filter { it.enabled }.joinToString("\n") { it.code }
        } catch (e: Exception) {
            Logger.e { "Failed to parse CSS snippets: ${e.message}" }
            ""
        }

        // Generate font-face declaration for custom fonts
        // For custom fonts (URIs), copy to cache and use file:// URL
        val (fontFaceDeclaration, effectiveFontFamily) = if (!useOriginalFonts &&
            (fontFamily.startsWith("file://") || fontFamily.startsWith("content://"))
        ) {
            try {
                // Copy font to cache directory for WebView access
                val fontUri = android.net.Uri.parse(fontFamily)
                val inputStream = activity.contentResolver.openInputStream(fontUri)
                val fontFile = java.io.File(activity.cacheDir, "custom_font.ttf")
                inputStream?.use { input ->
                    fontFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val fontUrl = "file://" + fontFile.absolutePath
                val declaration = """
                @font-face {
                    font-family: 'CustomFont';
                    src: url('$fontUrl');
                }
                """.trimIndent()
                declaration to "'CustomFont', sans-serif"
            } catch (e: Exception) {
                Logger.e { "Failed to load custom font: ${e.message}" }
                "" to fontFamily
            }
        } else {
            "" to fontFamily
        }

        // Only include font-family if not using original fonts
        val fontFamilyCss = if (useOriginalFonts) {
            ""
        } else {
            "font-family: $effectiveFontFamily;"
        }

        val css = """
            $fontFaceDeclaration
            body {
                display: block !important;
                font-size: ${fontSize}px;
                $fontFamilyCss
                line-height: $lineHeight;
                margin: ${marginTop}px ${marginRight}px ${marginBottom}px ${marginLeft}px;
                color: $textColorHex !important;
                background-color: $bgColorHex !important;
                text-align: $textAlign;
                -webkit-user-select: ${if (preferences.novelTextSelectable.get()) "text" else "none"};
                user-select: ${if (preferences.novelTextSelectable.get()) "text" else "none"};
            }
            p {
                text-indent: ${paragraphIndent}em;
                margin-top: ${paragraphSpacing}em;
                margin-bottom: ${paragraphSpacing}em;
            }
            * {
                color: inherit !important;
            }
            $customCss
            $enabledSnippetsCss
        """.trimIndent().replace("\n", " ")

        return CustomStylePayload(
            css = css,
            hideChapterTitle = hideChapterTitle,
            backgroundColor = finalBgColor,
        )
    }

    private fun injectCustomStyles() {
        val payload = buildCustomStylePayload()
        webView.setBackgroundColor(payload.backgroundColor)
        container.setBackgroundColor(payload.backgroundColor)

        val js = """
            (function() {
                var style = document.getElementById('tsundoku-custom-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'tsundoku-custom-style';
                    document.head.appendChild(style);
                }
                style.textContent = `${payload.css}`;

                // Hide chapter title if enabled
                if (${payload.hideChapterTitle}) {
                    var headings = document.querySelectorAll('h1, h2, h3, h4, h5, h6');
                    if (headings.length > 0) {
                        headings[0].style.display = 'none';
                    }
                }
            })();
        """

        evaluateJavascriptSafe(js, null)
    }

    private fun injectCustomScript() {
        val chapter = currentChapters?.currChapter?.chapter
        val chapterTitle = (chapter?.name ?: "").jsEscape()
        val chapterNumber = chapter?.chapter_number ?: -1f
        val chapterUrl = (chapter?.url ?: "").jsEscape()
        val novelUrl = (activity.viewModel.manga?.url ?: "").jsEscape()

        val script = """
        window.Tsundoku = {
            chapterTitle: "$chapterTitle",
            chapterNumber: $chapterNumber,
            chapterUrl: "$chapterUrl",
            novelUrl: "$novelUrl",
            isEditMode: $isEditingMode,
            isInfScroll: true,
            textSelectionBlocked: ${!preferences.novelTextSelectable.get()},
            forcedLowercase: ${preferences.novelForceTextLowercase.get()}
        };
        """.trimIndent()
        evaluateJavascriptSafe(script, null)

        val customJs = preferences.novelCustomJs.get()
        if (customJs.isNotBlank()) {
            evaluateJavascriptSafe(customJs, null)
        }

        val jsSnippetsJson = preferences.novelCustomJsSnippets.get()
        val enabledSnippetsJs = try {
            val snippets = Json.decodeFromString<List<CodeSnippet>>(jsSnippetsJson)
            snippets.filter { it.enabled }.joinToString("\n") { it.code }
        } catch (e: Exception) {
            Logger.e { "Failed to parse JS snippets: ${e.message}" }
            ""
        }
        if (enabledSnippetsJs.isNotBlank()) {
            evaluateJavascriptSafe(enabledSnippetsJs, null)
        }

        installSentenceClickHandler()
        // Phase B #6: ensure the in-WebView rAF auto-scroll loop is installed before any
        // future startAutoScroll() flips its globals. Idempotent — re-injection on
        // chapter change just leaves the existing state intact.
        installAutoScrollScript()
        // If auto-scroll was running before the page reloaded (chapter change), the new
        // document's freshly-installed loop starts in the off state — re-arm it with the
        // current speed so the user's session continues seamlessly.
        if (isAutoScrolling && !isAutoScrollPaused) {
            val speed = preferences.novelAutoScrollSpeed.get().coerceIn(1, 50)
            evaluateJavascriptSafe(
                "if(window.__hayaiSetAutoScroll){window.__hayaiSetAutoScroll(true, $speed);}",
            )
        }
    }

    /**
     * Installs a delegated click handler that, on tap, finds the clicked paragraph
     * (the same selector set used by [startTtsFromViewport]) and reports its index back
     * via the JS bridge so TTS can start from there. Idempotent — re-running just refreshes
     * the listener after DOM changes.
     */
    private fun installSentenceClickHandler() {
        evaluateJavascriptSafe(
            """
            (function() {
                if (window.__novelTtsClickInstalled) return;
                window.__novelTtsClickInstalled = true;
                // Initialise OFF: sentence-tap only navigates TTS when TTS is currently
                // active. The Kotlin side flips this true via setSentenceTapToTtsEnabled
                // once both the master pref is on AND TTS is speaking/paused.
                window.__novelTtsClickEnabled = false;
                document.addEventListener('click', function(e) {
                    if (!window.__novelTtsClickEnabled) return;
                    // Skip when there's an active text selection — the user is selecting, not starting TTS.
                    var sel = window.getSelection();
                    if (sel && sel.toString().length > 0) return;
                    // Skip taps in the side / top navigation zones so they keep working as
                    // menu / scroll / chapter-nav (Kotlin-side navigator handles those).
                    // The middle ~70% horizontally is the reading zone where sentence-tap fires.
                    var w = window.innerWidth || document.documentElement.clientWidth;
                    var h = window.innerHeight || document.documentElement.clientHeight;
                    if (e.clientX / w < 0.15 || e.clientX / w > 0.85) return;
                    if (e.clientY / h < 0.20 || e.clientY / h > 0.80) return;

                    // Phase C #17: walk up to the nearest [data-paragraph-index], then to the
                    // nearest [data-chapter-id] ancestor (chapter-content wrapper emitted by
                    // load/append/prepend). Both pieces are required — the click handler can
                    // only target a paragraph that was actually tagged by the Kotlin tagger,
                    // and the chapter scoping prevents Phase C #18 cross-chapter collisions.
                    var paraEl = e.target;
                    while (paraEl && paraEl !== document.body && !(paraEl.hasAttribute && paraEl.hasAttribute('data-paragraph-index'))) {
                        paraEl = paraEl.parentElement;
                    }
                    if (!paraEl || paraEl === document.body) return;
                    var paraAttr = paraEl.getAttribute('data-paragraph-index');
                    var paraIdx = paraAttr ? parseInt(paraAttr, 10) : -1;
                    if (isNaN(paraIdx) || paraIdx < 0) return;

                    var chapEl = paraEl.parentElement;
                    while (chapEl && chapEl !== document.body && !(chapEl.hasAttribute && chapEl.hasAttribute('data-chapter-id'))) {
                        chapEl = chapEl.parentElement;
                    }
                    if (!chapEl || chapEl === document.body) return;
                    var chapId = chapEl.getAttribute('data-chapter-id');
                    if (!chapId) return;

                    if (window.Android && typeof Android.startTtsAtParagraph === 'function') {
                        // chapId is passed as a String because JS numbers can't represent the
                        // full Long range Hayai's DB uses for chapter ids — Kotlin parses
                        // toLongOrNull and rejects malformed payloads.
                        Android.startTtsAtParagraph(chapId, paraIdx);
                    }
                }, true);
            })();
            """.trimIndent(),
            null,
        )
    }

    /**
     * Toggles whether a single tap on a paragraph starts TTS from there. Off by default
     * so a casual tap doesn't accidentally start the engine; the public hook is
     * [refreshSentenceTapToTtsState] which gates the JS flag on `(pref ON) && (TTS active)`.
     */
    fun setSentenceTapToTtsEnabled(enabled: Boolean) {
        val flag = if (enabled) "true" else "false"
        evaluateJavascriptSafe("window.__novelTtsClickEnabled = $flag;", null)
    }

    /**
     * Recompute whether a sentence-tap should drive TTS based on current state. Sentence-tap
     * activates ONLY while TTS is already speaking or paused — otherwise stray taps on text
     * would unexpectedly start the engine. The user pref [novelTtsTapToStart] is a master
     * enable: when off, sentence-tap is always disabled regardless of TTS state. Call this
     * after any TTS state transition (start, pause, resume, stop, utterance done).
     */
    fun refreshSentenceTapToTtsState() {
        val prefOn = preferences.novelTtsTapToStart.get()
        // The controller's state is the single source of truth — `Preparing` already
        // covers the window between user-Play and the first utterance firing, so we no
        // longer need a separate "chunks queued but not speaking yet" probe.
        val ttsActive = isTtsSpeaking() || isTtsPaused() || isTtsStarting()
        setSentenceTapToTtsEnabled(prefOn && ttsActive)
    }

    private fun injectScrollTracking() {
        // Continuous chapter loading is always on; the JS treats every chapter the same.
        val infiniteScrollActuallyEnabled = true
        val effectiveThreshold = AUTO_LOAD_NEXT_THRESHOLD
        val scrollTrackingScript = """
            (function() {
                if (window.__tsundokuInfiniteScrollInstalled) {
                    return;
                }
                window.__tsundokuInfiniteScrollInstalled = true;

                var lastProgress = 0;
                var saveTimeout = null;
                window.__tsundokuLoadingNext = window.__tsundokuLoadingNext || false;
                window.__tsundokuSetLoadingNext = function(v) { window.__tsundokuLoadingNext = !!v; };
                // Prepend (scroll-up to load previous chapter) — symmetric to the next-chapter
                // path. Tracked separately so an in-flight prev load doesn't gate next-load,
                // and vice versa.
                window.__tsundokuLoadingPrev = window.__tsundokuLoadingPrev || false;
                window.__tsundokuSetLoadingPrev = function(v) { window.__tsundokuLoadingPrev = !!v; };
                var infiniteScrollEnabled = $infiniteScrollActuallyEnabled;
                var loadThreshold = $effectiveThreshold;
                // Trigger prepend when within this many CSS pixels of the document top while
                // scrolling up. 200px is roughly one viewport-eighth on a phone — enough lead
                // time for the chapter to fetch + render before the user hits a hard stop at
                // scrollTop=0, but not so big that it fires on a glance-up.
                var prevLoadOffset = 200;
                var lastScrollTop = -1;
                // Allow the post-load scroll-past-top-card script to seed lastScrollTop with
                // the resting position so the very first scroll gesture is correctly
                // classified as upward instead of "first event, ignore".
                window.__novelPrimeLastScrollTop = function(v) { lastScrollTop = v | 0; };

                // Track chapter boundaries for multi-chapter infinite scroll
                window.chapterBoundaries = window.chapterBoundaries || [];
                window.__tsundokuLastBoundaryUpdate = window.__tsundokuLastBoundaryUpdate || 0;

                window.addEventListener('scroll', function() {
                    // Keep chapter boundaries in sync with actual DOM markers.
                    // This is important after appends/prepends.
                    if (infiniteScrollEnabled && typeof window.updateChapterBoundaries === 'function') {
                        var dividers = document.querySelectorAll('.chapter-divider');
                        if (!window.chapterBoundaries || window.chapterBoundaries.length !== dividers.length) {
                            window.updateChapterBoundaries();
                        } else if (Date.now() - window.__tsundokuLastBoundaryUpdate > 1000) {
                            window.__tsundokuLastBoundaryUpdate = Date.now();
                            window.updateChapterBoundaries();
                        }
                    }

                    var scrollTop = document.documentElement.scrollTop || document.body.scrollTop;
                    var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                    var progress = scrollHeight > 0 ? scrollTop / scrollHeight : 1;
                    // Round up if very close to 100% (within 2%) to allow reaching 100%
                    if (progress >= 0.98) progress = 1.0;

                    // Per-chapter progress: when boundaries span multiple chapters, narrow
                    // `progress` to the active chapter's slice; otherwise it's the
                    // single-chapter document progress.
                    var currentChapterProgress = progress;
                    var currentChapterIdx = 0;
                    if (infiniteScrollEnabled && window.chapterBoundaries.length > 1) {
                        for (var i = 0; i < window.chapterBoundaries.length; i++) {
                            var boundary = window.chapterBoundaries[i];
                            var chapterEnd = boundary.startOffset + boundary.height;
                            if (scrollTop >= boundary.startOffset && scrollTop < chapterEnd) {
                                currentChapterIdx = i;
                                var chapterScrollY = scrollTop - boundary.startOffset;
                                var effectiveHeight = Math.max(boundary.height - window.innerHeight, 1);
                                currentChapterProgress = Math.min(chapterScrollY / effectiveHeight, 1.0);
                                if (currentChapterProgress >= 0.98) currentChapterProgress = 1.0;
                                break;
                            }
                        }
                    }
                    // Always emit the active-chapter id (not index!) so Kotlin can map directly
                    // to a ReaderChapter in `loadedChapters` regardless of any prior index
                    // drift. Reporting unconditionally — including single-chapter sessions —
                    // closes a class of "callback never fires, history sticks to the entry
                    // chapter" bugs (see ReaderViewModel.setNovelVisibleChapter invariant).
                    if (window.chapterBoundaries.length > 0) {
                        var activeBoundary = window.chapterBoundaries[currentChapterIdx];
                        var activeChapterId = activeBoundary && activeBoundary.chapterId;
                        if (activeChapterId) {
                            Android.onActiveChapterUpdate(String(activeChapterId));
                        }
                    }

                    if (Math.abs(currentChapterProgress - lastProgress) > 0.01) {
                        lastProgress = currentChapterProgress;

                        // Immediate update for UI (throttled)
                        if (!window.lastScrollUpdate || Date.now() - window.lastScrollUpdate > 50) {
                            window.lastScrollUpdate = Date.now();
                            Android.onScrollUpdate(currentChapterProgress);
                        }

                        clearTimeout(saveTimeout);
                        saveTimeout = setTimeout(function() {
                            Android.onScrollProgress(currentChapterProgress);
                        }, 500);
                    }

                    // Infinite scroll: load next chapter when reaching threshold
                    var shouldLoadNext = false;
                    if (infiniteScrollEnabled) {
                        if (window.chapterBoundaries.length > 1) {
                            shouldLoadNext = (currentChapterIdx === (window.chapterBoundaries.length - 1)) && (currentChapterProgress >= loadThreshold);
                        } else {
                            shouldLoadNext = progress >= loadThreshold;
                        }
                    }
                    if (shouldLoadNext && !window.__tsundokuLoadingNext) {
                        console.log('Infinite scroll: Loading next chapter at progress ' + currentChapterProgress + ' (threshold: ' + loadThreshold + ')');
                        window.__tsundokuLoadingNext = true;
                        try {
                            Android.loadNextChapter();
                            console.log('Infinite scroll: Successfully called loadNextChapter()');
                        } catch(e) {
                            console.error('Infinite scroll: Error calling loadNextChapter:', e);
                            window.__tsundokuLoadingNext = false; // Reset immediately on error
                        }
                    }

                    // Infinite scroll: load previous chapter when user scrolls UP near the top.
                    // Direction guard (lastScrollTop) prevents firing on the natural rebound
                    // from a fling — only an actual upward scroll past the threshold counts.
                    var scrollingUp = (lastScrollTop !== -1) && (scrollTop < lastScrollTop);
                    lastScrollTop = scrollTop;
                    var shouldLoadPrev = false;
                    if (infiniteScrollEnabled && scrollingUp) {
                        if (window.chapterBoundaries.length > 1) {
                            shouldLoadPrev = (currentChapterIdx === 0) && (scrollTop < prevLoadOffset);
                        } else {
                            shouldLoadPrev = scrollTop < prevLoadOffset;
                        }
                    }
                    if (shouldLoadPrev && !window.__tsundokuLoadingPrev && !window.__tsundokuLoadingNext) {
                        console.log('Infinite scroll: Loading previous chapter at scrollTop ' + scrollTop);
                        window.__tsundokuLoadingPrev = true;
                        try {
                            Android.loadPrevChapter();
                            console.log('Infinite scroll: Successfully called loadPrevChapter()');
                        } catch(e) {
                            console.error('Infinite scroll: Error calling loadPrevChapter:', e);
                            window.__tsundokuLoadingPrev = false;
                        }
                    }
                });

                // Function to add a chapter boundary
                window.addChapterBoundary = function(chapterId, startOffset, height) {
                    window.chapterBoundaries.push({
                        chapterId: chapterId,
                        startOffset: startOffset,
                        height: height
                    });
                };

                // Function to update chapter boundary heights after content load
                window.updateChapterBoundaries = function() {
                    var dividers = document.querySelectorAll('.chapter-divider');
                    var boundaries = [];
                    var lastEnd = 0;
                    dividers.forEach(function(divider, index) {
                        var chapterId = divider.getAttribute('data-chapter-id');
                        var nextDivider = dividers[index + 1];
                        var endPos = nextDivider ? nextDivider.offsetTop : document.body.scrollHeight;
                        boundaries.push({
                            chapterId: chapterId,
                            startOffset: divider.offsetTop,
                            height: endPos - divider.offsetTop
                        });
                    });
                    window.chapterBoundaries = boundaries;
                    window.__tsundokuLastBoundaryUpdate = Date.now();
                };

                // Initialize boundaries on first load.
                setTimeout(function() {
                    if (typeof window.updateChapterBoundaries === 'function') {
                        window.updateChapterBoundaries();
                    }
                }, 0);
            })();
        """
        evaluateJavascriptSafe(scrollTrackingScript, null)
    }

    private fun restoreScrollPosition() {
        // The chapter loaded into the WebView is THE chapter we want to restore — the VM's
        // `chapterId` (savedState-backed) bootstraps init() to the right chapter, and the
        // chapter's own `last_page_read` carries the within-chapter percent. No separate
        // "saved active chapter" lookup needed; see ReaderViewModel.setNovelVisibleChapter
        // invariant.
        currentPage?.let { page ->
            val savedProgress = page.chapter.chapter.last_page_read
            val isRead = page.chapter.chapter.read

            Logger.d {
                "NovelWebViewViewer: Restoring progress, savedProgress=$savedProgress, isRead=$isRead for ${page.chapter.chapter.name}"
            }

            // If chapter is marked as read, start from top (0%) to avoid infinite scroll issues
            val shouldRestore = if (!isRead) {
                savedProgress > 0 && savedProgress <= 100
            } else {
                libraryPreferences.novelReadProgress100().get() && savedProgress > 0 && savedProgress <= 100
            }
            if (shouldRestore) {
                val progress = savedProgress / 100f
                lastSavedProgress = progress

                // Wait for the WebView to actually have laid out content before scrolling.
                // The previous `postDelayed(100)` raced with layout and frequently fired before
                // scrollHeight was non-zero, leaving the user stuck unable to scroll. Now we
                // poll inside JS via requestAnimationFrame until content height appears, with
                // a hard cap so we don't loop forever on a genuinely-empty page.
                val js = """
                    (function() {
                        var attempts = 0;
                        function tryScroll() {
                            var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                            if (scrollHeight > 0 && document.readyState !== 'loading') {
                                window.scrollTo(0, scrollHeight * $progress);
                                console.log('Restored scroll to ' + Math.round($progress * 100) + '% (' + Math.round(scrollHeight * $progress) + 'px) after ' + attempts + ' frames');
                                return;
                            }
                            attempts++;
                            if (attempts < 60) {  // give up after ~1s at 60fps
                                requestAnimationFrame(tryScroll);
                            }
                        }
                        requestAnimationFrame(tryScroll);
                    })();
                """
                evaluateJavascriptSafe(js, null)
            } else {
                // Ensure we are at top for read chapters
                webView.scrollTo(0, 0)
                lastSavedProgress = 0f
            }
        }
    }

    private fun getThemeColors(theme: String): Pair<Int, Int> =
        NovelViewerTextUtils.getThemeColors(activity, preferences, theme)

    override fun destroy() {
        // Save progress before destroying — but mark as teardown so the VM's read-gate
        // never flips chapter.read = true on the way out (Phase A #19).
        saveProgress(isTeardown = true)

        // Stop any active TTS playback through the controller — the engine instance now
        // lives in the service, so the viewer no longer owns shutdown; the activity's
        // `unbindTtsService` (and the service's own `onDestroy`) does the engine teardown.
        activity.ttsController?.dispatch(hayai.novel.reader.tts.TtsCommand.Stop)

        // Stop the auto-scroll JS loop before tearing the WebView down (Phase B #6 — the
        // loop now lives in the WebView itself; the setter no-ops on a destroyed view via
        // evaluateJavascriptSafe's isDestroyed guard, but flipping isAutoScrolling=false
        // keeps the Kotlin side consistent).
        stopAutoScroll()

        // Mark destroyed first so coroutine finally-blocks won't touch WebView.
        isDestroyed = true

        scope.cancel()
        loadJob?.cancel()
        webView.destroy()
    }

    /**
     * Phase B #16 — central dispatch for [NovelTapAction] entries. Used by both the
     * double-tap and long-press paths in the gesture detector so both preferences share
     * the exact same action surface.
     */
    private fun dispatchTapAction(action: hayai.novel.reader.NovelTapAction) {
        when (action) {
            hayai.novel.reader.NovelTapAction.NONE -> Unit
            hayai.novel.reader.NovelTapAction.TOGGLE_MENU -> activity.toggleMenu()
            hayai.novel.reader.NovelTapAction.NEXT_CHAPTER -> activity.loadNextChapter()
            hayai.novel.reader.NovelTapAction.PREVIOUS_CHAPTER -> activity.loadPreviousChapter()
            hayai.novel.reader.NovelTapAction.SCROLL_DOWN -> {
                webView.evaluateJavascript("window.scrollBy(0, ${(container.height * 0.8).toInt()});", null)
            }
            hayai.novel.reader.NovelTapAction.SCROLL_UP -> {
                webView.evaluateJavascript("window.scrollBy(0, -${(container.height * 0.8).toInt()});", null)
            }
            hayai.novel.reader.NovelTapAction.TOGGLE_AUTO_SCROLL -> toggleAutoScroll()
            hayai.novel.reader.NovelTapAction.START_TTS -> startTts()
            hayai.novel.reader.NovelTapAction.STOP_TTS -> stopTts()
            hayai.novel.reader.NovelTapAction.DEFINE_SELECTED -> triggerDefineFromSelection()
        }
    }

    /**
     * Phase B #15 — fire `Intent.ACTION_PROCESS_TEXT` with the WebView's current text
     * selection. Prefers Google's quick-search box when installed (deterministic Define
     * dictionary handler), otherwise routes through a chooser. Toast if nothing handles
     * the intent. The selection is pulled out of the WebView asynchronously via
     * `window.getSelection().toString()`.
     */
    private fun triggerDefineFromSelection(actionMode: ActionMode? = null) {
        webView.evaluateJavascript(
            "(function(){var s=window.getSelection();return s?s.toString():'';})();",
        ) { result ->
            val raw = result ?: ""
            // The bridge returns JSON-encoded values — strip the surrounding quotes and
            // unescape the few sequences that matter for plain text selection.
            val selected = raw.trim('"')
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "")
                .replace("\\t", "\t")
                .trim()
            activity.runOnUiThread {
                // Finish the action mode only AFTER the selection has been read — finishing it
                // first clears window.getSelection(), leaving Define with no text.
                actionMode?.finish()
                if (selected.isEmpty()) {
                    activity.toast(activity.getString(MR.strings.novel_quote_no_selection))
                    return@runOnUiThread
                }
                launchDefineIntent(selected)
            }
        }
    }

    private fun launchDefineIntent(selected: String) {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        val pm = activity.packageManager
        val resolved = pm.queryIntentActivities(intent, 0)
        if (resolved.isEmpty()) {
            activity.toast(activity.getString(MR.strings.novel_define_no_handler))
            return
        }
        val googleSearchPkg = "com.google.android.googlequicksearchbox"
        val googleHandler = resolved.firstOrNull { it.activityInfo?.packageName == googleSearchPkg }
        try {
            if (googleHandler != null) {
                val info = googleHandler.activityInfo
                intent.setClassName(info.packageName, info.name)
                activity.startActivity(intent)
            } else {
                activity.startActivity(
                    Intent.createChooser(intent, activity.getString(MR.strings.novel_define)),
                )
            }
        } catch (t: Throwable) {
            Logger.w { "NovelWebViewViewer: Define intent failed: ${t.message}" }
            activity.toast(activity.getString(MR.strings.novel_define_no_handler))
        }
    }

    private fun triggerTranslateFromSelection(actionMode: ActionMode? = null) {
        webView.evaluateJavascript(
            "(function(){var s=window.getSelection();return s?s.toString():'';})();",
        ) { result ->
            val raw = result ?: ""
            val selected = raw.trim('"')
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "")
                .replace("\\t", "\t")
                .trim()
            activity.runOnUiThread {
                // Finish the action mode only AFTER the selection has been read — finishing it
                // first clears window.getSelection(), leaving Translate with no text.
                actionMode?.finish()
                if (selected.isEmpty()) {
                    activity.toast(activity.getString(MR.strings.novel_quote_no_selection))
                    return@runOnUiThread
                }
                launchTranslateIntent(selected)
            }
        }
    }

    private fun launchTranslateIntent(selected: String) {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        val pm = activity.packageManager
        val resolved = pm.queryIntentActivities(intent, 0)
        if (resolved.isEmpty()) {
            activity.toast(activity.getString(MR.strings.novel_translate_no_handler))
            return
        }
        val googleTranslatePkg = "com.google.android.apps.translate"
        val deeplPkg = "com.deepl.mobiletranslator"
        val preferredHandler = resolved.firstOrNull {
            val pkg = it.activityInfo?.packageName ?: ""
            pkg == googleTranslatePkg || pkg == deeplPkg || pkg.contains("translate", ignoreCase = true)
        }
        try {
            if (preferredHandler != null) {
                val info = preferredHandler.activityInfo
                intent.setClassName(info.packageName, info.name)
                activity.startActivity(intent)
            } else {
                activity.startActivity(
                    Intent.createChooser(intent, activity.getString(MR.strings.novel_translate)),
                )
            }
        } catch (t: Throwable) {
            Logger.w { "NovelWebViewViewer: Translate intent failed: ${t.message}" }
            activity.toast(activity.getString(MR.strings.novel_translate_no_handler))
        }
    }

    private fun triggerWebSearchFromSelection(actionMode: ActionMode? = null) {
        webView.evaluateJavascript(
            "(function(){var s=window.getSelection();return s?s.toString():'';})();",
        ) { result ->
            val raw = result ?: ""
            val selected = raw.trim('"')
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "")
                .replace("\\t", "\t")
                .trim()
            activity.runOnUiThread {
                // Finish the action mode only AFTER the selection has been read — finishing it
                // first clears window.getSelection(), leaving Web Search with no text.
                actionMode?.finish()
                if (selected.isEmpty()) {
                    activity.toast(activity.getString(MR.strings.novel_quote_no_selection))
                    return@runOnUiThread
                }
                launchWebSearchIntent(selected)
            }
        }
    }

    private fun launchWebSearchIntent(selected: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, selected)
        }
        try {
            activity.startActivity(intent)
        } catch (t: Throwable) {
            Logger.w { "NovelWebViewViewer: Web search intent failed: ${t.message}" }
            // Fallback: Open in Web Browser via Google Search URL
            try {
                val queryUrl = "https://www.google.com/search?q=" + android.net.Uri.encode(selected)
                val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(queryUrl))
                activity.startActivity(browserIntent)
            } catch (t2: Throwable) {
                activity.toast(activity.getString(MR.strings.novel_web_search_no_handler))
            }
        }
    }

    private fun evaluateJavascriptSafe(js: String, callback: ((String) -> Unit)? = null) {
        if (isDestroyed) return
        activity.runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            try {
                webView.evaluateJavascript(js, callback)
            } catch (t: Throwable) {
                // WebView may already be destroyed; avoid crashing.
                Logger.w { "NovelWebViewViewer: evaluateJavascript ignored (${t.message})" }
            }
        }
    }

    /**
     * The chapter the user is currently looking at. Derived from `currentChapters.currChapter`,
     * which the ViewModel keeps in sync with the visible chapter via `setNovelVisibleChapter`.
     * See the invariant note on `ReaderViewModel.setNovelVisibleChapter`.
     */
    private fun currentActiveChapter(): ReaderChapter? = currentChapters?.currChapter

    /**
     * First active page of the currently-visible chapter, suitable as the saveProgress
     * "page" argument. Returns the entry `currentPage` only as a last resort so single-
     * chapter loads continue to work unchanged.
     */
    private fun currentActivePage(): ReaderPage? {
        val active = currentActiveChapter()
        val activePage = active?.pages?.firstOrNull() as? ReaderPage
        return activePage ?: currentPage
    }

    private fun saveProgress(isTeardown: Boolean = false) {
        val page = currentActivePage() ?: return
        val progressValue = (lastSavedProgress * 100).toInt().coerceIn(0, 100)
        activity.saveNovelProgress(page, progressValue, isTeardown = isTeardown)
        val activeId = currentActiveChapter()?.chapter?.id
        Logger.d {
            "NovelWebViewViewer: Saving progress $progressValue% chapter=$activeId teardown=$isTeardown"
        }
    }

    /**
     * Activity-pause/stop hook. Flushes the visible chapter's within-chapter scroll
     * percent to its `last_page_read` (teardown-safe — never flips the read flag).
     * The chapter id itself is persisted automatically by `ReaderViewModel.chapterId`'s
     * savedState backing, kept in sync via [ReaderViewModel.setNovelVisibleChapter].
     */
    fun flushPersistentState() {
        val active = currentActiveChapter() ?: return
        Logger.d {
            "NovelWebViewViewer: flushPersistentState chapter=${active.chapter.id} progress=${(lastSavedProgress * 100).toInt()}%"
        }
        saveProgress(isTeardown = true)
    }

    override fun getView(): View = container

    /**
     * Reload content with current translation state.
     * Re-renders the WebView with or without translation.
     */
    fun reloadWithTranslation() {
        val page = currentPage ?: return
        val chapter = currentChapters?.currChapter ?: return
        val chapterId = chapter.chapter.id
        var content = page.text ?: return

        // Optionally strip chapter title from content
        if (preferences.novelHideChapterTitle.get()) {
            content = stripChapterTitle(content, chapter.chapter.name)
        }

        // Apply translation if enabled (async)
        val finalContent = content
        if (activity.isTranslationEnabled()) {
            // Show loading indicator while translating
            loadingIndicator?.show()
            scope.launch {
                val translatedContent = activity.translateContentIfEnabled(finalContent)
                withContext(Dispatchers.Main) {
                    loadingIndicator?.hide()
                    loadHtmlContent(translatedContent, chapterId, chapter.chapter.name, chapter.chapter.url)
                }
            }
        } else {
            scope.launch {
                loadHtmlContent(finalContent, chapterId, chapter.chapter.name, chapter.chapter.url)
            }
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        val page = chapters.currChapter.pages?.firstOrNull() as? ReaderPage ?: return
        val chapterId = chapters.currChapter.chapter.id ?: return

        loadJob?.cancel()
        // Stop TTS when loading a new chapter. The controller's chapter-advance path
        // re-issues the appropriate `StartAt` once the new chapter is Ready; any
        // user-initiated chapter change goes through here and silences playback.
        val ttsState = activity.ttsController?.state?.value
        if (ttsState != null && ttsState != hayai.novel.reader.tts.TtsState.AdvancingChapter) {
            activity.ttsController?.dispatch(hayai.novel.reader.tts.TtsCommand.Stop)
        }
        // Phase B #6: tear the auto-scroll loop down whenever a fresh chapter renders.
        // The next document's installAutoScrollScript() call will set up a fresh loop;
        // not stopping here would leave isAutoScrolling=true while the JS state object on
        // the new page is fresh (running=false), producing a confused "scrolling but not
        // scrolling" state on the next user toggle.
        if (isAutoScrolling) stopAutoScroll()

        currentPage = page
        currentChapters = chapters

        // setChapters() is for loading a single chapter (manual navigation or initial load).
        // Infinite scroll appends/prepends are handled explicitly via WebViewInterface.loadNext/PrevChapter().
        val isInfiniteScrollAppend = false

        val isPrepend = isInfiniteScrollPrepend
        isInfiniteScrollPrepend = false

        // Check if chapter is already loaded
        if (loadedChapterIds.contains(chapterId)) {
            Logger.d { "NovelWebViewViewer: Chapter $chapterId already loaded, updating active references and skipping reload" }
            currentPage = page
            currentChapters = chapters
            return
        }

        // Clear previous chapters on initial load only (continuous scroll keeps them).
        if (loadedChapterIds.isEmpty()) {
            loadedChapterIds.clear()
            loadedChapters.clear()
        }

        if (page.status == Page.State.Ready && !page.text.isNullOrEmpty()) {
            if (!isInfiniteScrollAppend && !isPrepend) {
                hideLoadingIndicator()
            }
            displayContent(chapters.currChapter, page, isInfiniteScrollAppend || isPrepend, isPrepend)
            // Trigger download of next chapters (needed for non-infinite-scroll mode)
            if (!isInfiniteScrollAppend && !isPrepend) {
                activity.viewModel.setNovelVisibleChapter(page.chapter)
            }
            return
        }

        // Only show loading for manual navigation, NEVER for infinite scroll (seamless append)
        if (!isInfiniteScrollAppend && !isPrepend) {
            showLoadingIndicator()
        }
        // No loading indicator at all for infinite scroll - should be completely seamless

        loadJob = scope.launch {
            val loader = page.chapter.pageLoader
            if (loader == null) {
                Logger.e { "NovelWebViewViewer: No page loader available" }
                if (!isInfiniteScrollAppend && !isPrepend) {
                    hideLoadingIndicator()
                }
                return@launch
            }

            launch(Dispatchers.IO) {
                loader.loadPage(page)
            }

            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue, Page.State.LoadPage -> {
                        if (!isInfiniteScrollAppend && !isPrepend) {
                            showLoadingIndicator()
                        }
                    }
                    Page.State.Ready -> {
                        // Only hide loading for manual navigation
                        if (!isInfiniteScrollAppend && !isPrepend) {
                            hideLoadingIndicator()
                        }
                        // Infinite scroll is seamless - no loading indicators to hide
                        displayContent(chapters.currChapter, page, isInfiniteScrollAppend || isPrepend, isPrepend)
                        // Trigger download of next chapters (needed for non-infinite-scroll mode)
                        if (!isInfiniteScrollAppend && !isPrepend) {
                            activity.viewModel.setNovelVisibleChapter(page.chapter)
                        }
                    }
                    is Page.State.Error -> {
                        // Only hide loading for manual navigation
                        if (!isInfiniteScrollAppend && !isPrepend) {
                            hideLoadingIndicator()
                        }
                        // Infinite scroll is seamless - no loading indicators to hide
                        lastErrorRetryContext = chapters.currChapter to page
                        displayError(state.error)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun showInlineLoading(isPrepend: Boolean) {
        val js = """
            (function() {
                var loadingDiv = document.getElementById('inline-loading');
                if (!loadingDiv) {
                    loadingDiv = document.createElement('div');
                    loadingDiv.id = 'inline-loading';
                    loadingDiv.style.textAlign = 'center';
                    loadingDiv.style.padding = '20px';
                    loadingDiv.style.color = '#888';
                    loadingDiv.innerHTML = 'Loading...';
                }

                if ($isPrepend) {
                    document.body.insertBefore(loadingDiv, document.body.firstChild);
                } else {
                    document.body.appendChild(loadingDiv);
                }
            })();
        """.trimIndent()
        evaluateJavascriptSafe(js, null)
    }

    private fun hideInlineLoading(isPrepend: Boolean) {
        val js = """
            (function() {
                var loadingDiv = document.getElementById('inline-loading');
                if (loadingDiv) {
                    loadingDiv.remove();
                }
            })();
        """.trimIndent()
        evaluateJavascriptSafe(js, null)
    }

    private fun showInlineError(message: String, isPrepend: Boolean) {
        scope.launch(Dispatchers.Main) {
            val escapedMessage = message.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")

            val js = """
                (function() {
                    var errorDiv = document.getElementById('inline-error');
                    if (errorDiv) {
                        errorDiv.remove();
                    }
                    errorDiv = document.createElement('div');
                    errorDiv.id = 'inline-error';
                    errorDiv.style.textAlign = 'center';
                    errorDiv.style.padding = '16px';
                    errorDiv.style.color = '#FF5252';
                    errorDiv.style.backgroundColor = 'rgba(255, 82, 82, 0.1)';
                    errorDiv.style.cursor = 'pointer';
                    errorDiv.innerHTML = '$escapedMessage (tap to dismiss)';
                    errorDiv.onclick = function() { errorDiv.remove(); };

                    if ($isPrepend) {
                        document.body.insertBefore(errorDiv, document.body.firstChild);
                    } else {
                        document.body.appendChild(errorDiv);
                    }
                })();
            """.trimIndent()
            evaluateJavascriptSafe(js, null)

            // Auto-dismiss after 8 seconds
            delay(8000)
            evaluateJavascriptSafe(
                "document.getElementById('inline-error')?.remove();",
                null,
            )
        }
    }

    private fun scrollToChapterIndex(index: Int) {
        val js = """
            (function() {
                var dividers = document.querySelectorAll('.chapter-divider');
                if (dividers[$index]) {
                    dividers[$index].scrollIntoView({ behavior: 'smooth' });
                }
            })();
        """.trimIndent()
        evaluateJavascriptSafe(js, null)
    }

    private fun displayContent(
        chapter: ReaderChapter,
        page: ReaderPage,
        isAppendOrPrepend: Boolean = false,
        isPrepend: Boolean = false,
    ) {
        val rawText = page.text
        if (rawText.isNullOrBlank()) {
            lastErrorRetryContext = chapter to page
            displayError(Exception(activity.getString(MR.strings.novel_error_no_text)))
            return
        }

        val chapterId = chapter.chapter.id ?: return

        val hideTitle = preferences.novelHideChapterTitle.get()
        val forceLowercase = preferences.novelForceTextLowercase.get()
        val chapterName = chapter.chapter.name
        val chapterUrl = chapter.chapter.url
        val shouldTranslate = !isAppendOrPrepend

        scope.launch {
            val renderableContent = withContext(Dispatchers.Default) {
                var content = rawText
                if (hideTitle) content = stripChapterTitle(content, chapterName)
                content = applyRegexReplacements(content)
                if (forceLowercase) content = content.lowercase()
                val processed = if (shouldTranslate) {
                    activity.translateContentIfEnabled(content)
                } else {
                    content
                }
                val plainTextMode = NovelViewerTextUtils.isPlainTextChapter(chapterUrl)
                if (plainTextMode) {
                    NovelViewerTextUtils.normalizePlainTextContent(processed)
                } else {
                    NovelViewerTextUtils.normalizeContentForHtml(processed, chapterUrl)
                }
            }

            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                if (isAppendOrPrepend && loadedChapterIds.contains(chapterId)) {
                    Logger.d { "NovelWebViewViewer: Chapter $chapterId already loaded during suspension, skipping displayContent" }
                    return@withContext
                }
                if (isAppendOrPrepend) {
                    // Capture the existing edge chapter BEFORE mutating loadedChapters so the
                    // transition card binds the right "from" → "to" pair. Without this snapshot
                    // the prepend/append helpers would see the just-inserted chapter as the
                    // anchor and label the card "Finished: <new>" / "Next: <new>".
                    val fromChapter = if (isPrepend) loadedChapters.firstOrNull() else loadedChapters.lastOrNull()
                    if (!loadedChapterIds.contains(chapterId)) {
                        if (isPrepend) {
                            loadedChapterIds.add(0, chapterId)
                            loadedChapters.add(0, chapter)
                        } else {
                            loadedChapterIds.add(chapterId)
                            loadedChapters.add(chapter)
                        }
                    }
                    if (isPrepend) {
                        prependHtmlContent(renderableContent, chapterId, chapter, fromChapter)
                    } else {
                        appendHtmlContent(renderableContent, chapterId, chapter, fromChapter)
                    }
                } else {
                    loadHtmlContent(renderableContent, chapterId, chapter.chapter.name, chapter.chapter.url)

                    // Fresh load: reset tracking to this single chapter.
                    loadedChapterIds.clear()
                    loadedChapters.clear()
                    loadedChapterIds.add(chapterId)
                    loadedChapters.add(chapter)
                }
            }
        }
    }

    /**
     * Prepend content to the existing WebView for infinite scroll (loading previous chapter).
     *
     * Inserts a "Previous: <new>" / "Current: <existing top>" transition card BETWEEN the
     * prepended chapter and the previously-first chapter — i.e. above the boundary marker
     * that anchors the previously-first content. The user scrolling up sees:
     *
     *     [prepended chapter content]
     *     [transition card: Previous / Current]
     *     [previously-first chapter content (unchanged)]
     *
     * Scroll-position preservation: we measure the document height delta after the DOM
     * mutates and offset `window.scrollTo` by that diff so the user's reading position
     * doesn't jump when the new content shifts everything below it.
     */
    private fun prependHtmlContent(
        content: String,
        chapterId: Long,
        chapter: ReaderChapter,
        fromChapter: ReaderChapter?,
    ) {
        val chapterUrl = chapter.chapter.url
        val plainTextMode = NovelViewerTextUtils.isPlainTextChapter(chapterUrl)
        var cleanContent = if (plainTextMode) {
            NovelViewerTextUtils.normalizePlainTextContent(content)
        } else {
            normalizeContentForHtml(content, chapterUrl)
                .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("<script[^>]*/>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("<noscript[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        }
        if (preferences.novelBlockMedia.get()) {
            cleanContent = stripMediaTags(cleanContent)
        }
        // Phase C #17: tag every text block with data-paragraph-index so the JS click
        // handler reports an index that lines up exactly with chapterParagraphsById.
        cleanContent = tagAndStashViaHelper(chapterId, cleanContent, plainTextMode, chapterUrl)
        val escapedContent = JSONObject.quote(cleanContent)

        // Visible Prev/Current card. `from` is the previously-top chapter (the one the user
        // is scrolling up from); `to` is the chapter being prepended above it. Gated on
        // alwaysShowChapterTransition (mirrors the manga viewers); when off, the card is
        // suppressed but the invisible boundary marker below still keeps tracking intact.
        val showTransition = activity.preferences.alwaysShowChapterTransition().get()
        val transitionHtml = if (showTransition) {
            buildTransitionCardHtml(from = chapter, to = fromChapter, isNext = false)
        } else {
            ""
        }
        val escapedTransition = JSONObject.quote(transitionHtml)
        val transitionClass = if (showTransition) "chapter-divider chapter-transition" else "chapter-divider"

        val js = """
            (function() {
                var oldHeight = document.body.scrollHeight;
                var oldScrollY = window.scrollY || window.pageYOffset;

                // Boundary marker for the prepended chapter (kept invisible — same
                // shape as the initial chapter divider in loadHtmlContent).
                var marker = document.createElement('div');
                marker.className = 'chapter-divider';
                marker.setAttribute('data-chapter-id', '$chapterId');
                marker.style.height = '0';
                marker.style.margin = '0';
                marker.style.padding = '0';

                // Visible transition card sits between the prepended content and the
                // previously-first content (which already has its own boundary marker).
                var transition = document.createElement('div');
                transition.className = '$transitionClass';
                transition.setAttribute('data-chapter-id', '$chapterId');
                transition.innerHTML = $escapedTransition;

                // New chapter content above the visible card.
                var contentDiv = document.createElement('div');
                contentDiv.className = 'chapter-content';
                contentDiv.setAttribute('data-chapter-id', '$chapterId');
                ${if (plainTextMode) "contentDiv.textContent = $escapedContent;" else "contentDiv.innerHTML = $escapedContent;"}

                var firstChild = document.body.firstChild;
                document.body.insertBefore(transition, firstChild);
                document.body.insertBefore(contentDiv, transition);
                document.body.insertBefore(marker, contentDiv);

                setTimeout(function() {
                    var newHeight = document.body.scrollHeight;
                    var diff = newHeight - oldHeight;
                    if (diff > 0) {
                        window.scrollTo(0, oldScrollY + diff);
                    }
                    if (typeof window.updateChapterBoundaries === 'function') {
                        window.updateChapterBoundaries();
                    }
                }, 10);
            })();
        """.trimIndent()

        evaluateJavascriptSafe(js, null)
        Logger.d {
            "NovelWebViewViewer: Prepended chapter $chapterId with transition (${loadedChapterIds.size} total)"
        }
    }

    /**
     * Append content to the existing WebView for infinite scroll.
     *
     * Renders a "Finished: <prev>" / "Next: <new>" transition card mirroring the manga
     * ReaderTransitionView (Compose `ChapterTransition.Next`): two stacked rows with the
     * `titleMedium` label, the `titleLarge` chapter name, an optional scanlator subtitle
     * line, an inline cloud icon next to the chapter name, and a missing-chapters warning
     * card slotted between the rows when a numerical gap is detected.
     */
    private fun appendHtmlContent(
        content: String,
        chapterId: Long,
        chapter: ReaderChapter,
        fromChapter: ReaderChapter?,
    ) {
        val chapterUrl = chapter.chapter.url
        val plainTextMode = NovelViewerTextUtils.isPlainTextChapter(chapterUrl)
        var cleanContent = if (plainTextMode) {
            NovelViewerTextUtils.normalizePlainTextContent(content)
        } else {
            normalizeContentForHtml(content, chapterUrl)
                .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("<script[^>]*/>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("<noscript[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        }
        if (preferences.novelBlockMedia.get()) {
            cleanContent = stripMediaTags(cleanContent)
        }
        // Phase C #17: tag every text block with data-paragraph-index so the JS click
        // handler reports an index that lines up exactly with chapterParagraphsById.
        cleanContent = tagAndStashViaHelper(chapterId, cleanContent, plainTextMode, chapterUrl)
        val escapedContent = JSONObject.quote(cleanContent)
        // Gate the visible transition card on alwaysShowChapterTransition (mirrors the manga
        // pager/webtoon viewers). When off, still emit the invisible boundary divider so
        // chapter tracking/scroll math keep working — only the card UI is suppressed.
        val showTransition = activity.preferences.alwaysShowChapterTransition().get()
        val transitionHtml = if (showTransition) {
            buildTransitionCardHtml(from = fromChapter, to = chapter, isNext = true)
        } else {
            ""
        }
        val escapedTransition = JSONObject.quote(transitionHtml)
        val dividerClass = if (showTransition) "chapter-divider chapter-transition" else "chapter-divider"

        val js = """
            (function() {
                var divider = document.createElement('div');
                divider.className = '$dividerClass';
                divider.setAttribute('data-chapter-id', '$chapterId');
                divider.innerHTML = $escapedTransition;
                document.body.appendChild(divider);

                var contentDiv = document.createElement('div');
                contentDiv.className = 'chapter-content';
                contentDiv.setAttribute('data-chapter-id', '$chapterId');
                ${if (plainTextMode) "contentDiv.textContent = $escapedContent;" else "contentDiv.innerHTML = $escapedContent;"}
                document.body.appendChild(contentDiv);

                setTimeout(function() {
                    if (typeof window.updateChapterBoundaries === 'function') {
                        window.updateChapterBoundaries();
                    }
                }, 100);
            })();
        """.trimIndent()

        evaluateJavascriptSafe(js, null)
        Logger.d { "NovelWebViewViewer: Appended chapter $chapterId (${loadedChapterIds.size} total)" }
    }

    /**
     * Build the inner HTML for a chapter-transition card. Mirrors the layout of
     * `yokai.presentation.reader.ChapterTransition` (manga widget):
     *
     *   - top row: header label (titleMedium spirit) + chapter name (titleLarge, ~20sp) + cloud icon + scanlator
     *   - 24dp spacer
     *   - optional missing-chapters warning card (OutlinedCard with warning icon)
     *   - bottom row: same shape as top
     *   - or a "no next/previous chapter" fallback notification card if [to] is null
     *
     * `currentColor` lets the card inherit whatever `novelFontColor` body is using, so
     * the theming follows the active novel theme automatically. The warning/end-of-book
     * cards use a 1px outline at 40% opacity to stay visible on every theme without
     * needing a per-theme palette.
     */
    private fun buildTransitionCardHtml(from: ReaderChapter?, to: ReaderChapter?, isNext: Boolean): String {
        val topLabelStr: String
        val topChapter: ReaderChapter?
        val bottomLabelStr: String
        val bottomChapter: ReaderChapter?
        val fallbackLabelStr: String
        if (isNext) {
            topLabelStr = activity.getString(MR.strings.finished_chapter)
            topChapter = from
            bottomLabelStr = activity.getString(MR.strings.next_title)
            bottomChapter = to
            fallbackLabelStr = activity.getString(MR.strings.theres_no_next_chapter)
        } else {
            topLabelStr = activity.getString(MR.strings.previous_title)
            topChapter = from
            bottomLabelStr = activity.getString(MR.strings.current_chapter)
            bottomChapter = to
            fallbackLabelStr = activity.getString(MR.strings.theres_no_previous_chapter)
        }

        val gap = if (from != null && to != null) {
            val higher = if (isNext) to else from
            val lower = if (isNext) from else to
            calculateChapterDifference(higher, lower).toInt().coerceAtLeast(0)
        } else {
            0
        }

        val gapHtml = if (gap > 0) {
            // moko-resources Context extension (yokai.util.lang.getString) routes through
            // StringDesc.PluralFormatted so the quantity selects the right plural form.
            val warning = activity.getString(MR.plurals.missing_chapters_warning, gap, gap)
            """<div class="ch-trans-warning">$WARNING_ICON_SVG<span>${escapeHtml(warning)}</span></div>"""
        } else {
            ""
        }

        // Look up download state once per row. The manga ChapterTransition only renders an
        // inline icon when AT LEAST ONE of the two chapters is downloaded — when both are
        // online it leaves the title clean (see ChapterTransition.kt:233-244, where the
        // `if (downloaded || otherDownloaded)` guard wraps the appendInlineContent block).
        // We mirror that: each row gets a per-row "downloaded" bool plus the "otherDownloaded"
        // bool of its sibling, and renderChapterRow uses both to decide whether to draw a
        // CheckCircle, a Cloud, or no icon at all.
        val manga = activity.viewModel.manga
        val topDownloaded = topChapter?.let { isChapterDownloadedSafe(it, manga) } ?: false
        val bottomDownloaded = bottomChapter?.let { isChapterDownloadedSafe(it, manga) } ?: false

        val topHtml = if (topChapter != null) {
            renderChapterRow(topLabelStr, topChapter, downloaded = topDownloaded, otherDownloaded = bottomDownloaded)
        } else {
            renderFallbackNotice(fallbackLabelStr)
        }
        val bottomHtml = if (bottomChapter != null) {
            renderChapterRow(bottomLabelStr, bottomChapter, downloaded = bottomDownloaded, otherDownloaded = topDownloaded)
        } else {
            renderFallbackNotice(fallbackLabelStr)
        }

        return buildString {
            append(topHtml)
            append(gapHtml)
            append(bottomHtml)
        }
    }

    /**
     * Safe wrapper around [DownloadManager.isChapterDownloaded] that swallows any
     * exception (e.g. transient cache miss, missing storage permission) and returns false.
     * The transition card is purely cosmetic — a wrong icon is preferable to a crash.
     */
    private fun isChapterDownloadedSafe(
        chapter: ReaderChapter,
        manga: eu.kanade.tachiyomi.domain.manga.models.Manga?,
    ): Boolean {
        if (manga == null) return false
        return try {
            downloadManager.isChapterDownloaded(chapter.chapter, manga, skipCache = true)
        } catch (e: Throwable) {
            Logger.d { "NovelWebViewViewer: download check failed: ${e.message}" }
            false
        }
    }

    private fun renderChapterRow(
        label: String,
        chapter: ReaderChapter,
        downloaded: Boolean,
        otherDownloaded: Boolean,
    ): String {
        val name = chapter.chapter.name
        val scanlator = chapter.chapter.scanlator?.takeIf { it.isNotBlank() }
        val scanlatorHtml = scanlator?.let {
            """<div class="ch-trans-scanlator">${escapeHtml(it)}</div>"""
        }.orEmpty()
        // Mirror the manga widget: only show an icon if either row is downloaded; the
        // downloaded row gets CheckCircle, the not-downloaded sibling gets Cloud.
        val iconHtml = when {
            !downloaded && !otherDownloaded -> ""
            downloaded -> CHECK_CIRCLE_ICON_SVG
            else -> CLOUD_ICON_SVG
        }
        return """
            <div class="ch-trans-row">
                <div class="ch-trans-label">${escapeHtml(label)}</div>
                <div class="ch-trans-title">$iconHtml<span>${escapeHtml(name)}</span></div>
                $scanlatorHtml
            </div>
        """.trimIndent()
    }

    private fun renderFallbackNotice(text: String): String {
        return """<div class="ch-trans-notice">$INFO_ICON_SVG<span>${escapeHtml(text)}</span></div>"""
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    private suspend fun loadHtmlContent(
        content: String,
        chapterId: Long? = null,
        chapterName: String? = null,
        chapterUrl: String? = null,
    ) {
        // Resolve the current chapter (for the top transition card) from currentChapters,
        // which setChapters wires up before we reach here. Falls back to the first loaded
        // chapter for the reloadWithTranslation path. The top card mirrors the manga
        // reader's "scroll up to see the prev-transition" affordance: render either
        // "Previous: X / Current: Y" or a "There's no previous chapter" notice statically
        // at the top of the body, with breathing room above so the card has a scroll
        // target. After the page loads JS scrolls past the card so the user starts at
        // the chapter content; scrolling back up reveals the card and (if a prev exists)
        // eventually triggers the prepend handler.
        val currChapterForCard: ReaderChapter? = currentChapters?.currChapter
            ?: loadedChapters.firstOrNull()
        val prevChapterForCard: ReaderChapter? = currentChapters?.prevChapter
        val normalizedChapterUrl = normalizeUrl(chapterUrl)
        val blockMedia = preferences.novelBlockMedia.get()
        // Gate the initial top transition card on alwaysShowChapterTransition (mirrors the
        // manga viewers). Read here so it's available inside the Default-dispatcher block.
        val showTransition = activity.preferences.alwaysShowChapterTransition().get()
        val stylePayload = buildCustomStylePayload()

        val prepared = withContext(Dispatchers.Default) {
            val plainTextMode = NovelViewerTextUtils.isPlainTextChapter(normalizedChapterUrl)
            var cleanContent = if (plainTextMode) {
                NovelViewerTextUtils.normalizePlainTextContent(content)
            } else {
                NovelViewerTextUtils.normalizeContentForHtml(content, normalizedChapterUrl)
                    .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                    .replace(Regex("<script[^>]*/>", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                    .replace(Regex("<noscript[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            }
            if (blockMedia) cleanContent = stripMediaTags(cleanContent)
            cleanContent = applyRegexReplacements(cleanContent)

            var finalContent = cleanContent
            var paragraphsForChapter: List<String>? = null

            if (plainTextMode) {
                paragraphsForChapter = if (cleanContent.isBlank()) emptyList() else listOf(cleanContent)
                finalContent = """
                    <pre class="chapter-content" data-tsundoku-plain-text="1" style="white-space: pre-wrap; word-break: break-word; overflow-wrap: anywhere; margin: 0;"></pre>
                    <script>
                        document.querySelector('.chapter-content').textContent = ${JSONObject.quote(cleanContent)};
                    </script>
                """.trimIndent()
            } else {
                try {
                    val doc = org.jsoup.Jsoup.parse(finalContent)
                    doc.select("style, link[rel=stylesheet]").remove()
                    doc.select("script, noscript").remove()
                    val bodyNode = doc.body()
                    val sanitized = if (bodyNode != null && (bodyNode.hasText() || bodyNode.children().isNotEmpty())) {
                        bodyNode.html()
                    } else {
                        finalContent
                    }
                    val tagged = NovelViewerTextUtils.normalizeAndTagContentForHtml(sanitized, normalizedChapterUrl)
                    finalContent = tagged.html
                    paragraphsForChapter = tagged.paragraphs
                } catch (e: Exception) {
                    Logger.w {
                        "NovelWebViewViewer: loadHtmlContent Jsoup pass failed: ${e.message}"
                    }
                }
            }

            val chapterDivider = if (chapterId != null) {
                """<div class="chapter-divider" data-chapter-id="$chapterId" style="height:0;margin:0;padding:0;"></div>
                   <div class="chapter-content" data-chapter-id="$chapterId">"""
            } else {
                ""
            }
            val chapterDividerEnd = if (chapterId != null) "</div>" else ""

            val topCardHtml = if (currChapterForCard != null && showTransition) {
                val cardInner = buildTransitionCardHtml(
                    from = prevChapterForCard,
                    to = currChapterForCard,
                    isNext = false,
                )
                val cardId = prevChapterForCard?.chapter?.id?.toString() ?: "no-prev"
                """<div class="chapter-divider chapter-transition initial-prev-card" data-chapter-id="$cardId">$cardInner</div>"""
            } else {
                ""
            }

            val mediaBlockCss = if (blockMedia) {
                "img, video, audio, source, svg, image { display: none !important; }"
            } else {
                ""
            }

            val chapterMetaScript = buildChapterMetaScript()

            val escapedInitialStyle = stylePayload.css
                .replace("</style>", "<\\/style>")
                .replace("</Style>", "<\\/Style>")
                .replace("</STYLE>", "<\\/STYLE>")

            val hideHeadingCss = if (stylePayload.hideChapterTitle) {
                "h1:first-of-type, h2:first-of-type, h3:first-of-type, h4:first-of-type, h5:first-of-type, h6:first-of-type { display: none !important; }"
            } else {
                ""
            }

            val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        display: block !important;
                    }
                    /* Boundary marker without the transition card (used by the first chapter
                       and as a JS scroll-position anchor). Kept invisible. */
                    .chapter-divider:not(.chapter-transition) {
                        height: 1px;
                        margin: 32px auto;
                        padding: 0;
                        border: none;
                        border-top: 1px solid currentColor;
                        opacity: 0.4;
                        width: 60%;
                    }
                    /* Static top card needs extra breathing room above so the user has
                       a real scroll-up surface — the regular .chapter-transition margin
                       would render the card flush with the page top edge otherwise. */
                    .chapter-transition.initial-prev-card {
                        margin-top: 80px;
                    }
                    /* Transition between chapters during continuous scroll. Mirrors the
                       manga ReaderTransitionView: a centered label header in the
                       titleMedium spirit, followed by a large titleLarge-sized chapter
                       name with an inline cloud "online" icon, an optional scanlator
                       subtitle (bodySmall, secondary alpha), a 24dp gap-spacer between
                       rows, and an optional missing-chapters warning card slotted between
                       the rows. The "outer" card has no border — only the warning and
                       end-of-book notice are wrapped in an outlined Surface, exactly like
                       the manga widget which uses OutlinedCard for those alerts only. */
                    .chapter-transition {
                        margin: 56px auto 48px;
                        padding: 0 8px;
                        max-width: 460px;
                        text-align: left;
                    }
                    .chapter-transition .ch-trans-row { margin: 0; }
                    /* 24dp gap between rows / between row and warning card. */
                    .chapter-transition .ch-trans-row + .ch-trans-row,
                    .chapter-transition .ch-trans-row + .ch-trans-warning,
                    .chapter-transition .ch-trans-warning + .ch-trans-row {
                        margin-top: 24px;
                    }
                    .chapter-transition .ch-trans-label {
                        font-size: 0.95em;
                        font-weight: 500;
                        letter-spacing: 0.01em;
                        opacity: 0.80;
                        margin-bottom: 4px;
                    }
                    .chapter-transition .ch-trans-title {
                        font-size: 1.40em;
                        font-weight: 600;
                        line-height: 1.3;
                        display: flex;
                        align-items: center;
                        gap: 6px;
                    }
                    .chapter-transition .ch-trans-title svg {
                        width: 22px;
                        height: 22px;
                        flex: 0 0 auto;
                        opacity: 0.85;
                    }
                    .chapter-transition .ch-trans-scanlator {
                        font-size: 0.80em;
                        opacity: 0.60;
                        margin-top: 4px;
                        line-height: 1.2;
                    }
                    /* Outlined warning + end-of-book notice cards: 1px outline at 40%
                       opacity stays visible across all novel themes without per-theme
                       palette knobs (manga's OutlinedCard uses the colorScheme outline). */
                    .chapter-transition .ch-trans-warning,
                    .chapter-transition .ch-trans-notice {
                        display: flex;
                        align-items: center;
                        gap: 16px;
                        padding: 12px 16px;
                        border: 1px solid currentColor;
                        border-radius: 12px;
                        opacity: 0.85;
                        font-size: 0.95em;
                        line-height: 1.35;
                    }
                    .chapter-transition .ch-trans-warning svg,
                    .chapter-transition .ch-trans-notice svg {
                        width: 24px;
                        height: 24px;
                        flex: 0 0 auto;
                    }
                    img {
                        max-width: 100%;
                        height: auto;
                        display: block;
                        margin: 8px auto;
                        min-height: 100px;
                        background: rgba(150, 150, 150, 0.2) url('data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 50 50"><circle cx="25" cy="25" r="20" fill="none" stroke="%23888" stroke-width="5" stroke-dasharray="31.4 31.4"><animateTransform attributeName="transform" type="rotate" from="0 25 25" to="360 25 25" dur="1s" repeatCount="indefinite"/></circle></svg>') no-repeat center center;
                    }
                    video {
                        max-width: 100%;
                        height: auto;
                    }
                    $hideHeadingCss
                    $mediaBlockCss
                </style>
                <style id="tsundoku-custom-style">$escapedInitialStyle</style>
                <script>$chapterMetaScript</script>
            </head>
            <body>
                $topCardHtml
                $chapterDivider
                $finalContent
                $chapterDividerEnd
                <script>
                    // Scroll past the static top card so the user starts at the chapter
                    // content. The card stays accessible by scrolling up. We post the
                    // scroll twice — once on DOMContentLoaded for fast paint, once after
                    // image/font load so late layout shifts don't leave us short. After
                    // the second scroll we prime lastScrollTop in the infinite-scroll
                    // handler with the resting position so the next genuine upward gesture
                    // is correctly detected as scrolling-up.
                    (function() {
                        function scrollPastTopCard() {
                            var card = document.querySelector('.initial-prev-card');
                            if (!card) return;
                            var rect = card.getBoundingClientRect();
                            var target = rect.top + window.pageYOffset + rect.height;
                            // Snap rather than smooth-scroll — smooth would race with
                            // the user's first touch and feel laggy.
                            window.scrollTo(0, target);
                            if (typeof window.__novelPrimeLastScrollTop === 'function') {
                                window.__novelPrimeLastScrollTop(target);
                            }
                        }
                        if (document.readyState === 'loading') {
                            document.addEventListener('DOMContentLoaded', scrollPastTopCard);
                        } else {
                            scrollPastTopCard();
                        }
                        window.addEventListener('load', scrollPastTopCard);
                    })();
                </script>
            </body>
            </html>
            """.trimIndent()

            PreparedHtml(html = html, paragraphsForChapter = paragraphsForChapter)
        }

        webView.setBackgroundColor(stylePayload.backgroundColor)
        container.setBackgroundColor(stylePayload.backgroundColor)
        loadedChapterIds.clear()
        loadedChapters.clear()
        chapterParagraphsById.clear()
        if (chapterId != null && prepared.paragraphsForChapter != null) {
            chapterParagraphsById[chapterId] = prepared.paragraphsForChapter
            chapterParagraphReadyFlow.tryEmit(chapterId)
        }
        webView.loadDataWithBaseURL(resolveWebViewBaseUrl(normalizedChapterUrl), prepared.html, "text/html", "UTF-8", null)
    }

    private data class PreparedHtml(val html: String, val paragraphsForChapter: List<String>?)



    private fun resolveWebViewBaseUrl(chapterUrl: String?): String? {
        val repairedChapterUrl = normalizeUrl(chapterUrl)
        val absoluteChapterUrl = repairedChapterUrl?.trim().takeUnless { it.isNullOrBlank() }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (absoluteChapterUrl != null) return absoluteChapterUrl

        val novelUrl = normalizeUrl(activity.viewModel.manga?.url)?.trim().takeUnless { it.isNullOrBlank() }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        return novelUrl
    }

    private fun normalizeUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isBlank()) return url
        return when {
            value.startsWith("https//") -> "https://" + value.removePrefix("https//")
            value.startsWith("http//") -> "http://" + value.removePrefix("http//")
            else -> value
        }
    }

    private fun stripMediaTags(content: String): String {
        return content
            .replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<image[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</image>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<svg[^>]*>.*?</svg>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<video[^>]*>.*?</video>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<audio[^>]*>.*?</audio>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<source[^>]*>", RegexOption.IGNORE_CASE), "")
    }

    /**
     * Build a JS snippet that sets global chapter/novel metadata variables.
     * Called during initial [loadHtmlContent] to embed in `<script>` tags.
     */
    private fun buildChapterMetaScript(): String {
        val chapter = currentChapters?.currChapter?.chapter
        val chapterTitle = (chapter?.name ?: "").jsEscape()
        val chapterNumber = chapter?.chapter_number ?: -1f
        val chapterUrl = normalizeUrl(chapter?.url).orEmpty().jsEscape()
        val novelUrl = normalizeUrl(activity.viewModel.manga?.url).orEmpty().jsEscape()

        return """
            window.__TSUNDOKU_CHAPTER_TITLE = "$chapterTitle";
            window.__TSUNDOKU_CHAPTER_NUMBER = $chapterNumber;
            window.__TSUNDOKU_CHAPTER_URL = "$chapterUrl";
            window.__TSUNDOKU_NOVEL_URL = "$novelUrl";
            window.__TSUNDOKU_IS_EDIT_MODE = $isEditingMode;
            window.__TSUNDOKU_IS_INF_SCROLL = true;
            window.__TSUNDOKU_TEXT_SELECTION_BLOCKED = ${!preferences.novelTextSelectable.get()};
            window.__TSUNDOKU_FORCED_LOWERCASE = ${preferences.novelForceTextLowercase.get()};
        """.trimIndent()
    }

    /**
     * Update global JS chapter metadata variables after an infinite-scroll
     * boundary change (when the user scrolls into a different chapter).
     */
    private fun updateChapterMetaJs() {
        val js = buildChapterMetaScript()
        evaluateJavascriptSafe("(function(){$js})();", null)
    }

    /**
     * Escape a string for safe embedding inside a JS double-quoted literal.
     * Also escapes `</script>` sequences which would prematurely close the script tag.
     */
    private fun String.jsEscape(): String =
        this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("</script>", "<\\/script>")
            .replace("</Script>", "<\\/Script>")
            .replace("</SCRIPT>", "<\\/SCRIPT>")

    /**
     * Apply user-configured find & replace rules to content.
     * Rules are stored as JSON in the novelRegexReplacements preference.
     * Each enabled rule is applied in order — supports both plain text and regex patterns.
     */
    private fun applyRegexReplacements(content: String): String =
        NovelViewerTextUtils.applyRegexReplacements(content, preferences)

    private fun normalizeContentForHtml(content: String, chapterUrl: String?): String =
        NovelViewerTextUtils.normalizeContentForHtml(content, chapterUrl)

    /**
     * Phase C #17: thin wrapper that defers tagging + paragraph capture to the shared
     * [NovelViewerTextUtils.normalizeAndTagContentForHtml] helper so both the WebView
     * viewer and the native [NovelViewer] go through one tagger. Stores the captured
     * paragraph list into [chapterParagraphsById] keyed by chapter id and returns the
     * tagged HTML for the JS payload.
     *
     * Plain-text chapters are special-cased: their rendering is a single `<pre>` (no inner
     * `<p>` tags), so we stash the whole blob as one paragraph and skip tagging since the
     * Tsundoku pre-script already injects textContent at runtime.
     */
    private fun tagAndStashViaHelper(
        chapterId: Long,
        cleanContent: String,
        plainTextMode: Boolean,
        chapterUrl: String?,
    ): String {
        if (plainTextMode) {
            chapterParagraphsById[chapterId] =
                if (cleanContent.isBlank()) emptyList() else listOf(cleanContent)
            chapterParagraphReadyFlow.tryEmit(chapterId)
            return cleanContent
        }
        val tagged = NovelViewerTextUtils.normalizeAndTagContentForHtml(cleanContent, chapterUrl)
        chapterParagraphsById[chapterId] = tagged.paragraphs
        chapterParagraphReadyFlow.tryEmit(chapterId)
        return tagged.html
    }

    /**
     * Strips the chapter title from the beginning of the content.
     * Removes the first H1-H6 heading element, first paragraph, div, span, or plain text if it matches the chapter name.
     */
    private fun stripChapterTitle(content: String, chapterName: String): String =
        NovelViewerTextUtils.stripChapterTitle(content, chapterName)

    private fun isTitleMatch(text: String, chapterName: String): Boolean =
        NovelViewerTextUtils.isTitleMatch(text, chapterName)

    private fun showLoadingIndicator() {
        // Render a blank background-only WebView underneath, then layer the M3 Expressive
        // LoadingIndicator on top via [showComposeLoadingOverlay] so the loader matches
        // the native viewer.
        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val fontColor = preferences.novelFontColor.get()
        val (themeBgColor, themeTextColor) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        val finalTextColor = if (theme == "custom" && fontColor != 0) fontColor else themeTextColor

        val bgColorHex = String.format("#%06X", 0xFFFFFF and finalBgColor)
        val textColorHex = String.format("#%06X", 0xFFFFFF and finalTextColor)

        val loadingHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        margin: 0;
                        padding: 16px;
                        background-color: $bgColorHex;
                        color: $textColorHex;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        height: 100vh;
                        font-family: sans-serif;
                    }
                </style>
            </head>
            <body>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, loadingHtml, "text/html", "UTF-8", null)

        // Mount the M3 Expressive loader on top of the WebView until the chapter renders.
        if (loadingComposeOverlay == null) {
            val overlay = hayai.novel.reader.ui.buildNovelLoadingComposeView(
                context = activity,
                backgroundArgb = finalBgColor,
            )
            loadingComposeOverlay = overlay
            container.addView(overlay)
        }

        // Safety net: if a chapter fails to fire onPageFinished (blank page, network
        // error, dropped JS bridge call), the overlay used to stay forever. Force-dismiss
        // after LOADING_TIMEOUT_MS so the user can at least see the broken state and retry.
        loadingTimeoutJob?.cancel()
        loadingTimeoutJob = scope.launch {
            delay(LOADING_TIMEOUT_MS)
            hideLoadingIndicator()
        }
    }

    private fun hideLoadingIndicator() {
        loadingTimeoutJob?.cancel()
        loadingTimeoutJob = null
        loadingComposeOverlay?.let { container.removeView(it) }
        loadingComposeOverlay = null
    }

    private fun displayError(error: Throwable) {
        android.util.Log.e("NovelWebViewViewer", "displayError: ${error.javaClass.simpleName}: ${error.message}", error)
        // Make sure the loading overlay doesn't outlive a hard error.
        hideLoadingIndicator()
        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val fontColor = preferences.novelFontColor.get()
        val (themeBgColor, themeTextColor) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        val finalTextColor = if (theme == "custom" && fontColor != 0) fontColor else themeTextColor
        val bgColorHex = String.format("#%06X", 0xFFFFFF and finalBgColor)
        val textColorHex = String.format("#%06X", 0xFFFFFF and finalTextColor)

        val escapedMessage = "${error.javaClass.simpleName}: ${error.message ?: "(null)"}"
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
        val canRetry = lastErrorRetryContext != null
        val retryLabel = activity.getString(MR.strings.retry)
        val titleLabel = activity.getString(MR.strings.novel_error_loading_chapter)
        val retryButtonHtml = if (canRetry) {
            """<button onclick="Android.retryFailedChapter()"
                       style="margin-top: 24px; padding: 12px 24px; font-size: 16px;
                              background-color: #ff5555; color: white; border: none;
                              border-radius: 8px; cursor: pointer;">$retryLabel</button>"""
        } else {
            ""
        }
        val errorHtml = """
            <!DOCTYPE html>
            <html>
            <body style="display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; background-color: $bgColorHex; color: $textColorHex;">
                <div style="text-align: center; color: #ff5555;">
                    <h2>$titleLabel</h2>
                    <p>$escapedMessage</p>
                    $retryButtonHtml
                </div>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
    }

    override fun moveToPage(page: ReaderPage, animated: Boolean) {
        // For novels, navigation is by chapter not page
    }

    override fun moveToNext() {
        webView.evaluateJavascript("window.scrollBy(0, ${(container.height * 0.8).toInt()});", null)
    }

    override fun moveToPrevious() {
        webView.evaluateJavascript("window.scrollBy(0, -${(container.height * 0.8).toInt()});", null)
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        // Use a smaller scroll amount (30% of viewport) for volume keys
        val scrollAmount = (container.height * 0.30).toInt()

        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (isUp) activity.toggleMenu()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (preferences.novelVolumeKeysScroll.get()) {
                    if (!isUp) webView.evaluateJavascript("window.scrollBy(0, $scrollAmount);", null)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (preferences.novelVolumeKeysScroll.get()) {
                    if (!isUp) webView.evaluateJavascript("window.scrollBy(0, -$scrollAmount);", null)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_SPACE -> {
                if (!isUp) {
                    if (event.isShiftPressed) {
                        webView.pageUp(false)
                    } else {
                        webView.pageDown(false)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (!isUp) webView.pageUp(false)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (!isUp) webView.pageDown(false)
                return true
            }
        }
        return false
    }

    fun toggleEditMode(isEditing: Boolean, save: Boolean = true) {
        if (!isEditing && !save) {
            this.isEditingMode = false
            webView.evaluateJavascript(
                "(function() { window.getSelection().removeAllRanges(); document.activeElement.blur(); })();",
                null,
            )
            webView.clearFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)

            // Reload chapter to discard edits
            loadedChapterIds.clear()
            loadedChapters.clear()
            activity.viewModel.reloadChapter(fromSource = false)
            return
        }

        this.isEditingMode = isEditing
        injectCustomScript()
        updateChapterMetaJs()

        if (isEditing) {
            webView.post {
                activity.window.decorView.clearFocus()
                webView.requestFocus()
                webView.requestFocusFromTouch()
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(webView, 0)
                webView.postDelayed({
                    imm?.showSoftInput(webView, 0)
                }, 120)
            }
        } else {
            webView.evaluateJavascript(
                "(function() { window.getSelection().removeAllRanges(); document.activeElement.blur(); })();",
                null,
            )
            webView.clearFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)
        }

        val script = """
            (function() {
                function enableEdit() {
                    document.designMode = 'off';
                    var styleId = 'edit-mode-style';
                    if ('$isEditing' === 'true') {
                        if (!document.getElementById(styleId)) {
                            var style = document.createElement('style');
                            style.id = styleId;
                            style.innerHTML = '.chapter-content, [data-tsundoku-editable="1"], body { -webkit-user-select: text !important; user-select: text !important; pointer-events: auto !important; -webkit-tap-highlight-color: transparent; outline: none; } ' +
                                'body { padding-bottom: max(220px, 38vh) !important; }';
                            document.head.appendChild(style);
                        }

                        var editTargets = document.querySelectorAll('.chapter-content');
                        if (editTargets.length === 0 && document.body) {
                            document.body.setAttribute('contenteditable', 'true');
                            document.body.setAttribute('data-tsundoku-editable', '1');
                            document.body.setAttribute('tabindex', '0');
                        } else {
                            for (var i = 0; i < editTargets.length; i++) {
                                editTargets[i].setAttribute('contenteditable', 'true');
                                editTargets[i].setAttribute('data-tsundoku-editable', '1');
                                editTargets[i].setAttribute('tabindex', '0');
                            }
                        }

                        if (!window.__tsundokuEditInputBound) {
                            window.__tsundokuEditInputBound = true;
                            document.addEventListener('input', function(e) {
                                if (window.Android && window.Android.onContentEdited) {
                                    window.Android.onContentEdited();
                                }
                            });
                        }
                    } else {
                        var style = document.getElementById(styleId);
                        if (style) {
                            style.parentNode.removeChild(style);
                        }

                        var editableNodes = document.querySelectorAll('[data-tsundoku-editable="1"]');
                        for (var j = 0; j < editableNodes.length; j++) {
                            editableNodes[j].removeAttribute('contenteditable');
                            editableNodes[j].removeAttribute('data-tsundoku-editable');
                            editableNodes[j].removeAttribute('tabindex');
                        }

                        var contents = [];
                        var nodes = document.querySelectorAll('.chapter-content');
                        if (nodes.length > 0) {
                            for (var i = 0; i < nodes.length; i++) {
                                var html = nodes[i].innerHTML;
                                var chapterId = nodes[i].getAttribute('data-chapter-id');
                                contents.push({id: chapterId, content: html});
                            }
                        } else if (document.body) {
                            var currentId = '${currentChapters?.currChapter?.chapter?.id ?: -1}';
                            contents.push({id: currentId, content: document.body.innerHTML});
                        }
                        if (window.Android && window.Android.onSaveEditedContent) {
                            window.Android.onSaveEditedContent(JSON.stringify(contents));
                        }
                    }
                }

                if (document.readyState === 'complete') {
                    enableEdit();
                } else {
                    window.addEventListener('load', enableEdit);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    /**
     * JavaScript interface for communication from WebView
     */
    @Keep
    inner class WebViewInterface {
        @JavascriptInterface
        fun onContentEdited() {
            activity.viewModel.setHasUnsavedChanges(true)
        }

        @JavascriptInterface
        fun onSaveEditedContent(json: String) {
            Logger.d { "NovelWebViewViewer: onSaveEditedContent(length=${json.length})" }
            activity.viewModel.saveEditedChapterContent(json)
        }

        @JavascriptInterface
        fun onScrollProgress(progress: Float) {
            activity.runOnUiThread {
                lastSavedProgress = progress
                saveProgress()
            }
        }

        @JavascriptInterface
        fun onScrollUpdate(progress: Float) {
            activity.runOnUiThread {
                lastSavedProgress = progress
                activity.onNovelProgressChanged(progress)
            }
        }

        /**
         * The DOM is the source of truth for "which chapter is visible". JS reports the
         * `data-chapter-id` of the divider nearest scrollTop; Kotlin maps that to a
         * `ReaderChapter` in `loadedChapters` and pushes it through `setNovelVisibleChapter`.
         *
         * No index tracking, no parallel "active chapter" state — everything derives from
         * `currentChapters.currChapter` once the VM accepts the push. The per-chapter
         * within-progress is persisted via the existing scroll-progress save path
         * (`chapter.last_page_read`), so process restore lands on the right chapter at the
         * right percent without a separate saved-state field.
         */
        @JavascriptInterface
        fun onActiveChapterUpdate(chapterIdStr: String) {
            val newChapterId = chapterIdStr.toLongOrNull() ?: return
            activity.runOnUiThread {
                val currentId = currentChapters?.currChapter?.chapter?.id
                if (newChapterId == currentId) return@runOnUiThread

                val newChapter = loadedChapters.firstOrNull { it.chapter.id == newChapterId }
                if (newChapter == null) {
                    Logger.w {
                        "NovelWebViewViewer: onActiveChapterUpdate chapter=$newChapterId " +
                            "not in loadedChapters (loaded=${loadedChapterIds.size}); " +
                            "boundary marker outpaced Kotlin-side append"
                    }
                    return@runOnUiThread
                }
                Logger.d {
                    "NovelWebViewViewer: active chapter changed $currentId -> $newChapterId"
                }

                activity.viewModel.setNovelVisibleChapter(newChapter)
                (newChapter.pages?.firstOrNull() as? ReaderPage)?.let { page ->
                    currentPage = page
                    activity.onPageSelected(page, false)
                }
                lastSavedProgress = 0f
                activity.onNovelProgressChanged(0f)
                updateChapterMetaJs()
            }
        }

        @JavascriptInterface
        fun retryFailedChapter() {
            activity.runOnUiThread {
                val ctx = lastErrorRetryContext ?: return@runOnUiThread
                val (chapter, page) = ctx
                val loader = chapter.pageLoader ?: return@runOnUiThread
                lastErrorRetryContext = null
                showLoadingIndicator()
                loader.retryPage(page)
                scope.launch(Dispatchers.IO) { loader.loadPage(page) }
            }
        }

        @JavascriptInterface
        fun startTtsAtParagraph(chapterIdJs: String, paragraphIndex: Int) {
            activity.runOnUiThread {
                val chapterId = chapterIdJs.toLongOrNull()
                if (chapterId == null) {
                    Logger.w { "NovelWebViewViewer: startTtsAtParagraph rejected — bad chapterId '$chapterIdJs'" }
                    return@runOnUiThread
                }
                Logger.d {
                    "NovelWebViewViewer: startTtsAtParagraph(chapterId=$chapterId, paragraph=$paragraphIndex)"
                }
                activity.ttsController?.dispatch(
                    hayai.novel.reader.tts.TtsCommand.StartAt(
                        chapterId = chapterId,
                        paragraphIndex = paragraphIndex.coerceAtLeast(0),
                    ),
                )
            }
        }

        @JavascriptInterface
        fun loadNextChapter() {
            activity.runOnUiThread {
                Logger.d {
                    "NovelWebViewViewer: loadNextChapter triggered, isLoadingNext=$isLoadingNext, loadedCount=${loadedChapterIds.size}"
                }
                if (!isLoadingNext) {
                    isLoadingNext = true
                    scope.launch {
                        try {
                            appendNextChapterIfAvailable()
                        } finally {
                            isLoadingNext = false
                            setJsLoadingNext(false)
                        }
                    }
                } else {
                    Logger.w { "NovelWebViewViewer: loadNextChapter ignored (already loading)" }
                }
            }
        }

        /**
         * Symmetric counterpart of [loadNextChapter]. The JS scroll handler fires this
         * when the user scrolls up to within `prevLoadOffset` pixels of the topmost
         * loaded chapter's start. We immediately reset the JS flag if there's no prev
         * chapter to load — otherwise the JS would stay "loading" forever and never
         * retry on subsequent scroll events.
         */
        @JavascriptInterface
        fun loadPrevChapter() {
            activity.runOnUiThread {
                Logger.d {
                    "NovelWebViewViewer: loadPrevChapter triggered, isLoadingPrev=$isLoadingPrev, loadedCount=${loadedChapterIds.size}"
                }
                if (isLoadingPrev) {
                    Logger.w { "NovelWebViewViewer: loadPrevChapter ignored (already loading)" }
                    return@runOnUiThread
                }
                isLoadingPrev = true
                scope.launch {
                    try {
                        prependPreviousChapterIfAvailable()
                    } finally {
                        isLoadingPrev = false
                        setJsLoadingPrev(false)
                    }
                }
            }
        }
    }

    private fun setJsLoadingNext(isLoading: Boolean) {
        val flag = if (isLoading) "true" else "false"
        evaluateJavascriptSafe(
            "(function(){ if (window.__tsundokuSetLoadingNext) window.__tsundokuSetLoadingNext($flag); })();",
            null,
        )
    }

    private fun setJsLoadingPrev(isLoading: Boolean) {
        val flag = if (isLoading) "true" else "false"
        evaluateJavascriptSafe(
            "(function(){ if (window.__tsundokuSetLoadingPrev) window.__tsundokuSetLoadingPrev($flag); })();",
            null,
        )
    }

    private suspend fun awaitPageText(page: ReaderPage, loader: PageLoader, timeoutMs: Long): Boolean =
        NovelViewerTextUtils.awaitPageText("NovelWebViewViewer", page, loader, timeoutMs, scope)

    private suspend fun displayContentImmediate(
        chapter: ReaderChapter,
        page: ReaderPage,
        isAppendOrPrepend: Boolean,
        isPrepend: Boolean,
    ) {
        if (isDestroyed) return

        var content = page.text
        if (content.isNullOrBlank()) {
            lastErrorRetryContext = chapter to page
            displayError(Exception(activity.getString(MR.strings.novel_error_no_text)))
            return
        }

        val chapterId = chapter.chapter.id ?: return

        if (preferences.novelHideChapterTitle.get()) {
            content = stripChapterTitle(content, chapter.chapter.name)
        }

        // Optionally force lowercase
        if (preferences.novelForceTextLowercase.get()) {
            content = content.lowercase()
        }

        val processedContent = activity.translateContentIfEnabled(content)
        val plainTextMode = NovelViewerTextUtils.isPlainTextChapter(chapter.chapter.url)
        val renderableContent = if (plainTextMode) {
            NovelViewerTextUtils.normalizePlainTextContent(processedContent)
        } else {
            NovelViewerTextUtils.normalizeContentForHtml(
                processedContent,
                chapter.chapter.url,
            )
        }

        withContext(Dispatchers.Main) {
            if (isDestroyed) return@withContext
            if (isAppendOrPrepend && loadedChapterIds.contains(chapterId)) {
                Logger.d { "NovelWebViewViewer: Chapter $chapterId already loaded during suspension, skipping displayContentImmediate" }
                return@withContext
            }

            if (isAppendOrPrepend) {
                if (isPrepend) {
                    // Backward prepend is disabled in this code path; keep behavior forward-only.
                    return@withContext
                }
                // Snapshot the previous tail BEFORE the add so the transition card binds the
                // correct from/to pair (see displayContent for the same pattern + rationale).
                val fromChapter = loadedChapters.lastOrNull()
                if (!loadedChapterIds.contains(chapterId)) {
                    loadedChapterIds.add(chapterId)
                    loadedChapters.add(chapter)
                }
                appendHtmlContent(renderableContent, chapterId, chapter, fromChapter)
            } else {
                loadHtmlContent(renderableContent, chapterId, chapter.chapter.name, chapter.chapter.url)
                loadedChapterIds.clear()
                loadedChapters.clear()
                loadedChapterIds.add(chapterId)
                loadedChapters.add(chapter)
            }
        }
    }

    /**
     * Reason-aware boundary message for the infinite-scroll inline notice. Resolves WHY the
     * next/prev chapter is absent relative to [anchor] (read-skipped vs filtered-skipped vs
     * genuine end) so the user isn't told "no next chapter" when chapters were merely skipped.
     */
    private fun messageForSkippedAdjacent(next: Boolean, anchor: ReaderChapter): String {
        return when (val result = activity.viewModel.adjacentChapterResult(next, anchor.chapter.id)) {
            is ReaderViewModel.AdjacentChapterResult.SkippedRead ->
                activity.getString(MR.plurals.skipped_read_chapters, result.count, result.count)
            is ReaderViewModel.AdjacentChapterResult.SkippedFiltered ->
                activity.getString(MR.plurals.skipped_filtered_chapters, result.count, result.count)
            // Found (race) and EndOfList both mean nothing more to splice here.
            else -> activity.getString(MR.strings.novel_no_more_chapters)
        }
    }

    private suspend fun appendNextChapterIfAvailable() {
        val anchor = loadedChapters.lastOrNull() ?: currentChapters?.currChapter ?: run {
            Logger.e {
                "NovelWebViewViewer: appendNext failed, no anchor chapter (loadedCount=${loadedChapters.size})"
            }
            showInlineError("No anchor chapter for infinite scroll", isPrepend = false)
            return
        }
        Logger.d {
            "NovelWebViewViewer: appendNext starting from anchor=${anchor.chapter.id}/${anchor.chapter.name}"
        }

        val preparedChapter = activity.viewModel.prepareNextChapterForInfiniteScroll(anchor) ?: run {
            Logger.w { "NovelWebViewViewer: No next chapter available after ${anchor.chapter.name}" }
            showInlineError(messageForSkippedAdjacent(next = true, anchor = anchor), isPrepend = false)
            return
        }
        val nextId = preparedChapter.chapter.id ?: return
        Logger.d { "NovelWebViewViewer: prepared next=$nextId/${preparedChapter.chapter.name}" }

        if (loadedChapterIds.contains(nextId)) {
            Logger.d { "NovelWebViewViewer: next chapter $nextId already loaded, skipping" }
            return
        }

        val page = preparedChapter.pages?.firstOrNull() as? ReaderPage ?: run {
            Logger.e { "NovelWebViewViewer: No page in prepared next chapter" }
            showInlineError("No page in next chapter", isPrepend = false)
            return
        }
        val loader = page.chapter.pageLoader ?: run {
            Logger.e { "NovelWebViewViewer: No page loader for next chapter" }
            showInlineError("No loader for next chapter", isPrepend = false)
            return
        }

        showInlineLoading(isPrepend = false)
        try {
            Logger.d {
                "NovelWebViewViewer: loading page for next chapter $nextId, state=${page.status}"
            }
            val loaded = try {
                awaitPageText(page = page, loader = loader, timeoutMs = 30_000)
            } catch (e: TimeoutCancellationException) {
                Logger.e { "NovelWebViewViewer: Timed out loading next chapter page after 30s" }
                showInlineError("Timeout loading next chapter", isPrepend = false)
                false
            } catch (e: CancellationException) {
                Logger.d { "NovelWebViewViewer: appendNext cancelled" }
                false
            } catch (e: Exception) {
                Logger.e { "NovelWebViewViewer: Error loading next chapter page: ${e.message}" }
                showInlineError("Error: ${e.message ?: "Unknown error"}", isPrepend = false)
                false
            }

            if (!loaded) return

            Logger.d { "NovelWebViewViewer: appending content for chapter $nextId" }
            displayContentImmediate(preparedChapter, page, isAppendOrPrepend = true, isPrepend = false)
            Logger.i {
                "NovelWebViewViewer: Successfully appended next chapter ${preparedChapter.chapter.name}"
            }
        } finally {
            hideInlineLoading(isPrepend = false)
            setJsLoadingNext(false)
        }
    }

    private suspend fun prependPreviousChapterIfAvailable() {
        val anchor = loadedChapters.firstOrNull() ?: currentChapters?.currChapter ?: return
        val preparedChapter = activity.viewModel.preparePreviousChapterForInfiniteScroll(anchor) ?: run {
            showInlineError(messageForSkippedAdjacent(next = false, anchor = anchor), isPrepend = true)
            return
        }
        val prevId = preparedChapter.chapter.id ?: return
        if (loadedChapterIds.contains(prevId)) return

        val page = preparedChapter.pages?.firstOrNull() as? ReaderPage ?: return
        val loader = page.chapter.pageLoader ?: return

        showInlineLoading(isPrepend = true)
        try {
            val loaded = awaitPageText(page, loader, 30_000)

            if (!loaded) {
                Logger.e { "NovelWebViewViewer: Failed to load previous chapter page" }
                return
            }

            withContext(Dispatchers.Main) {
                displayContent(preparedChapter, page, isAppendOrPrepend = true, isPrepend = true)
                // Remove the static top card now that the real previous chapter is loaded;
                // prependHtmlContent already inserted its own transition card above the
                // newly-prepended content, so leaving the static card would leave two
                // stacked Prev/Current cards on the page. Scroll-position adjustment
                // mirrors prependHtmlContent's: we measure the height shrink and pull
                // the user's scroll up by that amount so they stay anchored.
                evaluateJavascriptSafe(
                    """
                    (function() {
                        var card = document.querySelector('.initial-prev-card');
                        if (!card) return;
                        var oldHeight = document.body.scrollHeight;
                        var oldScrollY = window.scrollY || window.pageYOffset;
                        card.parentNode.removeChild(card);
                        setTimeout(function() {
                            var newHeight = document.body.scrollHeight;
                            var diff = oldHeight - newHeight;
                            if (diff > 0) {
                                window.scrollTo(0, Math.max(0, oldScrollY - diff));
                            }
                            if (typeof window.updateChapterBoundaries === 'function') {
                                window.updateChapterBoundaries();
                            }
                        }, 10);
                    })();
                    """.trimIndent(),
                    null,
                )
            }
        } finally {
            hideInlineLoading(isPrepend = true)
        }
    }

    /**
     * Scroll to the top of the content
     */
    fun scrollToTop() {
        webView.scrollTo(0, 0)
    }

    /**
     * Toggle auto-scroll for the WebView. Phase B #6: backed by a JS requestAnimationFrame
     * loop running inside the WebView (see [installAutoScrollScript]). Kotlin only flips
     * the on/off + speed globals via evaluateJavascript so the actual scrolling never
     * crosses the JS bridge mid-frame.
     */
    fun toggleAutoScroll() {
        if (isAutoScrolling) stopAutoScroll() else startAutoScroll()
    }

    private fun startAutoScroll() {
        if (isAutoScrolling) return
        isAutoScrolling = true
        isAutoScrollPaused = false
        // The rAF script is injected at page load (see installAutoScrollScript). The
        // setter is idempotent and will (re-)kick the loop with the current speed.
        val speed = preferences.novelAutoScrollSpeed.get().coerceIn(1, 50)
        evaluateJavascriptSafe(
            "if(window.__hayaiSetAutoScroll){window.__hayaiSetAutoScroll(true, $speed);}",
        )
    }

    private fun stopAutoScroll() {
        if (!isAutoScrolling) return
        isAutoScrolling = false
        isAutoScrollPaused = false
        evaluateJavascriptSafe(
            "if(window.__hayaiSetAutoScroll){window.__hayaiSetAutoScroll(false, 0);}",
        )
    }

    private fun pauseAutoScroll() {
        if (!isAutoScrolling) return
        isAutoScrollPaused = true
        evaluateJavascriptSafe(
            "if(window.__hayaiSetAutoScroll){window.__hayaiSetAutoScroll(false, 0);}",
        )
    }

    private fun resumeAutoScroll() {
        if (!isAutoScrolling) return
        if (!isAutoScrollPaused) return
        isAutoScrollPaused = false
        val speed = preferences.novelAutoScrollSpeed.get().coerceIn(1, 50)
        evaluateJavascriptSafe(
            "if(window.__hayaiSetAutoScroll){window.__hayaiSetAutoScroll(true, $speed);}",
        )
    }

    /**
     * Inject the auto-scroll rAF loop into the WebView. Idempotent — re-running just
     * leaves the existing state object in place. Called at page load via
     * [injectCustomScript] so the loop is always available before [startAutoScroll]
     * flips its on-flag.
     */
    private fun installAutoScrollScript() {
        evaluateJavascriptSafe(
            """
            (function(){
                window.__hayaiAutoScroll = window.__hayaiAutoScroll || { running:false, speed:15, selPaused:false };
                if (window.__hayaiAutoStep) return;
                // True while a non-collapsed selection exists: the WebView re-lays out the
                // selection handles + highlight rect on every scrolled frame, so stepping
                // during a selection is the source of the reported lag.
                window.__hayaiHasSelection = function(){
                    var sel = window.getSelection();
                    return !!(sel && sel.rangeCount > 0 && !sel.isCollapsed && sel.toString().length > 0);
                };
                window.__hayaiAutoStep = function(ts){
                    var s = window.__hayaiAutoScroll;
                    if (!s || !s.running) return;
                    // Gate on selection state, not just touch state: a finished selection
                    // leaves no finger down, so without this the loop runs concurrently with
                    // an active selection. Keep the rAF alive (reset timing) so scrolling
                    // resumes seamlessly once the selection clears.
                    if (window.__hayaiHasSelection()) {
                        s.last = 0;
                        requestAnimationFrame(window.__hayaiAutoStep);
                        return;
                    }
                    if (!s.last) s.last = ts;
                    var dt = Math.min(0.05, (ts - s.last) / 1000);
                    s.last = ts;
                    window.scrollBy(0, s.speed * 30 * dt);
                    requestAnimationFrame(window.__hayaiAutoStep);
                };
                window.__hayaiSetAutoScroll = function(on, speed){
                    var s = window.__hayaiAutoScroll;
                    s.speed = Math.max(1, Math.min(50, speed|0));
                    if (on && !s.running) {
                        s.running = true;
                        s.last = 0;
                        requestAnimationFrame(window.__hayaiAutoStep);
                    }
                    if (!on) { s.running = false; }
                };
                // selectionchange drives selPaused so selection state (not only touch state)
                // controls the loop. The step's own guard already early-returns; this listener
                // resets timing on selection end so the next stepped frame starts cleanly.
                if (!window.__hayaiSelListener) {
                    window.__hayaiSelListener = function(){
                        var s = window.__hayaiAutoScroll;
                        if (!s) return;
                        var has = window.__hayaiHasSelection();
                        s.selPaused = has;
                        if (!has) s.last = 0;
                    };
                    document.addEventListener('selectionchange', window.__hayaiSelListener);
                }
            })();
            """.trimIndent(),
        )
    }

    /**
     * Check if auto-scroll is currently active
     */
    fun isAutoScrollActive(): Boolean = isAutoScrolling

    /**
     * Gets the current scroll progress as percentage (0 to 100)
     */
    fun getProgressPercent(): Int {
        // Return last saved progress since WebView scroll can't be accessed synchronously
        return (lastSavedProgress * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Sets the scroll position by progress percentage (0 to 100)
     */
    fun setProgressPercent(percent: Int) {
        val progress = percent.coerceIn(0, 100)
        // Update local state immediately for consistent UI
        lastSavedProgress = progress / 100f

        evaluateJavascriptSafe(
            """
            (function() {
                var scrollHeight = document.documentElement.scrollHeight - window.innerHeight;
                var targetScroll = scrollHeight * $progress / 100;
                window.scrollTo(0, targetScroll);
                // Update the tracking variable to prevent immediate re-report
                if (typeof lastProgress !== 'undefined') {
                    lastProgress = $progress / 100.0;
                }
            })();
            """.trimIndent(),
            null,
        )
    }

    /**
     * Reload the current chapter
     */
    fun reloadChapter() {
        currentChapters?.let { setChapters(it) }
    }

    // -------------------------------------------------------------------- TTS public API
    //
    // Every method below is a thin delegate onto [hayai.novel.reader.tts.NovelTtsController],
    // owned by [hayai.novel.reader.service.NovelTtsPlaybackService] and bound to this
    // activity via `ReaderActivity.ttsController`. The controller owns the TextToSpeech
    // instance, the utterance queue, audio focus, paragraph progress persistence — every
    // piece of state the old in-viewer engine kept volatile-but-unsynchronised. The viewer
    // only contributes what is genuinely viewer-shaped: the tagged paragraph list, the
    // highlight paint, and the viewport / current-chapter probe.

    private val ttsController get() = activity.ttsController
    private val ttsState get() = activity.ttsController?.state?.value

    fun startTts() {
        ttsController?.dispatch(hayai.novel.reader.tts.TtsCommand.Play)
        refreshSentenceTapToTtsState()
    }

    fun stopTts() {
        ttsController?.dispatch(hayai.novel.reader.tts.TtsCommand.Stop)
        clearHighlight()
        refreshSentenceTapToTtsState()
    }

    fun pauseTts() {
        ttsController?.dispatch(hayai.novel.reader.tts.TtsCommand.Pause)
        refreshSentenceTapToTtsState()
    }

    fun resumeTts() {
        ttsController?.dispatch(hayai.novel.reader.tts.TtsCommand.Resume)
        refreshSentenceTapToTtsState()
    }

    fun isTtsPaused(): Boolean = ttsState is hayai.novel.reader.tts.TtsState.Paused

    fun isTtsSpeaking(): Boolean = ttsState is hayai.novel.reader.tts.TtsState.Speaking

    /** TTS is bootstrapping but not yet emitting audio. */
    fun isTtsStarting(): Boolean {
        val s = ttsState
        return s is hayai.novel.reader.tts.TtsState.Preparing ||
            s is hayai.novel.reader.tts.TtsState.AdvancingChapter
    }

    fun getTtsProgressPercent(): Int {
        val s = ttsState ?: return 0
        val (idx, total) = when (s) {
            is hayai.novel.reader.tts.TtsState.Speaking -> s.paragraphIndex to s.totalParagraphs
            is hayai.novel.reader.tts.TtsState.Paused -> s.paragraphIndex to s.totalParagraphs
            else -> return 0
        }
        if (total <= 0) return 0
        return (((idx + 1) * 100f) / total).toInt().coerceIn(0, 100)
    }

    /** Starts TTS from the first visible paragraph in the viewport. */
    fun startTtsFromViewport() {
        ttsController?.dispatch(hayai.novel.reader.tts.TtsCommand.Play)
        refreshSentenceTapToTtsState()
    }

    /** Starts TTS at exactly `paragraphIndex` within `chapterId`. */
    fun startTtsFromChapterParagraph(chapterId: Long, paragraphIndex: Int) {
        ttsController?.dispatch(
            hayai.novel.reader.tts.TtsCommand.StartAt(
                chapterId = chapterId,
                paragraphIndex = paragraphIndex.coerceAtLeast(0),
            ),
        )
        refreshSentenceTapToTtsState()
    }

    /** Infers chapter from the visible page, then delegates to [startTtsFromChapterParagraph]. */
    fun startTtsFromParagraph(paragraphIndex: Int) {
        val chapterId = currentPage?.chapter?.chapter?.id ?: return
        startTtsFromChapterParagraph(chapterId, paragraphIndex)
    }

    // ----------------------------------------------------------------- TtsSource overrides

    override suspend fun awaitChapterParagraphs(chapterId: Long): List<String>? {
        chapterParagraphsById[chapterId]?.let { return it }
        // Wait briefly in case a chapter-load fired by `awaitNextChapterReady` is still
        // mid-flight when the controller asks for the paragraphs. The chapter-ready flow
        // emits as soon as the tagged list is stashed; a longer wait here would mean the
        // caller already gave up and we'd be racing against a stop command anyway.
        return kotlinx.coroutines.withTimeoutOrNull(5_000) {
            chapterParagraphReadyFlow
                .filter { it == chapterId }
                .map { chapterParagraphsById[chapterId] ?: emptyList() }
                .first()
        }
    }

    override suspend fun awaitNextChapterReady(): Long? {
        val nextId = currentChapters?.nextChapter?.chapter?.id ?: run {
            Logger.d { "TTS: awaitNextChapterReady — no next chapter (end of novel)" }
            return null
        }
        // Infinite-scroll may have already preloaded and tagged the next chapter; if so,
        // the tagged paragraph list is already present and we can return immediately
        // without going through the activity's chapter-load path again.
        if (chapterParagraphsById.containsKey(nextId)) return nextId
        activity.loadNextChapter()
        return kotlinx.coroutines.withTimeoutOrNull(15_000) {
            chapterParagraphReadyFlow.first { it == nextId }
            nextId
        }
    }

    override suspend fun awaitPreviousChapterReady(): Long? {
        val prevId = currentChapters?.prevChapter?.chapter?.id ?: run {
            Logger.d { "TTS: awaitPreviousChapterReady — no previous chapter" }
            return null
        }
        if (chapterParagraphsById.containsKey(prevId)) return prevId
        activity.loadPreviousChapter()
        return kotlinx.coroutines.withTimeoutOrNull(15_000) {
            chapterParagraphReadyFlow.first { it == prevId }
            prevId
        }
    }

    override fun currentVisibleChapterId(): Long? = currentPage?.chapter?.chapter?.id

    override suspend fun currentVisibleParagraphIndex(): Int? {
        val chapterId = currentPage?.chapter?.chapter?.id ?: return null
        val deferred = kotlinx.coroutines.CompletableDeferred<Int?>()
        activity.runOnUiThread {
            evaluateJavascriptSafe(
                """
                (function() {
                    var selectors = 'p, li, blockquote, h1, h2, h3, h4, h5, h6, pre';
                    var container = document.querySelector('.chapter-content[data-chapter-id="' + $chapterId + '"]');
                    if (!container) return 0;
                    var elements = Array.from(container.querySelectorAll(selectors)).filter(function(el) {
                        return !!el && el.hasAttribute('data-paragraph-index') && !!el.innerText && el.innerText.trim().length > 0;
                    });
                    var viewportHeight = window.innerHeight || document.documentElement.clientHeight;
                    for (var i = 0; i < elements.length; i++) {
                        var rect = elements[i].getBoundingClientRect();
                        if (rect.bottom > 0 && rect.top < viewportHeight) {
                            var attr = elements[i].getAttribute('data-paragraph-index');
                            return attr ? parseInt(attr, 10) : 0;
                        }
                    }
                    return 0;
                })();
                """.trimIndent(),
            ) { raw ->
                val parsed = raw?.trim('"')?.toIntOrNull()
                deferred.complete(parsed)
            }
        }
        return try {
            kotlinx.coroutines.withTimeoutOrNull(2_000) { deferred.await() }
        } catch (_: Exception) {
            null
        }
    }

    override fun chapterTitle(chapterId: Long): String? =
        loadedChapters.firstOrNull { it.chapter.id == chapterId }?.chapter?.name
            ?: currentChapters?.currChapter?.chapter?.takeIf { it.id == chapterId }?.name

    override fun novelTitle(): String? = activity.viewModel.manga?.title

    /**
     * Get the currently selected text from the WebView
     */
    fun getSelectedText(): String? {
        var selectedText: String? = null
        evaluateJavascriptSafe(
            """
            (function() {
                var selection = window.getSelection();
                if (selection && selection.toString().trim()) {
                    return selection.toString().trim();
                }
                return null;
            })();
            """.trimIndent(),
        ) { result ->
            // JavaScript returns quoted string, need to unquote and unescape
            selectedText = result?.let {
                if (it.startsWith("\"") && it.endsWith("\"")) {
                    it.substring(1, it.length - 1)
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                } else {
                    it
                }
            }
        }
        return selectedText
    }

    /**
     * Get the current chapter name for quote context
     */
    fun getCurrentChapterName(): String? = currentChapters?.currChapter?.chapter?.name

    /**
     * Check if text is currently selected in the WebView
     */
    fun hasTextSelection(): Boolean {
        var hasSelection = false
        evaluateJavascriptSafe(
            """
            (function() {
                var selection = window.getSelection();
                return selection && selection.toString().trim().length > 0;
            })();
            """.trimIndent(),
        ) { result ->
            hasSelection = result == "true"
        }
        return hasSelection
    }

    /**
     * Clear text selection in the WebView
     */
    fun clearTextSelection() {
        evaluateJavascriptSafe(
            """
            (function() {
                var selection = window.getSelection();
                if (selection) {
                    selection.removeAllRanges();
                }
            })();
            """.trimIndent(),
            null,
        )
    }

    /**
     * Handle the "Remember" action from text selection menu
     */
    private fun onRememberSelectedText(actionMode: ActionMode? = null) {
        evaluateJavascriptSafe(
            """
        (function() {
            var selection = window.getSelection();
            if (selection && selection.toString().trim()) {
                return selection.toString().trim();
            }
            return null;
        })();
            """.trimIndent(),
        ) { result ->
            activity.runOnUiThread {
                actionMode?.finish() // finish AFTER JS has read the selection
                val selectedText = if (result != null && result != "null" &&
                    result.startsWith("\"") && result.endsWith("\"")
                ) {
                    result.substring(1, result.length - 1)
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                } else {
                    null
                }

                if (!selectedText.isNullOrBlank()) {
                    pendingSelectedText = selectedText
                    val manga = activity.viewModel.manga
                    val novelId = manga?.id
                    val chapterName = getCurrentChapterName()
                    if (novelId != null && chapterName != null) {
                        val quote = hayai.novel.reader.quote.Quote.create(
                            novelName = manga.title,
                            chapterName = chapterName,
                            content = selectedText,
                        )
                        hayai.novel.reader.quote.QuoteManager(activity).addQuote(novelId, quote)
                        activity.toast(activity.getString(MR.strings.novel_quote_saved))
                    }
                    clearTextSelection()
                } else {
                    activity.toast(activity.getString(MR.strings.novel_quote_no_selection))
                }
            }
        }
    }
    companion object {
        private const val REMEMBER_MENU_ITEM_ID = 0xBEEF // arbitrary unique ID
        // Phase B #15 — second action-mode menu item we own (Define). Must not collide
        // with REMEMBER_MENU_ITEM_ID or any default Android selection IDs.
        private const val DEFINE_MENU_ITEM_ID = 0xBEF0
        private const val TRANSLATE_MENU_ITEM_ID = 0xBEF1
        private const val WEB_SEARCH_MENU_ITEM_ID = 0xBEF2
        // Auto-load the next chapter once the user has scrolled past this fraction of the current one.
        private const val AUTO_LOAD_NEXT_THRESHOLD = 0.95
        // Force-dismiss the loading overlay after this long; protects against onPageFinished
        // never firing (network failure, JS bridge crash, blank page).
        private const val LOADING_TIMEOUT_MS = 15_000L

        // Inline SVGs sized via the surrounding CSS rule (see .ch-trans-* selectors).
        // `currentColor` lets each icon inherit the active novel theme's foreground so
        // the transition card automatically themes itself without per-theme overrides.
        // Paths are condensed Material Symbols outlines (Cloud, Info, Warning) to keep
        // the embedded HTML small.
        private const val CLOUD_ICON_SVG =
            """<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" fill="currentColor"><path d="M19.35 10.04A7.49 7.49 0 0 0 12 4a7.5 7.5 0 0 0-6.98 4.78A5.5 5.5 0 0 0 6 19.5h13a4.5 4.5 0 0 0 .35-9.46zM19 17.5H6a3.5 3.5 0 0 1-.45-6.97l1.16-.15.43-1.09A5.5 5.5 0 0 1 17.5 11.5l.27 1.5h1.23a2.5 2.5 0 0 1 0 5z"/></svg>"""
        // Filled CheckCircle (Material Symbols), used for the row whose chapter is locally
        // downloaded — same role as Icons.Filled.CheckCircle in the manga ChapterTransition.
        private const val CHECK_CIRCLE_ICON_SVG =
            """<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" fill="currentColor"><path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm-1.41 14.59L5 11l1.41-1.41 4.18 4.17 7-7L19 8.18l-8.41 8.41z"/></svg>"""
        private const val INFO_ICON_SVG =
            """<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" fill="currentColor"><path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16zm-1-13h2v2h-2V7zm0 4h2v6h-2v-6z"/></svg>"""
        private const val WARNING_ICON_SVG =
            """<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" fill="currentColor"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg>"""
    }
}
