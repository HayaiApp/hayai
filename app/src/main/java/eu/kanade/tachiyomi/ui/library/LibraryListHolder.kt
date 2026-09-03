package eu.kanade.tachiyomi.ui.library

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import coil.dispose
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.image.coil.loadManga
import eu.kanade.tachiyomi.databinding.MangaListItemBinding
import eu.kanade.tachiyomi.util.lang.highlightText
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.view.makeContainerShape
import eu.kanade.tachiyomi.util.view.setCards
import dev.ahmedmohamed.hayai.novel.integration.NovelTitlePresentation
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Class used to hold the displayed data of a manga in the library, like the cover or the binding.title.
 * All the elements from the layout file "item_library_list" are available in this class.
 *
 * @param view the inflated view for this holder.
 * @param adapter the adapter handling this holder.
 * @constructor creates a new library holder.
 */

class LibraryListHolder(
    private val view: View,
    adapter: LibraryCategoryAdapter,
) : LibraryHolder(view, adapter) {
    private val binding = MangaListItemBinding.bind(view)
    private val novelTitles = Injekt.get<NovelTitlePresentation>()

    private var transitionMangaId: Long? = null

    init {
        binding.unreadDownloadBadge.badgeView.libraryColors = adapter.colors
        binding.playLayout.setOnClickListener { playButtonClicked() }
        binding.playLayout.setOnLongClickListener { itemView.performLongClick() }
    }

    /**
     * Method called from [LibraryCategoryAdapter.onBindViewHolder]. It updates the data for this
     * holder with the given manga.
     *
     * @param item the manga item to bind.
     */
    override fun onSetValues(item: LibraryItem) {
        setCards(adapter.showOutline, binding.card, binding.unreadDownloadBadge.root)
        applyListCardStyle(LibraryItem.libraryLayout == LibraryItem.LAYOUT_LIST)
        binding.title.isVisible = true
        binding.constraintLayout.minHeight = 56.dpToPx
        if (item.manga.isBlank()) {
            binding.constraintLayout.minHeight = 0
            binding.constraintLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                height = ViewGroup.MarginLayoutParams.WRAP_CONTENT
            }
            if (item.manga.status == -1) {
                binding.title.text = null
                binding.title.isVisible = false
            } else {
                binding.title.text =
                    itemView.context.getString(
                        if (adapter.hasActiveFilters) {
                            R.string.no_matches_for_filters_short
                        } else {
                            R.string.category_is_empty
                        },
                    )
            }
            binding.title.textAlignment = View.TEXT_ALIGNMENT_CENTER
            binding.card.isVisible = false
            binding.unreadDownloadBadge.badgeView.isVisible = false
            binding.padding.isVisible = false
            binding.subtitle.isVisible = false
            binding.playLayout.isVisible = false
            return
        }
        val configuredMaxLines = novelTitles.maxLines(item.manga, authorMatched = false)
        binding.constraintLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            height = if (configuredMaxLines > 2) ViewGroup.LayoutParams.WRAP_CONTENT else 52.dpToPx
        }
        binding.padding.isVisible = true
        binding.card.isVisible = true
        binding.title.textAlignment = View.TEXT_ALIGNMENT_TEXT_START

        // Only the view that started a transition keeps a name, dropped once it shows another manga
        if (transitionMangaId != null && transitionMangaId != item.manga.id) {
            binding.playButton.transitionName = null
            transitionMangaId = null
        }

        // Update the binding.title of the manga.
        binding.title.text = item.manga.title.highlightText(item.filter, color)
        setUnreadBadge(binding.unreadDownloadBadge.badgeView, item)
        binding.playLayout.isVisible = item.manga.unread > 0 && !LibraryItem.hideReadingButton

        val authorArtist =
            if (item.manga.author == item.manga.artist || item.manga.artist.isNullOrBlank()) {
                item.manga.author?.trim() ?: ""
            } else {
                listOfNotNull(
                    item.manga.author
                        ?.trim()
                        ?.takeIf { it.isNotBlank() },
                    item.manga.artist
                        ?.trim()
                        ?.takeIf { it.isNotBlank() },
                ).joinToString(", ")
            }

        binding.subtitle.text = authorArtist.highlightText(item.filter, color)
        binding.title.maxLines = configuredMaxLines
        binding.title.post {
            val hasAuthorInFilter =
                item.filter.isNotBlank() && authorArtist.contains(item.filter, true)
            binding.subtitle.isVisible = binding.title.lineCount <= 1 || hasAuthorInFilter
            binding.title.maxLines = novelTitles.maxLines(item.manga, hasAuthorInFilter)
        }

        // Update the cover.
        binding.coverThumbnail.dispose()
        binding.coverThumbnail.loadManga(item.manga)
    }

    /**
     * The blank placeholder row reuses this layout even while the library is shown in grid mode,
     * where it should stay flat like before rather than pick up the list's card styling.
     */
    private fun applyListCardStyle(isListMode: Boolean) {
        binding.listCard.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            val margin = if (isListMode) 10.dpToPx else 0
            marginStart = margin
            marginEnd = margin
        }
        if (isListMode) {
            binding.listCard.setCardBackgroundColor(itemView.context.getResourceColor(R.attr.colorSurfaceContainerLowest))
        } else {
            binding.listCard.setCardBackgroundColor(Color.TRANSPARENT)
            binding.listCard.cardElevation = 0f
            binding.listCard.strokeWidth = 0
        }
    }

    /** Merges consecutive rows in the same category into one rounded card, like ChapterHolder does for chapters. */
    fun setCorners(
        top: Boolean,
        bottom: Boolean,
    ) {
        if (LibraryItem.libraryLayout != LibraryItem.LAYOUT_LIST) return
        binding.listCard.shapeAppearanceModel =
            binding.listCard.makeContainerShape(top, bottom, clipContentTo = binding.constraintLayout)
    }

    private fun playButtonClicked() {
        val manga = (adapter.getItem(flexibleAdapterPosition) as? LibraryItem)?.manga
        transitionMangaId = manga?.id
        binding.playButton.transitionName = "library chapter ${manga?.id ?: bindingAdapterPosition} transition"
        adapter.libraryListener?.startReading(flexibleAdapterPosition, binding.playButton)
    }

    override fun onActionStateChanged(
        position: Int,
        actionState: Int,
    ) {
        super.onActionStateChanged(position, actionState)
        if (actionState == 2) {
            binding.card.isDragged = true
        }
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        binding.card.isDragged = false
    }
}
