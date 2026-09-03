package dev.ahmedmohamed.hayai.novel.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import dev.ahmedmohamed.hayai.novel.dictionary.NovelDictionaryLauncher
import dev.ahmedmohamed.hayai.novel.dictionary.NovelDictionarySettingsStore
import dev.ahmedmohamed.hayai.novel.download.NovelOfflineManager
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import dev.ahmedmohamed.hayai.novel.highlight.NovelHighlightStore
import dev.ahmedmohamed.hayai.novel.highlight.NovelHighlight
import dev.ahmedmohamed.hayai.novel.highlight.NovelHighlightAnchor
import dev.ahmedmohamed.hayai.novel.lookup.NovelLookupAction
import dev.ahmedmohamed.hayai.novel.lookup.NovelLookupLauncher
import dev.ahmedmohamed.hayai.novel.lookup.NovelLookupRequest
import dev.ahmedmohamed.hayai.novel.lookup.NovelLookupWebSheet
import dev.ahmedmohamed.hayai.novel.lookup.NovelSelectionQuery
import dev.ahmedmohamed.hayai.novel.quote.NovelQuoteStore
import dev.ahmedmohamed.hayai.novel.quote.NovelQuote
import dev.ahmedmohamed.hayai.novel.quote.QuoteAddResult
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationHash
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationLocator
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationPresentation
import dev.ahmedmohamed.hayai.novel.translation.NovelOfflineTranslationWorker
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationService
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationSettingsStore
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationText
import dev.ahmedmohamed.hayai.novel.translation.SqliteNovelTranslationStore
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.main.SearchActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterSheet
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Closeable
import java.text.NumberFormat

