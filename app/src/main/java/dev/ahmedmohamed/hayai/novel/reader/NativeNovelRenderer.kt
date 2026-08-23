package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.view.ActionMode
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.textclassifier.TextClassifier
import androidx.core.text.HtmlCompat
import androidx.core.widget.NestedScrollView
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import eu.kanade.tachiyomi.R
import org.jsoup.Jsoup

internal class NativeNovelRenderer(
    context: Context,
    private val callbacks: NovelRenderer.Callbacks,
    private val fontStore: NovelFontStore,
) : NovelRenderer {
    override val mode = NovelRenderingMode.Native
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

    init {
        scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, _, _, _ -> reportVisibleBlock() })
        scroll.setOnTouchListener { touched, event ->
            if (event.action == MotionEvent.ACTION_UP && activeBlock()?.textView?.hasSelection() != true) {
                callbacks.onTap(event.x / touched.width.coerceAtLeast(1), event.y / touched.height.coerceAtLeast(1))
            }
            false
        }
    }

    override fun display(block: NovelRenderBlock, placement: NovelBlockPlacement, focus: Boolean) {
        if (placement == NovelBlockPlacement.ReplaceAll) {
            container.removeAllViews()
            blocks.clear()
            activeChapterId = null
        }
        val existing = blocks[block.chapterId]
        val anchor = activeBlock()?.root
        val anchorTop = anchor?.top ?: 0
        val anchorScroll = scroll.scrollY
        val native = existing ?: createBlock(block.chapterId).also { created ->
            blocks[block.chapterId] = created
            val oldHeight = container.height
            val oldScroll = scroll.scrollY
            val index = if (placement == NovelBlockPlacement.Before) 0 else container.childCount
            container.addView(created.root, index)
            rebuildBlockOrder()
            if (placement == NovelBlockPlacement.Before) {
                container.post {
                    val insertedHeight = (container.height - oldHeight).coerceAtLeast(0)
                    scroll.scrollTo(0, oldScroll + insertedHeight)
                    if (focus) focusBlock(block.chapterId, block.readyProgress())
                }
            }
        }
        bind(native, block, focus)
        if (!focus && placement == NovelBlockPlacement.Before && existing != null && anchor != null) {
            container.post { scroll.scrollTo(0, anchorScroll + anchor.top - anchorTop) }
        }
        if (focus && !(existing == null && placement == NovelBlockPlacement.Before)) {
            native.root.post { focusBlock(block.chapterId, block.readyProgress()) }
        }
    }

    override fun retain(chapterIds: Set<Long>) {
        blocks.keys.filterNot(chapterIds::contains).toList().forEach { id ->
            val block = blocks.remove(id) ?: return@forEach
            val aboveViewport = block.root.bottom <= scroll.scrollY
            val height = block.root.height
            container.removeView(block.root)
            if (aboveViewport) scroll.scrollBy(0, -height)
        }
        rebuildBlockOrder()
        if (activeChapterId !in blocks) reportVisibleBlock()
    }

    override fun seek(progress: Int) {
        val block = activeBlock() ?: return
        val range = (block.root.height - scroll.height).coerceAtLeast(1)
        scroll.scrollTo(0, block.root.top + range * progress.coerceIn(0, 100) / 100)
    }

    override fun step(direction: Int) = scroll.smoothScrollBy(0, (scroll.height * 0.85f * direction.coerceIn(-1, 1)).toInt())
    override fun stepPixels(pixels: Int) = scroll.scrollBy(0, pixels)
    override fun scrollToTop() {
        activeBlock()?.let { scroll.smoothScrollTo(0, it.root.top) }
    }

    override fun selection(callback: (NovelSelection?) -> Unit) {
        val textView = activeBlock()?.textView ?: return callback(null)
        callback(textView.novelSelection())
    }

    private fun TextView.novelSelection(): NovelSelection? {
        val start = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(0)
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
        val local = (scroll.scrollY - block.root.top).toFloat() / block.root.height.coerceAtLeast(1)
        callback((local * block.paragraphs.size).toInt().coerceIn(0, block.paragraphs.lastIndex))
    }

    override fun showTranslation(text: String) {
        val block = activeBlock() ?: return
        setBlockText(block, text)
        block.paragraphs = text.split(Regex("\\n{2,}")).filter(String::isNotBlank)
    }

    override fun showOriginal() {
        val block = activeBlock() ?: return
        applyingText = true
        block.textView?.text = SpannableStringBuilder(block.original)
        block.paragraphs = block.originalParagraphs
        applyingText = false
    }

    override fun applyHighlights(items: List<NovelPersistentHighlight>) {
        val block = activeBlock() ?: return
        val content = SpannableStringBuilder(block.textView?.text ?: return)
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
        setBlockText(block, content)
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
        editMode = enabled
        blocks.values.forEach { block ->
            block.textView?.configureInteraction(enabled, block.style?.textSelectable == true)
        }
        if (enabled) activeBlock()?.textView?.requestFocus()
    }

    override fun destroy() {
        blocks.values.forEach { it.retryRunnable?.let(it.root::removeCallbacks) }
        blocks.clear()
        container.removeAllViews()
    }

    private fun createBlock(id: Long): NativeBlock {
        val root = FrameLayout(scroll.context).apply { tag = id; minimumHeight = scroll.resources.displayMetrics.heightPixels }
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
        val textView = EditText(scroll.context).apply {
            background = null
            gravity = Gravity.TOP or Gravity.START
            setPadding(0, 0, 0, 0)
            configureInteraction(editMode, request.style.textSelectable)
            val selectionTextView = this
            customSelectionActionModeCallback =
                NovelSelectionActionModes.wrap(
                    context = context,
                    readOnly = { !editMode },
                    onAction = { action, mode -> dispatchSelectionAction(selectionTextView, action, mode) },
                    onSelectionModeChanged = callbacks::onSelectionModeChanged,
                )
        }
        block.textView = textView
        block.style = request.style
        scroll.setBackgroundColor(request.style.backgroundColor)
        container.setBackgroundColor(request.style.backgroundColor)
        val title = if (request.style.hideChapterTitle) "" else "<h1>${android.text.TextUtils.htmlEncode(request.chapterTitle)}</h1>"
        val spanned = HtmlCompat.fromHtml(title + request.content.html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        block.original = SpannableStringBuilder(spanned)
        block.paragraphs = Jsoup.parse(request.content.html).select("h1,h2,h3,h4,h5,h6,p,li,blockquote").map { it.text().trim() }.filter(String::isNotBlank)
        block.originalParagraphs = block.paragraphs
        style(textView, request.style)
        setBlockText(block, SpannableStringBuilder(block.original))
        textView.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { if (editMode && !applyingText && activeChapterId == block.id) callbacks.onContentEdited(s?.toString().orEmpty()) }
            },
        )
        block.root.addView(textView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (notifyReady) block.root.post { callbacks.onReady(request.initialProgress) }
    }

    private fun style(textView: EditText, style: NovelReaderStyle) {
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
        textView.setPadding((style.marginLeft * density).toInt(), (style.marginTop * density).toInt(), (style.marginRight * density).toInt(), (style.marginBottom * density).toInt())
    }

    private fun setBlockText(block: NativeBlock, text: CharSequence) {
        applyingText = true
        block.textView?.setText(text, TextView.BufferType.SPANNABLE)
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
        val range = (block.root.height - scroll.height).coerceAtLeast(1)
        scroll.scrollTo(0, block.root.top + range * progress.coerceIn(0, 100) / 100)
        callbacks.onVisibleChapter(id, progress.coerceIn(0, 100))
    }

    private fun reportVisibleBlock() {
        if (blocks.isEmpty()) return
        val marker = scroll.scrollY + scroll.height / 3
        val block = blocks.values.minByOrNull { kotlin.math.abs((it.root.top + it.root.height / 2) - marker) } ?: return
        if (block.textView == null) return
        activeChapterId = block.id
        val localRange = (block.root.height - scroll.height).coerceAtLeast(1)
        val progress = ((scroll.scrollY - block.root.top).coerceAtLeast(0) * 100 / localRange).coerceIn(0, 100)
        callbacks.onVisibleChapter(block.id, progress)
        callbacks.onProgress(progress)
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
    private fun EditText.configureInteraction(editing: Boolean, selectable: Boolean) {
        showSoftInputOnFocus = editing
        setTextIsSelectable(selectable && !editing)
        movementMethod = if (editing || selectable) ArrowKeyMovementMethod.getInstance() else null
        linksClickable = false
        isFocusable = editing || selectable
        isFocusableInTouchMode = editing || selectable
        isLongClickable = selectable && !editing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setTextClassifier(TextClassifier.NO_OP)
        if (!editing) clearFocus()
    }
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
        var textView: EditText? = null,
        var original: CharSequence = "",
        var paragraphs: List<String> = emptyList(),
        var originalParagraphs: List<String> = emptyList(),
        var style: NovelReaderStyle? = null,
        var retryRunnable: Runnable? = null,
    )

    private class PersistentHighlightSpan(color: Int) : BackgroundColorSpan(color)
    private class SpokenParagraphSpan : UnderlineSpan()
}
