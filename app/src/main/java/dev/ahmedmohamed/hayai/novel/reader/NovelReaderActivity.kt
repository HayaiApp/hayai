package dev.ahmedmohamed.hayai.novel.reader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.BatteryManager
import android.text.format.DateFormat
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import dev.ahmedmohamed.hayai.novel.download.NovelDownloadStore
import dev.ahmedmohamed.hayai.novel.dictionary.NovelDictionaryLauncher
import dev.ahmedmohamed.hayai.novel.dictionary.NovelDictionarySettingsStore
import dev.ahmedmohamed.hayai.novel.highlight.NovelHighlightAnchor
import dev.ahmedmohamed.hayai.novel.highlight.NovelHighlight
import dev.ahmedmohamed.hayai.novel.highlight.NovelHighlightStore
import dev.ahmedmohamed.hayai.novel.quote.NovelQuote
import dev.ahmedmohamed.hayai.novel.quote.NovelQuoteStore
import dev.ahmedmohamed.hayai.novel.quote.QuoteAddResult
import dev.ahmedmohamed.hayai.novel.settings.NovelCustomizationStore
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationCache
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationService
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationSettingsStore
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
import java.text.NumberFormat
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
    private val customization by lazy { NovelCustomizationStore(preferences) }
    private val quoteStore by lazy { NovelQuoteStore(Injekt.get<DatabaseHelper>()) }
    private val highlightStore by lazy { NovelHighlightStore(Injekt.get<DatabaseHelper>()) }
    private val translationSettings by lazy { NovelTranslationSettingsStore(this) }
    private val translationService by lazy { NovelTranslationService(Injekt.get<NetworkHelper>(), NovelTranslationCache(this)) }
    private val dictionary by lazy { NovelDictionaryLauncher(this) }
    private val dictionarySettings by lazy { NovelDictionarySettingsStore(this) }
    private lateinit var webView: WebView
    private lateinit var titleView: TextView
    private lateinit var chapterView: TextView
    private lateinit var statusView: TextView
    private lateinit var loading: ProgressBar
    private lateinit var progressSlider: SeekBar
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var playButton: Button
    private lateinit var ttsController: NovelTtsController
    private var loaded: LoadedNovelChapter? = null
    private var loadJob: Job? = null
    private var renderJob: Job? = null
    private var prefetchJob: Job? = null
    private var currentProgress = 0
    private var programmaticProgress = false
    private var autoLoadArmed = true
    private var ttsAutoStartPending = false
    private var autoScroll = false
    private var autoScrollRunnable: Runnable? = null
    private var statusRunnable: Runnable? = null

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
        renderJob?.cancel()
        prefetchJob?.cancel()
        stopAutoScroll()
        stopStatusUpdates()
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
            stepReader(direction)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showChapter(chapter: LoadedNovelChapter) {
        loaded = chapter
        currentProgress = chapter.chapter.last_page_read.coerceIn(0, 100)
        autoLoadArmed = true
        ttsAutoStartPending = ttsAutoStartPending || preferences.novelTtsAutoStartOnPanelOpen.get()
        loading.visibility = View.VISIBLE
        titleView.text = chapter.manga.title
        val displayedChapterTitle = displayedChapterTitle(chapter)
        chapterView.text =
            buildString {
                append(displayedChapterTitle, "  •  ", chapter.position + 1, "/", chapter.total)
                if (chapter.isDownloaded) append("  •  Offline")
                append(
                    "  •  ",
                    NumberFormat.getIntegerInstance().format(chapter.statistics.wordCount),
                    " words  •  ",
                    chapter.statistics.estimatedMinutes(),
                    " min",
                )
            }
        previousButton.isEnabled = chapter.hasPrevious
        nextButton.isEnabled = chapter.hasNext

        val options = contentOptions()
        val style = readerStyle(options)
        renderJob?.cancel()
        renderJob =
            lifecycleScope.launch {
                val rendered =
                    withContext(Dispatchers.Default) {
                        val processed = contentProcessor.process(chapter.document, chapter.chapter.name, options)
                        RenderedNovelDocument(
                            html = NovelHtmlDocumentBuilder.build(processed, displayedChapterTitle, style),
                            baseUrl = processed.baseUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
                        )
                    }
                if (loaded?.chapter?.id != chapter.chapter.id) return@launch
                webView.webViewClient =
                    NovelAssetWebViewClient(
                        assetProvider = session,
                        chapterUrl = { loaded?.chapter?.url.orEmpty() },
                        offline = { loaded?.let { session.isOffline(it.chapter.url) } == true },
                        blockMedia = { preferences.novelBlockMedia.get() },
                    )
                webView.loadDataWithBaseURL(rendered.baseUrl, rendered.html, "text/html", "UTF-8", null)
            }
        prefetchJob?.cancel()
        prefetchJob = lifecycleScope.launch(Dispatchers.IO) { session.prefetchAdjacent(preferences.novelKeepChaptersLoaded.get()) }
        updateProgressSlider(currentProgress)
        configureWindow()
        startStatusUpdates()
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
        val themeColors =
            when (preferences.novelTheme.get()) {
                "light" -> 0xFFFFFFFF.toInt() to 0xFF202124.toInt()
                "dark" -> 0xFF121212.toInt() to 0xFFE6E1E5.toInt()
                "sepia" -> 0xFFF4ECD8.toInt() to 0xFF3B2F2F.toInt()
                else -> if (dark) 0xFF121212.toInt() to 0xFFE6E1E5.toInt() else 0xFFFFFBFE.toInt() to 0xFF1C1B1F.toInt()
            }
        val defaultBackground = themeColors.first
        val defaultText = themeColors.second
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
            renderingMode = preferences.novelRenderingMode.get(),
            customCss = listOf(preferences.novelCustomCss.get(), customization.enabledCss()).filter(String::isNotBlank).joinToString("\n"),
            customJs = listOf(preferences.novelCustomJs.get(), customization.enabledJs()).filter(String::isNotBlank).joinToString("\n"),
            ttsHighlightColor = preferences.novelTtsHighlightColor.get(),
            ttsHighlightTextColor = preferences.novelTtsHighlightTextColor.get(),
            ttsHighlightStyle = preferences.novelTtsHighlightStyle.get(),
            keepTtsHighlightInView = preferences.novelTtsKeepHighlightInView.get(),
        )
    }

    private fun displayedChapterTitle(chapter: LoadedNovelChapter): String =
        when (preferences.novelChapterTitleDisplay.get()) {
            0 -> chapter.chapter.name
            1 -> "Chapter ${chapter.position + 1}"
            else -> "Chapter ${chapter.position + 1}: ${chapter.chapter.name}"
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
        updateStatus()
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
        restorePersistentHighlights(chapter)
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
                "Saved quotes",
                "Chapter statistics",
                "Highlight selection",
                "Saved highlights",
                "Translate selection",
                "Translate chapter",
                "Show original chapter",
                "Dictionary lookup",
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
                    6 -> showSavedQuotes()
                    7 -> showChapterStatistics()
                    8 -> captureHighlight()
                    9 -> showHighlights()
                    10 -> translateSelection()
                    11 -> translateChapter()
                    12 -> { webView.evaluateJavascript("window.hayaiReader.showOriginal()", null); restorePersistentHighlights(requireNotNull(loaded)) }
                    13 -> dictionarySelection()
                    14 -> {
                        currentProgress = 0
                        loaded?.chapter?.let(NovelProgress::reset)
                        saveProgress()
                    }
                }
                if (which in 0..3 || which == 14) loaded?.let(::showChapter)
            }.show()
    }

    private fun showChapterStatistics() {
        val chapter = loaded ?: return
        val statistics = chapter.statistics
        val number = NumberFormat.getIntegerInstance()
        AlertDialog
            .Builder(this)
            .setTitle("Chapter statistics")
            .setMessage(
                buildString {
                    append("Words: ", number.format(statistics.wordCount))
                    append("\nEstimated reading time: ", statistics.estimatedMinutes(), " min")
                    append("\nEstimated words read: ", number.format(statistics.wordsRead(currentProgress)))
                    append("\nEstimated time remaining: ", statistics.remainingMinutes(currentProgress), " min")
                    append("\nProgress: ", currentProgress.coerceIn(0, 100), "%")
                },
            ).setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun captureSelectedQuote() {
        val chapter = loaded ?: return
        webView.evaluateJavascript("window.hayaiReader.takeSelection()") { encoded ->
            val selection = runCatching { json.decodeFromString<String>(encoded) }.getOrNull().orEmpty()
            lifecycleScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            quoteStore.add(
                                mangaId = requireNotNull(chapter.manga.id),
                                novelName = chapter.manga.title,
                                chapterName = chapter.chapter.name,
                                selectedText = selection,
                            )
                        }
                    }
                result.fold(
                    onSuccess = { added ->
                        toast(if (added is QuoteAddResult.Created) "Quote saved" else "This quote is already saved")
                    },
                    onFailure = { toast(it.message ?: "The quote could not be saved") },
                )
            }
        }
    }

    private fun captureHighlight() {
        val chapter = loaded ?: return
        captureSelection { capture ->
            val anchor = NovelHighlightAnchor.fromContext(
                capture.documentText,
                capture.selectedText,
                capture.prefix,
                capture.suffix,
                capture.occurrence,
            )
            if (anchor == null) {
                toast("Select text to highlight")
                return@captureSelection
            }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        highlightStore.create(
                            requireNotNull(chapter.manga.id),
                            requireNotNull(chapter.chapter.id),
                            chapter.manga.source,
                            chapter.manga.url,
                            chapter.chapter.url,
                            DEFAULT_HIGHLIGHT_COLOR,
                            null,
                            anchor,
                        )
                    }
                }
                result.fold(
                    onSuccess = {
                        restorePersistentHighlights(chapter)
                        toast("Highlight saved")
                    },
                    onFailure = { toast(it.message ?: "Highlight could not be saved") },
                )
            }
        }
    }

    private fun restorePersistentHighlights(chapter: LoadedNovelChapter) {
        lifecycleScope.launch {
            val highlights = withContext(Dispatchers.IO) {
                highlightStore.forStableChapter(chapter.manga.source, chapter.manga.url, chapter.chapter.url)
            }
            if (loaded?.chapter?.id != chapter.chapter.id) return@launch
            val payload = highlights.map {
                HighlightRender(
                    it.id,
                    it.anchor.exact,
                    it.anchor.prefix,
                    it.anchor.suffix,
                    it.anchor.occurrence,
                    NovelHtmlDocumentBuilder.color(it.color),
                )
            }
            webView.evaluateJavascript("window.hayaiReader.applyPersistentHighlights(${json.encodeToString(payload)})", null)
        }
    }

    private fun showHighlights() {
        val chapter = loaded ?: return
        lifecycleScope.launch {
            val highlights = withContext(Dispatchers.IO) {
                highlightStore.forStableChapter(chapter.manga.source, chapter.manga.url, chapter.chapter.url)
            }
            if (highlights.isEmpty()) {
                toast("No highlights in this chapter")
                return@launch
            }
            val labels = highlights.map {
                (it.note?.let { note -> "$note · " }.orEmpty()) + it.anchor.exact.take(100)
            }.toTypedArray()
            AlertDialog.Builder(this@NovelReaderActivity)
                .setTitle("Highlights")
                .setItems(labels) { _, index -> showHighlight(chapter, highlights[index]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showHighlight(chapter: LoadedNovelChapter, selected: NovelHighlight) {
        webView.evaluateJavascript(
            "window.hayaiReader.navigateHighlight(${json.encodeToString(selected.id)})",
            null,
        )
        AlertDialog.Builder(this)
            .setTitle("Highlight")
            .setMessage(selected.anchor.exact)
            .setPositiveButton("Edit") { _, _ -> editHighlight(chapter, selected.id, selected.note, selected.color) }
            .setNeutralButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { highlightStore.delete(selected.id) }
                    restorePersistentHighlights(chapter)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editHighlight(chapter: LoadedNovelChapter, id: String, note: String?, color: Int) {
        val input = EditText(this).apply {
            hint = "Optional note"
            setText(note)
        }
        val colors = intArrayOf(0xFFFFEB3B.toInt(), 0xFF80DEEA.toInt(), 0xFFA5D6A7.toInt(), 0xFFF8BBD0.toInt(), 0xFFFFCC80.toInt())
        var selectedColor = colors.indexOf(color).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle("Edit highlight")
            .setView(input)
            .setSingleChoiceItems(arrayOf("Yellow", "Cyan", "Green", "Pink", "Orange"), selectedColor) { _, which -> selectedColor = which }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        highlightStore.update(id, colors[selectedColor], input.text.toString())
                    }
                    restorePersistentHighlights(chapter)
                }
            }
            .show()
    }

    private fun translateSelection() {
        val chapter = loaded ?: return
        captureSelection { capture ->
            if (capture.selectedText.isBlank()) {
                toast("Select text to translate")
                return@captureSelection
            }
            lifecycleScope.launch {
                loading.visibility = View.VISIBLE
                val settings = translationSettings.get()
                val cacheKey = "${chapter.manga.source}:${chapter.manga.url}:${chapter.chapter.url}:${capture.selectedText.hashCode()}"
                val result = withContext(Dispatchers.IO) {
                    runCatching { translationService.translate(cacheKey, capture.selectedText, settings) }
                }
                loading.visibility = View.GONE
                result.fold(
                    onSuccess = { translated ->
                        AlertDialog.Builder(this@NovelReaderActivity)
                            .setTitle(if (translated.complete) "Translation to ${settings.targetLanguage}" else "Partial translation")
                            .setMessage(listOfNotNull(translated.warning, translated.text).joinToString("\n\n"))
                            .setPositiveButton("Copy") { _, _ ->
                                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                                    ClipData.newPlainText("Translation", translated.text),
                                )
                            }
                            .setNegativeButton(android.R.string.ok, null)
                            .show()
                    },
                    onFailure = { toast(it.message ?: "Translation failed") },
                )
            }
        }
    }

    private fun dictionarySelection() {
        captureSelection { capture ->
            runCatching { dictionary.open(capture.selectedText, dictionarySettings.get()) }
                .onFailure { toast(it.message ?: "Dictionary lookup failed") }
        }
    }

    private fun translateChapter() {
        val chapter = loaded ?: return
        webView.evaluateJavascript("window.hayaiReader.documentText()") { encoded ->
            val text = runCatching { json.decodeFromString<String>(encoded) }.getOrNull().orEmpty()
            if (text.isBlank()) {
                toast("Chapter has no translatable text")
                return@evaluateJavascript
            }
            lifecycleScope.launch {
                loading.visibility = View.VISIBLE
                val settings = translationSettings.get()
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        translationService.translate(
                            "chapter:${chapter.manga.source}:${chapter.manga.url}:${chapter.chapter.url}",
                            text,
                            settings,
                        )
                    }
                }
                loading.visibility = View.GONE
                result.fold(
                    onSuccess = { translated ->
                        webView.evaluateJavascript(
                            "window.hayaiReader.showTranslation(${json.encodeToString(translated.text)})",
                            null,
                        )
                        translated.warning?.let { warning -> toast(warning) }
                        extractTtsParagraphs()
                    },
                    onFailure = { toast(it.message ?: "Chapter translation failed") },
                )
            }
        }
    }

    private fun captureSelection(action: (SelectionCapture) -> Unit) {
        webView.evaluateJavascript("JSON.stringify(window.hayaiReader.takeSelectionAnchor())") { encoded ->
            val payload = runCatching { json.decodeFromString<String>(encoded) }.getOrNull() ?: return@evaluateJavascript
            val capture = runCatching { json.decodeFromString<SelectionCapture>(payload) }.getOrNull() ?: return@evaluateJavascript
            action(capture)
        }
    }

    private fun showSavedQuotes() {
        val mangaId = loaded?.manga?.id ?: return
        lifecycleScope.launch {
            val quotes = withContext(Dispatchers.IO) { quoteStore.forManga(mangaId) }
            if (quotes.isEmpty()) {
                toast("No saved quotes for this novel")
                return@launch
            }
            val labels = quotes.map { quote -> "${quote.chapterName}  •  ${quote.displayedContent.replace('\n', ' ').take(80)}" }.toTypedArray()
            AlertDialog
                .Builder(this@NovelReaderActivity)
                .setTitle("Saved quotes")
                .setItems(labels) { _, index -> showQuote(quotes[index]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showQuote(quote: NovelQuote) {
        AlertDialog
            .Builder(this)
            .setTitle(quote.chapterName)
            .setMessage(quote.displayedContent)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Novel quote", quote.displayedContent))
                toast("Quote copied")
            }.setNeutralButton("Delete") { _, _ -> confirmDeleteQuote(quote) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteQuote(quote: NovelQuote) {
        AlertDialog
            .Builder(this)
            .setTitle("Delete quote?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) { quoteStore.delete(quote.id) }
                    toast(if (deleted) "Quote deleted" else "The quote no longer exists")
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
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
                    webView.evaluateJavascript("window.hayaiReader.stepPixels(2)", null)
                    webView.postDelayed(this, delay)
                }
            }.also(webView::post)
    }

    private fun stopAutoScroll() {
        autoScroll = false
        autoScrollRunnable?.let(webView::removeCallbacks)
        autoScrollRunnable = null
    }

    private fun stepReader(direction: Int) {
        webView.evaluateJavascript("window.hayaiReader.step(${direction.coerceIn(-1, 1)})", null)
    }

    private fun startStatusUpdates() {
        stopStatusUpdates()
        updateStatus()
        if (!preferences.novelStatusBarEnabled.get() || !preferences.novelStatusBarShowTime.get()) return
        statusRunnable =
            object : Runnable {
                override fun run() {
                    updateStatus()
                    statusView.postDelayed(this, 30_000L)
                }
            }.also { statusView.postDelayed(it, 30_000L) }
    }

    private fun stopStatusUpdates() {
        statusRunnable?.let { if (::statusView.isInitialized) statusView.removeCallbacks(it) }
        statusRunnable = null
    }

    private fun updateStatus() {
        if (!::statusView.isInitialized) return
        statusView.visibility = if (preferences.novelStatusBarEnabled.get()) View.VISIBLE else View.GONE
        if (statusView.visibility != View.VISIBLE) return
        val chapter = loaded
        val parts = mutableListOf<String>()
        if (preferences.novelStatusBarShowTime.get()) parts += DateFormat.getTimeFormat(this).format(java.util.Date())
        if (preferences.novelStatusBarShowBattery.get()) {
            val battery = (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery in 0..100) parts += "$battery%"
        }
        if (preferences.novelStatusBarShowCharging.get()) {
            val charging = (getSystemService(BATTERY_SERVICE) as BatteryManager).isCharging
            if (charging) parts += "Charging"
        }
        if (chapter != null && preferences.novelStatusBarShowChapterNumber.get()) parts += "${chapter.position + 1}/${chapter.total}"
        if (chapter != null && preferences.novelStatusBarShowChapterTitle.get()) parts += displayedChapterTitle(chapter)
        if (preferences.novelStatusBarShowProgress.get()) parts += "$currentProgress%"
        statusView.text = parts.joinToString("  •  ")
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

        statusView =
            TextView(this).apply {
                textSize =
                    when (preferences.novelStatusBarSize.get()) {
                        "large" -> 14f
                        "medium" -> 12f
                        else -> 10f
                    }
                gravity = Gravity.CENTER
                setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
                visibility = if (preferences.novelStatusBarEnabled.get()) View.VISIBLE else View.GONE
            }
        if (preferences.novelStatusBarPosition.get() == "top") root.addView(statusView)

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
                verticalScrollbarPosition =
                    if (preferences.novelVerticalScrollbarPosition.get() == "left") {
                        View.SCROLLBAR_POSITION_LEFT
                    } else {
                        View.SCROLLBAR_POSITION_RIGHT
                    }
                scrollBarSize =
                    (resources.displayMetrics.density *
                        when (preferences.novelVerticalProgressSliderSize.get()) {
                            "quarter" -> 2
                            "full" -> 6
                            else -> 4
                        }).toInt()
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

        if (preferences.novelStatusBarPosition.get() != "top") root.addView(statusView)

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
        if (preferences.novelTtsControlsVisible.get()) controls.addView(controlButton("◀") { ttsController.previousParagraph() })
        controls.addView(playButton)
        if (preferences.novelTtsControlsVisible.get()) controls.addView(controlButton("▶") { ttsController.nextParagraph() })
        controls.addView(nextButton)
        controls.addView(controlButton("❝") { captureSelectedQuote() })
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
                    stepReader(direction)
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

        @JavascriptInterface
        fun onHighlightReport(applied: Int, orphaned: Int, overlaps: Int) = runOnUiThread { if (orphaned > 0 || overlaps > 0) toast("Highlights restored: $applied. Stale: $orphaned. Overlaps skipped: $overlaps") }
    }

    @Serializable
    private data class TtsParagraph(
        val index: Int,
        val text: String,
    )

    @Serializable
    private data class SelectionCapture(val documentText: String, val selectedText: String, val prefix: String, val suffix: String, val occurrence: Int)

    @Serializable
    private data class HighlightRender(val id: String, val exact: String, val prefix: String, val suffix: String, val occurrence: Int, val color: String)

    private data class RenderedNovelDocument(
        val html: String,
        val baseUrl: String?,
    )

    companion object {
        private const val EXTRA_MANGA_ID = "hayai.manga_id"
        private const val EXTRA_CHAPTER_ID = "hayai.chapter_id"
        private const val JS_INTERFACE = "HayaiReader"
        private const val DEFAULT_HIGHLIGHT_COLOR = 0xFFFFEB3B.toInt()

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
