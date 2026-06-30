package eu.kanade.tachiyomi.ui.migration

import android.view.View
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import coil3.load
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR
import eu.kanade.tachiyomi.R
import yokai.i18n.MR
import yokai.util.lang.getString
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.databinding.MigrationCardItemBinding
import eu.kanade.tachiyomi.source.icon
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.lang.withColor
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.getResourceColor
// NOVEL -->
import hayai.novel.source.NovelSource
// NOVEL <--
import java.util.Locale

class SourceHolder(view: View, val adapter: SourceAdapter) :
    BaseFlexibleViewHolder(view, adapter) {

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
        binding.lang.text = when {
            item.isUninstalled -> itemView.context.getString(MR.strings.source_not_installed)
                .withColor(itemView.context.getResourceColor(appcompatR.attr.colorError))
            item.isObsolete -> buildSpannedString {
                append(LocaleHelper.getSourceDisplayName(source.lang, itemView.context))
                append("  ")
                color(itemView.context.getResourceColor(appcompatR.attr.colorError)) {
                    append(itemView.context.getString(MR.strings.obsolete).uppercase())
                }
            }
            else -> LocaleHelper.getSourceDisplayName(source.lang, itemView.context)
        }

        // Set circle letter image.
        itemView.post {
            val icon = source.icon()
            when {
                icon != null -> binding.sourceImage.setImageDrawable(icon)
                // NOVEL -->
                source is NovelSource && !source.iconUrl.isNullOrBlank() -> {
                    binding.sourceImage.load(source.iconUrl)
                }
                source is NovelSource -> binding.sourceImage.setImageResource(R.drawable.ic_book_24dp)
                // NOVEL <--
            }
        }
    }
}
