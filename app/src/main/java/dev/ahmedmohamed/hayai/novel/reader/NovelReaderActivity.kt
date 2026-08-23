package dev.ahmedmohamed.hayai.novel.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.BatteryManager
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.core.view.isVisible
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import dev.ahmedmohamed.hayai.novel.download.NovelDownloadStore
import dev.ahmedmohamed.hayai.novel.dictionary.NovelDictionaryLauncher
import dev.ahmedmohamed.hayai.novel.dictionary.NovelDictionarySettingsStore
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import dev.ahmedmohamed.hayai.novel.highlight.NovelHighlightAnchor
import dev.ahmedmohamed.hayai.novel.highlight.NovelHighlight
import dev.ahmedmohamed.hayai.novel.highlight.NovelHighlightStore
import dev.ahmedmohamed.hayai.novel.lookup.NovelLookupAction
import dev.ahmedmohamed.hayai.novel.lookup.NovelLookupLauncher
import dev.ahmedmohamed.hayai.novel.lookup.NovelLookupRequest
import dev.ahmedmohamed.hayai.novel.lookup.NovelLookupWebSheet
import dev.ahmedmohamed.hayai.novel.lookup.NovelSelectionQuery
import dev.ahmedmohamed.hayai.novel.lookup.NovelSelectionQueryException
import dev.ahmedmohamed.hayai.novel.lookup.NovelSelectionQueryFailure
import dev.ahmedmohamed.hayai.novel.quote.NovelQuote
import dev.ahmedmohamed.hayai.novel.quote.NovelQuoteStore
import dev.ahmedmohamed.hayai.novel.quote.QuoteAddResult
import dev.ahmedmohamed.hayai.novel.settings.NovelCustomizationStore
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationCache
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationService
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationSettingsStore
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationWarning
import dev.ahmedmohamed.hayai.novel.tracker.J2kNovelChapterTrackSync
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.ui.reader.ReaderSlider
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterSheet
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.ui.main.SearchActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.NumberFormat
import eu.kanade.tachiyomi.R

