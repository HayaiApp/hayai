package eu.kanade.tachiyomi.ui.library

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.lang.removeArticles
import eu.kanade.tachiyomi.util.system.isLTR
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.timeSpanFromNow
import eu.kanade.tachiyomi.util.system.withDefContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.util.*
import yokai.util.koin.injectLazy
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.history.interactor.GetHistory
import yokai.domain.ui.UiPreferences
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Adapter storing a list of manga in a certain category.
 *
 * @param view the fragment containing this adapter.
 */
class LibraryCategoryAdapter(val controller: LibraryController?) :
    FlexibleAdapter<IFlexible<*>>(null, controller, true) {

    val sourceManager by injectLazy<SourceManager>()

    private val uiPreferences: UiPreferences by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    var showNumber = preferences.categoryNumberOfItems().get()

    var showOutline = uiPreferences.outlineOnCovers().get()

    /** When true, this adapter is rendering a single category in a pager page; section headers are not shown. */
    var isPagedMode: Boolean = false

    private var lastCategory = ""

    val hasActiveFilters: Boolean
        get() = controller?.hasActiveFilters == true

    init {
        setDisplayHeadersAtStartUp(true)
    }

    /**
     * The number of manga in each category.
     */
    var itemsPerCategory: Map<Int, Int> = emptyMap()

    /**
     * The list of manga in this category.
     */
    private var mangas: List<LibraryItem> = emptyList()

    // Single in-flight filter job. setItems and external requestFilter() both go through
    // launchFilter() so updateDataSet / notifyDataSetChanged calls can't interleave.
    private var filterJob: Job? = null

    val libraryListener: LibraryListener? = controller

    val isSingleCategory
        get() = isPagedMode ||
            controller?.singleCategory == true ||
            controller?.presenter?.showAllCategories == false

    /**
     * Sets a list of manga in the adapter.
     *
     * @param list the list to set.
     */
    fun setItems(list: List<LibraryItem>) {
        // A copy of manga always unfiltered.
        mangas = list.toList()
        launchFilter()
    }

    /** Public entry for callers that mutate filter state (setFilter) and need to re-apply. */
    fun requestFilter() = launchFilter()

    private fun setItemsPerCategoryMap() {
        val controller = controller ?: return
        itemsPerCategory = headerItems.associate { header ->
            (header as LibraryHeaderItem).catId to
                controller.presenter.getItemCountInCategories(header.catId)
        }
    }

    /**
     * Returns the position in the adapter for the given manga.
     *
     * @param manga the manga to find.
     */
    fun indexOf(categoryOrder: Int): Int {
        return currentItems.indexOfFirst {
            if (it is LibraryHeaderItem) {
                it.category.order == categoryOrder
            } else {
                false
            }
        }
    }

    /**
     * Returns the position in the adapter for the given manga.
     *
     * @param manga the manga to find.
     */
    fun findCategoryHeader(catId: Int): LibraryHeaderItem? {
        return currentItems.find {
            (it is LibraryHeaderItem) && it.category.id == catId
        } as? LibraryHeaderItem
    }

    /**
     * Returns the position in the adapter for the given manga.
     *
     * @param manga the manga to find.
     */
    fun indexOf(manga: Manga): Int {
        return currentItems.indexOfFirst {
            if (it is LibraryMangaItem) {
                it.manga.manga.id == manga.id
            } else {
                false
            }
        }
    }

    fun getHeaderPositions(): List<Int> {
        return currentItems.mapIndexedNotNull { index, it ->
            if (it is LibraryHeaderItem) {
                index
            } else {
                null
            }
        }
    }

    /**
     * Returns the position in the adapter for the given manga.
     *
     * @param manga the manga to find.
     */
    fun allIndexOf(manga: Manga): List<Int> {
        return currentItems.mapIndexedNotNull { index, it ->
            if (it is LibraryMangaItem && it.manga.manga.id == manga.id) {
                index
            } else {
                null
            }
        }
    }

    private fun launchFilter() {
        // Cancel-and-replace so updateDataSet calls can't interleave when the controller
        // mutates state from multiple paths (setItems, setFilter+requestFilter, flow re-emits).
        // Falls back to a process-scope launch if the controller's viewScope isn't ready yet
        // (rare: setItems racing preCreateView).
        filterJob?.cancel()
        val scope = runCatching { controller?.viewScope }.getOrNull()
        filterJob = if (scope != null) {
            scope.launchUI { runFilterWithSuppression() }
        } else {
            launchUI { runFilterWithSuppression() }
        }
    }

    // In paged mode the per-tab recycler renders a single category, so FlexibleAdapter's
    // auto-section insertion (which fires inside performFilter → updateDataSet →
    // prepareItemsForUpdate and is not gated by setDisplayHeadersAtStartUp / headersShown)
    // would draw a category bubble above the items — the flash users saw when switching tabs.
    // Suppress getHeader() on the launching thread for the duration of the filter; concurrent
    // flow work on Dispatchers.IO continues to see the real header.
    private suspend fun runFilterWithSuppression() {
        val suppress = isPagedMode
        if (suppress) LibraryItem.suppressSectionHeader.set(true)
        try {
            performFilterAsync()
        } finally {
            if (suppress) LibraryItem.suppressSectionHeader.remove()
        }
    }

    suspend fun performFilterAsync() {
        val s = getFilter(String::class.java)
        if (s.isNullOrBlank()) {
            if (mangas.firstOrNull()?.filter?.isNotBlank() == true) {
                mangas.forEach { it.filter = "" }
            }
            updateDataSet(mangas)
        } else {
            val filteredManga = withDefContext {
                mangas.filter {
                    ensureActive()
                    it.filter(s)
                }
            }
            if (filteredManga.isEmpty() && controller?.presenter?.showAllCategories == false) {
                val catId = (mangas.firstOrNull() as? LibraryMangaItem)?.let { it.header?.catId ?: it.manga.category }
                val blankItem = catId?.let { controller.presenter.blankItem(it) }
                updateDataSet(blankItem ?: emptyList())
            } else {
                updateDataSet(filteredManga)
            }
        }
        isLongPressDragEnabled = libraryListener?.canDrag() == true && s.isNullOrBlank()
        setItemsPerCategoryMap()
        notifyDataSetChanged()
    }

    private fun getFirstLetter(name: String): String {
        val letter = name.firstOrNull() ?: '#'
        return if (letter.isLetter()) getFirstChar(name) else "#"
    }

    private fun getFirstChar(string: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val chars = string.codePoints().toArray().firstOrNull() ?: return ""
            val char = Character.toChars(chars)
            return String(char).uppercase(Locale.US)
        } else {
            return string.toCharArray().firstOrNull()?.toString()?.uppercase(Locale.US) ?: ""
        }
    }

    override fun onCreateBubbleText(position: Int): String {
        val preferences: PreferencesHelper by injectLazy()
        val getChapter: GetChapter by injectLazy()
        val getHistory: GetHistory by injectLazy()
        val context = recyclerView.context
        if (position == itemCount - 1) return context.getString(MR.strings.bottom)
        return when (val item: IFlexible<*>? = getItem(position)) {
            is LibraryHeaderItem -> {
                vibrateOnCategoryChange(item.category.name)
                item.category.name
            }
            is LibraryPlaceholderItem -> {
                item.header?.category?.name.orEmpty()
            }
            is LibraryMangaItem -> {
                val text =
                    when (getSort(position)) {
                        LibrarySort.DragAndDrop -> {
                            if (item.sectionHeader.category.isDynamic && item.manga.manga.id != null) {
                                // O(1) lookup against the presenter cache; no DB hit on the scroll bubble.
                                val category = controller?.presenter
                                    ?.categoryNamesById
                                    ?.get(item.manga.category)
                                category ?: context.getString(MR.strings.default_value)
                            } else {
                                val title = item.manga.manga.title
                                if (preferences.removeArticles().get()) {
                                    title.removeArticles().chop(15)
                                } else {
                                    title.take(10)
                                }
                            }
                        }
                        LibrarySort.DateFetched -> {
                            // Pre-computed by the LibraryManga SQL mapper.
                            context.timeSpanFromNow(MR.strings.fetched_, item.manga.lastFetch)
                        }
                        LibrarySort.LastRead -> {
                            context.timeSpanFromNow(MR.strings.read_, item.manga.lastRead)
                        }
                        LibrarySort.Unread -> {
                            val unread = item.manga.unread
                            if (unread > 0) {
                                context.getString(MR.strings._unread, unread)
                            } else {
                                context.getString(MR.strings.read)
                            }
                        }
                        LibrarySort.TotalChapters -> {
                            val total = item.manga.totalChapters
                            if (total > 0) {
                                recyclerView.context.getString(
                                    MR.plurals.chapters_plural,
                                    total,
                                    total,
                                )
                            } else {
                                "N/A"
                            }
                        }
                        LibrarySort.LatestChapter -> {
                            context.timeSpanFromNow(MR.strings.updated_, item.manga.manga.last_update)
                        }
                        LibrarySort.NextExpectedUpdate -> {
                            val nextUpdate = item.manga.manga.next_update
                            if (nextUpdate > 0L) {
                                context.timeSpanFromNow(MR.strings.manga_next_update_, nextUpdate)
                            } else {
                                "N/A"
                            }
                        }
                        LibrarySort.DateAdded -> {
                            context.timeSpanFromNow(MR.strings.added_, item.manga.manga.date_added)
                        }
                        LibrarySort.Title -> {
                            val title = if (preferences.removeArticles().get()) {
                                item.manga.manga.title.removeArticles()
                            } else {
                                item.manga.manga.title
                            }
                            getFirstLetter(title)
                        }
                        LibrarySort.Random -> {
                            context.getString(MR.strings.random)
                        }
                    }
                if (!isSingleCategory) {
                    vibrateOnCategoryChange(item.header?.category?.name.orEmpty())
                }
                when {
                    isSingleCategory -> text
                    recyclerView.resources.isLTR -> text + " - " + item.header?.category?.name.orEmpty()
                    else -> item.header?.category?.name.orEmpty() + " - " + text
                }
            }
            else -> ""
        }
    }

    private fun vibrateOnCategoryChange(categoryName: String) {
        if (categoryName != lastCategory) {
            lastCategory = categoryName
            recyclerView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun getSort(position: Int): LibrarySort {
        val header = (getItem(position) as? LibraryItem)?.header
        return header?.category?.sortingMode() ?: LibrarySort.DragAndDrop
    }

    interface LibraryListener {
        fun startReading(position: Int, view: View?)
        fun onItemReleased(position: Int)
        fun canDrag(): Boolean
        fun updateCategory(position: Int): Boolean
        fun sortCategory(catId: Int, sortBy: Char)
        fun selectAll(position: Int)
        fun allSelected(position: Int): Boolean
        fun toggleCategoryVisibility(position: Int)
        fun manageCategory(position: Int)
        fun globalSearch(query: String)
    }
}
