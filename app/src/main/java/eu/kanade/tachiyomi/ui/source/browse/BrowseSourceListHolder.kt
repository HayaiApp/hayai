package eu.kanade.tachiyomi.ui.source.browse

import android.app.Activity
import android.view.View
import android.widget.ImageView
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
    private var boundContent: BindingContent? = null
    private var boundCover: CoverContent? = null

    private data class BindingContent(
        val title: String,
        val favorite: Boolean,
        val subtitle: String?,
    )

    private data class CoverContent(
        val mangaId: Long?,
        val sourceId: Long,
        val url: String?,
        val lastModified: Long,
        val inLibrary: Boolean,
    )

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
        val subtitleText = when {
            manga.isNovel() -> view.context.getString(MR.strings.novel)
            manga.isEhBasedManga() -> ehCategoryLabel(manga)
            else -> null
        }
        val content = BindingContent(manga.title, manga.favorite, subtitleText)
        if (content != boundContent) {
            boundContent = content
            binding.title.text = content.title
            binding.inLibraryBadge.badge.isVisible = content.favorite
            binding.subtitle.isVisible = !content.subtitle.isNullOrEmpty()
            binding.subtitle.text = content.subtitle
            binding.coverThumbnail.alpha = if (content.favorite) 0.34f else 1.0f
        }

        setImage(manga)
    }

    private fun ehCategoryLabel(manga: Manga): String? {
        val token = manga.genre?.substringBefore(',')?.trim()?.lowercase()
        val (_, label) = SourceTagsUtil.getEhCategoryDisplay(token) ?: return null
        return view.context.getString(label)
    }

    override fun setImage(manga: Manga) {
        val cover = CoverContent(
            mangaId = manga.id,
            sourceId = manga.source,
            url = manga.thumbnail_url,
            lastModified = manga.cover_last_modified,
            inLibrary = manga.favorite,
        )
        if (cover == boundCover) return
        boundCover = cover
        if ((view.context as? Activity)?.isDestroyed == true) return

        binding.coverThumbnail.dispose()
        binding.coverThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
        binding.coverThumbnail.setImageDrawable(null)
        if (!cover.url.isNullOrEmpty() && cover.mangaId != null) {
            binding.coverThumbnail.loadManga(manga.cover())
        }
    }

    override fun recycle() {
        binding.coverThumbnail.dispose()
        boundContent = null
        boundCover = null
    }
}
