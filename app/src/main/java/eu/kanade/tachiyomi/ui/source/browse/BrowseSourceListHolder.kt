package eu.kanade.tachiyomi.ui.source.browse

import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.database.models.isNovel
import eu.kanade.tachiyomi.databinding.MangaListItemBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.util.view.setCards
import exh.source.isEhBasedManga
import exh.util.SourceTagsUtil
import yokai.domain.manga.models.cover
import yokai.i18n.MR
import yokai.util.coil.loadManga
import yokai.util.lang.getString

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
) :
    BrowseSourceHolder(view, adapter) {

    private val binding = MangaListItemBinding.bind(view)

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

        val subtitleText = when {
            manga.isNovel() -> view.context.getString(MR.strings.novel)
            manga.isEhBasedManga() -> ehCategoryLabel(manga)
            else -> null
        }
        binding.subtitle.isVisible = !subtitleText.isNullOrEmpty()
        binding.subtitle.text = subtitleText

        setImage(manga)
    }

    private fun ehCategoryLabel(manga: Manga): String? {
        val token = manga.genre?.substringBefore(',')?.trim()?.lowercase()
        val (_, label) = SourceTagsUtil.getEhCategoryDisplay(token) ?: return null
        return view.context.getString(label)
    }

    override fun setImage(manga: Manga) {
        // Update the cover.
        if (manga.thumbnail_url == null) {
            binding.coverThumbnail.dispose()
        } else {
            manga.id ?: return
            binding.coverThumbnail.loadManga(manga.cover())
            binding.coverThumbnail.alpha = if (manga.favorite) 0.34f else 1.0f
        }
    }
}
