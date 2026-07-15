package eu.kanade.tachiyomi.ui.manga.chapter

import yokai.util.koin.get
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractHeaderItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.manga.MangaDetailsAdapter
import yokai.domain.ui.UiPreferences

class ChapterItem(chapter: Chapter, val manga: Manga) :
    BaseChapterItem<ChapterHolder, AbstractHeaderItem<FlexibleViewHolder>>(chapter) {

    var isLocked = false
    var hasCachedTranslation = false
    var isTranslating = false

    override fun getLayoutRes(): Int {
        return R.layout.chapters_item
    }

    override fun isSelectable(): Boolean {
        return true
    }

    override fun isSwipeable(): Boolean {
        return !isLocked && get<UiPreferences>().enableChapterSwipeAction().get()
    }

    override fun createViewHolder(view: View, adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>): ChapterHolder {
        return ChapterHolder(view, adapter as MangaDetailsAdapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: ChapterHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.bind(this, manga)
    }

    /** Full snapshot of state consumed by [ChapterHolder]. Identity equality only compares ids. */
    internal fun bindingContentSignature(progress: Int = this.progress): Int {
        var result = javaClass.hashCode()
        fun include(value: Any?) {
            result = 31 * result + (value?.hashCode() ?: 0)
        }

        include(id)
        include(manga_id)
        include(url)
        include(name)
        include(scanlator)
        include(read)
        include(bookmark)
        include(last_page_read)
        include(pages_left)
        include(date_fetch)
        include(date_upload)
        include(source_order)
        include(chapter_number)
        include(status)
        include(progress)
        include(isLocked)
        include(hasCachedTranslation)
        include(isTranslating)
        include(manga.id)
        include(manga.source)
        include(manga.genre)
        include(manga.hide_title)
        include(manga.chapter_flags)
        return result
    }

    override fun unbindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>?,
        holder: ChapterHolder?,
        position: Int,
    ) {
        holder?.recycle()
        super.unbindViewHolder(adapter, holder, position)
        (adapter as MangaDetailsAdapter).controller.dismissPopup(position)
    }
}
