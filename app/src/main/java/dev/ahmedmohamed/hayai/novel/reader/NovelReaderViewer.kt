package dev.ahmedmohamed.hayai.novel.reader

import android.content.res.Configuration
import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.ahmedmohamed.hayai.novel.settings.NovelCustomizationStore
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import dev.ahmedmohamed.hayai.novel.source.NovelAssetProvider
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.R
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    private val root = object : FrameLayout(activity) {
        private var focusParent: ViewGroup? = null
        private var parentFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            focusParent = parent as? ViewGroup
            focusParent?.let {
                parentFocusability = it.descendantFocusability
                it.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
        }

        override fun onDetachedFromWindow() {
            focusParent?.descendantFocusability = parentFocusability
            focusParent = null
            super.onDetachedFromWindow()
        }
    }
    private val contentProcessor = NovelContentProcessor()
    private val customization = NovelCustomizationStore(preferences)
    private val fontStore = NovelFontStore(activity, preferences)
    private val contentByChapterId = java.util.concurrent.ConcurrentHashMap<Long, NovelChapterContent>()
    private val preloadJobs = mutableMapOf<Long, Job>()
    private val renderedBlocks = linkedMapOf<Long, NovelRenderBlock>()
    private val renderedContents = linkedMapOf<Long, NovelChapterContent>()
    private var renderedStyle: NovelReaderStyle? = null
    private var renderedOptions: NovelContentOptions? = null
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
    private var selectionActive = false
    private var explicitNavigationJob: Job? = null
    private var pendingRendererSeek: Pair<Long, Int>? = null
    private var awaitingDocumentId: Long? = null

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
        if (transitioningChapterId != null && transitioningChapterId != chapterId) return
        if (transitioningChapterId == chapterId) transitioningChapterId = null
        val progress = if (chapterId == currentChapterId) currentProgress else chapters.currChapter.requestedPage
        showWindow(page.content, progress.coerceIn(NovelProgressPage.MIN_PROGRESS, NovelProgressPage.MAX_PROGRESS))
    }

    override fun moveToPage(page: ReaderPage, animated: Boolean) {
        val novelPage = page as? NovelProgressPage ?: return
        val currentId = currentContent?.chapter?.id
        if (currentId != novelPage.content.chapter.id) {
            explicitNavigationJob?.cancel()
            transitioningChapterId = null
            pendingRendererSeek = requireNotNull(novelPage.content.chapter.id) to novelPage.index
            showWindow(novelPage.content, novelPage.index, requestedChapter = novelPage.chapter)
        } else {
            currentProgress = novelPage.index
            if (awaitingDocumentId == null) renderer?.seek(currentProgress)
            reportLocation(currentProgress, currentPageNumber, currentPageCount)
        }
    }

    override fun moveToNext() {
        if (selectionActive) return
        val next = viewerChapters?.nextChapter
        val atPageEnd = currentPageNumber != null && currentPageNumber == currentPageCount
        if ((currentProgress >= NovelProgressPage.MAX_PROGRESS || atPageEnd) && next != null) {
            transitionTo(next, 0)
        } else {
            renderer?.step(1)
        }
    }

    override fun moveToPrevious() {
        if (selectionActive) return
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

    fun refreshStyle() { currentContent?.let { showWindow(it, currentProgress, styleChange = true) } }

    fun navigateChapter(direction: Int) {
        val chapter = if (direction > 0) viewerChapters?.nextChapter else viewerChapters?.prevChapter
        chapter?.let { transitionTo(it, 0) }
    }

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
        explicitNavigationJob?.cancel()
        preloadJobs.values.toList().forEach(Job::cancel)
        preloadJobs.clear()
        renderer?.destroy()
        renderer = null
        activeContent = null
        renderedBlocks.clear()
        renderedContents.clear()
        contentByChapterId.clear()
        root.removeAllViews()
    }

    private fun showWindow(content: NovelChapterContent, progress: Int, styleChange: Boolean = false, requestedChapter: ReaderChapter? = null) {
        if (destroyed) return
        val chapterId = requireNotNull(content.chapter.id) { "Novel chapter is missing its durable database identity" }
        val window = chapterWindow(content, requestedChapter)
        if (window.isEmpty()) return
        val previousContent = activeContent
        activeContent = content
        val contents = window.mapNotNull { chapter ->
            if (chapter.chapter.id == chapterId) content else chapter.novelContent()
        }
        contents.forEach { contentByChapterId[requireNotNull(it.chapter.id)] = it }
        currentProgress = progress.coerceIn(0, 100)
        if (previousContent != content) {
            currentPageNumber = null
            currentPageCount = null
            actions.onChapterContent(content)
        }
        val options = contentOptions()
        val style = readerStyle(options)
        val plan = renderPlan()
        awaitingDocumentId = chapterId.takeIf { renderedStyle != style || renderedBlocks[chapterId]?.content !is NovelBlockContent.Ready }
        val cachedBlocks = renderedBlocks.toMap()
        val cachedContents = renderedContents.toMap()
        val cachedOptions = renderedOptions
        renderJob?.cancel()
        renderJob =
            activity.scope.launch {
                if (styleChange) delay(120)
                val processedByChapterId =
                    withContext(Dispatchers.Default) {
                        contents.associate { item ->
                            val id = requireNotNull(item.chapter.id)
                            val previous = (cachedBlocks[id]?.content as? NovelBlockContent.Ready)?.request
                            val processed = if (cachedOptions == options && previous != null && cachedContents[id]?.document == item.document && previous.chapterTitle == item.chapter.name) {
                                previous.content
                            } else {
                                contentProcessor.process(item.document, item.chapter.name, options)
                            }
                            id to processed
                        }
                    }
                if (destroyed || currentContent?.chapter?.id != content.chapter.id) return@launch
                val target = ensureRenderer(plan)
                fun block(chapter: ReaderChapter): NovelRenderBlock {
                    val id = requireNotNull(chapter.chapter.id)
                    val item = contents.firstOrNull { it.chapter.id == id }
                    if (item == null) {
                        val error = chapter.state as? ReaderChapter.State.Error
                        return NovelRenderBlock(id, chapter.chapter.name, if (error == null) NovelBlockContent.Loading else NovelBlockContent.Error(activity.novelFailureMessage(error.error, R.string.hayai_novel_reader_load_error), 0))
                    }
                    return NovelRenderBlock(
                        id,
                        item.chapter.name,
                        NovelBlockContent.Ready(
                            NovelRenderRequest(
                                chapterId = id,
                                content = requireNotNull(processedByChapterId[id]),
                                chapterTitle = item.chapter.name,
                                style = style,
                                initialProgress = 0,
                                appendJavaScript = customization.enabledJs(runOnAppend = true),
                            ),
                        ),
                    )
                }
                val blocks = window.map(::block)
                val current = blocks.first { it.chapterId == chapterId }
                val reset = renderedBlocks.isEmpty() || renderedStyle != style || renderedBlocks[chapterId]?.content !is NovelBlockContent.Ready
                if (reset) {
                    renderedBlocks.clear()
                    val ready = (current.content as NovelBlockContent.Ready).request.copy(initialProgress = currentProgress)
                    target.display(current.copy(content = NovelBlockContent.Ready(ready)), NovelBlockPlacement.ReplaceAll, focus = true)
                    renderedBlocks[chapterId] = current
                    pendingRendererSeek = null
                }
                val currentIndex = blocks.indexOf(current)
                val ordered = listOf(current) + blocks.take(currentIndex).asReversed() + blocks.drop(currentIndex + 1)
                ordered.forEach { next ->
                    if (renderedBlocks[next.chapterId] != next) {
                        target.display(next, if (blocks.indexOf(next) < currentIndex) NovelBlockPlacement.Before else NovelBlockPlacement.After, focus = false)
                        renderedBlocks[next.chapterId] = next
                    }
                }
                val ids = blocks.mapTo(linkedSetOf()) { it.chapterId }
                target.retain(ids)
                renderedBlocks.keys.retainAll(ids)
                renderedContents.clear()
                contents.forEach { renderedContents[requireNotNull(it.chapter.id)] = it }
                contentByChapterId.keys.retainAll(ids)
                renderedStyle = style
                renderedOptions = options
                if (!reset) {
                    target.focus(chapterId, pendingRendererSeek?.takeIf { it.first == chapterId }?.second)
                    pendingRendererSeek = null
                    reportLocation(currentProgress, currentPageNumber, currentPageCount)
                    if (previousContent != content) actions.onDocumentReady()
                }
            }
    }

    private fun chapterWindow(current: NovelChapterContent, requestedChapter: ReaderChapter?): List<ReaderChapter> {
        val chapters = viewerChapters ?: return emptyList()
        val currentChapter = listOfNotNull(chapters.prevChapter, chapters.currChapter, chapters.nextChapter).firstOrNull { it.chapter.id == current.chapter.id }
            ?: return listOfNotNull(requestedChapter)
        if (renderPlan().layout == NovelLayoutMode.Scroll) return listOf(currentChapter)
        val window = listOfNotNull(chapters.prevChapter, chapters.currChapter, chapters.nextChapter)
        window.filter { it.chapter.id != current.chapter.id && it.state !is ReaderChapter.State.Error }.forEach(::requestPreload)
        return window
    }

    private fun requestPreload(chapter: ReaderChapter) {
        val id = chapter.chapter.id ?: return
        if (chapter.pages != null || preloadJobs[id]?.isActive == true) return
        if (chapter.state is ReaderChapter.State.Error) chapter.state = ReaderChapter.State.Wait
        val job = activity.scope.launch {
            activity.requestPreloadChapter(chapter)
            chapter.stateFlow.first { it is ReaderChapter.State.Loaded || it is ReaderChapter.State.Error }
            if (!destroyed) currentContent?.let { showWindow(it, currentProgress) }
        }
        preloadJobs[id] = job
        job.invokeOnCompletion { if (preloadJobs[id] === job) preloadJobs.remove(id) }
    }

    private fun transitionTo(chapter: ReaderChapter, progress: Int) {
        if (transitioningChapterId != null || explicitNavigationJob?.isActive == true) return
        val origin = currentChapterId
        explicitNavigationJob = activity.scope.launch {
            if (chapter.pages == null) {
                requestPreload(chapter)
                val state = chapter.stateFlow.first { it is ReaderChapter.State.Loaded || it is ReaderChapter.State.Error }
                if (state is ReaderChapter.State.Error) {
                    actions.onRendererError(activity.novelFailureMessage(state.error, R.string.hayai_novel_reader_load_error))
                    return@launch
                }
            }
            if (!destroyed && currentChapterId == origin) selectChapter(chapter, progress, seek = true)
        }
    }

    private fun ReaderChapter.novelContent(): NovelChapterContent? = (pages?.firstOrNull() as? NovelProgressPage)?.content

    private fun selectChapter(chapter: ReaderChapter, progress: Int, seek: Boolean) {
        val page = chapter.pages?.getOrNull(progress.coerceIn(0, 100)) as? NovelProgressPage ?: return
        val id = requireNotNull(chapter.chapter.id)
        val changesJ2kChapter = viewerChapters?.currChapter?.chapter?.id != id
        transitioningChapterId = id.takeIf { changesJ2kChapter }
        activeContent = page.content
        currentProgress = page.index
        currentPageNumber = null
        currentPageCount = null
        actions.onChapterContent(page.content)
        if (renderedBlocks[id]?.content is NovelBlockContent.Ready) {
            renderer?.focus(id, if (seek) page.index else null)
            actions.onDocumentReady()
        } else if (seek) {
            pendingRendererSeek = id to page.index
        }
        if (changesJ2kChapter) activity.onPageSelected(page, false)
    }

    private fun ensureRenderer(plan: NovelRenderPlan): NovelRenderer {
        renderer?.takeIf { it.mode == plan.backend }?.let { return it }
        renderer?.destroy()
        renderedBlocks.clear()
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
            NovelLayoutMode.fromPreference(preferences.novelReadingMode.get()),
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
            renderingMode = preferences.novelReadingMode.get(),
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
        viewerChapters?.let { chapters ->
            listOfNotNull(chapters.currChapter, chapters.prevChapter, chapters.nextChapter)
                .firstOrNull { it.chapter.id == currentChapterId }?.pages?.getOrNull(progress.coerceIn(0, 100)) as? NovelProgressPage
        }

    private fun reportLocation(progress: Int, pageNumber: Int?, pageCount: Int?) {
        currentProgress = progress.coerceIn(0, 100)
        currentPageNumber = pageNumber
        currentPageCount = pageCount
        val page = currentNovelPage(currentProgress) ?: return
        page.presentation = NovelPagePresentation(currentProgress, pageNumber, pageCount)
        if (transitioningChapterId != null) return
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
        awaitingDocumentId = null
        if (progress != currentProgress) renderer?.seek(currentProgress)
        reportLocation(currentProgress, currentPageNumber, currentPageCount)
        actions.onDocumentReady()
    }

    override fun onProgress(progress: Int) {
        if (awaitingDocumentId == null) reportLocation(progress, null, null)
    }

    override fun onPageLocation(chapterId: Long, progress: Int, pageNumber: Int, pageCount: Int) {
        if (awaitingDocumentId != null) return
        if (chapterId == currentChapterId) {
            reportLocation(progress, pageNumber, pageCount)
        } else {
            if (transitioningChapterId == null && !selectionActive) adjacentChapter(chapterId)?.let { selectChapter(it, progress, seek = false) }
        }
    }

    override fun onVisibleChapter(chapterId: Long, progress: Int) {
        if (awaitingDocumentId != null) return
        if (chapterId == currentChapterId) {
            reportLocation(progress, currentPageNumber, currentPageCount)
        } else {
            if (transitioningChapterId == null && !selectionActive) adjacentChapter(chapterId)?.let { selectChapter(it, progress, seek = false) }
        }
    }

    override fun onRetryChapter(chapterId: Long) {
        adjacentChapter(chapterId)?.let(::requestPreload)
    }

    private fun adjacentChapter(chapterId: Long): ReaderChapter? =
        listOfNotNull(viewerChapters?.prevChapter, viewerChapters?.nextChapter)
            .firstOrNull { it.chapter.id == chapterId }

    override fun onTap(xFraction: Float, yFraction: Float) {
        if (selectionActive || awaitingDocumentId != null) return
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
    override fun onSelectionModeChanged(active: Boolean) {
        selectionActive = active
        actions.onSelectionModeChanged(active)
    }
    override fun onContentEdited(content: String) = actions.onContentEdited(content)
    override fun onRendererError(message: String) = actions.onRendererError(message)
}
