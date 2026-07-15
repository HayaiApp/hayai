package eu.kanade.tachiyomi.ui.source.browse

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
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
    private val endPaddingPx = 8.dpToPx
    private val slots = mutableListOf<Slot>()
    private var currentSegments = emptyList<Segment>()

    private data class Slot(
        val wedge: ImageView,
        val label: MaterialTextView,
    )

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipToPadding = false
    }

    /**
     * Updates the strip without rebuilding its child hierarchy. Browse cells are rebound often
     * while paging and returning from details; allocating MaterialTextViews during every bind made
     * those otherwise-small updates trigger repeated measure/layout work across the whole row.
     */
    fun setSegments(segments: List<Segment>) {
        if (segments == currentSegments) return
        currentSegments = segments.toList()

        isVisible = segments.isNotEmpty()
        while (slots.size < segments.size) addSlot(slots.size)

        slots.forEachIndexed { index, slot ->
            val segment = segments.getOrNull(index)
            val visibility = if (segment == null) View.GONE else View.VISIBLE
            slot.label.visibility = visibility
            slot.wedge.visibility = if (index > 0 && segment != null) View.VISIBLE else View.GONE
            if (segment == null) return@forEachIndexed

            slot.wedge.setColorFilter(segment.backgroundColor)
            slot.label.text = segment.text
            slot.label.setTextColor(segment.textColor)
            slot.label.setBackgroundColor(segment.backgroundColor)
        }
    }

    private fun addSlot(index: Int) {
        // The forward slant between adjacent segments mirrors the Compose ForwardSlantedShape:
        // a tinted angled wedge tucked in front of every segment after the first.
        val wedge = ImageView(context).apply {
            setImageResource(R.drawable.unread_angled_badge)
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = View.GONE
            layoutParams = LayoutParams(slantPx, LayoutParams.MATCH_PARENT)
        }
        val label = MaterialTextView(context).apply {
            textSize = 13f
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            visibility = View.GONE
            val start = if (index == 0) endPaddingPx else 0
            setPadding(start, 0, endPaddingPx, 0)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        }
        addView(wedge)
        addView(label)
        slots += Slot(wedge, label)
    }
}
