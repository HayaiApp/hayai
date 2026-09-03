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
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
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
    private val chapterQueue = NovelChapterQueue<NovelChapterContent, Long>({ requireNotNull(it.chapter.id) }, 3)
    private val requestedPreloads = mutableSetOf<Long>()
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
    private var transitioningChapterId: Long? = null
    private var pendingChapterSeek: Pair<Long, Int>? = null

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
        val chapterId = requireNotNull(page.content.chapter.id)
        val progress = pendingChapterSeek?.takeIf { it.first == chapterId }?.second?.also { pendingChapterSeek = null }
            ?: chapters.currChapter.requestedPage
        showWindow(page.content, progress.coerceIn(NovelProgressPage.MIN_PROGRESS, NovelProgressPage.MAX_PROGRESS))
    }

    override fun moveToPage(page: ReaderPage, animated: Boolean) {
        val novelPage = page as? NovelProgressPage ?: return
        val currentId = currentContent?.chapter?.id
        if (currentId != novelPage.content.chapter.id) {
            showWindow(novelPage.content, novelPage.index)
        } else {
            currentProgress = novelPage.index
            renderer?.seek(currentProgress)
            reportLocation(currentProgress, currentPageNumber, currentPageCount)
        }
    }

    override fun moveToNext() {
        val next = viewerChapters?.nextChapter
        val atPageEnd = currentPageNumber != null && currentPageNumber == currentPageCount
        if ((currentProgress >= NovelProgressPage.MAX_PROGRESS || atPageEnd) && next != null) {
            transitionTo(next, 0)
        } else {
            renderer?.step(1)
        }
    }

    override fun moveToPrevious() {
        val previous = viewerChapters?.prevChapter
        if (currentProgress <= NovelProgressPage.MIN_PROGRESS && previous != null) {
            transitionTo(previous, NovelProgressPage.MAX_PROGRESS)
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

    fun refreshStyle() { currentContent?.let { showWindow(it, currentProgress) } }

    fun replaceContent(content: NovelChapterContent) = showWindow(content, currentProgress)

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
    fun showTranslation(paragraphs: List<String>, onComplete: () -> Unit = {}) = renderer?.showTranslation(paragraphs, onComplete)
    fun showOriginal(onComplete: () -> Unit = {}) = renderer?.showOriginal(onComplete)
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
        chapterQueue.clear()
        contentByChapterId.clear()
        root.removeAllViews()
    }

    private fun showWindow(content: NovelChapterContent, progress: Int) {
        if (destroyed) return
        val chapterId = requireNotNull(content.chapter.id) { "Novel chapter is missing its durable database identity" }
        activeContent = content
        val window = chapterWindow(content)
        contentByChapterId.clear()
        window.forEach { contentByChapterId[requireNotNull(it.chapter.id)] = it }
        currentProgress = progress.coerceIn(0, 100)
        currentPageNumber = null
        currentPageCount = null
        // Content can be replaced while the durable chapter identity stays the same,
        // for example after an edit or an offline translation is refreshed.
        actions.onChapterContent(content)
        val options = contentOptions()
        val style = readerStyle(options)
        val plan = renderPlan()
        renderJob?.cancel()
        renderJob =
            activity.scope.launch {
                val processedByChapterId =
                    withContext(Dispatchers.Default) {
                        window.associate { item ->
                            requireNotNull(item.chapter.id) to contentProcessor.process(item.document, item.chapter.name, options)
                        }
                    }
                if (destroyed || currentContent?.chapter?.id != content.chapter.id) return@launch
                val target = ensureRenderer(plan)
                fun block(item: NovelChapterContent): NovelRenderBlock {
                    val id = requireNotNull(item.chapter.id)
                    return NovelRenderBlock(
                        id,
                        item.chapter.name,
                        NovelBlockContent.Ready(
                            NovelRenderRequest(
                                chapterId = id,
                                content = requireNotNull(processedByChapterId[id]),
                                chapterTitle = item.chapter.name,
                                style = style,
                                initialProgress = if (id == chapterId) currentProgress else 0,
                                appendJavaScript = customization.enabledJs(runOnAppend = true),
                            ),
                        ),
                    )
                }
                target.display(
                    block(content),
                    NovelBlockPlacement.ReplaceAll,
                    focus = true,
                )
                val currentIndex = window.indexOfFirst { it.chapter.id == chapterId }
                window.take(currentIndex).asReversed().forEach {
                    target.display(block(it), NovelBlockPlacement.Before, focus = false)
                }
                window.drop(currentIndex + 1).forEach {
                    target.display(block(it), NovelBlockPlacement.After, focus = false)
                }
                target.retain(window.mapTo(linkedSetOf()) { requireNotNull(it.chapter.id) })
            }
    }

    private fun chapterWindow(current: NovelChapterContent): List<NovelChapterContent> {
        chapterQueue.clear()
        chapterQueue.replaceCurrent(current)
        if (!preferences.novelInfiniteScroll.get()) return chapterQueue.snapshot()
        val keep = preferences.novelKeepChaptersLoaded.get().coerceIn(0, 3)
        // Infinite reading always needs a following block. The retention preference decides
        // whether the previous chapter also remains mounted after the boundary changes.
        chapterQueue.capacity = 2 + (if (keep == 1 || keep == 3) 1 else 0)
        if (keep == 1 || keep == 3) addAdjacent(viewerChapters?.prevChapter, before = true)
        addAdjacent(viewerChapters?.nextChapter, before = false)
        return chapterQueue.snapshot()
    }

    private fun addAdjacent(chapter: ReaderChapter?, before: Boolean) {
        chapter ?: return
        if (chapter.state is ReaderChapter.State.Error) requestedPreloads.remove(chapter.chapter.id)
        val content = chapter.pages?.filterIsInstance<NovelProgressPage>()?.firstOrNull()?.content
        if (content == null) {
            requestPreload(chapter)
        } else {
            chapter.chapter.id?.let(requestedPreloads::remove)
            if (before) chapterQueue.prepend(content) else chapterQueue.append(content)
        }
    }

    private fun requestPreload(chapter: ReaderChapter) {
        val id = chapter.chapter.id ?: return
        if (requestedPreloads.add(id)) activity.requestPreloadChapter(chapter)
    }

    private fun transitionTo(chapter: ReaderChapter, progress: Int) {
        val id = chapter.chapter.id ?: return
        if (transitioningChapterId != null) return
        transitioningChapterId = id
        pendingChapterSeek = id to progress.coerceIn(0, 100)
        activity.scope.launch {
            try {
                activity.loadChapter(chapter.chapter)
            } finally {
                transitioningChapterId = null
            }
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
        if (currentProgress >= preferences.novelAutoLoadNextChapterAt.get().coerceIn(50, 100)) {
            viewerChapters?.nextChapter?.let(::requestPreload)
        }
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

    override fun onPageLocation(chapterId: Long, progress: Int, pageNumber: Int, pageCount: Int) {
        if (chapterId == currentChapterId) {
            reportLocation(progress, pageNumber, pageCount)
        } else {
            adjacentChapter(chapterId)?.let { transitionTo(it, progress) }
        }
    }

    override fun onVisibleChapter(chapterId: Long, progress: Int) {
        if (chapterId == currentChapterId) {
            reportLocation(progress, currentPageNumber, currentPageCount)
        } else {
            adjacentChapter(chapterId)?.let { transitionTo(it, progress) }
        }
    }

    override fun onRetryChapter(chapterId: Long) {
        currentContent?.takeIf { it.chapter.id == chapterId }?.let { showWindow(it, currentProgress) }
    }

    private fun adjacentChapter(chapterId: Long): ReaderChapter? =
        listOfNotNull(viewerChapters?.prevChapter, viewerChapters?.nextChapter)
            .firstOrNull { it.chapter.id == chapterId }

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
