package eu.kanade.tachiyomi.ui.source.browse

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.widget.AutofitRecyclerView

class BrowseSourceItem(
    initialManga: Manga,
    private val catalogueAsList: Preference<Boolean>,
    private val catalogueListType: Preference<Int>,
    private val outlineOnCovers: Preference<Boolean>,
) :
    AbstractFlexibleItem<BrowseSourceHolder>() {

    val mangaId: Long = initialManga.id!!
    var manga: Manga = initialManga
        private set

    override fun getLayoutRes(): Int {
        return if (catalogueAsList.get()) R.layout.manga_list_item else R.layout.browse_source_grid_item
    }

    override fun createViewHolder(view: View, adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>): BrowseSourceHolder {
        val parent = adapter.recyclerView
        return if (parent is AutofitRecyclerView && !catalogueAsList.get()) {
            val listType = catalogueListType.get()
            BrowseSourceGridHolder(view, adapter, listType == LibraryItem.LAYOUT_COMPACT_GRID, outlineOnCovers.get())
        } else {
            BrowseSourceListHolder(view, adapter, outlineOnCovers.get())
        }
    }

    fun updateManga(
        holder: BrowseSourceHolder,
        manga: Manga,
    ) {
        if (manga.id != mangaId) return

        this.manga = manga
        holder.onSetValues(manga)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: BrowseSourceHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.onSetValues(manga)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is BrowseSourceItem) {
            return this.mangaId == other.mangaId
        }
        return false
    }

    override fun hashCode(): Int {
        return mangaId.hashCode()
    }
}
