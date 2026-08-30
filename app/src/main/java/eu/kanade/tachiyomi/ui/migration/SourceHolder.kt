package eu.kanade.tachiyomi.ui.migration

import android.view.View
import androidx.core.view.isVisible
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import dev.ahmedmohamed.hayai.source.presentation.SourcePresentation
import dev.ahmedmohamed.hayai.source.presentation.bindSourceArtwork
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.MigrationCardItemBinding
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.lang.withColor
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.getResourceColor
import java.util.Locale

class SourceHolder(
    view: View,
    val adapter: SourceAdapter,
) : BaseFlexibleViewHolder(view, adapter) {
    private val binding = MigrationCardItemBinding.bind(view)

    init {
        binding.migrationAll.setOnClickListener {
            adapter.allClickListener.onAllClick(flexibleAdapterPosition)
        }
    }

    fun bind(item: SourceItem) {
        val source = item.source

        // Set source name
        val sourceName = source.name.replaceFirstChar { it.titlecase(Locale.getDefault()) } + " (${item.numberOfItems})"
        binding.title.text = sourceName
        binding.sourceBadge.text = SourcePresentation.badgeText(itemView.context, source)
        binding.sourceBadge.isVisible = binding.sourceBadge.text != null
        binding.lang.text =
            when {
                item.isUninstalled ->
                    itemView.context
                        .getString(R.string.source_not_installed)
                        .withColor(itemView.context.getResourceColor(R.attr.colorError))
                item.isObsolete ->
                    buildSpannedString {
                        append(LocaleHelper.getSourceDisplayName(source.lang, itemView.context))
                        append("  ")
                        color(itemView.context.getResourceColor(R.attr.colorError)) {
                            append(itemView.context.getString(R.string.orphaned).uppercase())
                        }
                    }
                else -> LocaleHelper.getSourceDisplayName(source.lang, itemView.context)
            }

        // Set circle letter image.
        itemView.post {
            binding.sourceImage.bindSourceArtwork(source)
        }
    }
}
