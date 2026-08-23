package dev.ahmedmohamed.hayai.source.presentation

import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.view.isVisible
import dev.ahmedmohamed.hayai.source.metadata.SourceMetadataUi
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.getResourceColor
import java.util.Locale

object SourceBrowseBadgeUi {
    fun bindType(view: TextView, type: SourceContentType?) {
        view.isVisible = type != null
        if (type == null) return
        val color = SourceMetadataUi.typeColor(type.value)
        view.text = SourceMetadataUi.typeLabel(view.context, type.value)
        view.setTextColor(SourceMetadataUi.contrastingTextColor(color))
        view.background = badgeBackground(view, color)
    }

    fun bindLanguage(view: TextView, language: String?) {
        view.isVisible = !language.isNullOrBlank()
        if (language.isNullOrBlank()) return
        view.text = language.uppercase(Locale.getDefault())
        val color = view.context.getResourceColor(R.attr.colorSecondaryContainer)
        view.setTextColor(view.context.getResourceColor(R.attr.colorOnSecondaryContainer))
        view.background = badgeBackground(view, color)
    }

    private fun badgeBackground(view: TextView, color: Int) = GradientDrawable().apply {
        cornerRadius = 6 * view.resources.displayMetrics.density
        setColor(color)
    }
}
