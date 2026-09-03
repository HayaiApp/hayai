package eu.kanade.tachiyomi.ui.library

import android.app.Activity
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.core.view.updateLayoutParams
import coil.dispose
import coil.size.Precision
import coil.size.Scale
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.image.coil.loadManga
import eu.kanade.tachiyomi.databinding.MangaGridItemBinding
import eu.kanade.tachiyomi.util.lang.highlightText
import eu.kanade.tachiyomi.util.manga.MangaCoverMetadata
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.view.backgroundColor
import eu.kanade.tachiyomi.util.view.setCards
import eu.kanade.tachiyomi.widget.AutofitRecyclerView
import dev.ahmedmohamed.hayai.novel.integration.NovelTitlePresentation
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Class used to hold the displayed data of a manga in the library, like the cover or the title.
 * All the elements from the layout file "item_catalogue_grid" are available in this class.
 *
 * @param view the inflated view for this holder.
 * @param adapter the adapter handling this holder.
 * @constructor creates a new library holder.
 */
class LibraryGridHolder(
    private val view: View,
    adapter: LibraryCategoryAdapter,
    private val libraryLayout: Int,
    val fixedSize: Boolean,
    isStaggered: Boolean,
) : LibraryHolder(view, adapter) {
    private val binding = MangaGridItemBinding.bind(view)

    private val compact = libraryLayout == LibraryItem.LAYOUT_COMPACT_GRID
    private val coverOnly = libraryLayout == LibraryItem.LAYOUT_COVER_ONLY_GRID

    /** Only the comfortable grid shows the title/subtitle below the cover. */
    private val showsTextLayout = libraryLayout == LibraryItem.LAYOUT_COMFORTABLE_GRID

    private var lastOutline: Boolean? = null
    private var hasCoverSize = false
    private var lastCoverRatio: Float? = null
    private var lastCoverWidth = 0

    private var authorArtist = ""
    private var filter = ""
    private var transitionMangaId: Long? = null
    private val novelTitles = Injekt.get<NovelTitlePresentation>()
    private var boundManga: Manga? = null

    /**
     * The title has to be laid out before its line count is known, so the subtitle can only be
     * settled afterwards. Reusing one runnable (instead of posting a new lambda per bind) keeps a
     * pending pass from applying values of the item this holder used to show.
     */
    private val updateSubtitle =
        Runnable {
            val hasAuthorInFilter = filter.isNotBlank() && authorArtist.contains(filter, true)
            val showSubtitle =
                (binding.title.lineCount <= 1 || hasAuthorInFilter) && authorArtist.isNotBlank()
            if (binding.subtitle.isVisible != showSubtitle) {
                binding.subtitle.isVisible = showSubtitle
            }
            val maxLines = boundManga?.let { novelTitles.maxLines(it, hasAuthorInFilter) } ?: 2
            if (binding.title.maxLines != maxLines) {
                binding.title.maxLines = maxLines
            }
        }

    init {
        binding.unreadDownloadBadge.badgeView.libraryColors = adapter.colors
        binding.behindTitle.isVisible = coverOnly
        if (libraryLayout >= LibraryItem.LAYOUT_COMFORTABLE_GRID) {
            binding.textLayout.isVisible = showsTextLayout
            binding.card.setCardForegroundColor(
                ContextCompat.getColorStateList(
                    view.context,
                    R.color.library_comfortable_grid_foreground,
                ),
            )
        }
        if (fixedSize) {
            binding.constraintLayout.layoutParams =
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            binding.coverThumbnail.maxHeight = Int.MAX_VALUE
            binding.coverThumbnail.minimumHeight = 0
            binding.constraintLayout.minHeight = 0
            binding.coverThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
            binding.coverThumbnail.adjustViewBounds = false
            binding.coverThumbnail.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                dimensionRatio = "15:22"
            }
        }
        if (!showsTextLayout) {
            binding.card.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = (if (isStaggered) 2 else 6).dpToPx
            }
        }
        binding.setBGAndFG(libraryLayout)

        binding.playLayout.setOnClickListener { playButtonClicked() }
        binding.playLayout.setOnLongClickListener { itemView.performLongClick() }
        if (compact) {
            binding.textLayout.isVisible = false
        } else {
            binding.compactTitle.isVisible = false
            binding.gradient.isVisible = false
            listOf(binding.playLayout, binding.playButton).forEach {
                it.updateLayoutParams<FrameLayout.LayoutParams> {
                    gravity = Gravity.BOTTOM or Gravity.END
                }
            }
        }
    }

    /**
     * Method called from [LibraryCategoryAdapter.onBindViewHolder]. It updates the data for this
     * holder with the given manga.
     *
     * @param item the manga item to bind.
     */
    override fun onSetValues(item: LibraryItem) {
        boundManga = item.manga
        applyOutline()
        // Only the view that started a transition keeps a name, dropped once it shows another manga
        if (transitionMangaId != null && transitionMangaId != item.manga.id) {
            binding.playButton.transitionName = null
            transitionMangaId = null
        }
        binding.constraintLayout.isVisible = !item.manga.isBlank()
        filter = item.filter

        // Each layout shows the title in a different view, no need to fill in the hidden ones
        when {
            compact -> binding.compactTitle.text = item.manga.title.highlightText(filter, color)
            coverOnly -> binding.behindTitle.text = item.manga.title
            else -> binding.title.text = item.manga.title.highlightText(filter, color)
        }

        val mangaColor = item.manga.dominantCoverColors
        val coverBackground = mangaColor?.first ?: adapter.colors.background
        if (binding.coverConstraint.backgroundColor != coverBackground) {
            binding.coverConstraint.backgroundColor = coverBackground
        }
        if (coverOnly) {
            binding.behindTitle.setTextColor(mangaColor?.second ?: adapter.colors.onBackground)
        }

        if (showsTextLayout) {
            authorArtist =
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
            binding.subtitle.text = authorArtist.highlightText(filter, color)

            // Measure the title with the room it normally gets, a previous bind may have capped it
            if (binding.title.maxLines != 2) {
                binding.title.maxLines = 2
            }
            binding.title.removeCallbacks(updateSubtitle)
            binding.title.post(updateSubtitle)
        }

        setUnreadBadge(binding.unreadDownloadBadge.badgeView, item)
        setReadingButton(item)

        // Update the cover.
        binding.coverThumbnail.dispose()
//        binding.coverThumbnail.setImageDrawable(null)
        setCover(item.manga)
    }

    private fun applyOutline() {
        val showOutline = adapter.showOutline
        if (lastOutline == showOutline) return
        lastOutline = showOutline
        setCards(showOutline, binding.card, binding.unreadDownloadBadge.root)
    }

    private fun setReadingButton(item: LibraryItem) {
        binding.playLayout.isVisible = item.manga.unread > 0 && !LibraryItem.hideReadingButton
    }

    override fun toggleActivation() {
        super.toggleActivation()
        setSelected(adapter.isSelected(flexibleAdapterPosition))
    }

    fun setSelected(isSelected: Boolean) {
        with(binding) {
            val strokeWidth =
                when {
                    isSelected -> 3.dpToPx
                    adapter.showOutline -> 1.dpToPx
                    else -> 0
                }
            if (card.strokeWidth != strokeWidth) {
                card.strokeWidth = strokeWidth
            }
            card.isSelected = isSelected
            unreadDownloadBadge.root.isSelected = isSelected
            title.isSelected = isSelected
            subtitle.isSelected = isSelected
        }
    }

    private fun setCover(manga: Manga) {
        if ((adapter.recyclerView.context as? Activity)?.isDestroyed == true) return
        binding.coverThumbnail.loadManga(manga) {
            val hasRatio = binding.coverThumbnail.layoutParams.height != ViewGroup.LayoutParams.WRAP_CONTENT
            if (!fixedSize && !hasRatio) {
                precision(Precision.INEXACT)
                scale(Scale.FIT)
            }
            listener(
                onSuccess = { _, _ ->
                    if (!fixedSize && !hasRatio && MangaCoverMetadata.getRatio(manga) != null) {
                        setFreeformCoverRatio(manga)
                    }
                },
            )
        }
    }

    fun setFreeformCoverRatio(
        manga: Manga,
        parent: AutofitRecyclerView? = null,
    ) {
        // Every one of these sizes triggers a layout pass, skip when the view already has them
        val ratio = MangaCoverMetadata.getRatio(manga)
        val itemWidth = parent?.itemWidth ?: binding.root.width
        if (hasCoverSize && ratio == lastCoverRatio && itemWidth == lastCoverWidth) return
        hasCoverSize = true
        lastCoverRatio = ratio
        lastCoverWidth = itemWidth
        binding.setFreeformCoverRatio(manga, parent)
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
            binding.unreadDownloadBadge.badgeView.isDragged = true
        }
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        binding.card.isDragged = false
        binding.unreadDownloadBadge.badgeView.isDragged = false
    }
}

