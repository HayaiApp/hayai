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
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchNonCancellableIO
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.withUIContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import yokai.util.koin.injectLazy
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MangaUpdate
import yokai.domain.source.browse.filter.FilterSerializer
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

    /**
     * Modifiable list of filters. Mutated in place by the new
     * [eu.kanade.tachiyomi.ui.source.browse.compose.ComposeSourceFilterSheet] composables —
     * setting this field is reserved for wholesale swaps (reset, saved-search apply).
     */
    var sourceFilters = FilterList()
        set(value) {
            field = value
            filtersChanged = true
        }

    /**
     * List of filters used by the [Pager]. If empty alongside [query], the popular query is used.
     */
    var appliedFilters = FilterList()

    /**
     * Pager containing a list of manga results.
     */
    private lateinit var pager: Pager
    private var pagerJob: Job? = null

    /**
     * Subscription for one request from the pager.
     */
    private var nextPageJob: Job? = null

    var query = searchQuery ?: ""

    private val oldFilters = mutableListOf<Any?>()

    override fun onCreate() {
        super.onCreate()
        if (!::pager.isInitialized) {
            // Cheap in-memory lookup first; only block if the source isn't registered yet.
            source = (sourceManager.get(sourceId) as? CatalogueSource)
                ?: runBlocking { sourceManager.awaitCatalogueSource(sourceId) }
                ?: return

            sourceFilters = source.getFilterList()

            if (oldFilters.isEmpty()) {
                for (i in sourceFilters) {
                    if (i is Filter.Group<*>) {
                        val subFilters = mutableListOf<Any?>()
                        for (j in i.state) {
                            subFilters.add((j as Filter<*>).state)
                        }
                        oldFilters.add(subFilters)
                    } else {
                        oldFilters.add(i.state)
                    }
                }
            }
            filtersChanged = false

            // Saved searches don't gate first paint; load off Main.
            presenterScope.launchUI {
                view?.savedSearches = loadSearches()
            }

            getSavedSearch.subscribeAllBySourceId(sourceId)
                .map { it.applyAllSave(source.getFilterList()) }
                .onEach {
                    withUIContext { view?.savedSearches = it }
                }
                .launchIn(presenterScope)
        }
    }

    fun filtersMatchDefault(): Boolean {
        for (i in sourceFilters.indices) {
            val filter = oldFilters.getOrNull(i)
            if (sourceFilters[i] is Filter.Group<*> && filter is List<*>) {
                for (j in filter.indices) {
                    val state = ((sourceFilters[i] as Filter.Group<*>).state[j] as Filter<*>).state
                    if (filter[j] != state) {
                        return false
                    }
                }
            } else if (filter != sourceFilters[i].state) {
                return false
            }
        }
        return true
    }

    /**
     * Restarts the pager for the active source with the provided query and filters.
     *
     * @param query the query.
     * @param filters the current state of the filters (for search mode).
     */
    fun restartPager(query: String = this.query, filters: FilterList = this.appliedFilters) {
        this.query = query
        this.appliedFilters = filters

        // Create a new pager.
        pager = createPager(
            query,
            filters.takeIf { it.isNotEmpty() || query.isBlank() } ?: source.getFilterList(),
        )

        val sourceId = source.id

        val browseAsList = preferences.browseAsList()
        val sourceListType = preferences.sourceGridLayout()
        val outlineCovers = uiPreferences.outlineOnCovers()

        view?.unsubscribe()

        // Prepare the pager.
        pagerJob?.cancel()
        pagerJob = presenterScope.launchIO {
            pager.asFlow()
                .map { (first, second) ->
                    first to second
                        .map { networkToLocalManga(it, sourceId) }
                        .filter { !preferences.hideInLibraryItems().get() || !it.favorite }
                }
                .onEach { initializeMangas(it.second) }
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

    /**
     * Initialize a list of manga.
     *
     * @param mangas the list of manga to initialize.
     */
    fun initializeMangas(mangas: List<Manga>) {
        presenterScope.launchIO {
            mangas.asFlow()
                .filter { it.thumbnail_url == null && !it.initialized }
                .map { getMangaDetails(it) }
                .onEach {
                    withUIContext { view?.onMangaInitialized(it) }
                }
                .catch { e -> Logger.e(e) { "Unable to initialize manga" } }
                .collect()
        }
    }

    /**
     * Returns the initialized manga.
     *
     * @param manga the manga to initialize.
     * @return the initialized manga
     */
    private suspend fun getMangaDetails(manga: Manga): Manga {
        try {
            val networkManga = source.getMangaDetails(manga.copy())
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
                try {
                    Json.encodeToString(filterSerializer.serialize(filters))
                } catch (e: Exception) {
                    "[]"
                },
            )
        }
    }

    fun deleteSearch(searchId: Long) {
        presenterScope.launchNonCancellableIO {
            deleteSavedSearch.await(searchId)
        }
    }

    suspend fun loadSearch(id: Long): SavedSearch? {
        return getSavedSearch.awaitById(id)?.applySave(source.getFilterList())
    }

    suspend fun loadSearches(): List<SavedSearch> {
       return getSavedSearch.awaitAllBySourceId(sourceId).applyAllSave(source.getFilterList())
    }
}
