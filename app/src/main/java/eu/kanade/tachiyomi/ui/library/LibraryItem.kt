package eu.kanade.tachiyomi.ui.library

import android.content.Context
import androidx.annotation.CallSuper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractSectionableItem
import eu.davidea.flexibleadapter.items.IFilterable
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.database.models.dominantCoverColors
import eu.kanade.tachiyomi.source.SourceManager
import yokai.util.koin.injectLazy
import yokai.domain.ui.UiPreferences

abstract class LibraryItem(
    header: LibraryHeaderItem,
    internal var context: Context?,
) : AbstractSectionableItem<LibraryHolder, LibraryHeaderItem>(header), IFilterable<String> {

    var filter = ""

    /**
     * Stable, non-null reference to the section header captured at construction. App code (presenter,
     * holders, fast-scroll, sorting) reads through this property; only FlexibleAdapter's section
     * machinery talks to [getHeader]. That separation lets the adapter return null transiently to
     * suppress auto-sectioning (see [getHeader] / [suppressSectionHeader]) without forcing the rest
     * of the codebase to deal with nullability.
     */
    val sectionHeader: LibraryHeaderItem = header

    /**
     * FlexibleAdapter's prepareItemsForUpdate (FlexibleAdapter.java:5658) unconditionally inserts a
     * header into mItems for every item whose getHeader() is non-null — regardless of headersShown
     * or setDisplayHeadersAtStartUp. The setDisplayHeadersAtStartUp(false) override is itself a no-op
     * once headersShown is true (FlexibleAdapter.java:1479-1484), so there is no public API to
     * suppress the section auto-insertion. The only path that works is hiding the header at the
     * source: returning null from getHeader() during prepareItemsForUpdate.
     *
     * The flag is a ThreadLocal so it only affects the thread doing setItems on a paged-mode adapter
     * (always the UI thread). Concurrent flow work on Dispatchers.IO — e.g.
     * LibraryPresenter.getLibraryItems' groupBy lambdas — keeps reading the real, non-null header.
     */
    override fun getHeader(): LibraryHeaderItem? {
        return if (suppressSectionHeader.get() == true) null else super.getHeader()
    }

    internal val sourceManager: SourceManager by injectLazy()
    private val uiPreferences: UiPreferences by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    internal val uniformSize: Boolean
        get() = uiPreferences.uniformGrid().get()

    internal val libraryLayout: Int
        get() = preferences.libraryLayout().get()

    val hideReadingButton: Boolean
        get() = preferences.hideStartReadingButton().get()

    /**
     * Snapshot of model state consumed by Library holders and headers. Item equality is
     * intentionally identity-oriented (manga/category), so it cannot safely gate a full
     * adapter submission when unread counts, cover metadata, titles or category controls move.
     */
    internal fun bindingContentSignature(): Int {
        var result = javaClass.hashCode()
        fun include(value: Any?) {
            result = 31 * result + (value?.hashCode() ?: 0)
        }

        fun includeHeader(header: LibraryHeaderItem) {
            val category = header.category
            include(header.catId)
            include(category.id)
            include(category.name)
            include(category.order)
            include(category.flags)
            include(category.mangaOrder)
            include(category.mangaSort)
            include(category.isAlone)
            include(category.isHidden)
            include(category.isDynamic)
            include(category.sourceId)
            include(category.langId)
            include(category.isSystem)
        }

        includeHeader(sectionHeader)
        include(filter)
        when (this) {
            is LibraryMangaItem -> {
                val libraryManga = manga
                val manga = libraryManga.manga
                include(manga.id)
                include(manga.source)
                include(manga.title)
                include(manga.author)
                include(manga.artist)
                include(manga.genre)
                include(manga.status)
                include(manga.thumbnail_url)
                include(manga.cover_last_modified)
                include(manga.dominantCoverColors)
                include(libraryManga.unread)
                include(libraryManga.read)
                include(libraryManga.category)
                include(libraryManga.bookmarkCount)
                include(libraryManga.totalChapters)
                include(libraryManga.latestUpdate)
                include(libraryManga.lastRead)
                include(libraryManga.lastFetch)
                include(downloadCount)
                include(unreadType)
                include(sourceLanguage)
            }
            is LibraryPlaceholderItem -> {
                include(category)
                when (val type = type) {
                    is LibraryPlaceholderItem.Type.Blank -> include(type.mangaCount)
                    is LibraryPlaceholderItem.Type.Hidden -> {
                        include(type.title)
                        include(type.hiddenItems.map { it.manga.manga.id })
                    }
                }
            }
        }
        return result
    }

    @CallSuper
    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: LibraryHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.onSetValues(this)
        (holder as? LibraryGridHolder)?.setSelected(adapter.isSelected(position))
        (holder.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan = this is LibraryPlaceholderItem
    }

    override fun unbindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>?,
        holder: LibraryHolder?,
        position: Int,
    ) {
        holder?.recycle()
        super.unbindViewHolder(adapter, holder, position)
    }

    companion object {
        const val LAYOUT_LIST = 0
        const val LAYOUT_COMPACT_GRID = 1
        const val LAYOUT_COMFORTABLE_GRID = 2
        const val LAYOUT_COVER_ONLY_GRID = 3

        const val DISPLAY_MODE_CONTINUOUS = 0
        const val DISPLAY_MODE_TABBED = 1

        /** ThreadLocal so the suppress is scoped to the thread doing setItems on a paged-mode adapter. */
        internal val suppressSectionHeader: ThreadLocal<Boolean> = ThreadLocal()
    }
}
