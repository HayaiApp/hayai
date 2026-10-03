package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahmedmohamed.hayai.novel.source.NovelAssetProvider
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.data.preference.AndroidPreferenceStore
import eu.kanade.tachiyomi.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovelReaderCallbackLifecycleInstrumentedTest {
    @Test fun queuedTtsEventsAreDiscardedWhenReaderClosesBeforeUiDispatch() {
        val pending = mutableListOf<() -> Unit>()
        var delivered = 0
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int) = true
            override fun unbindService(connection: ServiceConnection) = Unit
        }
        val callbacks = object : NovelTtsController.Callbacks {
            override fun onParagraphChanged(index: Int) { delivered++ }
            override fun onHighlightCleared() { delivered++ }
            override fun onPlaybackChanged(playing: Boolean) { delivered++ }
            override fun onChapterCompleted() { delivered++ }
            override fun onError(message: String) { delivered++ }
            override fun runOnUiThread(action: () -> Unit) { pending += action }
        }
        val controller = NovelTtsController(context, callbacks)
        controller.onParagraphChanged(2)
        controller.onChapterCompleted()
        controller.onError("Delayed engine failure")
        assertEquals(3, pending.size)
        controller.destroy()
        pending.forEach { it() }
        assertEquals(0, delivered)
    }

    @Test fun webRequestsCannotUpdateAReplacementDocumentOrDestroyedReader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var renderer: WebNovelRenderer
        var created = false
        var ready = 0
        var delivered = 0
        val events = object : NovelRenderer.Callbacks {
            override fun onReady(progress: Int) { ready++ }
            override fun onProgress(progress: Int) = Unit
            override fun onVisibleChapter(chapterId: Long, progress: Int) = Unit
            override fun onRetryChapter(chapterId: Long) = Unit
            override fun onTap(xFraction: Float, yFraction: Float) = Unit
            override fun onContentEdited(content: String) = Unit
            override fun onRendererError(message: String) = Unit
        }
        try {
            scenario.onActivity { activity ->
                val prefs = HayaiPreferences(AndroidPreferenceStore(activity, activity.getSharedPreferences("reader-callback-instrumentation", Context.MODE_PRIVATE)))
                renderer = WebNovelRenderer(activity, events, object : NovelAssetProvider {
                    override suspend fun getChapterAsset(chapterUrl: String, assetPath: String) = null
                }, { "chapter" }, { true }, { "chapter" }, { true }, { false }, { false }, false, NovelFontStore(activity, prefs))
                created = true
                activity.addContentView(renderer.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                renderer.display(chapter(1), NovelBlockPlacement.ReplaceAll, true)
            }
            val deadline = SystemClock.uptimeMillis() + 10000
            var isReady = false
            while (!isReady && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { isReady = ready > 0 }
                if (!isReady) SystemClock.sleep(50)
            }
            assertTrue("Web document did not initialize", isReady)
            scenario.onActivity {
                renderer.paragraphs { delivered++ }
                renderer.display(chapter(2), NovelBlockPlacement.ReplaceAll, true)
                renderer.documentText { delivered++ }
                renderer.selection { delivered++ }
                renderer.isShort { delivered++ }
                renderer.destroy()
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(0, delivered) }
        } finally {
            if (created) scenario.onActivity { renderer.destroy() }
            scenario.close()
        }
    }

    private fun chapter(id: Long) = NovelRenderBlock(id, "Chapter $id", NovelBlockContent.Ready(NovelRenderRequest(
        id, ProcessedNovelContent("<p>Chapter $id text.</p>", null), "Chapter $id",
        NovelReaderStyle(18, "sans-serif", 1.5f, "left", Color.BLACK, Color.WHITE, Color.BLUE, 0f, 0f, 20, 20, 20, 20, false, true, true, false, "continuous", "", "", Color.YELLOW, Color.BLACK, "background", true),
        0,
    )))
}