class NovelReaderActivity :
    AppCompatActivity(),
    NovelTtsController.Callbacks,
    NovelRenderer.Callbacks {
    private val preferences by lazy { HayaiPreferences(Injekt.get<PreferenceStore>()) }
    private val j2kPreferences by lazy { Injekt.get<PreferencesHelper>() }
    private val session by lazy {
        val database = Injekt.get<DatabaseHelper>()
        NovelReaderSession(
            this,
            database,
            Injekt.get<SourceManager>(),
            NovelDownloadStore(File(filesDir, "hayai/novel-downloads")),
            Injekt.get<NetworkHelper>(),
            J2kNovelChapterTrackSync(database, Injekt.get<PreferencesHelper>()),
        )
    }
    private val contentProcessor = NovelContentProcessor()
    private val customization by lazy { NovelCustomizationStore(preferences) }
    private val fontStore by lazy { NovelFontStore(this, preferences) }
    private val quoteStore by lazy { NovelQuoteStore(Injekt.get<DatabaseHelper>()) }
    private val highlightStore by lazy { NovelHighlightStore(Injekt.get<DatabaseHelper>()) }
    private val translationSettings by lazy { NovelTranslationSettingsStore(this) }
    private val translationService by lazy { NovelTranslationService(Injekt.get<NetworkHelper>(), NovelTranslationCache(this)) }
    private val dictionary by lazy { NovelDictionaryLauncher(this) }
    private val dictionarySettings by lazy { NovelDictionarySettingsStore(this) }
    private val lookupLauncher by lazy { NovelLookupLauncher(this, ::showLookupSheet) }
    private lateinit var viewerContainer: FrameLayout
    private lateinit var readerChrome: J2kNovelReaderChrome
    private lateinit var appBar: View
    private lateinit var toolbar: Toolbar
    private lateinit var statusView: TextView
    private lateinit var alternateStatusView: TextView
    private lateinit var loading: View
    private lateinit var progressSlider: ReaderSlider
    private lateinit var verticalProgressSlider: NovelVerticalProgressView
    private lateinit var progressText: TextView
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var chapterSheet: ReaderChapterSheet
    private lateinit var chapterSheetBehavior: BottomSheetBehavior<ReaderChapterSheet>
    private lateinit var chapterAdapter: NovelReaderChapterAdapter
    private lateinit var ttsButton: MaterialButton
    private lateinit var autoScrollButton: MaterialButton
    private var bookmarkMenuItem: MenuItem? = null
    private var renderer: NovelRenderer? = null
    private val chapterQueue = NovelChapterQueue<LoadedNovelChapter, Long>({ requireNotNull(it.chapter.id) }, 1)
    private lateinit var ttsController: NovelTtsController
    private var loaded: LoadedNovelChapter? = null
    private var loadJob: Job? = null
    private var renderJob: Job? = null
    private var prefetchJob: Job? = null
    private var currentProgress = 0
    private var programmaticProgress = false
    private var autoAppendArmed = true
    private var autoPrependArmed = true
    private var ttsAutoStartPending = false
    private var autoScroll = false
    private var autoScrollRunnable: Runnable? = null
    private var autoScrollResumePending = false
    private var selectionModeActive = false
    private var lookupSheet: NovelLookupWebSheet? = null
    private var statusRunnable: Runnable? = null
    private var controlsVisible = true
    private var editMode = false
    private var navigationDirection = 0
    private var navigationFocus = true
    private val chapterFailureCooldowns = mutableMapOf<Long, Long>()
    private val progressWriteMutex = Mutex()
    private val fontImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { fontStore.importFont(uri) }
                result.fold(
                    onSuccess = { font ->
                        preferences.novelFontFamily.set(fontStore.token(font))
                        loaded?.let(::showChapter)
                        toast(getString(R.string.hayai_novel_reader_imported_font_success, font.name))
                    },
                    onFailure = { toast(novelFailureMessage(it, R.string.hayai_novel_reader_imported_font_error)) },
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ttsController = NovelTtsController(this, this)
        // Inflate J2K's real image-reader shell. Hayai supplies novel behavior through
        // adapters below; upstream owns the chrome, navigation, and chapter-sheet visuals.
        setContentView(R.layout.reader_activity)
        bindReaderShell()
        configureWindow()

        openIntentChapter(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L)
        if (loaded?.chapter?.id != chapterId) {
            saveProgressBlocking()
            openIntentChapter(intent)
        }
    }

    private fun openIntentChapter(intent: Intent) {
        val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1L)
        val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L)
        if (mangaId < 0 || chapterId < 0) {
            showError(getString(R.string.hayai_novel_reader_open_error))
            return
        }
        loadJob?.cancel()
        renderJob?.cancel()
        chapterQueue.clear()
        renderer?.retain(emptySet())
        loading.visibility = View.VISIBLE
        loadJob =
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { session.initialize(mangaId, chapterId, recordHistory = !j2kPreferences.incognitoMode().get()) } }
                result.fold(::showChapter) { showError(novelFailureMessage(it, R.string.hayai_novel_reader_load_error)) }
            }
    }

    override fun onResume() {
        super.onResume()
        resumeInterruptedAutoScroll()
    }

    override fun onPause() {
        pauseAutoScrollForInterruption()
        saveProgressBlocking()
        if (!preferences.novelTtsBackgroundPlayback.get()) ttsController.pause()
        super.onPause()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        renderJob?.cancel()
        prefetchJob?.cancel()
        autoScrollResumePending = false
        stopAutoScroll()
        stopStatusUpdates()
        lookupSheet?.dismiss()
        lookupSheet = null
        ttsController.destroy()
        renderer?.destroy()
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
        chapterFailureCooldowns.remove(requireNotNull(chapter.chapter.id))
        val direction = navigationDirection
        val focus = navigationFocus
        val continuous = preferences.novelInfiniteScroll.get() && direction != 0 && renderer != null
        chapterQueue.capacity = if (continuous) 3 else 1
        when {
            direction < 0 && continuous -> chapterQueue.prepend(chapter)
            direction > 0 && continuous -> chapterQueue.append(chapter)
            else -> chapterQueue.replaceCurrent(chapter)
        }
        if (focus) chapterQueue.focus(requireNotNull(chapter.chapter.id))
        if (continuous) {
            val center = if (focus) chapter.position else loaded?.position ?: chapter.position
            retainChapterWindow(center, chapter.position.takeUnless { focus })
        }
        navigationDirection = 0
        navigationFocus = true
        if (focus) {
            loaded = chapter
            ttsController.setChapter(
                requireNotNull(chapter.manga.id),
                requireNotNull(chapter.chapter.id),
                chapter.manga.title,
                chapter.chapter.name,
            )
            currentProgress = chapter.chapter.last_page_read.coerceIn(0, 100)
            autoAppendArmed = true
            autoPrependArmed = true
        }
        if (focus) loading.visibility = View.VISIBLE
        if (focus) toolbar.title = chapter.manga.title
        val displayedChapterTitle = displayedChapterTitle(chapter)
        if (focus) toolbar.subtitle = chapterSubtitle(chapter, displayedChapterTitle)
        if (focus) updateBookmarkMenu()
        if (focus) {
            previousButton.isEnabled = chapter.hasPrevious
            nextButton.isEnabled = chapter.hasNext
        }

        val options = contentOptions()
        val style = readerStyle(options)
        viewerContainer.setBackgroundColor(style.backgroundColor)
        renderJob?.cancel()
        renderJob =
            lifecycleScope.launch {
                val rendered =
                    withContext(Dispatchers.Default) {
                        val processed = contentProcessor.process(chapter.document, chapter.chapter.name, options)
                        NovelRenderRequest(
                            chapterId = requireNotNull(chapter.chapter.id),
                            content = processed,
                            chapterTitle = displayedChapterTitle,
                            style = style,
                            initialProgress =
                                if (chapter.chapter.read && chapter.chapter.last_page_read >= 100) {
                                    0
                                } else {
                                    chapter.chapter.last_page_read.coerceIn(0, 100)
                                },
                            appendJavaScript = customization.enabledJs(runOnAppend = true),
                        )
                    }
                if (chapterQueue.find(requireNotNull(chapter.chapter.id)) == null) return@launch
                val target = ensureRenderer(NovelRenderingMode.fromPreference(preferences.novelRenderingMode.get()))
                val placement =
                    when {
                        !continuous -> NovelBlockPlacement.ReplaceAll
                        direction < 0 -> NovelBlockPlacement.Before
                        else -> NovelBlockPlacement.After
                    }
                target.display(
                    NovelRenderBlock(requireNotNull(chapter.chapter.id), displayedChapterTitle, NovelBlockContent.Ready(rendered)),
                    placement,
                    focus = focus,
                )
                target.retain(chapterQueue.keys())
            }
        prefetchJob?.cancel()
        prefetchJob = lifecycleScope.launch(Dispatchers.IO) { session.prefetchAdjacent(preferences.novelKeepChaptersLoaded.get()) }
        if (focus) updateProgressSlider(currentProgress)
        if (::chapterAdapter.isInitialized) {
            chapterAdapter.submit(session.chapterSnapshot(), chapter.chapter.id)
        }
        rebuildBottomActions()
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
            NovelThemeColors.resolve(
                theme = preferences.novelTheme.get(),
                customBackground = preferences.novelBackgroundColor.get(),
                customText = preferences.novelFontColor.get(),
                appBackground = if (dark) NovelThemeColors.DARK_BACKGROUND else 0xFFFFFBFE.toInt(),
                appText = if (dark) NovelThemeColors.DARK_TEXT else 0xFF1C1B1F.toInt(),
            )
        val defaultBackground = themeColors.first
        val defaultText = themeColors.second
        return NovelReaderStyle(
            fontSize = preferences.novelFontSize.get(),
            fontFamily = preferences.novelFontFamily.get(),
            lineHeight = preferences.novelLineHeight.get(),
            textAlign = preferences.novelTextAlign.get(),
            textColor = defaultText,
            backgroundColor = defaultBackground,
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
            1 -> getString(R.string.hayai_novel_reader_chapter_number_only, chapter.position + 1)
            else -> getString(R.string.hayai_novel_reader_chapter_number_name, chapter.position + 1, chapter.chapter.name)
        }

    private fun chapterSubtitle(
        chapter: LoadedNovelChapter,
        displayedTitle: String = displayedChapterTitle(chapter),
    ): String =
        buildString {
            append(displayedTitle, getString(R.string.hayai_novel_reader_status_separator), getString(R.string.hayai_novel_reader_chapter_count, chapter.position + 1, chapter.total))
            if (chapter.isDownloaded) append(getString(R.string.hayai_novel_reader_status_separator), getString(R.string.hayai_novel_reader_offline))
            append(
                getString(R.string.hayai_novel_reader_status_separator),
                getString(R.string.hayai_novel_reader_word_count, NumberFormat.getIntegerInstance().format(chapter.statistics.wordCount)),
                getString(R.string.hayai_novel_reader_status_separator),
                getString(R.string.hayai_novel_reader_reading_minutes, chapter.statistics.estimatedMinutes()),
            )
        }

    private fun updateChapterChrome(chapter: LoadedNovelChapter) {
        toolbar.title = chapter.manga.title
        toolbar.subtitle = chapterSubtitle(chapter)
        updateBookmarkMenu()
        previousButton.isEnabled = chapter.hasPrevious
        nextButton.isEnabled = chapter.hasNext
    }

    private fun retainChapterWindow(centerPosition: Int, stagedPosition: Int? = null) {
        val allowedPositions =
            when (preferences.novelKeepChaptersLoaded.get()) {
                1 -> mutableSetOf(centerPosition - 1, centerPosition)
                2 -> mutableSetOf(centerPosition, centerPosition + 1)
                3 -> mutableSetOf(centerPosition - 1, centerPosition, centerPosition + 1)
                else -> mutableSetOf(centerPosition)
            }.apply { stagedPosition?.let(::add) }
        val keys = chapterQueue.snapshot().filter { it.position in allowedPositions }.mapTo(linkedSetOf()) { requireNotNull(it.chapter.id) }
        chapterQueue.retain(keys)
        renderer?.retain(keys)
    }

    private fun navigate(next: Boolean, focus: Boolean = true) {
        if (loadJob?.isActive == true) return
        val adjacent = session.adjacent(next)
        val adjacentId = adjacent?.id
        val cachedAdjacent = adjacentId?.let(chapterQueue::find)
        if (cachedAdjacent != null) {
            if (!focus) {
                if (next) autoAppendArmed = false else autoPrependArmed = false
                return
            }
            val previous = loaded?.chapter
            val previousProgress = currentProgress
            previous?.let {
                lifecycleScope.launch(Dispatchers.IO) {
                    progressWriteMutex.withLock {
                        session.saveProgress(it, previousProgress, preferences.novelMarkAsReadThreshold.get())
                    }
                }
            }
            if (!session.focus(requireNotNull(cachedAdjacent.chapter.id))) return
            navigationDirection = if (next) 1 else -1
            navigationFocus = true
            showChapter(cachedAdjacent)
            return
        }
        val now = System.currentTimeMillis()
        if (adjacentId != null && (chapterFailureCooldowns[adjacentId] ?: 0L) > now) {
            toast(getString(R.string.hayai_novel_reader_retry_chapter_in, (chapterFailureCooldowns.getValue(adjacentId) - now + 999) / 1000))
            return
        }
        val chapterToSave = loaded?.chapter
        val progressToSave = currentProgress
        if (focus) {
            ttsController.stop()
            stopAutoScroll()
            loading.visibility = View.VISIBLE
        }
        navigationDirection = if (next) 1 else -1
        navigationFocus = focus
        if (preferences.novelInfiniteScroll.get() && adjacentId != null && renderer != null) {
            renderer?.display(
                NovelRenderBlock(adjacentId, adjacent?.name.orEmpty(), NovelBlockContent.Loading),
                if (next) NovelBlockPlacement.After else NovelBlockPlacement.Before,
                focus = focus,
            )
        }
        loadJob =
            lifecycleScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            chapterToSave?.let {
                                progressWriteMutex.withLock {
                                    session.saveProgress(it, progressToSave, preferences.novelMarkAsReadThreshold.get())
                                }
                            }
                            if (focus) {
                                if (next) session.next() else session.previous()
                            } else {
                                session.loadAdjacent(next)
                            }
                        }
                    }
                result.fold(
                    onSuccess = { chapter ->
                        chapter?.let(::showChapter) ?: run {
                            navigationDirection = 0
                            navigationFocus = true
                            toast(if (next) R.string.hayai_novel_reader_no_next_chapter else R.string.hayai_novel_reader_no_previous_chapter)
                        }
                    },
                    onFailure = { error ->
                        val direction = navigationDirection
                        navigationDirection = 0
                        navigationFocus = true
                        val message = novelFailureMessage(error, R.string.hayai_novel_reader_load_error)
                        if (preferences.novelInfiniteScroll.get() && adjacentId != null && renderer != null) {
                            val retryAt = System.currentTimeMillis() + CHAPTER_RETRY_COOLDOWN_MS
                            chapterFailureCooldowns[adjacentId] = retryAt
                            renderer?.display(
                                NovelRenderBlock(adjacentId, adjacent?.name.orEmpty(), NovelBlockContent.Error(message, retryAt)),
                                if (direction > 0) NovelBlockPlacement.After else NovelBlockPlacement.Before,
                                focus = focus,
                            )
                            loading.visibility = View.GONE
                        } else {
                            showError(message)
                        }
                    },
                )
            }
    }

    private fun saveProgress() {
        val chapter = loaded?.chapter ?: return
        val progress = currentProgress
        lifecycleScope.launch(Dispatchers.IO) {
            progressWriteMutex.withLock {
                session.saveProgress(chapter, progress, preferences.novelMarkAsReadThreshold.get())
            }
        }
    }

    private fun saveProgressBlocking() {
        val chapter = loaded?.chapter ?: return
        val progress = currentProgress
        runBlocking(Dispatchers.IO) {
            progressWriteMutex.withLock {
                session.saveProgress(chapter, progress, preferences.novelMarkAsReadThreshold.get(), syncTracking = false)
            }
        }
        launchIO { session.syncTracking(chapter) }
    }

    private fun onReaderProgress(progress: Int) {
        currentProgress = progress.coerceIn(0, 100)
        updateProgressSlider(currentProgress)
        updateStatus()
        val threshold = preferences.novelAutoLoadNextChapterAt.get().coerceIn(1, 100)
        if (preferences.novelInfiniteScroll.get()) {
            if (autoAppendArmed && currentProgress >= threshold && session.hasNext) {
                autoAppendArmed = false
                navigate(next = true, focus = false)
            } else if (autoPrependArmed && currentProgress <= PREVIOUS_CHAPTER_PRELOAD_THRESHOLD && session.hasPrevious) {
                autoPrependArmed = false
                navigate(next = false, focus = false)
            }
        }
    }

    private fun updateProgressSlider(value: Int) {
        programmaticProgress = true
        progressSlider.value = value.toFloat()
        verticalProgressSlider.progress = value
        progressText.text = getString(R.string.hayai_novel_reader_percent_value, value)
        alternateStatusView.text = getString(R.string.hayai_novel_reader_percent_value, 100)
        programmaticProgress = false
    }

    private fun onDocumentReady() {
        loading.visibility = View.GONE
        val chapter = loaded ?: return
        val openingProgress = if (chapter.chapter.read && chapter.chapter.last_page_read >= 100) 0 else currentProgress
        renderer?.seek(openingProgress)
        extractTtsParagraphs(autoStart = ttsAutoStartPending)
        restorePersistentHighlights(chapter)
        ttsAutoStartPending = false
        if (preferences.novelMarkShortChapterAsRead.get()) {
            renderer?.isShort { short ->
                if (short) {
                    currentProgress = 100
                    saveProgress()
                    updateProgressSlider(100)
                }
            }
        }
    }

    private fun extractTtsParagraphs(autoStart: Boolean = false) = extractTtsParagraphs(0, autoStart)

    private fun extractTtsParagraphs(startParagraph: Int, autoStart: Boolean) {
        renderer?.paragraphs { paragraphs ->
            ttsController.configure(
                preferences.novelTtsSpeed.get(),
                preferences.novelTtsPitch.get(),
                preferences.novelTtsVoice.get(),
                preferences.novelTtsBackgroundPlayback.get(),
            )
            ttsController.setParagraphs(paragraphs, startParagraph)
            if (autoStart) ttsController.play()
        }
    }

    private fun showReaderSettings() {
        NovelReaderSettingsSheet(
            this,
            preferences,
            onStyleChanged = { loaded?.let(::showChapter) },
            onChromeChanged = {
                configureWindow()
                bindStatusView()
                configureProgressControls()
                rebuildBottomActions()
                startStatusUpdates()
            },
            onAction = ::dispatch,
        ).show()
    }

    private fun showChapterStatistics() {
        val chapter = loaded ?: return
        val statistics = chapter.statistics
        val number = NumberFormat.getIntegerInstance()
        AlertDialog
            .Builder(this)
            .setTitle(R.string.hayai_novel_reader_chapter_statistics)
            .setMessage(getString(R.string.hayai_novel_reader_statistics_message, number.format(statistics.wordCount), statistics.estimatedMinutes(), number.format(statistics.wordsRead(currentProgress)), statistics.remainingMinutes(currentProgress), getString(R.string.hayai_novel_reader_percent_value, currentProgress.coerceIn(0, 100))))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun captureSelectedQuote() {
        val chapter = loaded ?: return
        renderer?.selection { capture ->
            val selection = capture?.selectedText.orEmpty()
            showCreateQuote(chapter, selection)
        }
    }

    private fun showCreateQuote(chapter: LoadedNovelChapter, initialText: String) {
        val content = EditText(this).apply { setText(initialText); minLines = 4; gravity = Gravity.TOP; setHint(R.string.hayai_novel_reader_quote) }
        val chapterName = EditText(this).apply { setText(chapter.chapter.name); setSingleLine(); setHint(R.string.hayai_novel_reader_chapter) }
        val language = EditText(this).apply { setSingleLine(); setHint(R.string.hayai_novel_reader_language_optional) }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 8.dp, 20.dp, 8.dp)
            addView(chapterName)
            addView(language)
            addView(content)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.hayai_novel_reader_save_quote).setView(form).setPositiveButton(R.string.save, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            quoteStore.add(
                                mangaId = requireNotNull(chapter.manga.id),
                                novelName = chapter.manga.title,
                                chapterName = chapterName.text.toString(),
                                selectedText = content.text.toString(),
                                language = language.text.toString(),
                            )
                        }
                    }
                    result.fold(
                        onSuccess = { added -> toast(if (added is QuoteAddResult.Created) R.string.hayai_novel_reader_quote_saved else R.string.hayai_novel_reader_quote_duplicate); dialog.dismiss() },
                        onFailure = { content.error = novelFailureMessage(it, R.string.hayai_novel_reader_quote_save_error) },
                    )
                }
            }
        }
        dialog.show()
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
                toast(R.string.hayai_novel_reader_select_highlight)
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
                        toast(R.string.hayai_novel_reader_highlight_saved)
                    },
                    onFailure = { toast(novelFailureMessage(it, R.string.hayai_novel_reader_highlight_save_error)) },
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
                NovelPersistentHighlight(
                    it.id,
                    it.anchor.exact,
                    it.anchor.prefix,
                    it.anchor.suffix,
                    it.anchor.occurrence,
                    it.color,
                )
            }
            renderer?.applyHighlights(payload)
        }
    }

    private fun showHighlights() {
        val chapter = loaded ?: return
        lifecycleScope.launch {
            val highlights = withContext(Dispatchers.IO) {
                highlightStore.forStableChapter(chapter.manga.source, chapter.manga.url, chapter.chapter.url)
            }
            if (highlights.isEmpty()) {
                toast(R.string.hayai_novel_reader_no_highlights)
                return@launch
            }
            val labels = highlights.map {
                it.note?.let { note -> getString(R.string.hayai_novel_reader_highlight_list_item, note, it.anchor.exact.take(100)) } ?: it.anchor.exact.take(100)
            }.toTypedArray()
            AlertDialog.Builder(this@NovelReaderActivity)
                .setTitle(R.string.hayai_novel_reader_highlights)
                .setItems(labels) { _, index -> showHighlight(chapter, highlights[index]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showHighlight(chapter: LoadedNovelChapter, selected: NovelHighlight) {
        AlertDialog.Builder(this)
            .setTitle(R.string.hayai_novel_reader_highlight)
            .setMessage(selected.anchor.exact)
            .setPositiveButton(R.string.edit) { _, _ -> editHighlight(chapter, selected.id, selected.note, selected.color) }
            .setNeutralButton(R.string.delete) { _, _ ->
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
            setHint(R.string.hayai_novel_reader_optional_note)
            setText(note)
        }
        val colors = intArrayOf(0xFFFFEB3B.toInt(), 0xFF80DEEA.toInt(), 0xFFA5D6A7.toInt(), 0xFFF8BBD0.toInt(), 0xFFFFCC80.toInt())
        var selectedColor = colors.indexOf(color).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.hayai_novel_reader_edit_highlight)
            .setView(input)
            .setSingleChoiceItems(arrayOf(getString(R.string.hayai_novel_reader_yellow), getString(R.string.hayai_novel_reader_cyan), getString(R.string.hayai_novel_reader_green), getString(R.string.hayai_novel_reader_pink), getString(R.string.hayai_novel_reader_orange)), selectedColor) { _, which -> selectedColor = which }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
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
                toast(R.string.hayai_novel_reader_select_translate)
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
                            .setTitle(if (translated.complete) getString(R.string.hayai_novel_reader_translation_to, settings.targetLanguage) else getString(R.string.hayai_novel_reader_partial_translation))
                            .setMessage(
                                listOfNotNull(translated.warning?.let(::translationWarningText), translated.text)
                                    .joinToString("\n\n"),
                            )
                            .setPositiveButton(R.string.copy_value) { _, _ ->
                                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                                    ClipData.newPlainText(getString(R.string.hayai_novel_reader_translation_clipboard), translated.text),
                                )
                            }
                            .setNegativeButton(android.R.string.ok, null)
                            .show()
                    },
                    onFailure = { toast(novelFailureMessage(it, R.string.hayai_novel_reader_translation_failed)) },
                )
            }
        }
    }

    private fun dictionarySelection() {
        captureSelection { capture ->
            runCatching { dictionary.open(capture.selectedText, dictionarySettings.get()) }
                .onFailure { toast(novelFailureMessage(it, R.string.hayai_novel_reader_dictionary_failed)) }
        }
    }

    private fun translateChapter() {
        val chapter = loaded ?: return
        renderer?.documentText { text ->
            if (text.isBlank()) {
                toast(R.string.hayai_novel_reader_no_translatable_text)
                return@documentText
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
                        renderer?.showTranslation(translated.text)
                        translated.warning?.let { warning -> toast(translationWarningText(warning)) }
                        extractTtsParagraphs()
                    },
                    onFailure = { toast(novelFailureMessage(it, R.string.hayai_novel_reader_chapter_translation_failed)) },
                )
            }
        }
    }

    private fun captureSelection(action: (NovelSelection) -> Unit) = renderer?.selection { it?.let(action) }

    private fun translationWarningText(warning: NovelTranslationWarning): String =
        getString(
            R.string.hayai_translation_partial,
            warning.completedParts,
            warning.totalParts,
            novelFailureMessage(warning.error, R.string.hayai_novel_reader_translation_failed),
        )

    private fun showSavedQuotes() {
        val mangaId = loaded?.manga?.id ?: return
        lifecycleScope.launch {
            val quotes = withContext(Dispatchers.IO) { quoteStore.forManga(mangaId) }
            if (quotes.isEmpty()) {
                toast(R.string.hayai_novel_reader_no_quotes)
                return@launch
            }
            val labels = quotes.map { quote -> getString(R.string.hayai_novel_reader_quote_list_item, quote.chapterName, quote.displayedContent.replace('\n', ' ').take(80)) }.toTypedArray()
            AlertDialog
                .Builder(this@NovelReaderActivity)
                .setTitle(R.string.hayai_novel_reader_saved_quotes)
                .setItems(labels) { _, index -> showQuote(quotes[index]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showQuote(quote: NovelQuote) {
        AlertDialog.Builder(this)
            .setTitle(quote.chapterName)
            .setMessage(quote.displayedContent)
            .setItems(arrayOf(getString(R.string.copy_value), getString(R.string.hayai_novel_reader_copy_attribution), getString(R.string.edit), getString(R.string.hayai_novel_reader_move_earlier), getString(R.string.hayai_novel_reader_move_later), getString(R.string.delete))) { _, action ->
                when (action) {
                    0 -> copyQuote(quote.displayedContent)
                    1 -> copyQuote(getString(R.string.hayai_novel_reader_quote_attribution, quote.displayedContent, quote.novelName, quote.chapterName))
                    2 -> editQuote(quote)
                    3 -> moveQuote(quote, -1)
                    4 -> moveQuote(quote, 1)
                    5 -> confirmDeleteQuote(quote)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyQuote(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(getString(R.string.hayai_novel_reader_quote_clipboard), text))
        toast(R.string.hayai_novel_reader_quote_copied)
    }

    private fun editQuote(quote: NovelQuote) {
        val content = EditText(this).apply { setText(quote.displayedContent); minLines = 4; gravity = Gravity.TOP }
        val chapter = EditText(this).apply { setText(quote.chapterName); setHint(R.string.hayai_novel_reader_chapter); setSingleLine() }
        val language = EditText(this).apply { setText(quote.language.orEmpty()); setHint(R.string.hayai_novel_reader_language_optional); setSingleLine() }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 8.dp, 20.dp, 8.dp)
            addView(chapter)
            addView(language)
            addView(content)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.hayai_novel_reader_edit_quote).setView(form).setPositiveButton(R.string.save, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { quoteStore.update(quote.id, content.text.toString(), chapter.text.toString(), language.text.toString()) } }
                    result.fold(
                        onSuccess = { if (it == null) toast(R.string.hayai_novel_reader_quote_missing) else { toast(R.string.hayai_novel_reader_quote_updated); dialog.dismiss() } },
                        onFailure = { content.error = novelFailureMessage(it, R.string.hayai_novel_reader_quote_update_error) },
                    )
                }
            }
        }
        dialog.show()
    }

    private fun moveQuote(quote: NovelQuote, direction: Int) {
        lifecycleScope.launch {
            val moved = withContext(Dispatchers.IO) { quoteStore.move(quote.mangaId, quote.id, direction) }
            toast(if (moved) R.string.hayai_novel_reader_quote_reordered else R.string.hayai_novel_reader_quote_at_edge)
        }
    }

    private fun confirmDeleteQuote(quote: NovelQuote) {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.hayai_novel_reader_delete_quote)
            .setMessage(R.string.hayai_novel_reader_cannot_undo)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) { quoteStore.delete(quote.id) }
                    toast(if (deleted) R.string.hayai_novel_reader_quote_deleted else R.string.hayai_novel_reader_quote_missing)
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
                            check(refreshed.isDownloaded != removing) { getString(R.string.hayai_novel_reader_offline_verify_error) }
                            download to refreshed
                        }
                    }
                result.fold(
                    onSuccess = { (download, refreshed) ->
                        showChapter(refreshed)
                        when {
                            removing -> toast(R.string.hayai_novel_reader_offline_removed)
                            download == null -> Unit
                            download.unavailableAssetCount > 0 ->
                                toast(getString(R.string.hayai_novel_reader_offline_partial, download.unavailableAssetCount))
                            else -> toast(R.string.hayai_novel_reader_offline_saved)
                        }
                    },
                    onFailure = { toast(novelFailureMessage(it, R.string.hayai_novel_reader_offline_error)) },
                )
            }
    }

    private fun startAutoScroll() {
        if (autoScroll) return
        if (selectionModeActive || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            autoScrollResumePending = true
            return
        }
        autoScroll = true
        if (::autoScrollButton.isInitialized) configureChapterSheetActions()
        val delay = (110L - preferences.novelAutoScrollSpeed.get().coerceIn(1, 20) * 5L).coerceAtLeast(10L)
        autoScrollRunnable =
            object : Runnable {
                override fun run() {
                    if (!autoScroll || isFinishing) return
                    renderer?.stepPixels(2)
                    renderer?.view?.postDelayed(this, delay)
                }
            }.also { renderer?.view?.post(it) }
    }

    private fun stopAutoScroll() {
        autoScroll = false
        autoScrollRunnable?.let { renderer?.view?.removeCallbacks(it) }
        autoScrollRunnable = null
        if (::autoScrollButton.isInitialized) configureChapterSheetActions()
    }

    private fun pauseAutoScrollForInterruption() {
        if (!autoScroll) return
        autoScrollResumePending = true
        stopAutoScroll()
    }

    private fun resumeInterruptedAutoScroll() {
        if (!autoScrollResumePending || selectionModeActive || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        autoScrollResumePending = false
        startAutoScroll()
    }

    private fun stepReader(direction: Int) {
        renderer?.step(direction)
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
        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        val parts = NovelStatusItems.deserialize(preferences.novelStatusBarOrder.get()).mapNotNull { item ->
            when (item) {
                NovelStatusItem.Time -> DateFormat.getTimeFormat(this).format(java.util.Date()).takeIf { preferences.novelStatusBarShowTime.get() }
                NovelStatusItem.Chapter -> chapter?.let {
                    listOfNotNull(
                        getString(R.string.hayai_novel_reader_chapter_count, it.position + 1, it.total).takeIf { preferences.novelStatusBarShowChapterNumber.get() },
                        displayedChapterTitle(it).takeIf { preferences.novelStatusBarShowChapterTitle.get() },
                    ).joinToString(" ").takeIf(String::isNotBlank)
                }
                NovelStatusItem.Progress -> getString(R.string.hayai_novel_reader_percent_value, currentProgress).takeIf { preferences.novelStatusBarShowProgress.get() }
                NovelStatusItem.Battery -> {
                    val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    buildString {
                        if (battery in 0..100 && preferences.novelStatusBarShowBattery.get()) append(getString(R.string.hayai_novel_reader_battery_value, battery))
                        if (batteryManager.isCharging && preferences.novelStatusBarShowCharging.get()) append(getString(R.string.hayai_novel_reader_charging_symbol))
                    }.takeIf(String::isNotBlank)
                }
            }
        }
        statusView.text = parts.joinToString(getString(R.string.hayai_novel_reader_status_separator))
    }

    private fun configureWindow() {
        if (preferences.novelKeepScreenOn.get()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            if (preferences.novelFullscreen.get()) hide(systemBars()) else show(systemBars())
        }
        requestedOrientation = orientationValue(preferences.novelOrientation.get())
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
        bindReaderInsets()
    }

    private fun bindReaderInsets() {
        readerChrome.bindInsets(statusView) { preferences.novelFullscreen.get() }
    }

    private fun showError(message: String) {
        loading.visibility = View.GONE
        toolbar.subtitle = message
        toast(message)
    }

    private fun bindReaderShell() {
        readerChrome = J2kNovelReaderChrome(this)
        viewerContainer = readerChrome.viewerContainer
        appBar = readerChrome.appBar
        toolbar = readerChrome.toolbar
        loading = findViewById(R.id.please_wait)
        chapterSheet = readerChrome.chapterSheet
        chapterSheetBehavior = BottomSheetBehavior.from(chapterSheet)
        progressSlider = findViewById(R.id.page_seekbar)
        progressSlider.valueFrom = 0f
        progressSlider.valueTo = 100f
        progressSlider.stepSize = 1f
        progressSlider.setLabelFormatter { getString(R.string.hayai_novel_reader_percent_value, it.toInt()) }
        verticalProgressSlider = NovelVerticalProgressView(this).apply { max = 100 }
        readerChrome.readerLayout.addView(verticalProgressSlider)
        progressText = findViewById(R.id.left_page_text)
        alternateStatusView = findViewById(R.id.right_page_text)
        previousButton = findViewById(R.id.left_chapter)
        nextButton = findViewById(R.id.right_chapter)
        viewerContainer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        readerChrome.touchView.visibility = View.GONE
        bindStatusView()
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnClickListener { showChapterPicker() }
        bindToolbarMenu()
        previousButton.setOnClickListener { dispatch(NovelReaderAction.Navigate(-1)) }
        nextButton.setOnClickListener { dispatch(NovelReaderAction.Navigate(1)) }
        bindChapterSheet()
        configureProgressControls()
        progressSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !programmaticProgress) {
                currentProgress = value.toInt()
                progressText.text = getString(R.string.hayai_novel_reader_percent_value, currentProgress)
            }
        }
        progressSlider.addOnSliderTouchListener(
            object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) = Unit

                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    renderer?.seek(currentProgress)
                    saveProgress()
                }
            },
        )
        verticalProgressSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser && !programmaticProgress) {
                        currentProgress = value
                        progressText.text = getString(R.string.hayai_novel_reader_percent_value, value)
                        renderer?.seek(value)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = saveProgress()
            },
        )
        setChromeVisible(true)
    }

    private fun bindStatusView() {
        statusView = findViewById(R.id.page_number)
        statusView.textSize = if (preferences.novelStatusBarSize.get() == "medium") 14f else 11f
    }

    private fun configureProgressControls() {
        val enabled = preferences.novelShowProgressSlider.get()
        val vertical = enabled && preferences.novelVerticalScrollbar.get()
        progressSlider.visibility = if (enabled && !vertical) View.VISIBLE else View.GONE
        progressText.visibility = View.GONE
        alternateStatusView.visibility = View.GONE
        verticalProgressSlider.visibility = if (vertical && controlsVisible) View.VISIBLE else View.GONE
        val heightFraction = if (preferences.novelVerticalProgressSliderSize.get() == "half") 0.5f else 1f
        verticalProgressSlider.layoutParams =
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(32.dp, (resources.displayMetrics.heightPixels * heightFraction).toInt()).apply {
                gravity = Gravity.CENTER_VERTICAL or if (preferences.novelVerticalScrollbarPosition.get() == "left") Gravity.START else Gravity.END
            }
    }

    private fun bindChapterSheet() {
        val binding = chapterSheet.binding
        chapterAdapter =
            NovelReaderChapterAdapter(
                onChapterSelected = ::openChapterFromSheet,
                onBookmarkToggled = ::toggleChapterBookmark,
            )
        binding.chapterRecycler.layoutManager = LinearLayoutManager(this)
        binding.chapterRecycler.adapter = chapterAdapter
        binding.chaptersButton.setOnClickListener { toggleChapterSheet() }
        binding.topbarLayout.setOnClickListener { toggleChapterSheet() }
        chapterSheetBehavior.isHideable = true
        chapterSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        chapterSheetBehavior.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    findViewById<View>(R.id.nav_layout).alpha = (1f - slideOffset.coerceAtLeast(0f)).coerceIn(0f, 1f)
                    binding.chapterRecycler.alpha = slideOffset.coerceAtLeast(0f)
                }

                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    val expanded = newState == BottomSheetBehavior.STATE_EXPANDED
                    findViewById<View>(R.id.nav_layout).isVisible = controlsVisible && !expanded
                    binding.chapterRecycler.isVisible = expanded
                }
            },
        )
        configureChapterSheetActions()
    }

    private fun configureChapterSheetActions() {
        if (!::chapterSheet.isInitialized) return
        val binding = chapterSheet.binding
        val ttsActive = ttsController.hasActivePlayback
        val configured = NovelBottomActions.deserialize(preferences.novelBottomBarItems.get())
        val enabled = configured.filter(NovelBottomActionState::enabled).map(NovelBottomActionState::action)
        previousButton.isVisible = NovelBottomAction.PreviousChapter in enabled
        nextButton.isVisible = NovelBottomAction.NextChapter in enabled
        binding.chaptersButton.apply {
            setIconResource(R.drawable.ic_format_list_numbered_24dp)
            contentDescription = getString(R.string.chapters)
            tooltipText = contentDescription
            isVisible = true
        }
        binding.webviewButton.apply {
            setIconResource(if (ttsActive) R.drawable.ic_skip_previous_24 else R.drawable.ic_open_in_webview_24dp)
            contentDescription = getString(if (ttsActive) R.string.hayai_novel_reader_previous_paragraph else R.string.open_in_webview)
            tooltipText = contentDescription
            isVisible = true
            setOnClickListener {
                if (ttsActive) ttsController.previousParagraph() else openSourcePage(inApp = true)
            }
        }
        val slots =
            listOf(
                binding.readingMode,
                binding.rotationSheetButton,
                binding.cropBordersSheetButton,
                binding.doublePage,
                binding.shiftPageButton,
            )
        if (ttsActive) {
            bindReaderSheetButton(slots[0], R.drawable.ic_pause_24dp.takeIf { ttsController.isPlaying } ?: R.drawable.ic_play_arrow_24dp, getString(if (ttsController.isPlaying) R.string.pause else R.string.resume)) {
                if (ttsController.isPlaying) ttsController.pause() else ttsController.play()
            }
            ttsButton = slots[0]
            bindReaderSheetButton(slots[1], R.drawable.ic_skip_next_24, getString(R.string.hayai_novel_reader_next_paragraph), ttsController::nextParagraph)
            bindReaderSheetButton(slots[2], R.drawable.ic_close_circle_24dp, getString(R.string.hayai_novel_reader_stop_tts), ttsController::stop)
            bindReaderSheetButton(slots[3], R.drawable.ic_text_fields_24dp, getString(R.string.hayai_novel_reader_read_viewport)) {
                dispatch(NovelReaderAction.StartTtsAtViewport)
            }
            slots[4].isVisible = false
        } else {
            val slotActions =
                enabled.filterNot {
                    it == NovelBottomAction.PreviousChapter ||
                        it == NovelBottomAction.NextChapter ||
                        it == NovelBottomAction.Settings
                }
            slots.forEachIndexed { index, button ->
                val action = slotActions.getOrNull(index)
                if (action == null) button.isVisible = false else bindNovelSheetAction(button, action)
            }
        }
        binding.displayOptions.apply {
            setIconResource(R.drawable.ic_tune_24dp)
            contentDescription = getString(R.string.reader_settings)
            tooltipText = contentDescription
            isVisible = NovelBottomAction.Settings in enabled || ttsActive
            setOnClickListener { dispatch(NovelReaderAction.ShowSettings) }
        }
    }

    private fun bindNovelSheetAction(button: MaterialButton, action: NovelBottomAction) {
        when (action) {
            NovelBottomAction.ScrollToTop -> bindReaderSheetButton(button, R.drawable.ic_arrow_upward_24dp, getString(R.string.hayai_novel_reader_scroll_top)) { dispatch(NovelReaderAction.Seek(0)) }
            NovelBottomAction.Translate -> bindReaderSheetButton(button, R.drawable.ic_translate_24dp, getString(R.string.hayai_novel_reader_select_translate)) { dispatch(NovelReaderAction.TranslateSelection) }
            NovelBottomAction.AutoScroll -> {
                autoScrollButton = button
                bindReaderSheetButton(button, if (autoScroll) R.drawable.ic_pause_24dp else R.drawable.ic_swap_vert_24dp, getString(if (autoScroll) R.string.hayai_novel_reader_stop_auto_scroll else R.string.hayai_novel_reader_auto_scroll)) {
                    dispatch(NovelReaderAction.ToggleAutoScroll)
                }
            }
            NovelBottomAction.Tts -> {
                ttsButton = button
                bindReaderSheetButton(button, R.drawable.ic_record_voice_over_24dp, getString(R.string.hayai_novel_reader_read_aloud)) { extractTtsParagraphs(autoStart = true) }
                button.setOnLongClickListener { ttsController.stop(); true }
            }
            NovelBottomAction.TtsViewport -> bindReaderSheetButton(button, R.drawable.ic_text_fields_24dp, getString(R.string.hayai_novel_reader_read_viewport)) { dispatch(NovelReaderAction.StartTtsAtViewport) }
            NovelBottomAction.TtsPreviousParagraph -> bindReaderSheetButton(button, R.drawable.ic_skip_previous_24, getString(R.string.hayai_novel_reader_previous_paragraph), ttsController::previousParagraph)
            NovelBottomAction.TtsNextParagraph -> bindReaderSheetButton(button, R.drawable.ic_skip_next_24, getString(R.string.hayai_novel_reader_next_paragraph), ttsController::nextParagraph)
            NovelBottomAction.Orientation -> bindReaderSheetButton(button, R.drawable.ic_screen_rotation_24dp, getString(R.string.hayai_novel_reader_change_orientation)) { dispatch(NovelReaderAction.ToggleOrientation) }
            NovelBottomAction.Edit -> bindReaderSheetButton(button, R.drawable.ic_edit_24dp, getString(R.string.hayai_novel_reader_edit_chapter)) { dispatch(NovelReaderAction.ToggleEditMode) }
            NovelBottomAction.Quotes -> bindReaderSheetButton(button, R.drawable.ic_format_quote_24dp, getString(R.string.hayai_novel_reader_quotes)) { dispatch(NovelReaderAction.ShowQuotes) }
            NovelBottomAction.PreviousChapter,
            NovelBottomAction.NextChapter,
            NovelBottomAction.Settings,
            -> button.isVisible = false
        }
    }

    private fun bindReaderSheetButton(
        button: MaterialButton,
        icon: Int,
        description: String,
        action: () -> Unit,
    ) {
        button.setIconResource(icon)
        button.contentDescription = description
        button.tooltipText = description
        button.isVisible = true
        button.setOnLongClickListener(null)
        button.setOnClickListener { action() }
    }

    private fun toggleChapterSheet() {
        chapterSheetBehavior.state =
            if (chapterSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                BottomSheetBehavior.STATE_COLLAPSED
            } else {
                BottomSheetBehavior.STATE_EXPANDED
            }
    }

    private fun openChapterFromSheet(chapter: eu.kanade.tachiyomi.data.database.models.Chapter) {
        val chapterId = chapter.id ?: return
        chapterSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        saveProgress()
        loadJob?.cancel()
        loadJob =
            lifecycleScope.launch {
                loading.visibility = View.VISIBLE
                val result = withContext(Dispatchers.IO) { runCatching { session.moveTo(chapterId) } }
                result.fold(
                    onSuccess = { loaded -> loaded?.let(::showChapter) ?: showError(getString(R.string.hayai_novel_reader_chapter_unavailable)) },
                    onFailure = { showError(novelFailureMessage(it, R.string.hayai_novel_reader_load_error)) },
                )
            }
    }

    private fun toggleChapterBookmark(chapter: eu.kanade.tachiyomi.data.database.models.Chapter) {
        chapter.bookmark = !chapter.bookmark
        lifecycleScope.launch(Dispatchers.IO) { Injekt.get<DatabaseHelper>().insertChapter(chapter).executeAsBlocking() }
        chapterAdapter.submit(session.chapterSnapshot(), loaded?.chapter?.id)
        if (chapter.id == loaded?.chapter?.id) updateBookmarkMenu()
    }

    private fun ensureRenderer(mode: NovelRenderingMode): NovelRenderer {
        renderer?.takeIf { it.mode == mode }?.let { return it }
        renderer?.destroy()
        val created =
            when (mode) {
                NovelRenderingMode.Native -> NativeNovelRenderer(this, this, fontStore)
                NovelRenderingMode.WebView ->
                    WebNovelRenderer(
                        this,
                        this,
                        session,
                        chapterUrl = { loaded?.chapter?.url.orEmpty() },
                        offline = { loaded?.let { session.isOffline(it.chapter.url) } == true },
                        chapterUrlForId = { id -> chapterQueue.find(id)?.chapter?.url },
                        offlineForId = { id -> chapterQueue.find(id)?.let { session.isOffline(it.chapter.url) } },
                        blockMedia = { preferences.novelBlockMedia.get() },
                        showConsoleErrors = { preferences.novelConsoleErrorToast.get() },
                        enableDevTools = preferences.novelWebViewDevTools.get(),
                        fontStore = fontStore,
                    )
            }
        renderer = created
        viewerContainer.removeAllViews()
        viewerContainer.addView(created.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        created.view.isVerticalScrollBarEnabled = false
        return created
    }

    private fun rebuildBottomActions() = configureChapterSheetActions()

    private fun dispatch(action: NovelReaderAction) {
        when (action) {
            is NovelReaderAction.Navigate -> navigate(action.direction > 0)
            is NovelReaderAction.Seek -> renderer?.seek(action.progress)
            NovelReaderAction.ToggleChrome -> setChromeVisible(!controlsVisible)
            NovelReaderAction.ToggleTts -> if (ttsController.isPlaying) ttsController.pause() else extractTtsParagraphs(autoStart = true)
            NovelReaderAction.StartTtsAtViewport -> renderer?.viewportParagraph { paragraph -> extractTtsParagraphs(paragraph, true) }
            NovelReaderAction.PreviousTtsParagraph -> ttsController.previousParagraph()
            NovelReaderAction.NextTtsParagraph -> ttsController.nextParagraph()
            NovelReaderAction.ShowSettings -> showReaderSettings()
            NovelReaderAction.ShowQuotes -> showSavedQuotes()
            NovelReaderAction.SaveQuote -> captureSelectedQuote()
            NovelReaderAction.ToggleEditMode -> setEditMode(!editMode)
            NovelReaderAction.ToggleBookmark -> toggleBookmark()
            NovelReaderAction.ToggleAutoScroll -> {
                autoScrollResumePending = false
                if (autoScroll) {
                    stopAutoScroll()
                } else if (selectionModeActive) {
                    autoScrollResumePending = true
                } else {
                    startAutoScroll()
                }
            }
            NovelReaderAction.ToggleOrientation -> cycleOrientation()
            NovelReaderAction.TranslateSelection -> translateSelection()
            NovelReaderAction.TranslateChapter -> translateChapter()
            NovelReaderAction.DictionaryLookup -> dictionarySelection()
            NovelReaderAction.ShowStatistics -> showChapterStatistics()
            NovelReaderAction.ToggleOffline -> toggleOfflineCopy()
            NovelReaderAction.ShowHighlights -> showHighlights()
            NovelReaderAction.ImportFont -> fontImportLauncher.launch(arrayOf("font/*", "application/font-sfnt", "application/octet-stream"))
            NovelReaderAction.ManageFonts -> showImportedFonts()
            NovelReaderAction.OpenFullSettings -> startActivity(SearchActivity.openReaderSettings(this))
        }
    }

    private fun showImportedFonts() {
        val fonts = fontStore.fonts()
        if (fonts.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.hayai_novel_reader_imported_fonts)
                .setMessage(R.string.hayai_novel_reader_no_imported_fonts)
                .setPositiveButton(R.string.hayai_novel_reader_import) { _, _ -> dispatch(NovelReaderAction.ImportFont) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val selected = preferences.novelFontFamily.get()
        val labels = fonts.map { "${if (fontStore.token(it) == selected) "✓  " else ""}${it.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.hayai_novel_reader_imported_fonts)
            .setItems(labels) { _, index ->
                val font = fonts[index]
                AlertDialog.Builder(this)
                    .setTitle(font.name)
                    .setItems(arrayOf(getString(R.string.hayai_novel_reader_use_font), getString(R.string.delete))) { _, action ->
                        if (action == 0) {
                            preferences.novelFontFamily.set(fontStore.token(font))
                            loaded?.let(::showChapter)
                        } else {
                            if (selected == fontStore.token(font)) preferences.novelFontFamily.set("sans-serif")
                            if (fontStore.delete(font.id)) {
                                loaded?.let(::showChapter)
                                toast(R.string.hayai_novel_reader_font_deleted)
                            }
                        }
                    }.show()
            }.setPositiveButton(R.string.hayai_novel_reader_import) { _, _ -> dispatch(NovelReaderAction.ImportFont) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setChromeVisible(visible: Boolean) {
        controlsVisible = visible
        readerChrome.setVisible(visible, chapterSheetBehavior)
        configureProgressControls()
    }

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        renderer?.setEditMode(enabled)
        toast(if (enabled) R.string.hayai_novel_reader_editing_enabled else R.string.hayai_novel_reader_editing_finished)
    }

    private fun cycleOrientation() {
        val next = (preferences.novelOrientation.get() + 1) % 3
        preferences.novelOrientation.set(next)
        requestedOrientation = orientationValue(next)
    }

    private fun toggleBookmark() {
        val chapter = loaded?.chapter ?: return
        chapter.bookmark = !chapter.bookmark
        lifecycleScope.launch(Dispatchers.IO) { Injekt.get<DatabaseHelper>().insertChapter(chapter).executeAsBlocking() }
        updateBookmarkMenu()
        toast(if (chapter.bookmark) R.string.hayai_novel_reader_chapter_bookmarked else R.string.hayai_novel_reader_bookmark_removed)
    }

    private fun bindToolbarMenu() {
        bookmarkMenuItem =
            toolbar.menu
                .add(Menu.NONE, MENU_BOOKMARK, Menu.NONE, getString(R.string.hayai_novel_reader_bookmark))
                .setIcon(R.drawable.ic_bookmark_border_24dp)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.menu.add(Menu.NONE, MENU_RELOAD, Menu.NONE, getString(R.string.hayai_novel_reader_reload_chapter))
        toolbar.menu.add(Menu.NONE, MENU_OFFLINE, Menu.NONE, getString(R.string.hayai_novel_reader_toggle_offline))
        toolbar.menu.add(Menu.NONE, MENU_STATISTICS, Menu.NONE, getString(R.string.hayai_novel_reader_chapter_statistics))
        toolbar.menu.add(Menu.NONE, MENU_HIGHLIGHTS, Menu.NONE, getString(R.string.hayai_novel_reader_highlights))
        toolbar.menu.add(Menu.NONE, MENU_OPEN_WEBVIEW, Menu.NONE, getString(R.string.open_in_webview))
        toolbar.menu.add(Menu.NONE, MENU_OPEN_BROWSER, Menu.NONE, getString(R.string.open_in_browser))
        toolbar.menu.add(Menu.NONE, MENU_SHARE, Menu.NONE, getString(R.string.share))
        toolbar.menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, getString(R.string.reader_settings))
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_BOOKMARK -> dispatch(NovelReaderAction.ToggleBookmark)
                MENU_RELOAD -> reloadChapter()
                MENU_OFFLINE -> dispatch(NovelReaderAction.ToggleOffline)
                MENU_STATISTICS -> dispatch(NovelReaderAction.ShowStatistics)
                MENU_HIGHLIGHTS -> dispatch(NovelReaderAction.ShowHighlights)
                MENU_OPEN_WEBVIEW -> openSourcePage(inApp = true)
                MENU_OPEN_BROWSER -> openSourcePage(inApp = false)
                MENU_SHARE -> shareSourcePage()
                MENU_SETTINGS -> dispatch(NovelReaderAction.ShowSettings)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    private fun updateBookmarkMenu() {
        bookmarkMenuItem?.apply {
            val bookmarked = loaded?.chapter?.bookmark == true
            title = getString(if (bookmarked) R.string.hayai_novel_reader_remove_bookmark else R.string.hayai_novel_reader_bookmark)
            setIcon(if (bookmarked) R.drawable.ic_bookmark_24dp else R.drawable.ic_bookmark_border_24dp)
        }
    }

    private fun showChapterPicker() {
        if (::chapterAdapter.isInitialized && session.chapterSnapshot().isNotEmpty()) toggleChapterSheet()
    }

    private fun reloadChapter() {
        loadJob?.cancel()
        loadJob =
            lifecycleScope.launch {
                loading.visibility = View.VISIBLE
                val result = withContext(Dispatchers.IO) { runCatching { session.reload() } }
                result.fold(::showChapter) { showError(novelFailureMessage(it, R.string.hayai_novel_reader_reload_error)) }
            }
    }

    private fun sourcePageUrl(): String? =
        runCatching { (session.source as? HttpSource)?.getMangaUrl(session.manga) }.getOrNull()

    private fun openSourcePage(inApp: Boolean) {
        val url = sourcePageUrl() ?: return toast(R.string.hayai_novel_reader_source_no_page)
        val intent =
            if (inApp) {
                WebViewActivity.newIntent(this, url, session.source.id, loaded?.manga?.title)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
        runCatching { startActivity(intent) }.onFailure { toast(R.string.hayai_novel_reader_no_page_app) }
    }

    private fun shareSourcePage() {
        val chapter = loaded ?: return
        val url = sourcePageUrl()
        val text = listOfNotNull(chapter.manga.title, chapter.chapter.name, url).joinToString("\n")
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                getString(R.string.hayai_novel_reader_share_chapter),
            ),
        )
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun orientationValue(value: Int): Int =
        when (value) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

    override fun onParagraphChanged(index: Int) {
        if (preferences.novelTtsEnableHighlight.get()) renderer?.highlightSpokenParagraph(index)
    }

    override fun onHighlightCleared() {
        renderer?.clearSpokenHighlight()
    }

    override fun onPlaybackChanged(playing: Boolean) {
        if (::ttsButton.isInitialized) configureChapterSheetActions()
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

    override fun onSelectionAction(action: NovelSelectionAction, selection: NovelSelection) {
        if (action == NovelSelectionAction.SaveQuote) {
            loaded?.let { showCreateQuote(it, selection.selectedText) }
            return
        }
        val query =
            NovelSelectionQuery.parse(selection.selectedText).getOrElse { error ->
                val message =
                    when ((error as? NovelSelectionQueryException)?.failure) {
                        NovelSelectionQueryFailure.TooLong -> R.string.hayai_novel_lookup_query_too_long
                        else -> R.string.hayai_novel_lookup_query_blank
                    }
                toast(message)
                return
            }
        val settings = translationSettings.get()
        lookupLauncher.launch(
            NovelLookupRequest(
                action =
                    when (action) {
                        NovelSelectionAction.Define -> NovelLookupAction.Define
                        NovelSelectionAction.GoogleTranslate -> NovelLookupAction.GoogleTranslate
                        NovelSelectionAction.SearchWeb -> NovelLookupAction.SearchWeb
                        NovelSelectionAction.SaveQuote -> return
                    },
                query = query,
                sourceLanguage = settings.sourceLanguage,
                targetLanguage = settings.targetLanguage,
            ),
        )
    }

    override fun onSelectionModeChanged(active: Boolean) {
        selectionModeActive = active
        if (active) pauseAutoScrollForInterruption() else resumeInterruptedAutoScroll()
    }

    private fun showLookupSheet(uri: Uri, query: String): Boolean {
        if (isFinishing || isDestroyed) return false
        return try {
            lookupSheet?.dismiss()
            pauseAutoScrollForInterruption()
            lateinit var sheet: NovelLookupWebSheet
            sheet =
                NovelLookupWebSheet(this, uri, query) {
                    if (lookupSheet === sheet) {
                        lookupSheet = null
                        resumeInterruptedAutoScroll()
                    }
                }
            lookupSheet = sheet
            sheet.show()
            true
        } catch (_: RuntimeException) {
            lookupSheet = null
            resumeInterruptedAutoScroll()
            false
        }
    }

    override fun onReady(progress: Int) = onDocumentReady()

    override fun onProgress(progress: Int) = onReaderProgress(progress)

    override fun onVisibleChapter(chapterId: Long, progress: Int) {
        val target = chapterQueue.find(chapterId) ?: return
        val current = loaded
        if (current?.chapter?.id == chapterId) return
        val crossed =
            current?.let { from ->
                if (target.position > from.position) {
                    chapterQueue.snapshot().filter { it.position in from.position until target.position }
                } else {
                    listOf(from)
                }
            }.orEmpty()
        val previousProgress = currentProgress
        lifecycleScope.launch(Dispatchers.IO) {
            progressWriteMutex.withLock {
                crossed.sortedBy(LoadedNovelChapter::position).forEach { crossedChapter ->
                    val progress = if (target.position > (current?.position ?: target.position)) 100 else previousProgress
                    session.saveProgress(crossedChapter.chapter, progress, preferences.novelMarkAsReadThreshold.get())
                }
                session.saveProgress(target.chapter, progress.coerceIn(0, 100), preferences.novelMarkAsReadThreshold.get())
            }
        }
        if (!session.focus(chapterId)) return
        chapterQueue.focus(chapterId)
        retainChapterWindow(target.position)
        loaded = target
        currentProgress = progress.coerceIn(0, 100)
        autoAppendArmed = currentProgress < preferences.novelAutoLoadNextChapterAt.get().coerceIn(1, 100)
        autoPrependArmed = currentProgress > PREVIOUS_CHAPTER_PRELOAD_THRESHOLD
        updateChapterChrome(target)
        updateProgressSlider(currentProgress)
        updateStatus()
        if (ttsController.isPlaying) ttsController.stop()
        ttsController.setChapter(requireNotNull(target.manga.id), chapterId, target.manga.title, target.chapter.name)
        restorePersistentHighlights(target)
    }

    override fun onRetryChapter(chapterId: Long) {
        val now = System.currentTimeMillis()
        if ((chapterFailureCooldowns[chapterId] ?: 0L) > now) return
        val next = session.adjacent(true)?.id == chapterId
        val previous = session.adjacent(false)?.id == chapterId
        if (!next && !previous) {
            toast(R.string.hayai_novel_reader_not_adjacent)
            return
        }
        chapterFailureCooldowns.remove(chapterId)
        navigate(next)
    }

    override fun onTap(xFraction: Float, yFraction: Float) {
        if (!preferences.novelTapToScroll.get()) {
            dispatch(NovelReaderAction.ToggleChrome)
            return
        }
        when (
            NovelTapZones.action(
                preferences.novelNavigationMode.get(),
                xFraction,
                yFraction,
                NovelTapInversion.parse(preferences.novelNavigationInverted.get()),
            )
        ) {
            NovelTapAction.Previous -> stepReader(-1)
            NovelTapAction.Next -> stepReader(1)
            NovelTapAction.Menu -> dispatch(NovelReaderAction.ToggleChrome)
            NovelTapAction.None -> Unit
        }
    }

    override fun onContentEdited(content: String) {
        if (content.isBlank()) toast(R.string.hayai_novel_reader_edit_empty)
    }

    override fun onRendererError(message: String) = toast(message)

    companion object {
        private const val MENU_BOOKMARK = 0x484100
        private const val MENU_RELOAD = 0x484101
        private const val MENU_OFFLINE = 0x484102
        private const val MENU_STATISTICS = 0x484103
        private const val MENU_HIGHLIGHTS = 0x484104
        private const val MENU_OPEN_WEBVIEW = 0x484105
        private const val MENU_OPEN_BROWSER = 0x484106
        private const val MENU_SHARE = 0x484107
        private const val MENU_SETTINGS = 0x484108
        private const val EXTRA_MANGA_ID = "hayai.manga_id"
        private const val EXTRA_CHAPTER_ID = "hayai.chapter_id"
        private const val DEFAULT_HIGHLIGHT_COLOR = 0xFFFFEB3B.toInt()
        private const val CHAPTER_RETRY_COOLDOWN_MS = 15_000L
        private const val PREVIOUS_CHAPTER_PRELOAD_THRESHOLD = 5

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
