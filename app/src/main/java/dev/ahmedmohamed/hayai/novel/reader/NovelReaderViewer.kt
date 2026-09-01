package dev.ahmedmohamed.hayai.novel.reader

import android.content.res.Configuration
import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import dev.ahmedmohamed.hayai.novel.settings.NovelCustomizationStore
import dev.ahmedmohamed.hayai.novel.source.NovelAssetProvider
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.BaseViewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface NovelReaderActionHost {
    fun onChapterContent(content: NovelChapterContent)
    fun onDocumentReady()
    fun onSelectionAction(action: NovelSelectionAction, selection: NovelSelection)
    fun onSelectionModeChanged(active: Boolean)
    fun onContentEdited(content: String)
    fun onRendererError(message: String)
}

internal class NovelReaderViewer(
    private val activity: ReaderActivity,
    private val preferences: HayaiPreferences,
    private val actions: NovelReaderActionHost,
) : BaseViewer,
    NovelRenderer.Callbacks {
    private val root = FrameLayout(activity)
    private val contentProcessor = NovelContentProcessor()
    private val customization = NovelCustomizationStore(preferences)
    private val fontStore = NovelFontStore(activity, preferences)
    private val contentByChapterId = linkedMapOf<Long, NovelChapterContent>()
    private val assetProvider =
        object : NovelAssetProvider {
            override suspend fun getChapterAsset(chapterUrl: String, assetPath: String) =
                contentByChapterId.values
                    .firstOrNull { it.chapter.url == chapterUrl }
                    ?.assets
                    ?.getChapterAsset(chapterUrl, assetPath)
        }
    private var viewerChapters: ViewerChapters? = null
    private var renderer: NovelRenderer? = null
    private var renderJob: Job? = null
    private var activeContent: NovelChapterContent? = null
    private var currentProgress = 0
    private var currentPageNumber: Int? = null
    private var currentPageCount: Int? = null
    private var destroyed = false

    val currentContent: NovelChapterContent?
        get() = activeContent

    val isRightToLeft: Boolean
        get() = NovelWritingDirection.fromPreference(preferences.novelWritingDirection.get()) == NovelWritingDirection.VerticalRl

    val currentChapterId: Long?
        get() = activeContent?.chapter?.id

    val nextChapterId: Long?
        get() = viewerChapters?.nextChapter?.chapter?.id

    override fun getView(): View = root

    override fun setChapters(chapters: ViewerChapters) {
        viewerChapters = chapters
        val page = chapters.currChapter.pages?.filterIsInstance<NovelProgressPage>()?.firstOrNull() ?: return
        val progress = chapters.currChapter.requestedPage.coerceIn(NovelProgressPage.MIN_PROGRESS, NovelProgressPage.MAX_PROGRESS)
        show(page.content, progress)
    }

    override fun moveToPage(page: ReaderPage, animated: Boolean) {
        val novelPage = page as? NovelProgressPage ?: return
        val currentId = currentContent?.chapter?.id
        if (currentId != novelPage.content.chapter.id) {
            show(novelPage.content, novelPage.index)
        } else {
            currentProgress = novelPage.index
            renderer?.seek(currentProgress)
            reportLocation(currentProgress, currentPageNumber, currentPageCount)
        }
    }

    override fun moveToNext() {
        val next = viewerChapters?.nextChapter
        if (currentProgress >= NovelProgressPage.MAX_PROGRESS && next != null) {
            activity.scope.launch { activity.loadChapter(next.chapter) }
        } else {
            renderer?.step(1)
        }
    }

    override fun moveToPrevious() {
        val previous = viewerChapters?.prevChapter
        if (currentProgress <= NovelProgressPage.MIN_PROGRESS && previous != null) {
            activity.scope.launch { activity.loadChapter(previous.chapter) }
        } else {
            renderer?.step(-1)
        }
    }

    override fun isAtEndOfReader(): Boolean = currentProgress >= NovelProgressPage.MAX_PROGRESS && viewerChapters?.nextChapter == null

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val initialDown = down && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!preferences.novelVolumeKeysScroll.get() || activity.menuVisible) return false
                if (initialDown) moveToNext()
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!preferences.novelVolumeKeysScroll.get() || activity.menuVisible) return false
                if (initialDown) moveToPrevious()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_BUTTON_R1,
            -> {
                if (activity.menuVisible) return false
                if (initialDown) moveToNext()
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_BUTTON_L1,
            -> {
                if (activity.menuVisible) return false
                if (initialDown) moveToPrevious()
            }
            else -> return false
        }
        return true
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0f) moveToNext() else moveToPrevious()
            return true
        }
        if (event.action == MotionEvent.ACTION_MOVE && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            val vertical = event.getAxisValue(MotionEvent.AXIS_Y)
            if (kotlin.math.abs(vertical) < 0.5f) return false
            if (vertical > 0) moveToNext() else moveToPrevious()
            return true
        }
        return false
    }

    fun refreshStyle() {
        currentContent?.let { show(it, currentProgress) }
    }

    fun replaceContent(content: NovelChapterContent) = show(content, currentProgress)

    fun seek(progress: Int) {
        val page = currentNovelPage(progress) ?: return
        moveToPage(page)
    }
    fun markCurrentChapterRead(chapterId: Long? = currentChapterId) {
        if (chapterId != null && currentChapterId == chapterId) seek(NovelProgressPage.MAX_PROGRESS)
    }
    fun scrollToTop() = seek(0)
    fun selection(callback: (NovelSelection?) -> Unit) = renderer?.selection(callback) ?: callback(null)
    fun documentText(callback: (String) -> Unit) = renderer?.documentText(callback) ?: callback("")
    fun paragraphs(callback: (List<String>) -> Unit) = renderer?.paragraphs(callback) ?: callback(emptyList())
    fun viewportParagraph(callback: (Int) -> Unit) = renderer?.viewportParagraph(callback) ?: callback(0)
    fun isShort(callback: (Boolean) -> Unit) = renderer?.isShort(callback) ?: callback(false)
    fun showTranslation(text: String) = renderer?.showTranslation(text)
    fun showOriginal() = renderer?.showOriginal()
    fun applyHighlights(items: List<NovelPersistentHighlight>) = renderer?.applyHighlights(items)
    fun highlightSpokenParagraph(index: Int) = renderer?.highlightSpokenParagraph(index)
    fun clearSpokenHighlight() = renderer?.clearSpokenHighlight()
    fun setEditMode(enabled: Boolean) = renderer?.setEditMode(enabled)
    fun stepPixels(pixels: Int) = renderer?.stepPixels(pixels)

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        renderJob?.cancel()
        renderer?.destroy()
        renderer = null
        activeContent = null
        contentByChapterId.clear()
        root.removeAllViews()
    }

    private fun show(content: NovelChapterContent, progress: Int) {
        if (destroyed) return
        val chapterId = requireNotNull(content.chapter.id) { "Novel chapter is missing its durable database identity" }
        activeContent = content
        contentByChapterId.clear()
        contentByChapterId[chapterId] = content
        currentProgress = progress.coerceIn(0, 100)
        currentPageNumber = null
        currentPageCount = null
        actions.onChapterContent(content)
        val options = contentOptions()
        val style = readerStyle(options)
        val plan = renderPlan()
        renderJob?.cancel()
        renderJob =
            activity.scope.launch {
                val processed = withContext(Dispatchers.Default) { contentProcessor.process(content.document, content.chapter.name, options) }
                if (destroyed || currentContent?.chapter?.id != content.chapter.id) return@launch
                val target = ensureRenderer(plan)
                target.display(
                    NovelRenderBlock(
                        chapterId,
                        content.chapter.name,
                        NovelBlockContent.Ready(
                            NovelRenderRequest(
                                chapterId = chapterId,
                                content = processed,
                                chapterTitle = content.chapter.name,
                                style = style,
                                initialProgress = currentProgress,
                                appendJavaScript = customization.enabledJs(runOnAppend = true),
                            ),
                        ),
                    ),
                    NovelBlockPlacement.ReplaceAll,
                    focus = true,
                )
            }
    }

    private fun ensureRenderer(plan: NovelRenderPlan): NovelRenderer {
        renderer?.takeIf { it.mode == plan.backend }?.let { return it }
        renderer?.destroy()
        val created =
            when (plan.backend) {
                NovelRenderingBackend.Native -> NativeNovelRenderer(activity, this, fontStore)
                NovelRenderingBackend.WebView ->
                    WebNovelRenderer(
                        activity,
                        this,
                        assetProvider,
                        chapterUrl = { currentContent?.chapter?.url.orEmpty() },
                        offline = { currentContent?.isDownloaded == true },
                        chapterUrlForId = { id -> contentByChapterId[id]?.chapter?.url },
                        offlineForId = { id -> contentByChapterId[id]?.isDownloaded },
                        blockMedia = { preferences.novelBlockMedia.get() },
                        showConsoleErrors = { preferences.novelConsoleErrorToast.get() },
                        enableDevTools = preferences.novelWebViewDevTools.get(),
                        fontStore = fontStore,
                    )
            }
        renderer = created
        root.removeAllViews()
        root.addView(created.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return created
    }

    private fun renderPlan(): NovelRenderPlan =
        NovelRenderPlan.resolve(
            NovelRenderingBackend.fromPreference(preferences.novelRenderingBackend.get()),
            NovelLayoutMode.fromPreference(preferences.novelLayoutMode.get()),
            NovelWritingDirection.fromPreference(preferences.novelWritingDirection.get()),
        )

    private fun contentOptions() = preferences.novelContentOptions()

    private fun readerStyle(options: NovelContentOptions): NovelReaderStyle {
        val dark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val colors =
            NovelThemeColors.resolve(
                theme = preferences.novelTheme.get(),
                customBackground = preferences.novelBackgroundColor.get(),
                customText = preferences.novelFontColor.get(),
                appBackground = if (dark) NovelThemeColors.DARK_BACKGROUND else 0xFFFFFBFE.toInt(),
                appText = if (dark) NovelThemeColors.DARK_TEXT else 0xFF1C1B1F.toInt(),
            )
        return NovelReaderStyle(
            fontSize = preferences.novelFontSize.get(),
            fontFamily = preferences.novelFontFamily.get(),
            lineHeight = preferences.novelLineHeight.get(),
            textAlign = preferences.novelTextAlign.get(),
            textColor = colors.second,
            backgroundColor = colors.first,
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
            renderingMode = preferences.novelLayoutMode.get(),
            customCss = listOf(preferences.novelCustomCss.get(), customization.enabledCss()).filter(String::isNotBlank).joinToString("\n"),
            customJs = listOf(preferences.novelCustomJs.get(), customization.enabledJs()).filter(String::isNotBlank).joinToString("\n"),
            ttsHighlightColor = preferences.novelTtsHighlightColor.get(),
            ttsHighlightTextColor = preferences.novelTtsHighlightTextColor.get(),
            ttsHighlightStyle = preferences.novelTtsHighlightStyle.get(),
            keepTtsHighlightInView = preferences.novelTtsKeepHighlightInView.get(),
            writingDirection = NovelWritingDirection.fromPreference(preferences.novelWritingDirection.get()),
        )
    }

    private fun currentNovelPage(progress: Int): NovelProgressPage? =
        viewerChapters?.currChapter?.pages?.getOrNull(progress.coerceIn(0, 100)) as? NovelProgressPage

    private fun reportLocation(progress: Int, pageNumber: Int?, pageCount: Int?) {
        currentProgress = progress.coerceIn(0, 100)
        currentPageNumber = pageNumber
        currentPageCount = pageCount
        val page = currentNovelPage(currentProgress) ?: return
        page.presentation = NovelPagePresentation(currentProgress, pageNumber, pageCount)
        activity.onPageSelected(page, false)
    }

    private fun navigator(): ViewerNavigation {
        val resolved =
            when (preferences.novelNavigationMode.get()) {
                1 -> LNavigation()
                2 -> KindlishNavigation()
                3 -> EdgeNavigation()
                4 -> RightAndLeftNavigation()
                5, 6 -> DisabledNavigation()
                else -> LNavigation()
            }
        resolved.invertMode =
            runCatching { ViewerNavigation.TappingInvertMode.valueOf(preferences.novelNavigationInverted.get()) }
                .getOrDefault(ViewerNavigation.TappingInvertMode.NONE)
        return resolved
    }

    override fun onReady(progress: Int) {
        renderer?.seek(currentProgress)
        reportLocation(currentProgress, currentPageNumber, currentPageCount)
        actions.onDocumentReady()
    }

    override fun onProgress(progress: Int) = reportLocation(progress, null, null)

    override fun onPageLocation(progress: Int, pageNumber: Int, pageCount: Int) = reportLocation(progress, pageNumber, pageCount)

    override fun onVisibleChapter(chapterId: Long, progress: Int) = reportLocation(progress, currentPageNumber, currentPageCount)

    override fun onRetryChapter(chapterId: Long) {
        currentContent?.takeIf { it.chapter.id == chapterId }?.let { show(it, currentProgress) }
    }

    override fun onTap(xFraction: Float, yFraction: Float) {
        when (navigator().getAction(PointF(xFraction, yFraction))) {
            ViewerNavigation.NavigationRegion.MENU -> activity.toggleMenu()
            ViewerNavigation.NavigationRegion.NEXT,
            ViewerNavigation.NavigationRegion.RIGHT,
            -> moveToNext()
            ViewerNavigation.NavigationRegion.PREV,
            ViewerNavigation.NavigationRegion.LEFT,
            -> moveToPrevious()
        }
    }

    override fun onSelectionAction(action: NovelSelectionAction, selection: NovelSelection) = actions.onSelectionAction(action, selection)
    override fun onSelectionModeChanged(active: Boolean) = actions.onSelectionModeChanged(active)
    override fun onContentEdited(content: String) = actions.onContentEdited(content)
    override fun onRendererError(message: String) = actions.onRendererError(message)
}
