package dev.ahmedmohamed.hayai.novel.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import dev.ahmedmohamed.hayai.novel.download.NovelDownloadStore
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.math.abs

class NovelReaderActivity :
    AppCompatActivity(),
    NovelTtsController.Callbacks {
    private val preferences by lazy { HayaiPreferences(Injekt.get<PreferenceStore>()) }
    private val json = Json { ignoreUnknownKeys = true }
    private val session by lazy {
        NovelReaderSession(
            Injekt.get<DatabaseHelper>(),
            Injekt.get<SourceManager>(),
            NovelDownloadStore(File(filesDir, "hayai/novel-downloads")),
            Injekt.get<NetworkHelper>(),
        )
    }
    private val contentProcessor = NovelContentProcessor()
    private lateinit var webView: WebView
    private lateinit var titleView: TextView
    private lateinit var chapterView: TextView
    private lateinit var loading: ProgressBar
    private lateinit var progressSlider: SeekBar
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var playButton: Button
    private lateinit var ttsController: NovelTtsController
    private var loaded: LoadedNovelChapter? = null
    private var loadJob: Job? = null
    private var currentProgress = 0
    private var programmaticProgress = false
    private var autoLoadArmed = true
    private var ttsAutoStartPending = false
    private var autoScroll = false
    private var autoScrollRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ttsController = NovelTtsController(this, this)
        setContentView(createContentView())
        configureWindow()

        val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1L)
        val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L)
        if (mangaId < 0 || chapterId < 0) {
            showError("The novel chapter could not be opened.")
            return
        }
        loadJob =
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { session.initialize(mangaId, chapterId) } }
                result.fold(::showChapter) { showError(it.message ?: "The novel chapter could not be loaded.") }
            }
    }

    override fun onPause() {
        saveProgressBlocking()
        if (!preferences.novelTtsBackgroundPlayback.get()) ttsController.pause()
        super.onPause()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        stopAutoScroll()
        ttsController.destroy()
        webView.removeJavascriptInterface(JS_INTERFACE)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean {
        if (preferences.novelVolumeKeysScroll.get() && keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) -1 else 1
            webView.scrollBy(0, direction * (webView.height * 0.85f).toInt())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showChapter(chapter: LoadedNovelChapter) {
        loaded = chapter
        currentProgress = chapter.chapter.last_page_read.coerceIn(0, 100)
        autoLoadArmed = true
        loading.visibility = View.VISIBLE
        titleView.text = chapter.manga.title
        chapterView.text =
            buildString {
                append(chapter.chapter.name, "  •  ", chapter.position + 1, "/", chapter.total)
                if (chapter.isDownloaded) append("  •  Offline")
            }
        previousButton.isEnabled = chapter.hasPrevious
        nextButton.isEnabled = chapter.hasNext

        val options = contentOptions()
        val processed = contentProcessor.process(chapter.document, chapter.chapter.name, options)
        val html = NovelHtmlDocumentBuilder.build(processed, chapter.chapter.name, readerStyle(options))
        webView.webViewClient =
            NovelAssetWebViewClient(
                assetProvider = session,
                chapterUrl = { loaded?.chapter?.url.orEmpty() },
                offline = { loaded?.let { session.isOffline(it.chapter.url) } == true },
                blockMedia = { preferences.novelBlockMedia.get() },
            )
        val baseUrl = processed.baseUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        updateProgressSlider(currentProgress)
        configureWindow()
    }

    private fun contentOptions() =
        NovelContentOptions(
            hideChapterTitle = preferences.novelHideChapterTitle.get(),
            forceLowercase = preferences.novelForceTextLowercase.get(),
            blockMedia = preferences.novelBlockMedia.get(),
            keepEmbeddedCss = preferences.novelEnableEpubStyles.get(),
            keepEmbeddedJs = preferences.novelEnableEpubJs.get(),
            showRawHtml = preferences.novelShowRawHtml.get(),
            autoSplitText = preferences.novelAutoSplitText.get(),
            autoSplitWordCount = preferences.novelAutoSplitWordCount.get(),
            regexReplacements = preferences.novelRegexReplacements.get(),
        )

    private fun readerStyle(options: NovelContentOptions): NovelReaderStyle {
        val dark = resources.configuration.uiMode and 0x30 == 0x20
        val defaultBackground = if (dark) 0xFF121212.toInt() else 0xFFFFFBFE.toInt()
        val defaultText = if (dark) 0xFFE6E1E5.toInt() else 0xFF1C1B1F.toInt()
        return NovelReaderStyle(
            fontSize = preferences.novelFontSize.get(),
            fontFamily = preferences.novelFontFamily.get(),
            lineHeight = preferences.novelLineHeight.get(),
            textAlign = preferences.novelTextAlign.get(),
            textColor = preferences.novelFontColor.get().takeUnless { it == 0 } ?: defaultText,
            backgroundColor = preferences.novelBackgroundColor.get().takeUnless { it == 0 } ?: defaultBackground,
            linkColor = if (dark) 0xFFD0BCFF.toInt() else 0xFF6750A4.toInt(),
            paragraphIndent = preferences.novelParagraphIndent.get(),
            paragraphSpacing = preferences.novelParagraphSpacing.get(),
            marginLeft = preferences.novelMarginLeft.get(),
            marginRight = preferences.novelMarginRight.get(),
            marginTop = preferences.novelMarginTop.get(),
            marginBottom = preferences.novelMarginBottom.get(),
            useOriginalFonts = preferences.novelUseOriginalFonts.get(),
            textSelectable = preferences.novelTextSelectable.get(),
            hideChapterTitle = options.hideChapterTitle,
            sourceCssPriority = preferences.novelSourceCssPriority.get(),
            customCss = preferences.novelCustomCss.get(),
            customJs = preferences.novelCustomJs.get(),
            ttsHighlightColor = preferences.novelTtsHighlightColor.get(),
            ttsHighlightTextColor = preferences.novelTtsHighlightTextColor.get(),
        )
    }

    private fun navigate(next: Boolean) {
        if (loadJob?.isActive == true) return
        val chapterToSave = loaded?.chapter
        val progressToSave = currentProgress
        ttsController.stop()
        stopAutoScroll()
        loading.visibility = View.VISIBLE
        loadJob =
            lifecycleScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            chapterToSave?.let { session.saveProgress(it, progressToSave, preferences.novelMarkAsReadThreshold.get()) }
                            if (next) session.next() else session.previous()
                        }
                    }
                result.fold(
                    onSuccess = { chapter -> chapter?.let(::showChapter) ?: toast(if (next) "No next chapter" else "No previous chapter") },
                    onFailure = { showError(it.message ?: "The chapter could not be loaded.") },
                )
            }
    }

    private fun saveProgress() {
        val chapter = loaded?.chapter ?: return
        val progress = currentProgress
        lifecycleScope.launch(Dispatchers.IO) {
            session.saveProgress(chapter, progress, preferences.novelMarkAsReadThreshold.get())
        }
    }

    private fun saveProgressBlocking() {
        val chapter = loaded?.chapter ?: return
        val progress = currentProgress
        runBlocking(Dispatchers.IO) {
            session.saveProgress(chapter, progress, preferences.novelMarkAsReadThreshold.get())
        }
    }

    private fun onReaderProgress(progress: Int) {
        currentProgress = progress.coerceIn(0, 100)
        updateProgressSlider(currentProgress)
        val threshold = preferences.novelAutoLoadNextChapterAt.get().coerceIn(1, 100)
        if (autoLoadArmed && preferences.novelInfiniteScroll.get() && currentProgress >= threshold && session.hasNext) {
            autoLoadArmed = false
            navigate(next = true)
        }
    }

    private fun updateProgressSlider(value: Int) {
        programmaticProgress = true
        progressSlider.progress = value
        programmaticProgress = false
    }

    private fun onDocumentReady() {
        loading.visibility = View.GONE
        val chapter = loaded ?: return
        val openingProgress = if (chapter.chapter.read && chapter.chapter.last_page_read >= 100) 0 else currentProgress
        webView.evaluateJavascript("window.hayaiReader.scrollToPercent($openingProgress)", null)
        extractTtsParagraphs(autoStart = ttsAutoStartPending)
        ttsAutoStartPending = false
        if (preferences.novelMarkShortChapterAsRead.get()) {
            webView.evaluateJavascript("document.documentElement.scrollHeight <= innerHeight") { short ->
                if (short == "true") {
                    currentProgress = 100
                    saveProgress()
                    updateProgressSlider(100)
                }
            }
        }
    }

    private fun extractTtsParagraphs(autoStart: Boolean = false) {
        webView.evaluateJavascript("JSON.stringify(window.hayaiReader.paragraphs())") { encoded ->
            val payload = runCatching { json.decodeFromString<String>(encoded) }.getOrNull() ?: return@evaluateJavascript
            val paragraphs = runCatching { json.decodeFromString<List<TtsParagraph>>(payload) }.getOrDefault(emptyList())
            ttsController.configure(preferences.novelTtsSpeed.get(), preferences.novelTtsPitch.get(), preferences.novelTtsVoice.get())
            ttsController.setParagraphs(paragraphs.map(TtsParagraph::text))
            if (autoStart) ttsController.play()
        }
    }

    private fun showReaderSettings() {
        val labels =
            arrayOf(
                "Smaller text",
                "Larger text",
                "Toggle alignment",
                "Toggle media",
                "Toggle auto-scroll",
                if (loaded?.isDownloaded == true) "Remove offline copy" else "Save chapter offline",
                "Reset chapter progress",
            )
        AlertDialog
            .Builder(this)
            .setTitle("Novel reader")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> preferences.novelFontSize.set((preferences.novelFontSize.get() - 1).coerceAtLeast(8))
                    1 -> preferences.novelFontSize.set((preferences.novelFontSize.get() + 1).coerceAtMost(72))
                    2 -> preferences.novelTextAlign.set(if (preferences.novelTextAlign.get() == "justify") "left" else "justify")
                    3 -> preferences.novelBlockMedia.set(!preferences.novelBlockMedia.get())
                    4 -> if (autoScroll) stopAutoScroll() else startAutoScroll()
                    5 -> toggleOfflineCopy()
                    6 -> {
                        currentProgress = 0
                        loaded?.chapter?.let(NovelProgress::reset)
                        saveProgress()
                    }
                }
                if (which !in setOf(4, 5)) loaded?.let(::showChapter)
            }.show()
    }

    private fun toggleOfflineCopy() {
        val chapter = loaded ?: return
        if (loadJob?.isActive == true) return
        val progress = currentProgress
        loadJob =
            lifecycleScope.launch {
                val removing = chapter.isDownloaded
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            session.saveProgress(chapter.chapter, progress, preferences.novelMarkAsReadThreshold.get())
                            val download =
                                if (removing) {
                                    session.removeOffline(chapter)
                                    null
                                } else {
                                    session.saveOffline(chapter)
                                }
                            val refreshed = session.reload()
                            check(refreshed.isDownloaded != removing) { "The offline copy could not be verified." }
                            download to refreshed
                        }
                    }
                result.fold(
                    onSuccess = { (download, refreshed) ->
                        showChapter(refreshed)
                        when {
                            removing -> toast("Offline copy removed")
                            download == null -> Unit
                            download.unavailableAssetCount > 0 ->
                                toast("Chapter saved; ${download.unavailableAssetCount} source assets were unavailable")
                            else -> toast("Chapter saved for offline reading")
                        }
                    },
                    onFailure = { toast(it.message ?: "The offline copy could not be changed") },
                )
            }
    }

    private fun startAutoScroll() {
        if (autoScroll) return
        autoScroll = true
        val delay = (110L - preferences.novelAutoScrollSpeed.get().coerceIn(1, 20) * 5L).coerceAtLeast(10L)
        autoScrollRunnable =
            object : Runnable {
                override fun run() {
                    if (!autoScroll || isFinishing) return
                    webView.scrollBy(0, 2)
                    webView.postDelayed(this, delay)
                }
            }.also(webView::post)
    }

    private fun stopAutoScroll() {
        autoScroll = false
        autoScrollRunnable?.let(webView::removeCallbacks)
        autoScrollRunnable = null
    }

    private fun configureWindow() {
        if (preferences.novelKeepScreenOn.get()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val brightness =
            if (preferences.novelCustomBrightness.get()) {
                preferences.novelCustomBrightnessValue
                    .get()
                    .coerceIn(-100, 100)
                    .let { (it + 100) / 200f }
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        window.attributes = window.attributes.apply { screenBrightness = brightness }
    }

    private fun showError(message: String) {
        loading.visibility = View.GONE
        chapterView.text = message
        toast(message)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createContentView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val density = resources.displayMetrics.density
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((8 * density).toInt())
            }
        titleView =
            TextView(this).apply {
                textSize = 16f
                maxLines = 1
            }
        chapterView =
            TextView(this).apply {
                textSize = 12f
                maxLines = 2
            }
        header.addView(titleView)
        header.addView(chapterView)
        root.addView(header)

        val content = FrameLayout(this)
        webView =
            WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                isVerticalScrollBarEnabled = preferences.novelVerticalScrollbar.get()
                addJavascriptInterface(ReaderBridge(), JS_INTERFACE)
                setOnTouchListener { _, event ->
                    gestureDetector.onTouchEvent(event)
                    false
                }
            }
        content.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        loading = ProgressBar(this).apply { isIndeterminate = true }
        content.addView(
            loading,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        progressSlider =
            SeekBar(this).apply {
                max = 100
                visibility = if (preferences.novelShowProgressSlider.get()) View.VISIBLE else View.GONE
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekBar: SeekBar?,
                            value: Int,
                            fromUser: Boolean,
                        ) {
                            if (fromUser && !programmaticProgress) currentProgress = value
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar?) {
                            webView.evaluateJavascript("window.hayaiReader.scrollToPercent($currentProgress)", null)
                            saveProgress()
                        }
                    },
                )
            }
        root.addView(progressSlider)

        val controls =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER
                orientation = LinearLayout.HORIZONTAL
            }
        previousButton = controlButton("‹") { navigate(next = false) }
        playButton =
            controlButton("Read") { if (ttsController.isPlaying) ttsController.pause() else extractTtsParagraphs(autoStart = true) }
        nextButton = controlButton("›") { navigate(next = true) }
        controls.addView(previousButton)
        controls.addView(controlButton("◀") { ttsController.previousParagraph() })
        controls.addView(playButton)
        controls.addView(controlButton("▶") { ttsController.nextParagraph() })
        controls.addView(nextButton)
        controls.addView(controlButton("Aa") { showReaderSettings() })
        root.addView(controls)
        return root
    }

    private fun controlButton(
        text: String,
        action: () -> Unit,
    ) = Button(this).apply {
        this.text = text
        minWidth = 0
        minimumWidth = 0
        setPadding(14, 0, 14, 0)
        setOnClickListener { action() }
    }

    private val gestureDetector by lazy {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    if (!preferences.novelTapToScroll.get()) return false
                    val direction = if (event.y < webView.height / 2f) -1 else 1
                    webView.scrollBy(0, direction * (webView.height * 0.85f).toInt())
                    return true
                }

                override fun onFling(
                    first: MotionEvent?,
                    second: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    first ?: return false
                    val horizontal = second.x - first.x
                    if (!preferences.novelSwipeNavigation.get() ||
                        abs(horizontal) < 120 * resources.displayMetrics.density ||
                        abs(velocityX) < abs(velocityY)
                    ) {
                        return false
                    }
                    navigate(next = horizontal < 0)
                    return true
                }
            },
        )
    }

    override fun onParagraphChanged(index: Int) {
        if (preferences.novelTtsEnableHighlight.get()) webView.evaluateJavascript("window.hayaiReader.highlight($index)", null)
    }

    override fun onHighlightCleared() {
        webView.evaluateJavascript("window.hayaiReader.clearHighlight()", null)
    }

    override fun onPlaybackChanged(playing: Boolean) {
        playButton.text = if (playing) "Pause" else "Read"
    }

    override fun onChapterCompleted() {
        currentProgress = 100
        saveProgress()
        if (preferences.novelTtsAutoNextChapter.get() && session.hasNext) {
            ttsAutoStartPending = true
            navigate(next = true)
        }
    }

    override fun onError(message: String) {
        toast(message)
    }

    override fun runOnUiThread(action: () -> Unit) {
        super.runOnUiThread(action)
    }

    private inner class ReaderBridge {
        @JavascriptInterface
        fun onProgress(progress: Int) = runOnUiThread { onReaderProgress(progress) }

        @JavascriptInterface
        fun onReady(progress: Int) = runOnUiThread { onDocumentReady() }
    }

    @Serializable
    private data class TtsParagraph(
        val index: Int,
        val text: String,
    )

    companion object {
        private const val EXTRA_MANGA_ID = "hayai.manga_id"
        private const val EXTRA_CHAPTER_ID = "hayai.chapter_id"
        private const val JS_INTERFACE = "HayaiReader"

        fun newIntent(
            context: Context,
            mangaId: Long,
            chapterId: Long,
        ): Intent =
            Intent(context, NovelReaderActivity::class.java).apply {
                putExtra(EXTRA_MANGA_ID, mangaId)
                putExtra(EXTRA_CHAPTER_ID, chapterId)
            }
    }
}
