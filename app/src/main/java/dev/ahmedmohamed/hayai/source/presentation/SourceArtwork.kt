package dev.ahmedmohamed.hayai.source.presentation

import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.annotation.DrawableRes
import coil.dispose
import coil.load
import coil.size.Scale
import coil.target.ImageViewTarget
import dev.ahmedmohamed.hayai.novel.plugin.source.NovelPluginSource
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelSource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.icon
import kotlin.math.min
import kotlin.math.roundToInt

/** Binds the same source artwork on Browse, migration, and source selection surfaces. */
fun ImageView.bindSourceArtwork(source: Source) {
    val artwork =
        when {
            source is NovelPluginSource -> source.iconUrl.takeIf(String::isNotBlank)
            source.id == LocalSource.ID -> R.mipmap.ic_local_source
            source.id == LocalNovelSource.ID -> R.drawable.ic_local_novel_source
            else -> source.icon()
        }
    bindSourceIcon(artwork, if (source is NovelPluginSource) R.drawable.ic_code_24dp else R.mipmap.ic_launcher)
}

fun ImageView.bindSourceIcon(artwork: Any?, @DrawableRes fallback: Int) {
    dispose()
    imageTintList = null
    scaleType = ImageView.ScaleType.FIT_CENTER
    setImageDrawable(null)
    outlineProvider = SourceIconOutline
    clipToOutline = true
    load(artwork) {
        scale(Scale.FIT)
        placeholder(fallback)
        error(fallback)
        fallback(fallback)
        crossfade(false)
        target(SourceIconTarget(this@bindSourceIcon))
    }
}

private class SourceIconTarget(view: ImageView) : ImageViewTarget(view) {
    override fun onStart(placeholder: Drawable?) {
        super.onStart(placeholder)
        view.invalidateOutline()
    }

    override fun onSuccess(result: Drawable) {
        super.onSuccess(result)
        view.invalidateOutline()
    }

    override fun onError(error: Drawable?) {
        super.onError(error)
        view.invalidateOutline()
    }
}

private object SourceIconOutline : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        val image = view as ImageView
        val drawable = image.drawable
        val width = image.width - image.paddingLeft - image.paddingRight
        val height = image.height - image.paddingTop - image.paddingBottom
        if (drawable == null || width <= 0 || height <= 0) {
            outline.setEmpty()
            return
        }
        val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: width
        val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: height
        val scale = min(width.toFloat() / intrinsicWidth, height.toFloat() / intrinsicHeight)
        val fittedWidth = (intrinsicWidth * scale).roundToInt()
        val fittedHeight = (intrinsicHeight * scale).roundToInt()
        val left = image.paddingLeft + (width - fittedWidth) / 2
        val top = image.paddingTop + (height - fittedHeight) / 2
        outline.setRoundRect(left, top, left + fittedWidth, top + fittedHeight, 8f * image.resources.displayMetrics.density)
    }
}
