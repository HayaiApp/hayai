package dev.ahmedmohamed.hayai.novel.reader

import android.view.View

internal data class NovelRenderRequest(
    val chapterId: Long,
    val content: ProcessedNovelContent,
    val chapterTitle: String,
    val style: NovelReaderStyle,
    val initialProgress: Int,
    val appendJavaScript: String = "",
)

internal sealed interface NovelBlockContent {
    data object Loading : NovelBlockContent
    data class Ready(val request: NovelRenderRequest) : NovelBlockContent
    data class Error(val message: String, val retryAtMillis: Long) : NovelBlockContent
}

internal data class NovelRenderBlock(
    val chapterId: Long,
    val chapterTitle: String,
    val content: NovelBlockContent,
)

internal enum class NovelBlockPlacement { ReplaceAll, Before, After }

data class NovelSelection(
    val documentText: String,
    val selectedText: String,
    val prefix: String,
    val suffix: String,
    val occurrence: Int,
)

enum class NovelSelectionAction {
    SaveQuote,
    Define,
    GoogleTranslate,
    SearchWeb,
}

internal data class NovelPersistentHighlight(
    val id: String,
    val exact: String,
    val prefix: String,
    val suffix: String,
    val occurrence: Int,
    val color: Int,
)

internal interface NovelRenderer {
    val view: View
    val mode: NovelRenderingMode

    fun display(block: NovelRenderBlock, placement: NovelBlockPlacement, focus: Boolean)
    fun retain(chapterIds: Set<Long>)
    fun seek(progress: Int)
    fun step(direction: Int)
    fun stepPixels(pixels: Int)
    fun scrollToTop()
    fun selection(callback: (NovelSelection?) -> Unit)
    fun documentText(callback: (String) -> Unit)
    fun paragraphs(callback: (List<String>) -> Unit)
    fun viewportParagraph(callback: (Int) -> Unit)
    fun showTranslation(text: String)
    fun showOriginal()
    fun applyHighlights(items: List<NovelPersistentHighlight>)
    fun highlightSpokenParagraph(index: Int)
    fun clearSpokenHighlight()
    fun isShort(callback: (Boolean) -> Unit)
    fun setEditMode(enabled: Boolean)
    fun destroy()

    interface Callbacks {
        fun onReady(progress: Int)
        fun onProgress(progress: Int)
        fun onPageLocation(progress: Int, pageNumber: Int, pageCount: Int) = onProgress(progress)
        fun onVisibleChapter(chapterId: Long, progress: Int)
        fun onRetryChapter(chapterId: Long)
        fun onTap(xFraction: Float, yFraction: Float)
        fun onSelectionAction(action: NovelSelectionAction, selection: NovelSelection) = Unit
        fun onSelectionModeChanged(active: Boolean) = Unit
        fun onContentEdited(content: String)
        fun onRendererError(message: String)
    }
}
