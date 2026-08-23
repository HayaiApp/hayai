package dev.ahmedmohamed.hayai.source.preview

import kotlin.math.floor

/** Geometry ported from TachiyomiSY's PagePreview composables. */
internal object SyPagePreviewLayout {
    const val PREVIEW_WIDTH_DP = 120
    const val PREVIEW_HEIGHT_DP = 200
    const val OUTER_PADDING_DP = 8
    const val COLUMN_SPACING_DP = 16
    const val ROW_SPACING_DP = 8
    const val CORNER_RADIUS_DP = 4

    fun columns(availableWidthPx: Int, density: Float): Int {
        require(availableWidthPx >= 0)
        require(density > 0f)
        return floor(availableWidthPx / (PREVIEW_WIDTH_DP * density)).toInt().coerceAtLeast(1)
    }
}
