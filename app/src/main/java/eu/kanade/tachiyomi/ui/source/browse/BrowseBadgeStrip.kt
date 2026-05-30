package eu.kanade.tachiyomi.ui.source.browse

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.google.android.material.textview.MaterialTextView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.dpToPx

/**
 * View-based equivalent of the Compose [yokai.presentation.manga.components.Badge] used by the
 * source-browse grid cell. Renders up to N colored text segments in a single slanted strip with
 * forward-slanted separators between segments, matching the Compose look (slant 6dp, height 18dp).
 * Built programmatically so the holder can rebind it cheaply on recycle instead of paying a
 * per-cell ComposeView first composition.
 */
class BrowseBadgeStrip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    data class Segment(val text: String, val backgroundColor: Int, val textColor: Int)

    private val slantPx = 6.dpToPx

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipToPadding = false
    }

    /** Rebuilds the strip from [segments]; hides itself when empty. */
    fun setSegments(segments: List<Segment>) {
        removeAllViews()
        isVisible = segments.isNotEmpty()
        if (segments.isEmpty()) return

        segments.forEachIndexed { index, segment ->
            // The forward slant between adjacent segments mirrors the Compose ForwardSlantedShape:
            // a tinted angled wedge tucked in front of every segment after the first.
            if (index > 0) {
                addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.unread_angled_badge)
                        scaleType = ImageView.ScaleType.FIT_XY
                        setColorFilter(segment.backgroundColor)
                        layoutParams = LayoutParams(slantPx, LayoutParams.MATCH_PARENT)
                    },
                )
            }
            addView(
                MaterialTextView(context).apply {
                    text = segment.text
                    setTextColor(segment.textColor)
                    setBackgroundColor(segment.backgroundColor)
                    textSize = 13f
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    maxLines = 1
                    // First segment squares off at the start; later segments butt against the wedge.
                    val start = if (index == 0) 8.dpToPx else 0
                    setPadding(start, 0, 8.dpToPx, 0)
                    layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                },
            )
        }
    }
}
