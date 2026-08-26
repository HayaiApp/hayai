package eu.kanade.tachiyomi.ui.migration.manga.design

import android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
import android.view.View
import androidx.core.view.isVisible
import dev.ahmedmohamed.hayai.source.presentation.SourcePresentation
import eu.kanade.tachiyomi.databinding.MigrationSourceItemBinding
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.icon
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder

class MigrationSourceHolder(
    view: View,
    val adapter: MigrationSourceAdapter,
) : BaseFlexibleViewHolder(view, adapter) {
    private val binding = MigrationSourceItemBinding.bind(view)

    init {
        setDragHandleView(binding.reorder)
    }

    fun bind(
        source: CatalogueSource,
        sourceEnabled: Boolean,
    ) {
        binding.title.text = source.nameBasedOnEnabledLanguages(adapter.enabledLanguages, adapter.extensionManager)
        binding.sourceBadge.text = SourcePresentation.badgeText(itemView.context, source)
        binding.sourceBadge.isVisible = binding.sourceBadge.text != null
        // Update circle letter image.
        itemView.post {
            val icon = source.icon()
            if (icon != null) binding.sourceImage.setImageDrawable(icon)
        }

        if (sourceEnabled) {
            binding.title.alpha = 1.0f
            binding.sourceImage.alpha = 1.0f
            binding.sourceBadge.alpha = 1.0f
            binding.title.paintFlags = binding.title.paintFlags and STRIKE_THRU_TEXT_FLAG.inv()
        } else {
            binding.title.alpha = DISABLED_ALPHA
            binding.sourceImage.alpha = DISABLED_ALPHA
            binding.sourceBadge.alpha = DISABLED_ALPHA
            binding.title.paintFlags = binding.title.paintFlags or STRIKE_THRU_TEXT_FLAG
        }
    }

    /**
     * Called when an item is released.
     *
     * @param position The position of the released item.
     */
    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        adapter.updateItems()
    }

    companion object {
        private const val DISABLED_ALPHA = 0.3f
    }
}
