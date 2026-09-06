package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.UnderlineSpan
import android.view.ActionMode
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.core.view.doOnPreDraw
import androidx.core.widget.NestedScrollView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap
import org.jsoup.Jsoup

internal class NativeNovelRenderer(
    context: Context,
    private val callbacks: NovelRenderer.Callbacks,
    private val fontStore: NovelFontStore,
) : NovelRenderer {
    override val mode = NovelRenderingBackend.Native
    private val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = NestedScrollView(context).apply {
        isFillViewport = true
        addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    override val view: View = scroll
    private val blocks = linkedMapOf<Long, NativeBlock>()
    private var activeChapterId: Long? = null
    private var editMode = false
    private var applyingText = false
    private var selectionModeActive = false
    private var suppressTap = false
    private var layoutPending = false
    private var viewportAnchor: NovelViewportAnchor? = null
    private var pendingFocus: Pair<Long, Int>? = null
    private var pendingReady: Pair<Long, Int>? = null
    private var destroyed = false
    private val gestureDetector =
        GestureDetectorWithLongTap(
            context,
            object : GestureDetectorWithLongTap.Listener() {
                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    if (!editMode && !suppressTap && !selectionModeActive && blocks.values.none { it.textView?.hasSelection() == true }) {
                        callbacks.onTap(
                            event.x / scroll.width.coerceAtLeast(1),
                            event.y / scroll.height.coerceAtLeast(1),
                        )
                    }
                    return true
                }
            },
        )

    init {
        scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, _, _, _ -> reportVisibleBlock() })
        scroll.setOnTouchListener { _, event ->
            detectTap(scroll, event)
            false
        }
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val height = bottom - top
            if (height > 0 && height != oldBottom - oldTop) {
                preserveViewport()
                blocks.values.forEach { it.root.minimumHeight = height }
            }
        }
    }

    override fun display(block: NovelRenderBlock, placement: NovelBlockPlacement, focus: Boolean) {
        preserveViewport()
        if (placement == NovelBlockPlacement.ReplaceAll) {
            blocks.values.forEach { it.retryRunnable?.let(it.root::removeCallbacks) }
            container.removeAllViews()
            blocks.clear()
            activeChapterId = null
            viewportAnchor = null
        }
        val existing = blocks[block.chapterId]
        val native = existing ?: createBlock(block.chapterId).also { created ->
            blocks[block.chapterId] = created
            val index = if (placement == NovelBlockPlacement.Before) 0 else container.childCount
            container.addView(created.root, index)
            rebuildBlockOrder()
        }
        bind(native, block, focus)
        if (focus) this.focus(block.chapterId, block.readyProgress())
    }

    override fun retain(chapterIds: Set<Long>) {
        if (blocks.keys.all(chapterIds::contains)) return
        preserveViewport()
        if (viewportAnchor?.chapterId !in chapterIds) {
            blocks.values.firstOrNull { it.id in chapterIds }?.let {
                viewportAnchor = NovelViewportAnchor(it.id, scroll.scrollY - it.root.top)
            }
        }
        blocks.keys.filterNot(chapterIds::contains).toList().forEach { id ->
            val block = blocks.remove(id) ?: return@forEach
            block.retryRunnable?.let(block.root::removeCallbacks)
            container.removeView(block.root)
        }
        rebuildBlockOrder()
    }

    override fun focus(chapterId: Long, progress: Int?) {
        if (chapterId !in blocks) return
        activeChapterId = chapterId
        if (progress != null) {
            preserveViewport()
            pendingFocus = chapterId to progress.coerceIn(0, 100)
        }
    }

    override fun seek(progress: Int) {
        val block = activeBlock() ?: return
        focus(block.id, progress)
    }

    override fun step(direction: Int) = scroll.smoothScrollBy(0, (scroll.height * 0.85f * direction.coerceIn(-1, 1)).toInt())
    override fun stepPixels(pixels: Int) = scroll.scrollBy(0, pixels)
    override fun scrollToTop() {
        activeBlock()?.let { scroll.smoothScrollTo(0, it.root.top) }
    }

    override fun selection(callback: (NovelSelection?) -> Unit) {
        val textView = blocks.values.firstOrNull { it.textView?.hasSelection() == true }?.textView ?: return callback(null)
        callback(textView.novelSelection())
    }

    private fun TextView.novelSelection(): NovelSelection? {
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(0, text.length)
        if (start == end) return null
        val from = minOf(start, end)
        val to = maxOf(start, end)
        val document = text.toString()
        val exact = document.substring(from, to).trim()
        if (exact.isBlank()) return null
        return NovelSelection(
            document,
            exact,
            document.substring(maxOf(0, from - 64), from),
            document.substring(to, minOf(document.length, to + 64)),
            occurrenceBefore(document, exact, from),
        )
    }

    override fun documentText(callback: (String) -> Unit) = callback(activeBlock()?.textView?.text?.toString().orEmpty())
    override fun paragraphs(callback: (List<String>) -> Unit) = callback(activeBlock()?.paragraphs.orEmpty())

    override fun viewportParagraph(callback: (Int) -> Unit) {
        val block = activeBlock() ?: return callback(0)
        if (block.paragraphs.isEmpty()) return callback(0)
        val textView = block.textView ?: return callback(0)
        val layout = textView.layout ?: return callback(0)
        val line = layout.getLineForVertical((scroll.scrollY - block.root.top - textView.totalPaddingTop).coerceAtLeast(0))
        val visibleOffset = layout.getLineStart(line)
        var searchFrom = 0
        var visibleParagraph = 0
        block.paragraphs.forEachIndexed { index, paragraph ->
            val start = textView.text.indexOf(paragraph, searchFrom)
            if (start >= 0) {
                if (start <= visibleOffset) visibleParagraph = index
                searchFrom = start + paragraph.length
            }
        }
        callback(visibleParagraph)
    }

    override fun showTranslation(paragraphs: List<String>, onComplete: () -> Unit) {
        val block = activeBlock() ?: return
        val text = paragraphs.joinToString("\n\n")
        setBlockText(block, text)
        block.paragraphs = paragraphs
        onComplete()
    }

    override fun showOriginal(onComplete: () -> Unit) {
        val block = activeBlock() ?: return
        setBlockText(block, SpannableStringBuilder(block.original))
        block.paragraphs = block.originalParagraphs
        onComplete()
    }

    override fun applyHighlights(items: List<NovelPersistentHighlight>) {
        val block = activeBlock() ?: return
        val content = block.textView?.text as? Spannable ?: return
        content.getSpans(0, content.length, PersistentHighlightSpan::class.java).forEach(content::removeSpan)
        items.forEach { item ->
            var offset = -1
            var searchFrom = 0
            for (occurrence in 0..item.occurrence.coerceAtLeast(0)) {
                offset = content.indexOf(item.exact, searchFrom)
                if (offset < 0) break
                searchFrom = offset + item.exact.length
            }
            if (offset >= 0) content.setSpan(PersistentHighlightSpan(item.color), offset, offset + item.exact.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    override fun highlightSpokenParagraph(index: Int) {
        val block = activeBlock() ?: return
        clearSpokenHighlight()
        val paragraph = block.paragraphs.getOrNull(index) ?: return
        val text = block.textView?.text as? Spannable ?: return
        val start = text.indexOf(paragraph)
        if (start < 0) return
        text.setSpan(SpokenParagraphSpan(), start, start + paragraph.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        block.textView?.layout?.let { layout -> scroll.smoothScrollTo(0, block.root.top + layout.getLineTop(layout.getLineForOffset(start))) }
    }

    override fun clearSpokenHighlight() {
        blocks.values.forEach { block ->
            val content = block.textView?.text as? Spannable ?: return@forEach
            content.getSpans(0, content.length, SpokenParagraphSpan::class.java).forEach(content::removeSpan)
        }
    }

    override fun isShort(callback: (Boolean) -> Unit) {
        val block = activeBlock() ?: return callback(false)
        block.root.post { callback(block.root.height <= scroll.height) }
    }

    override fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        preserveViewport()
        editMode = enabled
        blocks.values.forEach { block ->
            val oldText = block.textView ?: return@forEach
            val style = block.style ?: return@forEach
            val content = SpannableStringBuilder(oldText.text)
            block.root.removeView(oldText)
            block.textView = createTextView(block, style)
            setBlockText(block, content)
            block.root.addView(block.textView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (enabled) activeBlock()?.textView?.requestFocus()
    }

    override fun destroy() {
        destroyed = true
        blocks.values.forEach { it.retryRunnable?.let(it.root::removeCallbacks) }
        blocks.clear()
        container.removeAllViews()
    }

    private fun createBlock(id: Long): NativeBlock {
        val root = FrameLayout(scroll.context).apply { tag = id; minimumHeight = scroll.height }
        return NativeBlock(id, root)
    }

    private fun bind(native: NativeBlock, block: NovelRenderBlock, notifyReady: Boolean) {
        native.title = block.chapterTitle
        native.retryRunnable?.let(native.root::removeCallbacks)
        native.retryRunnable = null
        native.root.removeAllViews()
        native.textView = null
        native.paragraphs = emptyList()
        native.originalParagraphs = emptyList()
        when (val content = block.content) {
            NovelBlockContent.Loading -> {
                native.root.addView(ProgressBar(scroll.context), centeredParams())
            }
            is NovelBlockContent.Error -> bindError(native, content)
            is NovelBlockContent.Ready -> bindReady(native, content.request, notifyReady)
        }
    }

    private fun bindError(block: NativeBlock, error: NovelBlockContent.Error) {
        val column = LinearLayout(scroll.context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24.dp, 24.dp, 24.dp, 24.dp) }
        column.addView(
            TextView(scroll.context).apply {
                text = scroll.context.getString(
                    R.string.hayai_novel_reader_chapter_error_message,
                    block.title,
                    error.message,
                )
                gravity = Gravity.CENTER
            },
        )
        val retry = Button(scroll.context)
        column.addView(retry)
        block.root.addView(column, centeredParams())
        fun refresh() {
            val remaining = error.retryAtMillis - System.currentTimeMillis()
            retry.isEnabled = remaining <= 0
            retry.text = if (remaining <= 0) scroll.context.getString(R.string.retry) else scroll.context.getString(R.string.hayai_novel_reader_retry_in, (remaining + 999) / 1000)
            if (remaining > 0) {
                block.retryRunnable = Runnable(::refresh).also { block.root.postDelayed(it, minOf(remaining, 1_000L)) }
            }
        }
        retry.setOnClickListener { callbacks.onRetryChapter(block.id) }
        refresh()
    }

    private fun bindReady(block: NativeBlock, request: NovelRenderRequest, notifyReady: Boolean) {
        val textView = createTextView(block, request.style)
        block.textView = textView
        block.style = request.style
        scroll.setBackgroundColor(request.style.backgroundColor)
        container.setBackgroundColor(request.style.backgroundColor)
        val title = if (request.style.hideChapterTitle) "" else "<h1>${android.text.TextUtils.htmlEncode(request.chapterTitle)}</h1>"
        val spanned = HtmlCompat.fromHtml(title + request.content.html, HtmlCompat.FROM_HTML_MODE_COMPACT)
        block.original = SpannableStringBuilder(spanned)
        block.paragraphs = Jsoup.parse(request.content.html).select("h1,h2,h3,h4,h5,h6,p,li,blockquote").map { it.text().trim() }.filter(String::isNotBlank)
        block.originalParagraphs = block.paragraphs
        setBlockText(block, SpannableStringBuilder(block.original))
        block.root.addView(textView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (notifyReady) pendingReady = block.id to request.initialProgress
    }

    private fun createTextView(block: NativeBlock, readerStyle: NovelReaderStyle): TextView {
        val textView = (if (editMode) EditText(scroll.context) else TextView(scroll.context)).apply {
            background = null
            gravity = Gravity.TOP or Gravity.START
            setPadding(0, 0, 0, 0)
            if (this is EditText) {
                showSoftInputOnFocus = true
            } else {
                setTextIsSelectable(readerStyle.textSelectable)
            }
            linksClickable = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setTextClassifier(TextClassifier.NO_OP)
            val selectionTextView = this
            customSelectionActionModeCallback =
                NovelSelectionActionModes.wrap(
                    context = context,
                    readOnly = { !editMode },
                    onAction = { action, mode -> dispatchSelectionAction(selectionTextView, action, mode) },
                    onSelectionModeChanged = { active ->
                        selectionModeActive = active
                        if (active) {
                            activeChapterId = block.id
                            callbacks.onVisibleChapter(block.id, blockProgress(block))
                        }
                        callbacks.onSelectionModeChanged(active)
                    },
                )
            setOnTouchListener { target, event ->
                detectTap(target, event)
                false
            }
        }
        style(textView, readerStyle)
        if (textView is EditText) textView.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { if (editMode && !applyingText && activeChapterId == block.id) callbacks.onContentEdited(s?.toString().orEmpty()) }
            },
        )
        return textView
    }

    private fun style(textView: TextView, style: NovelReaderStyle) {
        textView.textSize = style.fontSize.toFloat()
        textView.setTextColor(style.textColor)
        textView.setBackgroundColor(style.backgroundColor)
        textView.typeface =
            if (style.useOriginalFonts) {
                Typeface.DEFAULT
            } else {
                fontStore.typeface(style.fontFamily) ?: Typeface.create(style.fontFamily, Typeface.NORMAL)
            }
        textView.setLineSpacing(0f, style.lineHeight.coerceIn(0.8f, 3f))
        textView.gravity = when (style.textAlign) { "center" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL; "right", "end" -> Gravity.TOP or Gravity.END; else -> Gravity.TOP or Gravity.START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            textView.justificationMode = if (style.textAlign == "justify") Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE
        }
        textView.setPadding((style.marginLeft * density).toInt(), (style.marginTop * density).toInt(), (style.marginRight * density).toInt(), (style.marginBottom * density).toInt())
    }

    private fun setBlockText(block: NativeBlock, text: CharSequence) {
        preserveViewport()
        applyingText = true
        val content = SpannableStringBuilder(text)
        content.getSpans(0, content.length, ParagraphIndentSpan::class.java).forEach(content::removeSpan)
        content.getSpans(0, content.length, ParagraphSpacingSpan::class.java).forEach(content::removeSpan)
        block.style?.let { style ->
            val em = block.textView?.textSize ?: 0f
            Regex("[^\\n]+(?:\\n|$)").findAll(content).forEach { paragraph ->
                val start = paragraph.range.first
                val end = paragraph.range.last + 1
                content.setSpan(ParagraphIndentSpan((style.paragraphIndent.coerceIn(0f, 10f) * em).toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                content.setSpan(ParagraphSpacingSpan((style.paragraphSpacing.coerceIn(0f, 5f) * em).toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        block.textView?.setText(content, TextView.BufferType.SPANNABLE)
        applyingText = false
    }

    private fun dispatchSelectionAction(
        textView: TextView,
        action: NovelSelectionAction,
        mode: ActionMode,
    ) {
        val selection = textView.novelSelection()
        mode.finish()
        selection?.let { callbacks.onSelectionAction(action, it) }
    }

    private fun focusBlock(id: Long, progress: Int) {
        val block = blocks[id] ?: return
        activeChapterId = id
        scroll.scrollTo(0, NovelViewportGeometry.offset(block.root.top, block.root.height, scroll.height, progress))
    }

    private fun reportVisibleBlock() {
        if (layoutPending || destroyed || selectionModeActive || scroll.height <= 0) return
        val block = visibleBlock() ?: return
        if (block.textView == null) return
        activeChapterId = block.id
        callbacks.onVisibleChapter(block.id, blockProgress(block))
    }

    private fun blockProgress(block: NativeBlock): Int =
        NovelViewportGeometry.progress(block.root.top, block.root.height, scroll.height, scroll.scrollY)

    private fun visibleBlock(): NativeBlock? = blocks.values.firstOrNull {
        NovelViewportGeometry.contains(it.root.top, it.root.bottom, scroll.scrollY)
    } ?: blocks.values.lastOrNull { it.root.top <= scroll.scrollY }

    private fun preserveViewport() {
        if (layoutPending || destroyed) return
        layoutPending = true
        viewportAnchor = visibleBlock()?.let { NovelViewportAnchor(it.id, scroll.scrollY - it.root.top) }
        scroll.doOnPreDraw {
            if (destroyed) return@doOnPreDraw
            val focus = pendingFocus
            pendingFocus = null
            if (focus != null) {
                focusBlock(focus.first, focus.second)
            } else {
                viewportAnchor?.let { anchor ->
                    blocks[anchor.chapterId]?.let { scroll.scrollTo(0, it.root.top + anchor.offset) }
                }
            }
            viewportAnchor = null
            layoutPending = false
            val ready = pendingReady
            pendingReady = null
            if (ready != null && activeChapterId == ready.first) callbacks.onReady(ready.second)
            reportVisibleBlock()
        }
        scroll.invalidate()
    }

    private fun detectTap(target: View, event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            suppressTap = editMode || selectionModeActive || blocks.values.any { it.textView?.hasSelection() == true }
        }
        val targetLocation = IntArray(2)
        val viewportLocation = IntArray(2)
        target.getLocationOnScreen(targetLocation)
        scroll.getLocationOnScreen(viewportLocation)
        val viewportEvent = MotionEvent.obtain(event)
        viewportEvent.offsetLocation((targetLocation[0] - viewportLocation[0]).toFloat(), (targetLocation[1] - viewportLocation[1]).toFloat())
        gestureDetector.onTouchEvent(viewportEvent)
        viewportEvent.recycle()
    }

    private fun rebuildBlockOrder() {
        val reordered = LinkedHashMap<Long, NativeBlock>()
        repeat(container.childCount) { index ->
            val id = container.getChildAt(index).tag as? Long ?: return@repeat
            blocks[id]?.let { reordered[id] = it }
        }
        blocks.clear()
        blocks.putAll(reordered)
    }

    private fun activeBlock(): NativeBlock? = activeChapterId?.let(blocks::get) ?: blocks.values.firstOrNull()
    private fun NovelRenderBlock.readyProgress(): Int = (content as? NovelBlockContent.Ready)?.request?.initialProgress ?: 0
    private fun centeredParams() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
    private val density get() = scroll.resources.displayMetrics.density
    private val Int.dp get() = (this * density).toInt()
    private fun occurrenceBefore(document: String, exact: String, end: Int): Int {
        var count = 0
        var cursor = 0
        while (cursor < end) {
            val found = document.indexOf(exact, cursor)
            if (found < 0 || found >= end) break
            count += 1
            cursor = found + exact.length.coerceAtLeast(1)
        }
        return count
    }

    private data class NativeBlock(
        val id: Long,
        val root: FrameLayout,
        var title: String = "",
        var textView: TextView? = null,
        var original: CharSequence = "",
        var paragraphs: List<String> = emptyList(),
        var originalParagraphs: List<String> = emptyList(),
        var style: NovelReaderStyle? = null,
        var retryRunnable: Runnable? = null,
    )

    private class PersistentHighlightSpan(color: Int) : BackgroundColorSpan(color)
    private class SpokenParagraphSpan : UnderlineSpan()
    private class ParagraphIndentSpan(indent: Int) : LeadingMarginSpan.Standard(indent, 0)
    private class ParagraphSpacingSpan(private val spacing: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
            if (end >= (text as Spanned).getSpanEnd(this)) {
                fm.descent += spacing
                fm.bottom += spacing
            }
        }
    }
}
