package dev.ahmedmohamed.hayai.novel.reader

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.WindowInsetsCompat.Type.displayCutout
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetBehavior
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterSheet
import eu.kanade.tachiyomi.util.view.doOnApplyWindowInsetsCompat

/** Concrete adapter for the unmodified J2K reader shell used by novel content. */
internal class J2kNovelReaderChrome(
    private val activity: AppCompatActivity,
) {
    private val themedActionBarHeight = activity.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize)).let { attributes ->
        attributes.getDimensionPixelSize(0, 0).also { attributes.recycle() }
    }
    val readerLayout: ViewGroup = activity.findViewById(R.id.reader_layout)
    val viewerContainer: FrameLayout = activity.findViewById(R.id.viewer_container)
    val appBar: View = activity.findViewById(R.id.app_bar)
    val toolbar: Toolbar = activity.findViewById(R.id.toolbar)
    val navigation: View = activity.findViewById(R.id.nav_layout)
    val touchView: View = activity.findViewById(R.id.touch_view)
    val chapterSheet: ReaderChapterSheet = activity.findViewById(R.id.chapters_sheet)

    fun bindInsets(
        statusView: TextView,
        fullscreen: () -> Boolean,
    ) {
        readerLayout.doOnApplyWindowInsetsCompat { _, insets, _ ->
            val systemInsets = insets.getInsetsIgnoringVisibility(systemBars())
            val cutoutInsets = insets.getInsetsIgnoringVisibility(displayCutout())
            val contentInsets = insets.getInsetsIgnoringVisibility(systemBars() or displayCutout())
            appBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = systemInsets.left
                rightMargin = systemInsets.right
            }
            toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemInsets.top
                leftMargin = cutoutInsets.left
                rightMargin = cutoutInsets.right
            }
            viewerContainer.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                leftMargin = cutoutInsets.left
                rightMargin = cutoutInsets.right
                val hideBars = fullscreen() && !activity.isInMultiWindowMode
                topMargin = if (hideBars) 0 else contentInsets.top
                bottomMargin = if (hideBars) 0 else contentInsets.bottom
                viewerContainer.setPadding(
                    viewerContainer.paddingLeft,
                    novelViewerTopPadding(
                        overlaysSystemBars = hideBars,
                        systemBarTop = systemInsets.top,
                        toolbarHeight = toolbar.layoutParams.height.takeIf { it > 0 } ?: themedActionBarHeight,
                    ),
                    viewerContainer.paddingRight,
                    viewerContainer.paddingBottom,
                )
            }
            // Match J2K exactly. Reader chrome overlays the viewer and never reflows its content.
            navigation.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = 12.dp + contentInsets.left
                rightMargin = 12.dp + contentInsets.right
            }
            statusView.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                bottomMargin = if (fullscreen() && !activity.isInMultiWindowMode) 0 else systemInsets.bottom
            }
            viewerContainer.requestLayout()
        }
    }

    fun setVisible(
        visible: Boolean,
        chapterSheetBehavior: BottomSheetBehavior<ReaderChapterSheet>,
    ) {
        appBar.visibility = if (visible) View.VISIBLE else View.GONE
        navigation.visibility = if (visible && chapterSheetBehavior.state != BottomSheetBehavior.STATE_EXPANDED) View.VISIBLE else View.GONE
        chapterSheetBehavior.state = if (visible) BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_HIDDEN
        readerLayout.requestApplyInsets()
    }

    private val Int.dp: Int
        get() = (this * activity.resources.displayMetrics.density).toInt()

}

internal fun novelViewerTopPadding(
    overlaysSystemBars: Boolean,
    systemBarTop: Int,
    toolbarHeight: Int,
): Int = 0
