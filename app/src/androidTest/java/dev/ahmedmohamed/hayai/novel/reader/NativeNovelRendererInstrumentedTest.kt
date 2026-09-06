package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.data.preference.AndroidPreferenceStore
import eu.kanade.tachiyomi.ui.main.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeNovelRendererInstrumentedTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var renderer: NativeNovelRenderer
    private lateinit var scroll: NestedScrollView
    private val events = ReaderEvents()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun attachReaderToActivity() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val prefs = activity.getSharedPreferences("native-reader-instrumentation", Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            renderer = NativeNovelRenderer(activity, events, NovelFontStore(activity, HayaiPreferences(AndroidPreferenceStore(activity, prefs))))
            scroll = renderer.view as NestedScrollView
            activity.addContentView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            renderer.display(chapter(2, 120), NovelBlockPlacement.ReplaceAll, focus = true)
        }
        await("initial chapter measured and ready") { events.ready > 0 && chapterView(2).height > scroll.height * 4 }
    }

    @After
    fun closeReader() {
        if (::renderer.isInitialized) scenario.onActivity { renderer.destroy() }
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun nativeLongPressSelectsTextWithoutNavigatingAndHighlightsPreserveSelection() {
        var x = 0f
        var y = 0f
        await("reader window accepts touch input") { scroll.hasWindowFocus() && scroll.isShown }
        onUi {
            val text = textView(2)
            assertFalse(text is EditText)
            assertTrue(text.isTextSelectable)
            val location = IntArray(2)
            text.getLocationOnScreen(location)
            val firstLine = text.layout.getLineForVertical(scroll.height / 3)
            val line = (firstLine until text.layout.lineCount).first { candidate ->
                Regex("\\p{L}{3,}").containsMatchIn(text.text.subSequence(text.layout.getLineStart(candidate), text.layout.getLineEnd(candidate)))
            }
            val lineStart = text.layout.getLineStart(line)
            val lineText = text.text.subSequence(lineStart, text.layout.getLineEnd(line)).toString()
            val word = requireNotNull(Regex("\\p{L}{3,}").find(lineText)) { "Touch target line has no word: $lineText" }
            val offset = lineStart + word.range.first + 1
            val localX = text.totalPaddingLeft + (text.layout.getPrimaryHorizontal(offset) + text.layout.getPrimaryHorizontal(offset + 1)) / 2f
            val localY = text.totalPaddingTop + text.layout.getLineBaseline(line) + text.paint.fontMetrics.ascent / 2f
            x = location[0] + localX
            y = location[1] + localY
            assertTrue(text.text[text.getOffsetForPosition(localX, localY)].isLetter())
            Log.i("HayaiNativeTest", "Long press word=${word.value} at $x,$y; ${selectionDiagnostics()}")
        }
        val downTime = SystemClock.uptimeMillis()
        pointer(downTime, MotionEvent.ACTION_DOWN, x, y)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 250)
        pointer(downTime, MotionEvent.ACTION_UP, x, y)
        try {
            await("Android selection action mode") { events.selectionActive && textView(2).hasSelection() }
        } catch (failure: AssertionError) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            if (screenshot != null) {
                val file = File(instrumentation.targetContext.getExternalFilesDir(null), "native-selection-failure.png")
                file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            var diagnostics = ""
            onUi { diagnostics = selectionDiagnostics() }
            throw AssertionError("${failure.message}; $diagnostics", failure)
        }
        onUi {
            val text = textView(2)
            val start = text.selectionStart
            val end = text.selectionEnd
            var selected: NovelSelection? = null
            renderer.selection { selected = it }
            assertNotNull(selected)
            assertTrue(selected!!.selectedText.isNotBlank())
            renderer.applyHighlights(emptyList())
            assertEquals(start, text.selectionStart)
            assertEquals(end, text.selectionEnd)
            assertEquals(0, events.taps)
        }
    }

    @Test
    fun unequalChapterLengthsKeepTheTopVisibleChapterCurrentInBothDirections() {
        onUi { renderer.display(chapter(3, 2), NovelBlockPlacement.After, focus = false) }
        await("next chapter measured") { chapterView(3).top == chapterView(2).bottom }
        onUi {
            scroll.scrollTo(0, chapterView(2).bottom - scroll.height / 2)
            assertEquals(2L, events.visibleChapter)
            scroll.scrollTo(0, chapterView(3).top)
            assertEquals(3L, events.visibleChapter)
            scroll.scrollTo(0, chapterView(3).top - 1)
            assertEquals(2L, events.visibleChapter)
            assertEquals(0, events.unkeyedProgress)
        }
    }

    @Test
    fun prependingReplacingAndRetainingChaptersPreservesTheVisibleChapterOffset() {
        onUi { renderer.focus(2, 50) }
        await("seek to midpoint") { scroll.scrollY > 0 }
        var localOffset = 0
        onUi {
            localOffset = scroll.scrollY - chapterView(2).top
            renderer.display(NovelRenderBlock(1, "Previous", NovelBlockContent.Loading), NovelBlockPlacement.Before, focus = false)
        }
        await("loading predecessor preserves anchor") { chapterView(2).top > 0 && scroll.scrollY - chapterView(2).top == localOffset }
        onUi { renderer.display(chapter(1, 70), NovelBlockPlacement.Before, focus = false) }
        await("loaded predecessor preserves anchor") { chapterView(1).height > scroll.height * 3 && scroll.scrollY - chapterView(2).top == localOffset }
        onUi { renderer.retain(setOf(2)) }
        await("removing predecessor preserves anchor") { chapterView(2).top == 0 && scroll.scrollY == localOffset }
        onUi { assertEquals(2L, events.visibleChapter) }
    }

    @Test
    fun focusingMountedChapterDoesNotReplaceTextOrSeekUnlessRequested() {
        onUi { renderer.display(chapter(3, 30), NovelBlockPlacement.After, focus = false) }
        await("next chapter layout") { chapterView(3).height > scroll.height }
        var offset = 0
        lateinit var mountedText: TextView
        onUi {
            scroll.scrollTo(0, chapterView(2).height / 3)
            offset = scroll.scrollY
            mountedText = textView(3)
            renderer.focus(3)
            assertTrue(mountedText === textView(3))
            assertEquals(offset, scroll.scrollY)
            renderer.documentText { assertTrue(it.startsWith("Chapter 3 paragraph 0")) }
            renderer.focus(3, 50)
        }
        await("explicit focus seeks within requested chapter") {
            val chapter = chapterView(3)
            scroll.scrollY == NovelViewportGeometry.offset(chapter.top, chapter.height, scroll.height, 50)
        }
        onUi {
            assertTrue(mountedText === textView(3))
            assertEquals(3L, events.visibleChapter)
        }
    }

    @Test
    fun returningFromEditingRestoresSelectableReadOnlyTextAndPreservesChanges() {
        onUi {
            renderer.setEditMode(true)
            assertTrue(textView(2) is EditText)
            (textView(2) as EditText).append("\nEdited ending")
            assertTrue(events.editedContent.endsWith("Edited ending"))
            renderer.setEditMode(false)
            assertFalse(textView(2) is EditText)
            assertTrue(textView(2).isTextSelectable)
            renderer.documentText { assertTrue(it.endsWith("Edited ending")) }
        }
    }

    private fun chapter(id: Long, paragraphs: Int): NovelRenderBlock = NovelRenderBlock(
        id,
        "Chapter $id",
        NovelBlockContent.Ready(
            NovelRenderRequest(
                id,
                ProcessedNovelContent((0 until paragraphs).joinToString("") { "<p>Chapter $id paragraph $it. This is selectable native text with enough words to wrap across several lines in the reader viewport.</p>" }, null),
                "Chapter $id",
                NovelReaderStyle(
                    fontSize = 18,
                    fontFamily = "sans-serif",
                    lineHeight = 1.5f,
                    textAlign = "justify",
                    textColor = Color.BLACK,
                    backgroundColor = Color.WHITE,
                    linkColor = Color.BLUE,
                    paragraphIndent = 1f,
                    paragraphSpacing = 0.5f,
                    marginLeft = 20,
                    marginRight = 20,
                    marginTop = 20,
                    marginBottom = 20,
                    useOriginalFonts = true,
                    textSelectable = true,
                    hideChapterTitle = true,
                    sourceCssPriority = false,
                    renderingMode = "continuous",
                    customCss = "",
                    customJs = "",
                    ttsHighlightColor = Color.YELLOW,
                    ttsHighlightTextColor = Color.BLACK,
                    ttsHighlightStyle = "background",
                    keepTtsHighlightInView = true,
                ),
                initialProgress = 0,
            ),
        ),
    )

    private fun chapterView(id: Long): FrameLayout = scroll.findViewWithTag(id)
    private fun textView(id: Long): TextView = chapterView(id).getChildAt(0) as TextView
    private fun onUi(action: () -> Unit) = scenario.onActivity { action() }

    private fun selectionDiagnostics(): String {
        val text = textView(2)
        val parents = generateSequence(text.parent) { it.parent }.filterIsInstance<ViewGroup>()
            .joinToString { "${it.javaClass.simpleName}=${it.descendantFocusability}" }
        return "focused=${text.hasFocus()}, window=${text.hasWindowFocus()}, focusable=${text.isFocusable}, " +
            "longClickable=${text.isLongClickable}, movement=${text.movementMethod?.javaClass?.simpleName}, " +
            "selection=${text.selectionStart}..${text.selectionEnd}, actionMode=${events.selectionActive}, " +
            "taps=${events.taps}, scrollY=${scroll.scrollY}, parents=[$parents]"
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var matches = false
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            onUi { matches = condition() }
            if (matches) return
            SystemClock.sleep(20)
        }
        assertTrue(description, matches)
    }

    private fun pointer(downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private class ReaderEvents : NovelRenderer.Callbacks {
        var ready = 0
        var visibleChapter: Long? = null
        var selectionActive = false
        var taps = 0
        var unkeyedProgress = 0
        var editedContent = ""
        override fun onReady(progress: Int) { ready++ }
        override fun onVisibleChapter(chapterId: Long, progress: Int) { visibleChapter = chapterId }
        override fun onSelectionModeChanged(active: Boolean) { selectionActive = active }
        override fun onProgress(progress: Int) { unkeyedProgress++ }
        override fun onTap(xFraction: Float, yFraction: Float) { taps++ }
        override fun onRetryChapter(chapterId: Long) = Unit
        override fun onContentEdited(content: String) { editedContent = content }
        override fun onRendererError(message: String) { throw AssertionError(message) }
    }
}