fun MangaGridItemBinding.setFreeformCoverRatio(
    manga: Manga?,
    parent: AutofitRecyclerView? = null,
) {
    val ratio = manga?.let { MangaCoverMetadata.getRatio(it) }
    val itemWidth = parent?.itemWidth ?: root.width
    if (ratio != null) {
        coverThumbnail.adjustViewBounds = false
        coverThumbnail.maxHeight = (itemWidth / 3f * 10f).toInt()
        coverThumbnail.minimumHeight = 56.dpToPx
        constraintLayout.minHeight = 56.dpToPx
    } else {
        val coverHeight = (itemWidth / 3f * 4f).toInt()
        constraintLayout.minHeight = coverHeight / 2
        coverThumbnail.minimumHeight =
            (itemWidth / 3f * 3.6f).toInt()
        coverThumbnail.maxHeight = (itemWidth / 3f * 6f).toInt()
        coverThumbnail.adjustViewBounds = true
    }
    coverThumbnail.updateLayoutParams<ConstraintLayout.LayoutParams> {
        if (ratio != null) {
            height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            matchConstraintMaxHeight = coverThumbnail.maxHeight
            matchConstraintMinHeight = coverThumbnail.minimumHeight
            dimensionRatio = "W,1:$ratio"
        } else {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            dimensionRatio = null
        }
    }
}

fun MangaGridItemBinding.setBGAndFG(libraryLayout: Int) {
    val bottom =
        if (libraryLayout == LibraryItem.LAYOUT_COMFORTABLE_GRID) {
            2.dpToPx
        } else {
            card.marginBottom - 2.dpToPx
        }
    val others =
        if (libraryLayout == LibraryItem.LAYOUT_COMPACT_GRID) {
            4.dpToPx
        } else {
            5.dpToPx
        }
    (constraintLayout.background as? RippleDrawable)?.apply {
        for (i in 0 until numberOfLayers) {
            setLayerInset(i, others, others, others, bottom)
        }
    }
    (constraintLayout.foreground as? RippleDrawable)?.apply {
        if (libraryLayout == LibraryItem.LAYOUT_COMFORTABLE_GRID) {
            setLayerSize(1, 0, 0)
        }
        for (i in 0 until numberOfLayers) {
            setLayerInset(i, others, others, others, bottom)
        }
    }
}
