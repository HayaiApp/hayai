package eu.kanade.tachiyomi.ui.manga.related

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.setMargins
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.util.system.dpToPx

class RelatedMangaCardItem(val manga: Manga) : AbstractFlexibleItem<RelatedMangaCardHolder>() {

    override fun getLayoutRes(): Int {
        return R.layout.related_manga_card_item
    }

    override fun createViewHolder(view: View, adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>): RelatedMangaCardHolder {
        val composeView = view as ComposeView
        val relatedAdapter = adapter as RelatedMangaCardAdapter
        // The compact carousel is a horizontal list (needs a fixed item width), while the
        // standalone Suggestions screen is a grid (each item should fill its column). Margins
        // match BrowseSourceItem's own grid ComposeView for the same spacing app-wide.
        composeView.layoutParams = if (relatedAdapter.compact) {
            ViewGroup.MarginLayoutParams(112.dpToPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
            ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }.apply { setMargins(4.dpToPx) }
        return RelatedMangaCardHolder(composeView, relatedAdapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: RelatedMangaCardHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.bind(manga)
    }

    override fun equals(other: Any?): Boolean {
        if (other is RelatedMangaCardItem) {
            return manga.id == other.manga.id
        }
        return false
    }

    override fun hashCode(): Int {
        return manga.id?.toInt() ?: 0
    }
}
