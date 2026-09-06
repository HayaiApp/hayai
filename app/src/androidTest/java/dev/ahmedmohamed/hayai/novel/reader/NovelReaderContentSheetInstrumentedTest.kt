package dev.ahmedmohamed.hayai.novel.reader

import android.graphics.Rect
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovelReaderContentSheetInstrumentedTest {
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var sheet: NovelReaderContentSheet

    @Before fun launch() { activity = ActivityScenario.launch(MainActivity::class.java) }
    @After fun close() { activity.onActivity { if (::sheet.isInitialized) sheet.dismiss() }; activity.close() }

    @Test fun longQuoteListScrollsWhileImportStaysInOverflow() {
        activity.onActivity { host ->
            sheet = NovelReaderContentSheet(host, host.getString(R.string.hayai_novel_reader_saved_quotes))
            sheet.overflow(R.string.hayai_novel_reader_import_quotes) {}
            sheet.items((1..200).toList(), R.string.hayai_novel_reader_no_quotes, { "Chapter $it" }, { "Saved quote $it" }) {}
            sheet.show()
        }
        await {
            val list = descendants(sheet.window!!.decorView).filterIsInstance<RecyclerView>().single()
            list.canScrollVertically(1)
        }
        activity.onActivity {
            val toolbar = sheet.findViewById<MaterialToolbar>(R.id.toolbar)!!
            val list = descendants(sheet.window!!.decorView).filterIsInstance<RecyclerView>().single()
            assertTrue(list.childCount < 200)
            list.scrollToPosition(199)
            assertTrue(toolbar.showOverflowMenu())
        }
        await { sheet.findViewById<MaterialToolbar>(R.id.toolbar)!!.isOverflowMenuShowing }
    }

    @Test fun editorSaveRemainsVisibleAboveKeyboardWithLongText() {
        activity.onActivity { host ->
            sheet = NovelReaderContentSheet(host, host.getString(R.string.hayai_novel_reader_edit_quote))
            sheet.input(R.string.hayai_novel_reader_chapter, "Chapter 1")
            val body = sheet.input(R.string.hayai_novel_reader_quote, "A long selected quote.\n".repeat(80), true)
            sheet.action(R.string.hayai_novel_sheet_save, primary = true) {}
            sheet.show()
            body.requestFocus()
        }
        await { sheet.window!!.decorView.hasWindowFocus() }
        activity.onActivity {
            val body = descendants(sheet.window!!.decorView).filterIsInstance<TextInputEditText>().last()
            WindowInsetsControllerCompat(sheet.window!!, body).show(WindowInsetsCompat.Type.ime())
        }
        await { ViewCompat.getRootWindowInsets(sheet.window!!.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        await {
            val button = descendants(sheet.window!!.decorView).filterIsInstance<MaterialButton>().single()
            val rect = Rect()
            button.getGlobalVisibleRect(rect) && rect.height() == button.height && button.height > 0
        }
    }

    private fun await(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        var passed = false
        while (!passed && SystemClock.uptimeMillis() < end) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            activity.onActivity { passed = check() }
            if (!passed) SystemClock.sleep(50)
        }
        if (!passed) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.getExternalFilesDir(null), "reader-sheet-failure.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        assertTrue("Reader sheet did not reach the expected visible state", passed)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
    }
}
