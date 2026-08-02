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
