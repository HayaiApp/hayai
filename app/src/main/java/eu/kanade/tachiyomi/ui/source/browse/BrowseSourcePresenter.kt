package eu.kanade.tachiyomi.ui.source.browse

import yokai.util.koin.get
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.database.models.removeCover
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchNonCancellableIO
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.withUIContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import yokai.util.koin.injectLazy
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MangaUpdate
import yokai.domain.source.browse.filter.FilterSerializer
import yokai.domain.source.browse.filter.ActiveConstraint
import yokai.domain.source.browse.filter.ConstraintRemoval
import yokai.domain.source.browse.filter.FilterTree
import yokai.domain.source.browse.filter.FilterTreeSnapshot
import yokai.domain.source.browse.filter.RefinementSnapshot
import yokai.domain.source.browse.filter.interactor.DeleteSavedSearch
import yokai.domain.source.browse.filter.interactor.GetSavedSearch
import yokai.domain.source.browse.filter.interactor.InsertSavedSearch
import yokai.domain.source.browse.filter.models.SavedSearch
import yokai.domain.ui.UiPreferences

// FIXME: Migrate to Compose
/**
 * Presenter of [BrowseSourceController].
 */
open class BrowseSourcePresenter(
    private val sourceId: Long,
    searchQuery: String? = null,
    var useLatest: Boolean = false,
    val sourceManager: SourceManager = get(),
    val uiPreferences: UiPreferences = get(),
    val preferences: PreferencesHelper = get(),
    private val coverCache: CoverCache = get(),
) : BaseCoroutinePresenter<BrowseSourceController>() {
    private val getManga: GetManga by injectLazy()
    private val insertManga: InsertManga by injectLazy()
    private val updateManga: UpdateManga by injectLazy()

    private val deleteSavedSearch: DeleteSavedSearch by injectLazy()
    private val getSavedSearch: GetSavedSearch by injectLazy()
    private val insertSavedSearch: InsertSavedSearch by injectLazy()
    private val filterSerializer: FilterSerializer by injectLazy()

    /**
     * Selected source.
     */
    lateinit var source: CatalogueSource

    val sourceIsInitialized
        get() = this::source.isInitialized

    var filtersChanged = false

    val page: Int
        get() = pager.currentPage

    /** Full, isolated materialization of the currently applied source filter tree. */
    var sourceFilters = FilterList()

    private var baselineFilters = FilterTreeSnapshot(emptyList())
    private var appliedRefinement = RefinementSnapshot(searchQuery.orEmpty(), baselineFilters)

    /**
     * List of filters used by the [Pager]. If empty alongside [query], the popular query is used.
     */
    var appliedFilters = FilterList()

    /**
     * Pager containing a list of manga results.
     */
    private lateinit var pager: Pager
    private var pagerJob: Job? = null
    private var mangaInitializationContext: MangaInitializationContext? = null
    private val mangaInitializationSemaphore = Semaphore(3)

    /**
     * Subscription for one request from the pager.
     */
    private var nextPageJob: Job? = null

    var query = searchQuery ?: ""

    override fun onCreate() {
        super.onCreate()
        if (sourceIsInitialized) return

        source = (sourceManager.get(sourceId) as? CatalogueSource)
            ?: runBlocking { sourceManager.awaitCatalogueSource(sourceId) }
            ?: return
        sourceFilters = source.getFilterList()
        baselineFilters = FilterTree.capture(sourceFilters)
        appliedRefinement = RefinementSnapshot(query, baselineFilters)
        filtersChanged = false

        presenterScope.launchUI {
            view?.savedSearches = loadSearches()
        }

        getSavedSearch.subscribeAllBySourceId(sourceId)
            .map { it.applyAllSave(source::getFilterList) }
            .onEach {
                withUIContext { view?.savedSearches = it }
            }
            .launchIn(presenterScope)
    }

    fun filtersMatchDefault(filters: FilterList = sourceFilters): Boolean =
        FilterTree.capture(filters) == baselineFilters

    fun createFilterDraft(): FilterList =
        FilterTree.materialize(appliedRefinement.filters, source::getFilterList)

    fun copyFilters(filters: FilterList): FilterList =
        FilterTree.materialize(FilterTree.capture(filters), source::getFilterList)

    fun activeConstraints(): List<ActiveConstraint> = FilterTree.activeConstraints(
        baseline = baselineFilters,
        current = appliedRefinement.filters,
        query = appliedRefinement.query,
    )

    fun currentRefinement(): RefinementSnapshot = appliedRefinement

    fun baselineFilterSnapshot(): FilterTreeSnapshot = baselineFilters

    fun applyRefinement(refinement: RefinementSnapshot): Boolean {
        if (refinement == appliedRefinement) return false
        val filters = FilterTree.materialize(refinement.filters, source::getFilterList)
        sourceFilters = FilterTree.materialize(refinement.filters, source::getFilterList)
        val isDefault = refinement.filters == baselineFilters
        filtersChanged = !isDefault
        restartPager(refinement.query, if (isDefault) FilterList() else filters)
        return true
    }

    fun commitFilterDraft(filters: FilterList, query: String = this.query): Boolean =
        applyRefinement(RefinementSnapshot(query, FilterTree.capture(filters)))

    fun resetDraft(filters: FilterList) {
        FilterTree.apply(baselineFilters, filters)
    }

    fun clearRefinement(): Boolean = applyRefinement(RefinementSnapshot("", baselineFilters))

    fun removeConstraint(removal: ConstraintRemoval): RefinementSnapshot? {
        val previous = appliedRefinement
        val draft = createFilterDraft()
        val nextQuery = when (removal) {
            ConstraintRemoval.ClearQuery -> ""
            is ConstraintRemoval.ResetPath -> {
                if (!FilterTree.resetPath(draft, baselineFilters, removal.path)) return null
                previous.query
            }
            is ConstraintRemoval.RemoveAutoCompleteValue -> {
                if (!FilterTree.removeAutoCompleteValue(draft, removal.path, removal.value)) return null
                previous.query
            }
        }
        val next = RefinementSnapshot(nextQuery, FilterTree.capture(draft))
        return previous.takeIf { applyRefinement(next) }
    }

    /**
     * Restarts the pager for the active source with the provided query and filters.
     *
     * @param query the query.
     * @param filters the current state of the filters (for search mode).
     */
    fun restartPager(query: String = this.query, filters: FilterList = this.appliedFilters) {
        this.query = query
        val filterSnapshot = if (filters.isEmpty()) baselineFilters else FilterTree.capture(filters)
        appliedRefinement = RefinementSnapshot(query, filterSnapshot)
        sourceFilters = FilterTree.materialize(filterSnapshot, source::getFilterList)
        val isDefault = filterSnapshot == baselineFilters
        this.appliedFilters = if (isDefault) {
            FilterList()
        } else {
            FilterTree.materialize(filterSnapshot, source::getFilterList)
        }
        val pagerFilters = if (isDefault) {
            FilterList()
        } else {
            FilterTree.materialize(filterSnapshot, source::getFilterList)
        }

        // Create a new pager.
        pager = createPager(
            query,
            pagerFilters.takeIf { it.isNotEmpty() || query.isBlank() } ?: source.getFilterList(),
        )

        val sourceId = source.id

        val browseAsList = preferences.browseAsList()
        val sourceListType = preferences.sourceGridLayout()
        val outlineCovers = uiPreferences.outlineOnCovers()

        view?.unsubscribe()

        // Prepare the pager.
        val mangaInitializationContext = resetMangaInitializationContext()
        pagerJob?.cancel()
        pagerJob = presenterScope.launchIO {
            pager.asFlow()
                .map { (first, second) ->
                    first to second
                        .map { networkToLocalManga(it, sourceId) }
                        .filter { !preferences.hideInLibraryItems().get() || !it.favorite }
                }
                .onEach { initializeMangas(it.second, mangaInitializationContext) }
                .map { (first, second) ->
                    first to second.map {
                        BrowseSourceItem(
                            it,
                            browseAsList,
                            sourceListType,
                            outlineCovers,
                        )
                    }
                }
                .catch { error ->
                    Logger.e(error) { "Unable to prepare a page" }
                    withUIContext { view?.onAddPageError(error) }
                }
                .collectLatest { (page, mangas) ->
                    if (mangas.isEmpty() && page == 1) {
                        withUIContext { view?.onAddPageError(NoResultsException()) }
                        return@collectLatest
                    }
                    withUIContext { view?.onAddPage(page, mangas) }
                }
        }

        // Request first page.
        requestNext()
    }

    /**
     * Requests the next page for the active pager.
     */
    fun requestNext() {
        if (!hasNextPage()) return

        nextPageJob?.cancel()
        nextPageJob = presenterScope.launchIO {
            try {
                pager.requestNextPage()
            } catch (e: Throwable) {
                withUIContext { view?.onAddPageError(e) }
            }
        }
    }

    /**
     * Returns true if the last fetched page has a next page.
     */
    fun hasNextPage(): Boolean {
        return pager.hasNextPage
    }

    /**
     * Returns a manga from the database for the given manga from network. It creates a new entry
     * if the manga is not yet in the database.
     *
     * @param sManga the manga from the source.
     * @return a manga from the database.
     */
    private suspend fun networkToLocalManga(sManga: SManga, sourceId: Long): Manga {
        var localManga = getManga.awaitByUrlAndSource(sManga.url, sourceId)
        if (localManga == null) {
            val newManga = Manga.create(sManga.url, sManga.title, sourceId)
            newManga.copyFrom(sManga)
            newManga.id = insertManga.await(newManga)
            localManga = newManga
        } else if (localManga.title.isBlank()) {
            localManga.title = sManga.title
            updateManga.await(
                MangaUpdate(
                    id = localManga.id!!,
                    title = sManga.title,
                )
            )
        } else if (!localManga.favorite) {
            // if the manga isn't a favorite, set its display title from source
            // if it later becomes a favorite, updated title will go to db
            localManga.title = sManga.title
        }
        return localManga
    }

    fun initializeMangas(mangas: List<Manga>) {
        val context = mangaInitializationContext ?: return
        initializeMangas(mangas, context)
    }

    private fun initializeMangas(mangas: List<Manga>, context: MangaInitializationContext) {
        mangas.asSequence()
            .filter { it.thumbnail_url == null && !it.initialized }
            .filter { manga -> manga.id?.let(context.initializingMangaIds::add) == true }
            .forEach { manga ->
                context.scope.launchIO {
                    try {
                        mangaInitializationSemaphore.withPermit {
                            val initializedManga = getMangaDetails(manga)
                            withUIContext { view?.onMangaInitialized(initializedManga) }
                        }
                    } finally {
                        manga.id?.let(context.initializingMangaIds::remove)
                    }
                }
            }
        }

    private fun resetMangaInitializationContext(): MangaInitializationContext {
        mangaInitializationContext?.scope?.cancel()
        return MangaInitializationContext(
            CoroutineScope(
                presenterScope.coroutineContext + SupervisorJob(presenterScope.coroutineContext[Job]),
            ),
        ).also { mangaInitializationContext = it }
    }

    override fun onDestroy() {
        mangaInitializationContext?.scope?.cancel()
        super.onDestroy()
    }

    private class MangaInitializationContext(
        val scope: CoroutineScope,
        val initializingMangaIds: MutableSet<Long> = ConcurrentHashMap.newKeySet(),
    )

    /**
     * Returns the initialized manga.
     *
     * @param manga the manga to initialize.
     * @return the initialized manga
     */
    private suspend fun getMangaDetails(manga: Manga): Manga {
        try {
            val networkManga = source.getMangaUpdate(
                manga.copy(),
                chapters = emptyList(),
                fetchDetails = true,
                fetchChapters = false,
            ).manga
            manga.copyFrom(networkManga)
            manga.initialized = true
            updateManga.await(manga.toMangaUpdate())
        } catch (e: Exception) {
            Logger.e(e) { "Something went wrong while trying to initialize manga" }
        }
        return manga
    }

    fun confirmDeletion(manga: Manga) {
        launchIO {
            manga.removeCover(coverCache)
            val downloadManager: DownloadManager = get()
            downloadManager.deleteManga(manga, source)
        }
    }

    /**
     * Set the filter states for the current source.
     *
     * @param filters a list of active filters.
     */
    fun setSourceFilter(filters: FilterList) {
        filtersChanged = true
        restartPager(filters = filters)
    }

    open fun createPager(query: String, filters: FilterList): Pager {
        return if (useLatest && query.isBlank() && !filtersChanged) {
            LatestUpdatesPager(source)
        } else {
            useLatest = false
            BrowseSourcePager(source, query, filters)
        }
    }

    fun saveSearch(name: String, query: String, filters: FilterList) {
        presenterScope.launchNonCancellableIO {
            insertSavedSearch.await(
                sourceId,
                name,
                query,
                Json.encodeToString(filterSerializer.serializeV2(filters)),
            )
        }
    }

    fun deleteSearch(searchId: Long) {
        presenterScope.launchNonCancellableIO {
            deleteSavedSearch.await(searchId)
        }
    }

    suspend fun loadSearch(id: Long): SavedSearch? {
        return getSavedSearch.awaitById(id)?.applySave(source::getFilterList)
    }

    suspend fun loadSearches(): List<SavedSearch> {
       return getSavedSearch.awaitAllBySourceId(sourceId).applyAllSave(source::getFilterList)
    }
}
