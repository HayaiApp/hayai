package eu.kanade.tachiyomi.ui.source.browse

import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import coil.Coil
import coil.dispose
import coil.request.ImageRequest
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.image.coil.CoverViewTarget
import eu.kanade.tachiyomi.data.image.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.databinding.MangaListItemBinding
import eu.kanade.tachiyomi.util.view.makeContainerShape
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.view.setCards
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import dev.ahmedmohamed.hayai.source.metadata.SourceMetadataUi
import dev.ahmedmohamed.hayai.source.presentation.SourceBrowsePresentation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Class used to hold the displayed data of a manga in the catalogue, like the cover or the title.
 * All the elements from the layout file "item_catalogue_list" are available in this class.
 *
 * @param view the inflated view for this holder.
 * @param adapter the adapter handling this holder.
 * @constructor creates a new catalogue holder.
 */
class BrowseSourceListHolder(
    private val view: View,
    adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    showOutline: Boolean,
) : BrowseSourceHolder(view, adapter) {
    private val binding = MangaListItemBinding.bind(view)
    var sourcePresentation: SourceBrowsePresentation? = null

    init {
        setCards(showOutline, binding.card, binding.unreadDownloadBadge.badgeView)
    }

    /**
     * Method called from [CatalogueAdapter.onBindViewHolder]. It updates the data for this
     * holder with the given manga.
     *
     * @param manga the manga to bind.
     */
    override fun onSetValues(manga: Manga) {
        binding.title.text = manga.title
        binding.inLibraryBadge.badge.isVisible = manga.favorite
        binding.duplicateInLibraryBadge.duplicateBadge.isVisible = isDuplicateInLibrary
        bindSourcePresentation()

        setImage(manga)
    }

    /** Merges consecutive rows into one rounded card, the way [ChapterHolder] does for chapters. */
    fun setCorners(
        top: Boolean,
        bottom: Boolean,
    ) {
        binding.listCard.shapeAppearanceModel =
            binding.listCard.makeContainerShape(top, bottom, clipContentTo = binding.constraintLayout)
    }

    private fun bindSourcePresentation() {
        val presentation = sourcePresentation
        binding.hayaiBrowseMetadata.isVisible = presentation != null
        binding.subtitle.isVisible = presentation?.uploader != null
        binding.subtitle.text = presentation?.uploader
        binding.constraintLayout.minimumHeight = if (presentation == null) dp(52) else dp(148)
        binding.card.updateLayoutParams<ViewGroup.LayoutParams> {
            width = if (presentation == null) dp(40) else dp(92)
            height = if (presentation == null) dp(40) else dp(132)
        }
        binding.coverThumbnail.updateLayoutParams<ViewGroup.LayoutParams> {
            width = if (presentation == null) dp(40) else ViewGroup.LayoutParams.MATCH_PARENT
            height = if (presentation == null) dp(40) else ViewGroup.LayoutParams.MATCH_PARENT
        }
        if (presentation == null) return

        binding.hayaiTypeBadge.isVisible = presentation.type != null
        presentation.type?.let { type ->
            val typeColor = SourceMetadataUi.typeColor(type.value)
            binding.hayaiTypeBadge.text = SourceMetadataUi.typeLabel(view.context, type.value)
            binding.hayaiTypeBadge.setTextColor(SourceMetadataUi.contrastingTextColor(typeColor))
            binding.hayaiTypeBadge.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(typeColor)
            }
        }
        binding.hayaiRating.rating = presentation.rating?.toFloat() ?: 0f
        binding.hayaiRating.isVisible = presentation.rating != null
        val pages = presentation.pageCount?.let {
            view.resources.getQuantityString(R.plurals.hayai_source_browse_pages, it, it)
        }
        binding.hayaiLanguagePages.text = listOfNotNull(presentation.language?.uppercase(Locale.getDefault()), pages).joinToString(" · ")
        binding.hayaiLanguagePages.isVisible = binding.hayaiLanguagePages.text.isNotBlank()
        binding.hayaiPosted.text = presentation.postedAtMillis?.let {
            BROWSE_DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
        }
        binding.hayaiPosted.isVisible = presentation.postedAtMillis != null
    }

    private fun dp(value: Int): Int = (value * view.resources.displayMetrics.density).toInt()

    private companion object {
        val BROWSE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    override fun setImage(manga: Manga) {
        // Update the cover.
        if (manga.thumbnail_url == null) {
            binding.coverThumbnail.dispose()
        } else {
            manga.id ?: return
            val request =
                ImageRequest
                    .Builder(view.context)
                    .data(manga)
                    .target(CoverViewTarget(binding.coverThumbnail))
                    .setParameter(MangaCoverFetcher.useCustomCover, false)
                    .build()
            Coil.imageLoader(view.context).enqueue(request)

            binding.coverThumbnail.alpha = if (manga.favorite || isDuplicateInLibrary) 0.34f else 1.0f
        }
    }
}
