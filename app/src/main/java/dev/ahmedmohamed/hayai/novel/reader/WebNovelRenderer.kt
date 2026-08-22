package dev.ahmedmohamed.hayai.novel.reader

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import dev.ahmedmohamed.hayai.novel.source.NovelAssetProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
internal class WebNovelRenderer(
    context: Context,
    private val callbacks: NovelRenderer.Callbacks,
    assetProvider: NovelAssetProvider,
    chapterUrl: () -> String,
    offline: () -> Boolean,
    chapterUrlForId: (Long) -> String?,
    offlineForId: (Long) -> Boolean?,
    blockMedia: () -> Boolean,
    showConsoleErrors: () -> Boolean,
    enableDevTools: Boolean,
    fontStore: NovelFontStore,
) : NovelRenderer {
    override val mode = NovelRenderingMode.WebView
    private val json = Json { ignoreUnknownKeys = true }
    private var requestedProgress = 0
    private var editMode = false
    private val webView = WebView(context).apply {
        WebView.setWebContentsDebuggingEnabled(enableDevTools)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        addJavascriptInterface(Bridge(), JS_INTERFACE)
        webViewClient = NovelAssetWebViewClient(assetProvider, chapterUrl, offline, chapterUrlForId, offlineForId, blockMedia, fontStore)
        webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    if (showConsoleErrors() && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        callbacks.onRendererError("Web reader line ${consoleMessage.lineNumber()}: ${consoleMessage.message()}")
                    }
                    return true
                }
            }
        setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                evaluateJavascript("Boolean(getSelection() && !getSelection().isCollapsed)") { selected ->
                    if (selected != "true") callbacks.onTap(event.x / view.width.coerceAtLeast(1), event.y / view.height.coerceAtLeast(1))
                }
            }
            false
        }
    }
    override val view: View = webView

    override fun display(block: NovelRenderBlock, placement: NovelBlockPlacement, focus: Boolean) {
        val content = block.content
        if (placement == NovelBlockPlacement.ReplaceAll && content is NovelBlockContent.Ready) {
            requestedProgress = content.request.initialProgress.coerceIn(0, 100)
            val request = content.request
            val html = NovelHtmlDocumentBuilder.build(request.content.scopeAssets(request.chapterId), request.chapterTitle, request.style, request.chapterId)
            val base = request.content.baseUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            webView.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
            return
        }
        val placementValue = if (placement == NovelBlockPlacement.Before) "before" else "after"
        when (content) {
            NovelBlockContent.Loading -> setBlockState(block.chapterId, loadingHtml(block.chapterTitle), placementValue, focus)
            is NovelBlockContent.Error -> {
                setBlockState(block.chapterId, errorHtml(block.chapterId, block.chapterTitle, content), placementValue, focus)
                scheduleRetryLabel(block.chapterId, content.retryAtMillis)
            }
            is NovelBlockContent.Ready -> {
                val request = content.request
                val heading = if (request.style.hideChapterTitle) "" else "<h1 class=\"hayai-chapter-title\">${android.text.TextUtils.htmlEncode(request.chapterTitle)}</h1>"
                evaluate(
                    "window.hayaiReader.upsertBlock(${json.encodeToString(block.chapterId.toString())}," +
                        "${json.encodeToString(heading + request.content.scopeAssets(request.chapterId).html)},${json.encodeToString(placementValue)},$focus)",
                )
                if (focus) evaluate("window.hayaiReader.focusBlock(${json.encodeToString(block.chapterId.toString())},${request.initialProgress})")
                if (request.appendJavaScript.isNotBlank()) {
                    evaluate("(()=>{${request.appendJavaScript}})()")
                }
                if (focus) {
                    webView.evaluateJavascript(
                        "window.hayaiReader.progress()",
                    ) { callbacks.onReady(request.initialProgress) }
                }
            }
        }
    }

    override fun retain(chapterIds: Set<Long>) {
        evaluate("window.hayaiReader.retainBlocks(${json.encodeToString(chapterIds.map { it.toString() })})")
    }

    override fun seek(progress: Int) = evaluate("window.hayaiReader.scrollToPercent(${progress.coerceIn(0, 100)})")
    override fun step(direction: Int) = evaluate("window.hayaiReader.step(${direction.coerceIn(-1, 1)})")
    override fun stepPixels(pixels: Int) = evaluate("window.hayaiReader.stepPixels($pixels)")
    override fun scrollToTop() = seek(0)

    override fun selection(callback: (NovelSelection?) -> Unit) {
        webView.evaluateJavascript("JSON.stringify(window.hayaiReader.takeSelectionAnchor())") { encoded ->
            val payload = decodeJsString(encoded) ?: return@evaluateJavascript callback(null)
            callback(runCatching { json.decodeFromString<SelectionPayload>(payload).asSelection() }.getOrNull())
        }
    }

    override fun documentText(callback: (String) -> Unit) {
        webView.evaluateJavascript("window.hayaiReader.documentText()") { callback(decodeJsString(it).orEmpty()) }
    }

    override fun paragraphs(callback: (List<String>) -> Unit) {
        webView.evaluateJavascript("JSON.stringify(window.hayaiReader.paragraphs())") { encoded ->
            val payload = decodeJsString(encoded)
            val paragraphs = payload?.let { runCatching { json.decodeFromString<List<ParagraphPayload>>(it) }.getOrNull() }.orEmpty()
            callback(paragraphs.map(ParagraphPayload::text))
        }
    }

    override fun viewportParagraph(callback: (Int) -> Unit) {
        webView.evaluateJavascript("window.hayaiReader.viewportParagraph ? window.hayaiReader.viewportParagraph() : 0") {
            callback(it.toIntOrNull()?.coerceAtLeast(0) ?: 0)
        }
    }

    override fun showTranslation(text: String) = evaluate("window.hayaiReader.showTranslation(${json.encodeToString(text)})")
    override fun showOriginal() = evaluate("window.hayaiReader.showOriginal()")

    override fun applyHighlights(items: List<NovelPersistentHighlight>) {
        val payload =
            items.map {
                HighlightPayload(it.id, it.exact, it.prefix, it.suffix, it.occurrence, NovelHtmlDocumentBuilder.color(it.color))
            }
        evaluate("window.hayaiReader.applyPersistentHighlights(${json.encodeToString(payload)})")
    }

    override fun highlightSpokenParagraph(index: Int) = evaluate("window.hayaiReader.highlight($index)")
    override fun clearSpokenHighlight() = evaluate("window.hayaiReader.clearHighlight()")
    override fun isShort(callback: (Boolean) -> Unit) = webView.evaluateJavascript("(window.hayaiReader.documentText().length > 0) && (window.hayaiReader.progress() === 0) && (document.querySelector('.hayai-chapter-block[data-active=true]')?.offsetHeight <= innerHeight)") { callback(it == "true") }

    override fun setEditMode(enabled: Boolean) {
        editMode = enabled
        evaluate(
            "const b=document.querySelector('.hayai-chapter-block[data-active=true]');if(b){b.contentEditable=${if (enabled) "'true'" else "'false'"};${if (enabled) "b.focus();" else "b.blur();"}}",
        )
        if (!enabled) documentText(callbacks::onContentEdited)
    }

    override fun destroy() {
        webView.removeJavascriptInterface(JS_INTERFACE)
        webView.stopLoading()
        webView.destroy()
    }

    private fun evaluate(script: String) = webView.evaluateJavascript(script, null)

    private fun setBlockState(chapterId: Long, html: String, placement: String, focus: Boolean) {
        evaluate(
            "window.hayaiReader.setBlockState(${json.encodeToString(chapterId.toString())},${json.encodeToString(html)},${json.encodeToString(placement)},$focus)",
        )
    }

    private fun loadingHtml(title: String): String =
        "<div class=\"hayai-block-state\"><strong>${android.text.TextUtils.htmlEncode(title)}</strong><div>Loading chapter…</div></div>"

    private fun errorHtml(chapterId: Long, title: String, error: NovelBlockContent.Error): String =
        "<div class=\"hayai-block-state\"><strong>${android.text.TextUtils.htmlEncode(title)}</strong>" +
            "<div>${android.text.TextUtils.htmlEncode(error.message)}</div><button class=\"hayai-block-retry\" data-retry-id=\"$chapterId\" disabled>Retry</button></div>"

    private fun scheduleRetryLabel(chapterId: Long, retryAt: Long) {
        evaluate(
            "(()=>{const id=${json.encodeToString(chapterId.toString())},until=$retryAt;const tick=()=>{" +
                "const b=document.querySelector('[data-retry-id=\"'+id+'\"]');if(!b)return;const left=until-Date.now();" +
                "b.disabled=left>0;b.textContent=left>0?'Retry in '+Math.ceil(left/1000)+'s':'Retry';" +
                "if(left>0)setTimeout(tick,Math.min(1000,left));else b.onclick=()=>HayaiReader.retryChapter(id);};tick();})()",
        )
    }

    private fun decodeJsString(encoded: String): String? = runCatching { json.decodeFromString<String>(encoded) }.getOrNull()

    private fun ProcessedNovelContent.scopeAssets(chapterId: Long): ProcessedNovelContent {
        val document = org.jsoup.Jsoup.parseBodyFragment(html)
        document.select("[src], [poster], [href]").forEach { element ->
            listOf("src", "poster", "href").forEach { attribute ->
                val value = element.attr(attribute)
                if (value.startsWith("hayai-novel-image://") || value.startsWith("novel-image://")) {
                    element.attr(attribute, "$value${if ('?' in value) '&' else '?'}hayaiChapterId=$chapterId")
                }
            }
        }
        return copy(html = document.body().html())
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onProgress(progress: Int) = webView.post { callbacks.onProgress(progress.coerceIn(0, 100)) }

        @JavascriptInterface
        fun onChapterProgress(chapterId: String, progress: Int) = webView.post {
            chapterId.toLongOrNull()?.let { callbacks.onVisibleChapter(it, progress.coerceIn(0, 100)) }
            callbacks.onProgress(progress.coerceIn(0, 100))
        }

        @JavascriptInterface
        fun retryChapter(chapterId: String) = webView.post { chapterId.toLongOrNull()?.let(callbacks::onRetryChapter) }

        @JavascriptInterface
        fun onReady(progress: Int) = webView.post {
            seek(requestedProgress)
            callbacks.onReady(requestedProgress)
        }

        @JavascriptInterface
        fun onHighlightReport(applied: Int, orphaned: Int, overlaps: Int) {
            if (orphaned > 0 || overlaps > 0) webView.post { callbacks.onRendererError("Highlights restored: $applied. Stale: $orphaned. Overlaps skipped: $overlaps") }
        }
    }

    @Serializable
    private data class ParagraphPayload(val index: Int, val text: String)

    @Serializable
    private data class SelectionPayload(
        val documentText: String,
        val selectedText: String,
        val prefix: String,
        val suffix: String,
        val occurrence: Int,
    ) {
        fun asSelection() = NovelSelection(documentText, selectedText, prefix, suffix, occurrence)
    }

    @Serializable
    private data class HighlightPayload(
        val id: String,
        val exact: String,
        val prefix: String,
        val suffix: String,
        val occurrence: Int,
        val color: String,
    )

    private companion object {
        const val JS_INTERFACE = "HayaiReader"
    }
}
