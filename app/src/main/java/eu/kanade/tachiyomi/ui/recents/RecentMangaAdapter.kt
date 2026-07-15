package eu.kanade.tachiyomi.ui.recents

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import eu.davidea.flexibleadapter.SelectableAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.ChapterHistory
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.changesIn
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import yokai.util.koin.injectLazy
import yokai.domain.recents.RecentsPreferences
import yokai.domain.ui.UiPreferences
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.*

class RecentMangaAdapter(val delegate: RecentsInterface) :
    BaseChapterAdapter<IFlexible<*>>(delegate) {

    private var submittedContentSignatures: List<Int> = emptyList()

    val preferences: PreferencesHelper by injectLazy()
    val uiPreferences: UiPreferences by injectLazy()
    val recentsPreferences: RecentsPreferences by injectLazy()

    var showDownloads = recentsPreferences.showRecentsDownloads().get()
    var showRemoveHistory = recentsPreferences.showRecentsRemHistory().get()
    var showTitleFirst = recentsPreferences.showTitleFirstInRecents().get()
    var showUpdatedTime = preferences.showUpdatedTime().get()
    var uniformCovers = uiPreferences.uniformGrid().get()
    var showOutline = uiPreferences.outlineOnCovers().get()
    var sortByFetched = preferences.sortFetchedTime().get()
    var lastUpdatedTime = preferences.libraryUpdateLastTimestamp().get()
    private var collapseGroupedUpdates = preferences.collapseGroupedUpdates().get()
    private var collapseGroupedHistory = preferences.collapseGroupedHistory().get()
    val collapseGrouped: Boolean
        get() = if (viewType.isHistory) {
            collapseGroupedHistory
        } else {
            collapseGroupedUpdates
        }

    val viewType: RecentsViewType
        get() = delegate.getViewType()

    val decimalFormat = DecimalFormat(
        "#.###",
        DecimalFormatSymbols()
            .apply { decimalSeparator = '.' },
    )
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    init {
        setDisplayHeadersAtStartUp(true)
    }

    /**
     * FlexibleAdapter's regular updateDataSet() always dispatches notifyDataSetChanged().
     * Recents can emit the same logical list repeatedly (download reconciliation,
     * lifecycle refreshes, preference fan-out), and each dispatch rebinds the expensive
     * visible rows. Skip only when the complete binding snapshot is unchanged.
     */
    fun updateDataSetIfChanged(items: List<RecentMangaItem>): Boolean {
        val nextSignatures = items.map(RecentMangaItem::bindingContentSignature)
        if (nextSignatures == submittedContentSignatures) return false
        submittedContentSignatures = nextSignatures
        updateDataSet(items)
        return true
    }

    fun setPreferenceFlows() {
        recentsPreferences.showRecentsDownloads().register { showDownloads = it }
        recentsPreferences.showRecentsRemHistory().register { showRemoveHistory = it }
        recentsPreferences.showTitleFirstInRecents().register { showTitleFirst = it }
        preferences.showUpdatedTime().register { showUpdatedTime = it }
        uiPreferences.uniformGrid().register { uniformCovers = it }
        preferences.collapseGroupedUpdates().register { collapseGroupedUpdates = it }
        preferences.collapseGroupedHistory().register { collapseGroupedHistory = it }
        preferences.sortFetchedTime().changesIn(delegate.scope()) { sortByFetched = it }
        uiPreferences.outlineOnCovers().register(false) {
            showOutline = it
            (0 until itemCount).forEach { i ->
                (recyclerView.findViewHolderForAdapterPosition(i) as? RecentMangaHolder)?.updateCards()
            }
        }
        preferences.libraryUpdateLastTimestamp().changesIn(delegate.scope()) {
            lastUpdatedTime = it
            if (viewType.isUpdates) {
                notifyItemChanged(0)
            }
        }
        // Grouping/hide prefs are owned by RecentsPresenter (it reads them in
        // runRecents and reissues the list). The presenter already observes
        // these via .changes() in onCreate, so the adapter doesn't need its
        // own register() call — re-emitting recents triggers updateDataSet().
    }

    fun getItemByChapterId(id: Long): RecentMangaItem? {
        return currentItems.find {
            val item = (it as? RecentMangaItem) ?: return@find false
            return@find id == item.chapter.id || id in item.mch.extraChapters.map { ch -> ch.id }
        } as? RecentMangaItem
    }

    /**
     * True when the History/Updates tabs allow multi-select. GroupedAll falls
     * back to the existing single-item dialogs.
     */
    val selectionEnabled: Boolean
        get() = viewType.isHistory || viewType.isUpdates

    /**
     * True when at least one item is currently selected — used to swap the
     * toolbar / suppress swipe / treat cover clicks as selection toggles.
     */
    val isInSelectionMode: Boolean
        get() = mode == SelectableAdapter.Mode.MULTI && selectedItemCount > 0

    /**
     * Collect every selected [RecentMangaItem] in adapter order. Sub-chapters
     * are never selectable, so this exposes only the top-level entries.
     */
    fun selectedRecentItems(): List<RecentMangaItem> {
        return getSelectedPositions().mapNotNull { getItem(it) as? RecentMangaItem }
    }

    private fun <T> Preference<T>.register(notify: Boolean = true, onChanged: (T) -> Unit) {
        changes()
            .drop(1)
            .onEach {
                onChanged(it)
                if (notify) {
                    notifyDataSetChanged()
                }
            }
            .launchIn(delegate.scope())
    }

    interface RecentsInterface : GroupedDownloadInterface {
        fun onCoverClick(position: Int)
        fun onRemoveHistoryClicked(position: Int)
        fun onSubChapterClicked(position: Int, chapter: Chapter, view: View)
        fun updateExpandedExtraChapters(position: Int, expanded: Boolean)
        fun areExtraChaptersExpanded(position: Int): Boolean
        fun markAsRead(position: Int)
        fun alwaysExpanded(): Boolean
        fun scope(): CoroutineScope
        fun getViewType(): RecentsViewType
        fun onItemLongClick(position: Int, chapter: ChapterHistory): Boolean

        /**
         * Cover tap fired while [isInSelectionMode] is true — the controller
         * routes it through the same toggle path as a regular row tap.
         */
        fun onItemSelectionToggled(position: Int)

        /**
         * Called from the source header overflow when the user picks "Hide all
         * from <source>". Adds [sourceId] to the per-tab hidden-sources pref and
         * refreshes the list.
         */
        fun onHideSourceClicked(sourceId: String)
    }

    override fun onItemSwiped(position: Int, direction: Int) {
        super.onItemSwiped(position, direction)
        // Suppress swipe-to-mark while the user is in multi-select; the
        // gesture is still hooked by the action mode toolbar.
        if (isInSelectionMode) return
        when (direction) {
            ItemTouchHelper.LEFT -> delegate.markAsRead(position)
            ItemTouchHelper.RIGHT -> delegate.markAsRead(position)
        }
    }

    enum class ShowRecentsDLs {
        None,
        OnlyUnread,
        OnlyDownloaded,
        UnreadOrDownloaded,
        All,
    }
}
