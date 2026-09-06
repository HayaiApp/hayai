package dev.ahmedmohamed.hayai.source.presentation

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.dispose
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceArtworkInstrumentedTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var image: ImageView
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun attachImage() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            image = ImageView(activity).apply { setPadding(20, 20, 20, 20) }
            activity.addContentView(image, FrameLayout.LayoutParams(160, 160))
        }
        await { image.width == 160 && image.height == 160 }
    }

    @After
    fun closeImage() {
        if (::image.isInitialized) scenario.onActivity { image.dispose() }
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun wideArtworkFitsInsidePaddingAndRoundsItsOwnBounds() {
        bindBitmap(240, 120)
        onUi { assertFittedOutline(Rect(20, 50, 140, 110)) }
    }

    @Test
    fun recycledArtworkResetsCropAndTintAndRecomputesPortraitOutline() {
        bindBitmap(240, 120)
        onUi {
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.imageTintList = ColorStateList.valueOf(Color.RED)
        }
        bindBitmap(120, 240)
        onUi {
            assertNull(image.imageTintList)
            assertFittedOutline(Rect(50, 20, 110, 140))
        }
    }

    private fun bindBitmap(width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        onUi { image.bindSourceIcon(BitmapDrawable(image.resources, bitmap), R.drawable.ic_code_24dp) }
        await { (image.drawable as? BitmapDrawable)?.bitmap === bitmap }
    }

    private fun assertFittedOutline(expected: Rect) {
        assertEquals(ImageView.ScaleType.FIT_CENTER, image.scaleType)
        assertTrue(image.clipToOutline)
        val drawable = image.drawable
        val displayed = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        image.imageMatrix.mapRect(displayed)
        displayed.offset(image.paddingLeft.toFloat(), image.paddingTop.toFloat())
        assertEquals(expected.left.toFloat(), displayed.left, 0.01f)
        assertEquals(expected.top.toFloat(), displayed.top, 0.01f)
        assertEquals(expected.right.toFloat(), displayed.right, 0.01f)
        assertEquals(expected.bottom.toFloat(), displayed.bottom, 0.01f)
        val outline = Outline()
        image.outlineProvider.getOutline(image, outline)
        val bounds = Rect()
        assertTrue(outline.getRect(bounds))
        assertEquals(expected, bounds)
        assertEquals(8f * image.resources.displayMetrics.density, outline.radius, 0.01f)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            instrumentation.waitForIdleSync()
            var satisfied = false
            onUi { satisfied = condition() }
            if (satisfied) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Source artwork did not finish loading or layout")
    }

    private fun onUi(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