internal class NovelReaderAttachment(
    private val activity: ReaderActivity,
    private val manga: Manga,
    private val source: Source,
) : Closeable,
    NovelReaderActionHost,
    NovelTtsController.Callbacks {
    private val database = Injekt.get<DatabaseHelper>()
    private val preferences = HayaiPreferences(Injekt.get<PreferenceStore>())
    private val quoteStore = NovelQuoteStore(database)
    private val highlightStore = NovelHighlightStore(database)
    private val translationSettings = NovelTranslationSettingsStore(activity)
    private val translationStore = SqliteNovelTranslationStore(database)
    private val translationService = NovelTranslationService(Injekt.get<NetworkHelper>(), translationStore)
    private val offlineManager = NovelOfflineManager(activity, database, Injekt.get<SourceManager>(), Injekt.get<NetworkHelper>())
    private val fontStore = NovelFontStore(activity, preferences)
    private val dictionary = NovelDictionaryLauncher(activity)
    private val dictionarySettings = NovelDictionarySettingsStore(activity)
    private val lookupLauncher = NovelLookupLauncher(activity, ::showLookupSheet)
    private val tts = NovelTtsController(activity, this)
    val viewer = NovelReaderViewer(activity, preferences, this)
    private var content: NovelChapterContent? = null
    private var editMode = false
    private var closed = false
    private var autoScroll = false
    private var autoScrollRunnable: Runnable? = null
    private var resumeAutoScrollAfterSelection = false
    private var resumeAutoScrollAfterPause = false
    private var displayedLanguageTag = ""
    private var bookmarkMenuItem: MenuItem? = null
    private var lookupSheet: NovelLookupWebSheet? = null
    private val ttsChapterHandoff = NovelTtsChapterHandoff()
    private val fontImportLauncher =
        activity.activityResultRegistry.register(
            "hayai-novel-font-${System.identityHashCode(this)}",
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri ?: return@register
            activity.scope.launch {
                val result = withContext(Dispatchers.IO) { fontStore.importFont(uri) }
                result.fold(
                    onSuccess = { font ->
                        preferences.novelFontFamily.set(fontStore.token(font))
                        viewer.refreshStyle()
                        activity.toast(activity.getString(R.string.hayai_novel_reader_imported_font_success, font.name))
                    },
                    onFailure = { activity.toast(activity.novelFailureMessage(it, R.string.hayai_novel_reader_imported_font_error)) },
                )
            }
        }

    fun bindBottomActions() {
        val sheet = activity.findViewById<ReaderChapterSheet>(R.id.chapters_sheet)?.binding ?: return
        val configured = NovelBottomActions.deserialize(preferences.novelBottomBarItems.get())
        val enabled = configured.filter(NovelBottomActionState::enabled).map(NovelBottomActionState::action)
        val actions =
            if (tts.isPlaying) {
                listOf(
                    NovelBottomAction.TtsViewport,
                    NovelBottomAction.TtsPreviousParagraph,
                    NovelBottomAction.TtsNextParagraph,
                    NovelBottomAction.Tts,
                )
            } else {
                enabled.filterNot {
                    it == NovelBottomAction.PreviousChapter ||
                        it == NovelBottomAction.NextChapter ||
                        it == NovelBottomAction.Settings
                }
            }
        val slots =
            listOf(
                sheet.webviewButton,
                sheet.readingMode,
                sheet.rotationSheetButton,
                sheet.cropBordersSheetButton,
                sheet.doublePage,
                sheet.shiftPageButton,
            )
        slots.forEachIndexed { index, button ->
            actions.getOrNull(index)?.let { bindBottomAction(button, it) } ?: run { button.isVisible = false }
        }
        sheet.chaptersButton.isVisible = true
        sheet.displayOptions.apply {
            isVisible = NovelBottomAction.Settings in enabled
            setOnClickListener { showSettings() }
            setOnLongClickListener {
                showSettings()
                true
            }
        }
    }

    private fun bindBottomAction(button: MaterialButton, action: NovelBottomAction) {
        val (icon, label) =
            when (action) {
                NovelBottomAction.ScrollToTop -> R.drawable.ic_arrow_upward_24dp to R.string.hayai_novel_reader_scroll_top
                NovelBottomAction.Translate -> R.drawable.ic_translate_24dp to R.string.hayai_novel_reader_select_translate
                NovelBottomAction.AutoScroll -> (if (autoScroll) R.drawable.ic_pause_24dp else R.drawable.ic_swap_vert_24dp) to (if (autoScroll) R.string.hayai_novel_reader_stop_auto_scroll else R.string.hayai_novel_reader_auto_scroll)
                NovelBottomAction.Tts ->
                    if (tts.isPlaying) {
                        R.drawable.ic_close_circle_24dp to R.string.hayai_novel_reader_stop_tts
                    } else {
                        R.drawable.ic_record_voice_over_24dp to R.string.hayai_novel_reader_read_aloud
                    }
                NovelBottomAction.TtsViewport -> R.drawable.ic_text_fields_24dp to R.string.hayai_novel_reader_read_viewport
                NovelBottomAction.TtsPreviousParagraph -> R.drawable.ic_skip_previous_24 to R.string.hayai_novel_reader_previous_paragraph
                NovelBottomAction.TtsNextParagraph -> R.drawable.ic_skip_next_24 to R.string.hayai_novel_reader_next_paragraph
                NovelBottomAction.Orientation -> R.drawable.ic_screen_rotation_24dp to R.string.hayai_novel_reader_change_orientation
                NovelBottomAction.Edit -> R.drawable.ic_edit_24dp to R.string.hayai_novel_reader_edit_chapter
                NovelBottomAction.Quotes -> R.drawable.ic_format_quote_24dp to R.string.hayai_novel_reader_quotes
                NovelBottomAction.PreviousChapter,
                NovelBottomAction.NextChapter,
                NovelBottomAction.Settings,
                -> return
            }
        button.apply {
            isVisible = true
            setIconResource(icon)
            contentDescription = activity.getString(label)
            tooltipText = contentDescription
            setOnClickListener {
                when (action) {
                    NovelBottomAction.ScrollToTop -> viewer.scrollToTop()
                    NovelBottomAction.Translate -> viewer.selection { it?.selectedText?.let(::translate) }
                    NovelBottomAction.AutoScroll -> if (autoScroll) stopAutoScroll() else startAutoScroll()
                    NovelBottomAction.Tts -> if (tts.isPlaying) tts.stop() else toggleTts()
                    NovelBottomAction.TtsViewport -> viewer.viewportParagraph(::startTts)
                    NovelBottomAction.TtsPreviousParagraph -> tts.previousParagraph()
                    NovelBottomAction.TtsNextParagraph -> tts.nextParagraph()
                    NovelBottomAction.Orientation -> cycleOrientation()
                    NovelBottomAction.Edit -> dispatch(NovelReaderAction.ToggleEditMode)
                    NovelBottomAction.Quotes -> showQuotes()
                    NovelBottomAction.PreviousChapter,
                    NovelBottomAction.NextChapter,
                    NovelBottomAction.Settings,
                    -> Unit
                }
            }
        }
    }

    private val quoteImportLauncher =
        activity.activityResultRegistry.register(
            "hayai-novel-quotes-${System.identityHashCode(this)}",
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            val current = content ?: return@register
            if (uris.isEmpty()) return@register
            activity.scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val documents =
                                uris.map { uri ->
                                    activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                        ?: error("Unable to read the selected quote file")
                                }
                            quoteStore.importLegacyJson(
                                documents = documents,
                                currentMangaId = requireNotNull(current.manga.id),
                                currentNovelName = current.manga.title,
                            )
                        }
                    }
                result.fold(
                    onSuccess = { imported ->
                        val message =
                            when {
                                imported.invalidDocuments + imported.unmatchedDocuments > 0 ->
                                    activity.getString(
                                        R.string.hayai_novel_reader_quote_import_partial,
                                        imported.insertedQuotes,
                                        imported.invalidDocuments,
                                        imported.unmatchedDocuments,
                                    )
                                imported.insertedQuotes > 0 ->
                                    activity.getString(R.string.hayai_novel_reader_quote_imported, imported.insertedQuotes)
                                else -> activity.getString(R.string.hayai_novel_reader_quote_imported_existing)
                            }
                        activity.toast(message)
                        showQuotes()
                    },
                    onFailure = { activity.toast(R.string.hayai_novel_reader_quote_import_failed) },
                )
            }
        }

    fun bindToolbarMenu(menu: Menu) {
        bookmarkMenuItem =
            menu.add(Menu.NONE, MENU_BOOKMARK, Menu.NONE, R.string.hayai_novel_reader_bookmark).apply {
                setIcon(R.drawable.ic_bookmark_border_24dp)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        menu.add(Menu.NONE, MENU_QUOTES, Menu.NONE, R.string.hayai_novel_reader_saved_quotes)
        menu.add(Menu.NONE, MENU_ADD_HIGHLIGHT, Menu.NONE, R.string.hayai_novel_reader_highlight)
        menu.add(Menu.NONE, MENU_HIGHLIGHTS, Menu.NONE, R.string.hayai_novel_reader_highlights)
        menu.add(Menu.NONE, MENU_STATISTICS, Menu.NONE, R.string.hayai_novel_reader_chapter_statistics)
        menu.add(Menu.NONE, MENU_OFFLINE, Menu.NONE, R.string.hayai_novel_reader_toggle_offline)
        menu.add(Menu.NONE, MENU_TRANSLATE, Menu.NONE, R.string.hayai_novel_reader_translate_chapter)
        menu.add(Menu.NONE, MENU_OPEN_SOURCE, Menu.NONE, R.string.open_in_webview)
        menu.add(Menu.NONE, MENU_OPEN_EXTERNAL, Menu.NONE, R.string.open_in_browser)
        menu.add(Menu.NONE, MENU_SHARE, Menu.NONE, R.string.share)
        updateBookmarkMenu()
    }

    fun onToolbarItem(item: MenuItem): Boolean =
        when (item.itemId) {
            MENU_BOOKMARK -> true.also { toggleBookmark() }
            MENU_QUOTES -> true.also { showQuotes() }
            MENU_ADD_HIGHLIGHT -> true.also { captureHighlight() }
            MENU_HIGHLIGHTS -> true.also { showHighlights() }
            MENU_STATISTICS -> true.also { showStatistics() }
            MENU_OFFLINE -> true.also { toggleOffline() }
            MENU_TRANSLATE -> true.also { translateChapter() }
            MENU_OPEN_SOURCE -> true.also { openSource(inApp = true) }
            MENU_OPEN_EXTERNAL -> true.also { openSource(inApp = false) }
            MENU_SHARE -> true.also { shareChapter() }
            else -> false
        }

    fun showSettings() {
        NovelReaderSettingsSheet(
            activity,
            preferences,
            onStyleChanged = viewer::refreshStyle,
            onChromeChanged = {
                applyWindowPreferences()
                bindBottomActions()
            },
            onAction = ::dispatch,
        ).show()
    }

    val isFullscreenEnabled: Boolean
        get() = preferences.novelFullscreen.get()

    fun applyWindowPreferences() {
        if (preferences.novelKeepScreenOn.get()) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        activity.requestedOrientation = orientationValue(preferences.novelOrientation.get())
        val brightness =
            if (preferences.novelCustomBrightness.get()) {
                (preferences.novelCustomBrightnessValue.get().coerceIn(-100, 100) + 100) / 200f
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            if (isFullscreenEnabled && !activity.menuVisible && !activity.isInMultiWindowMode) {
                hide(systemBars())
            } else {
                show(systemBars())
            }
        }
    }

    fun onPause() {
        if (autoScroll) {
            resumeAutoScrollAfterPause = true
            stopAutoScroll()
        }
    }

    fun onResume() {
        if (resumeAutoScrollAfterPause && !closed) {
            resumeAutoScrollAfterPause = false
            startAutoScroll()
        }
    }

    override fun onChapterContent(content: NovelChapterContent) {
        this.content = content
        displayedLanguageTag = content.translatedOfflineLanguage.orEmpty()
        val mangaId = requireNotNull(content.manga.id) { "Novel is missing its durable database identity" }
        val chapterId = requireNotNull(content.chapter.id) { "Novel chapter is missing its durable database identity" }
        ttsChapterHandoff.cancelUnlessTarget(chapterId)
        tts.setChapter(mangaId, chapterId, content.manga.title, content.chapter.name)
        updateBookmarkMenu()
    }

    override fun onDocumentReady() {
        val readyChapterId = content?.chapter?.id ?: return
        if (preferences.novelMarkShortChapterAsRead.get()) {
            viewer.isShort { short -> if (short) viewer.markCurrentChapterRead(readyChapterId) }
        }
        restoreHighlights()
        resolveDisplayedTranslation(readyChapterId)
    }

    override fun onSelectionAction(action: NovelSelectionAction, selection: NovelSelection) {
        when (action) {
            NovelSelectionAction.SaveQuote -> saveQuote(selection.selectedText)
            else -> launchLookup(action, selection.selectedText)
        }
    }

    override fun onSelectionModeChanged(active: Boolean) {
        if (active && autoScroll) {
            resumeAutoScrollAfterSelection = true
            stopAutoScroll()
        } else if (!active && resumeAutoScrollAfterSelection) {
            resumeAutoScrollAfterSelection = false
            startAutoScroll()
        }
    }

    override fun onContentEdited(content: String) {
        if (content.isBlank()) activity.toast(R.string.hayai_novel_reader_edit_empty)
    }

    override fun onRendererError(message: String) = activity.toast(message)

    override fun onParagraphChanged(index: Int) {
        if (preferences.novelTtsEnableHighlight.get()) viewer.highlightSpokenParagraph(index)
    }

    override fun onHighlightCleared() {
        viewer.clearSpokenHighlight()
    }
    override fun onPlaybackChanged(playing: Boolean) = bindBottomActions()

    override fun onChapterCompleted() {
        viewer.markCurrentChapterRead()
        val nextChapterId = viewer.nextChapterId
        if (preferences.novelTtsAutoNextChapter.get() && nextChapterId != null) {
            ttsChapterHandoff.schedule(nextChapterId)
            viewer.moveToNext()
        }
    }

    override fun onError(message: String) = activity.toast(message)
    override fun runOnUiThread(action: () -> Unit) = activity.runOnUiThread(action)

    override fun close() {
        if (closed) return
        closed = true
        lookupSheet?.dismiss()
        lookupSheet = null
        fontImportLauncher.unregister()
        quoteImportLauncher.unregister()
        stopAutoScroll()
        ttsChapterHandoff.clear()
        tts.destroy()
    }

    private fun dispatch(action: NovelReaderAction) {
        when (action) {
            is NovelReaderAction.Navigate -> if (action.direction > 0) viewer.moveToNext() else viewer.moveToPrevious()
            is NovelReaderAction.Seek -> viewer.seek(action.progress)
            NovelReaderAction.ToggleChrome -> activity.toggleMenu()
            NovelReaderAction.ToggleTts -> toggleTts()
            NovelReaderAction.StartTtsAtViewport -> viewer.viewportParagraph { startTts(it) }
            NovelReaderAction.PreviousTtsParagraph -> tts.previousParagraph()
            NovelReaderAction.NextTtsParagraph -> tts.nextParagraph()
            NovelReaderAction.ShowSettings -> showSettings()
            NovelReaderAction.ShowQuotes -> showQuotes()
            NovelReaderAction.SaveQuote -> viewer.selection { saveQuote(it?.selectedText.orEmpty()) }
            NovelReaderAction.ToggleEditMode -> {
                editMode = !editMode
                viewer.setEditMode(editMode)
                activity.toast(if (editMode) R.string.hayai_novel_reader_editing_enabled else R.string.hayai_novel_reader_editing_finished)
            }
            NovelReaderAction.ToggleBookmark -> toggleBookmark()
            NovelReaderAction.ToggleAutoScroll -> if (autoScroll) stopAutoScroll() else startAutoScroll()
            NovelReaderAction.ToggleOrientation -> cycleOrientation()
            NovelReaderAction.TranslateSelection -> viewer.selection { it?.selectedText?.let(::translate) }
            NovelReaderAction.TranslateChapter -> translateChapter()
            NovelReaderAction.TranslateAllChapters -> translateAllChapters()
            NovelReaderAction.DictionaryLookup -> viewer.selection { selection ->
                selection?.selectedText?.let { text ->
                    runCatching { dictionary.open(text, dictionarySettings.get()) }
                        .onFailure { activity.toast(activity.novelFailureMessage(it, R.string.hayai_novel_reader_dictionary_failed)) }
                }
            }
            NovelReaderAction.ShowStatistics -> showStatistics()
            NovelReaderAction.ToggleOffline -> toggleOffline()
            NovelReaderAction.ShowHighlights -> showHighlights()
            NovelReaderAction.ImportFont -> fontImportLauncher.launch(arrayOf("font/*", "application/font-sfnt", "application/octet-stream"))
            NovelReaderAction.ManageFonts -> showImportedFonts()
            NovelReaderAction.OpenFullSettings -> activity.startActivity(SearchActivity.openReaderSettings(activity))
        }
    }

    private fun toggleTts() {
        if (tts.isPlaying) {
            tts.pause()
        } else {
            startTts(0)
        }
    }

    private fun startTts(paragraph: Int) {
        viewer.paragraphs { paragraphs ->
            tts.configure(
                preferences.novelTtsSpeed.get(),
                preferences.novelTtsPitch.get(),
                preferences.novelTtsVoice.get(),
                displayedLanguageTag,
                preferences.novelTtsBackgroundPlayback.get(),
            )
            tts.setParagraphs(paragraphs, paragraph)
            tts.play()
        }
    }

    private fun startAutoScroll() {
        if (autoScroll || closed) return
        autoScroll = true
        val delay = (110L - preferences.novelAutoScrollSpeed.get().coerceIn(1, 20) * 5L).coerceAtLeast(10L)
        autoScrollRunnable =
            object : Runnable {
                override fun run() {
                    if (!autoScroll || closed) return
                    viewer.stepPixels(2)
                    viewer.getView().postDelayed(this, delay)
                }
            }.also(viewer.getView()::post)
        bindBottomActions()
    }

    private fun stopAutoScroll() {
        autoScroll = false
        autoScrollRunnable?.let(viewer.getView()::removeCallbacks)
        autoScrollRunnable = null
        if (!closed) bindBottomActions()
    }

    private fun toggleBookmark() {
        val chapter = content?.chapter ?: return
        activity.viewModel.toggleBookmark(chapter)
        updateBookmarkMenu()
        activity.toast(if (chapter.bookmark) R.string.hayai_novel_reader_chapter_bookmarked else R.string.hayai_novel_reader_bookmark_removed)
    }

    private fun updateBookmarkMenu() {
        val bookmarked = content?.chapter?.bookmark == true
        bookmarkMenuItem?.apply {
            title = activity.getString(if (bookmarked) R.string.hayai_novel_reader_remove_bookmark else R.string.hayai_novel_reader_bookmark)
            setIcon(if (bookmarked) R.drawable.ic_bookmark_24dp else R.drawable.ic_bookmark_border_24dp)
        }
    }

    private fun showStatistics() {
        val current = content ?: return
        val number = NumberFormat.getIntegerInstance()
        val progress = current.chapter.last_page_read.coerceIn(0, 100)
        AlertDialog.Builder(activity)
            .setTitle(R.string.hayai_novel_reader_chapter_statistics)
            .setMessage(activity.getString(R.string.hayai_novel_reader_statistics_message, number.format(current.statistics.wordCount), current.statistics.estimatedMinutes(), number.format(current.statistics.wordsRead(progress)), current.statistics.remainingMinutes(progress), activity.getString(R.string.hayai_novel_reader_percent_value, progress)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun saveQuote(initial: String) {
        val current = content ?: return
        val input = EditText(activity).apply {
            setText(initial)
            minLines = 4
            setHint(R.string.hayai_novel_reader_quote)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.hayai_novel_reader_save_quote)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                activity.scope.launch(Dispatchers.IO) {
                    val result = runCatching { quoteStore.add(requireNotNull(current.manga.id), current.manga.title, current.chapter.name, input.text.toString(), "") }
                    withContext(Dispatchers.Main) {
                        result.fold(
                            onSuccess = { activity.toast(if (it is QuoteAddResult.Created) R.string.hayai_novel_reader_quote_saved else R.string.hayai_novel_reader_quote_duplicate) },
                            onFailure = { activity.toast(activity.novelFailureMessage(it, R.string.hayai_novel_reader_quote_save_error)) },
                        )
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showQuotes() {
        val mangaId = content?.manga?.id ?: return
        activity.scope.launch {
            val quotes = withContext(Dispatchers.IO) { quoteStore.forManga(mangaId) }
            val labels = quotes.map { activity.getString(R.string.hayai_novel_reader_quote_list_item, it.chapterName, it.displayedContent.replace('\n', ' ').take(80)) }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle(R.string.hayai_novel_reader_saved_quotes)
                .setItems(labels) { _, index -> showQuote(quotes[index]) }
                .setNeutralButton(R.string.hayai_novel_reader_import_quotes) { _, _ ->
                    quoteImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showQuote(quote: NovelQuote) {
        AlertDialog.Builder(activity)
            .setTitle(quote.chapterName)
            .setMessage(quote.displayedContent)
            .setItems(
                arrayOf(
                    activity.getString(R.string.copy_value),
                    activity.getString(R.string.hayai_novel_reader_copy_attribution),
                    activity.getString(R.string.edit),
                    activity.getString(R.string.hayai_novel_reader_move_earlier),
                    activity.getString(R.string.hayai_novel_reader_move_later),
                    activity.getString(R.string.delete),
                ),
            ) { _, action ->
                when (action) {
                    0 -> copyQuote(quote.displayedContent)
                    1 -> copyQuote(activity.getString(R.string.hayai_novel_reader_quote_attribution, quote.displayedContent, quote.novelName, quote.chapterName))
                    2 -> editQuote(quote)
                    3 -> moveQuote(quote, -1)
                    4 -> moveQuote(quote, 1)
                    5 -> deleteQuote(quote)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyQuote(text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.hayai_novel_reader_quote_clipboard), text))
        activity.toast(R.string.hayai_novel_reader_quote_copied)
    }

    private fun editQuote(quote: NovelQuote) {
        val body = EditText(activity).apply {
            setText(quote.displayedContent)
            minLines = 4
            gravity = Gravity.TOP
        }
        val chapter = EditText(activity).apply {
            setText(quote.chapterName)
            setSingleLine()
        }
        val language = EditText(activity).apply {
            setText(quote.language.orEmpty())
            setSingleLine()
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 8.dp, 20.dp, 8.dp)
            addView(chapter)
            addView(language)
            addView(body)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.hayai_novel_reader_edit_quote)
            .setView(form)
            .setPositiveButton(R.string.save) { _, _ ->
                activity.scope.launch {
                    val updated = withContext(Dispatchers.IO) { quoteStore.update(quote.id, body.text.toString(), chapter.text.toString(), language.text.toString()) }
                    activity.toast(if (updated == null) R.string.hayai_novel_reader_quote_missing else R.string.hayai_novel_reader_quote_updated)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun moveQuote(quote: NovelQuote, direction: Int) {
        activity.scope.launch {
            val moved = withContext(Dispatchers.IO) { quoteStore.move(quote.mangaId, quote.id, direction) }
            activity.toast(if (moved) R.string.hayai_novel_reader_quote_reordered else R.string.hayai_novel_reader_quote_at_edge)
        }
    }

    private fun deleteQuote(quote: NovelQuote) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.hayai_novel_reader_delete_quote)
            .setMessage(R.string.hayai_novel_reader_cannot_undo)
            .setPositiveButton(R.string.delete) { _, _ ->
                activity.scope.launch {
                    val deleted = withContext(Dispatchers.IO) { quoteStore.delete(quote.id) }
                    activity.toast(if (deleted) R.string.hayai_novel_reader_quote_deleted else R.string.hayai_novel_reader_quote_missing)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun restoreHighlights() {
        val current = content ?: return
        activity.scope.launch {
            val highlights = withContext(Dispatchers.IO) { highlightStore.forStableChapter(current.manga.source, current.manga.url, current.chapter.url) }
            if (content?.chapter?.id != current.chapter.id) return@launch
            viewer.applyHighlights(highlights.map { NovelPersistentHighlight(it.id, it.anchor.exact, it.anchor.prefix, it.anchor.suffix, it.anchor.occurrence, it.color) })
        }
    }

    private fun captureHighlight() {
        val current = content ?: return
        viewer.selection { selection ->
            val capture = selection ?: return@selection activity.toast(R.string.hayai_novel_reader_select_highlight)
            val anchor =
                NovelHighlightAnchor.fromContext(
                    capture.documentText,
                    capture.selectedText,
                    capture.prefix,
                    capture.suffix,
                    capture.occurrence,
                ) ?: return@selection activity.toast(R.string.hayai_novel_reader_select_highlight)
            activity.scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            highlightStore.create(
                                requireNotNull(current.manga.id),
                                requireNotNull(current.chapter.id),
                                current.manga.source,
                                current.manga.url,
                                current.chapter.url,
                                DEFAULT_HIGHLIGHT_COLOR,
                                null,
                                anchor,
                            )
                        }
                    }
                result.fold(
                    onSuccess = {
                        restoreHighlights()
                        activity.toast(R.string.hayai_novel_reader_highlight_saved)
                    },
                    onFailure = { activity.toast(activity.novelFailureMessage(it, R.string.hayai_novel_reader_highlight_save_error)) },
                )
            }
        }
    }

    private fun showHighlights() {
        val current = content ?: return
        activity.scope.launch {
            val highlights = withContext(Dispatchers.IO) { highlightStore.forStableChapter(current.manga.source, current.manga.url, current.chapter.url) }
            if (highlights.isEmpty()) return@launch activity.toast(R.string.hayai_novel_reader_no_highlights)
            AlertDialog.Builder(activity)
                .setTitle(R.string.hayai_novel_reader_highlights)
                .setItems(highlights.map { it.note?.let { note -> "$note · ${it.anchor.exact.take(100)}" } ?: it.anchor.exact.take(100) }.toTypedArray()) { _, index ->
                    showHighlight(highlights[index])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showHighlight(highlight: NovelHighlight) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.hayai_novel_reader_highlight)
            .setMessage(highlight.anchor.exact)
            .setPositiveButton(R.string.edit) { _, _ -> editHighlight(highlight) }
            .setNeutralButton(R.string.delete) { _, _ ->
                activity.scope.launch {
                    withContext(Dispatchers.IO) { highlightStore.delete(highlight.id) }
                    restoreHighlights()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editHighlight(highlight: NovelHighlight) {
        val note = EditText(activity).apply {
            setHint(R.string.hayai_novel_reader_optional_note)
            setText(highlight.note)
        }
        val colors = intArrayOf(0xFFFFEB3B.toInt(), 0xFF80DEEA.toInt(), 0xFFA5D6A7.toInt(), 0xFFF8BBD0.toInt(), 0xFFFFCC80.toInt())
        var selectedColor = colors.indexOf(highlight.color).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(activity)
            .setTitle(R.string.hayai_novel_reader_edit_highlight)
            .setView(note)
            .setSingleChoiceItems(
                arrayOf(
                    activity.getString(R.string.hayai_novel_reader_yellow),
                    activity.getString(R.string.hayai_novel_reader_cyan),
                    activity.getString(R.string.hayai_novel_reader_green),
                    activity.getString(R.string.hayai_novel_reader_pink),
                    activity.getString(R.string.hayai_novel_reader_orange),
                ),
                selectedColor,
            ) { _, which -> selectedColor = which }
            .setPositiveButton(R.string.save) { _, _ ->
                activity.scope.launch {
                    withContext(Dispatchers.IO) { highlightStore.update(highlight.id, colors[selectedColor], note.text.toString()) }
                    restoreHighlights()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun translateChapter() {
        val current = content ?: return
        activity.scope.launch {
            val text = withContext(Dispatchers.Default) { NovelTranslationText.canonical(current.document) }
            translate(text, replaceDocument = true, locator = current.translationLocator())
        }
    }

    private fun translateAllChapters() {
        activity.scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val chapters = database.getChapters(manga).executeAsBlocking()
                        NovelOfflineTranslationWorker.enqueueAll(activity, manga, chapters, translationSettings.get())
                    }
                }
            result.fold(
                onSuccess = { queued -> activity.toast(activity.getString(R.string.hayai_novel_translation_offline_queued, queued)) },
                onFailure = { activity.toast(activity.novelFailureMessage(it, R.string.hayai_novel_reader_translation_failed)) },
            )
        }
    }

    private fun translate(
        text: String,
        replaceDocument: Boolean = false,
        locator: NovelTranslationLocator? = null,
    ) {
        if (text.isBlank()) return
        activity.scope.launch {
            val settings = translationSettings.get()
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        locator?.let { translationService.translate(it, text, settings) }
                            ?: translationService.translateText(text, settings)
                    }
                }
            result.fold(
                onSuccess = { translated ->
                    if (replaceDocument) {
                        if (locator == null || content?.chapter?.id == locator.chapterId) {
                            val presentation = NovelTranslationPresentation.from(translated.text, settings.targetLanguage)
                            val preservePlayback = tts.hasActivePlayback
                            displayedLanguageTag = presentation.languageTag
                            viewer.showTranslation(presentation.paragraphs) {
                                restoreHighlights()
                                configureTtsForDisplayedDocument(
                                    chapterId = content?.chapter?.id ?: return@showTranslation,
                                    preservePlayback = preservePlayback,
                                )
                            }
                        }
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.hayai_novel_reader_translate)
                            .setMessage(translated.text)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    Unit
                },
                onFailure = { activity.toast(activity.novelFailureMessage(it, R.string.hayai_novel_reader_translation_failed)) },
            )
        }
    }

    private fun resolveDisplayedTranslation(chapterId: Long) {
        val current = content ?: return
        if (current.chapter.id != chapterId) return
        if (current.translatedOfflineLanguage != null) {
            displayedLanguageTag = current.translatedOfflineLanguage
            configureTtsForDisplayedDocument(chapterId, preservePlayback = false)
            return
        }
        activity.scope.launch {
            val settings = translationSettings.get()
            val text = withContext(Dispatchers.Default) { NovelTranslationText.canonical(current.document) }
            if (text.isBlank()) return@launch
            val stored =
                withContext(Dispatchers.IO) {
                    translationStore.findCompleted(current.translationLocator(), settings.targetLanguage, NovelTranslationHash.sha256(text))
                }
            if (stored != null && content?.chapter?.id == chapterId) {
                val presentation = NovelTranslationPresentation.from(stored.translatedContent, settings.targetLanguage)
                displayedLanguageTag = presentation.languageTag
                viewer.showTranslation(presentation.paragraphs) {
                    restoreHighlights()
                    configureTtsForDisplayedDocument(chapterId, preservePlayback = false)
                }
            } else if (content?.chapter?.id == chapterId) {
                displayedLanguageTag = settings.sourceLanguage.takeUnless { it == "auto" }.orEmpty()
                configureTtsForDisplayedDocument(chapterId, preservePlayback = false)
            }
        }
    }

    private fun configureTtsForDisplayedDocument(
        chapterId: Long,
        preservePlayback: Boolean,
    ) {
        viewer.paragraphs { paragraphs ->
            if (viewer.currentChapterId != chapterId) return@paragraphs
            tts.configure(
                preferences.novelTtsSpeed.get(),
                preferences.novelTtsPitch.get(),
                preferences.novelTtsVoice.get(),
                displayedLanguageTag,
                preferences.novelTtsBackgroundPlayback.get(),
            )
            if (preservePlayback) {
                tts.replaceParagraphs(paragraphs)
            } else {
                tts.setParagraphs(paragraphs)
                if (ttsChapterHandoff.consume(chapterId)) tts.play()
            }
        }
    }

    private fun NovelChapterContent.translationLocator() =
        NovelTranslationLocator(
            chapterId = requireNotNull(chapter.id),
            sourceId = manga.source,
            mangaUrl = manga.url,
            chapterUrl = chapter.url,
        )

    private fun toggleOffline() {
        val current = content ?: return
        activity.scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (current.isDownloaded) {
                            offlineManager.remove(current.manga, listOf(current.chapter))
                        } else {
                            offlineManager.saveChapter(current.manga, current.chapter)
                        }
                    }
                }
            result.fold(
                onSuccess = {
                    val downloaded = !current.isDownloaded
                    (current.assets as? NovelOfflineAwareAssetProvider)?.setChapterOffline(current.chapter.url, downloaded)
                    current.copy(isDownloaded = downloaded).let { refreshed ->
                        content = refreshed
                        viewer.replaceContent(refreshed)
                    }
                    activity.toast(if (downloaded) R.string.hayai_novel_reader_offline_saved else R.string.hayai_novel_reader_offline_removed)
                },
                onFailure = { activity.toast(activity.novelFailureMessage(it, R.string.hayai_novel_reader_offline_error)) },
            )
        }
    }

    private fun openSelectionSearch(text: String) {
        if (text.isBlank()) return
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(text)}")))
    }

    private fun launchLookup(action: NovelSelectionAction, text: String) {
        val query = NovelSelectionQuery.parse(text).getOrElse {
            activity.toast(R.string.hayai_novel_lookup_query_blank)
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

    private fun showLookupSheet(uri: Uri, query: String): Boolean =
        runCatching {
            lookupSheet?.dismiss()
            lateinit var next: NovelLookupWebSheet
            next = NovelLookupWebSheet(activity, uri, query) {
                if (lookupSheet === next) lookupSheet = null
            }
            lookupSheet = next
            next.show()
            true
        }.getOrDefault(false)

    private fun showImportedFonts() {
        val fonts = fontStore.fonts()
        if (fonts.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.hayai_novel_reader_imported_fonts)
                .setMessage(R.string.hayai_novel_reader_no_imported_fonts)
                .setPositiveButton(R.string.hayai_novel_reader_import) { _, _ ->
                    fontImportLauncher.launch(arrayOf("font/*", "application/font-sfnt", "application/octet-stream"))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val selected = preferences.novelFontFamily.get()
        val labels = fonts.map { "${if (fontStore.token(it) == selected) "✓  " else ""}${it.name}" }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.hayai_novel_reader_imported_fonts)
            .setItems(labels) { _, index ->
                val font = fonts[index]
                AlertDialog.Builder(activity)
                    .setTitle(font.name)
                    .setItems(arrayOf(activity.getString(R.string.hayai_novel_reader_use_font), activity.getString(R.string.delete))) { _, action ->
                        if (action == 0) {
                            preferences.novelFontFamily.set(fontStore.token(font))
                        } else {
                            if (selected == fontStore.token(font)) preferences.novelFontFamily.set("sans-serif")
                            fontStore.delete(font.id)
                        }
                        viewer.refreshStyle()
                    }
                    .show()
            }
            .setPositiveButton(R.string.hayai_novel_reader_import) { _, _ ->
                fontImportLauncher.launch(arrayOf("font/*", "application/font-sfnt", "application/octet-stream"))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sourcePageUrl(): String? =
        runCatching { (source as? HttpSource)?.getMangaUrl(manga) }.getOrNull()

    private fun openSource(inApp: Boolean) {
        val url = sourcePageUrl() ?: return activity.toast(R.string.hayai_novel_reader_source_no_page)
        val intent =
            if (inApp) {
                WebViewActivity.newIntent(activity, url, source.id, manga.title)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
        runCatching { activity.startActivity(intent) }
            .onFailure { activity.toast(R.string.hayai_novel_reader_no_page_app) }
    }

    private fun shareChapter() {
        val current = content ?: return
        val text = listOfNotNull(current.manga.title, current.chapter.name, sourcePageUrl()).joinToString("\n")
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.hayai_novel_reader_share_chapter)))
    }

    private fun cycleOrientation() {
        val next = (preferences.novelOrientation.get() + 1) % 3
        preferences.novelOrientation.set(next)
        activity.requestedOrientation = orientationValue(next)
    }

    private fun orientationValue(value: Int): Int =
        when (value) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

    private val Int.dp: Int
        get() = (this * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val MENU_BOOKMARK = 0x484100
        const val MENU_QUOTES = 0x484101
        const val MENU_ADD_HIGHLIGHT = 0x484102
        const val MENU_HIGHLIGHTS = 0x484103
        const val MENU_STATISTICS = 0x484104
        const val MENU_OFFLINE = 0x484105
        const val MENU_TRANSLATE = 0x484106
        const val MENU_OPEN_SOURCE = 0x484107
        const val MENU_OPEN_EXTERNAL = 0x484108
        const val MENU_SHARE = 0x484109
        const val DEFAULT_HIGHLIGHT_COLOR = 0xFFFFEB3B.toInt()
    }
}
