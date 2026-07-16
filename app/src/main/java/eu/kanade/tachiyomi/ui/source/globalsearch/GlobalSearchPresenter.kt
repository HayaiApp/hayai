package eu.kanade.tachiyomi.ui.source.globalsearch

import yokai.util.koin.get
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.database.models.removeCover
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.withUIContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import yokai.util.koin.injectLazy
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga

/**
 * Presenter of [GlobalSearchController]
 * Function calls should be done from here. UI calls should be done from the controller.
 *
 * @param sourceManager manages the different sources.
 * @param preferences manages the preference calls.
 */
open class GlobalSearchPresenter(
    private val initialQuery: String? = "",
    private val initialExtensionFilter: String? = null,
    private val sourcesToUse: List<CatalogueSource>? = null,
    val sourceManager: SourceManager = get(),
    private val preferences: PreferencesHelper = get(),
    private val coverCache: CoverCache = get(),
) : BaseCoroutinePresenter<GlobalSearchController>() {
    private val getManga: GetManga by injectLazy()
    private val insertManga: InsertManga by injectLazy()
    private val updateManga: UpdateManga by injectLazy()

    /**
     * Enabled sources.
     */
    val sources by lazy { getSourcesToQuery() }

    private var fetchSourcesJob: Job? = null

    var query = ""

    @Volatile
    private var searchSession: SearchSession? = null

    private val extensionManager: ExtensionManager by injectLazy()

    private var extensionFilter: String? = null

    var items: List<GlobalSearchItem> = emptyList()

    private val sourceSearchSemaphore = Semaphore(5)
    private val mangaInitializationSemaphore = Semaphore(3)

    override fun onCreate() {
        super.onCreate()

        extensionFilter = initialExtensionFilter

        if (items.isEmpty()) {
            search(initialQuery.orEmpty())
        } else {
            presenterScope.launchUI {
                view?.setItems(items)
            }
        }
    }

    /**
     * Returns a list of enabled sources ordered by language and name.
     *
     * @return list containing enabled sources.
     */
    protected open fun getEnabledSources(): List<CatalogueSource> {
        val languages = preferences.enabledLanguages().get()
        val hiddenCatalogues = preferences.hiddenSources().get()
        val pinnedCatalogues = preferences.pinnedCatalogues().get()

        val list = sourceManager.getVisibleCatalogueSources()
            .filter { it.lang in languages }
            .filterNot { it.id.toString() in hiddenCatalogues }
            .sortedBy { "(${it.lang}) ${it.name}" }

        return if (preferences.onlySearchPinned().get()) {
            list.filter { it.id.toString() in pinnedCatalogues }
        } else {
            list.sortedBy { it.id.toString() !in pinnedCatalogues }
        }
    }

    private fun getSourcesToQuery(): List<CatalogueSource> {
        if (sourcesToUse != null) return sourcesToUse
        val filter = extensionFilter
        val enabledSources = getEnabledSources()
        if (filter.isNullOrEmpty()) {
            return enabledSources
        }

        val languages = preferences.enabledLanguages().get()
        val filterSources = extensionManager.installedExtensionsFlow.value
            .filter { it.pkgName == filter }
            .flatMap { it.sources }
            .filterIsInstance<CatalogueSource>()

        val result = filterSources.filter { it in enabledSources }

        if (result.isEmpty()) {
            return enabledSources
        }

        return result
    }

    /**
     * Creates a catalogue search item
     */
    protected open fun createCatalogueSearchItem(
        source: CatalogueSource,
        results: List<GlobalSearchMangaItem>?,
    ): GlobalSearchItem {
        return GlobalSearchItem(source, results)
    }

    fun confirmDeletion(manga: Manga) {
        manga.removeCover(coverCache)
        val downloadManager: DownloadManager = get()
        sourceManager.get(manga.source)?.let { source ->
            downloadManager.deleteManga(manga, source)
        }
    }

    /**
     * Initiates a search for manga per catalogue.
     *
     * @param query query on which to search.
     */
    fun search(query: String) {
        if (this.query == query) return

        this.query = query
        fetchSourcesJob?.cancel()
        val session = beginSearch()
        val initialItems = sources.map { createCatalogueSearchItem(it, null) }
        items = initialItems
        presenterScope.launchUI {
            if (searchSession === session) {
                view?.setItems(initialItems)
            }
        }
        fetchSourcesJob = presenterScope.launch {
            sources.forEach { source ->
                launch {
                    sourceSearchSemaphore.withPermit {
                        val mangas = try {
                            source.getSearchManga(1, query, source.getFilterList())
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            MangasPage(emptyList(), false)
                        }
                            .mangas.take(10)
                            .mapNotNull { networkToLocalManga(it, source.id) }
                        if (searchSession !== session) return@withPermit
                        initializeMangas(mangas, source, session)
                        val result = createCatalogueSearchItem(
                            source,
                            mangas.map {
                                GlobalSearchMangaItem(
                                    it,
                                    getManga.subscribeByUrlAndSource(it.url, it.source),
                                )
                            },
                        )
                        withUIContext {
                            if (searchSession !== session) return@withUIContext
                            val item = items.firstOrNull { it.source.id == source.id } ?: return@withUIContext
                            item.results = result.results
                            view?.updateItem(item)
                        }
                    }
                }
            }
        }
    }

    private fun initializeMangas(mangas: List<Manga>, source: CatalogueSource, session: SearchSession) {
        mangas.asSequence()
            .filter { it.thumbnail_url == null && !it.initialized }
            .filter { manga -> manga.id?.let(session.initializingMangaIds::add) == true }
            .forEach { manga ->
                session.scope.launchIO {
                    val initializedManga = try {
                        mangaInitializationSemaphore.withPermit {
                            getMangaDetails(manga, source)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        manga
                    } finally {
                        manga.id?.let(session.initializingMangaIds::remove)
                    }
                    withUIContext {
                        if (searchSession === session) {
                            view?.onMangaInitialized(source, initializedManga)
                        }
                    }
                }
            }
    }

    private fun beginSearch(): SearchSession {
        searchSession?.scope?.cancel()
        return SearchSession(
            CoroutineScope(
                presenterScope.coroutineContext + SupervisorJob(presenterScope.coroutineContext[Job]),
            ),
        ).also { searchSession = it }
    }

    override fun onDestroy() {
        fetchSourcesJob?.cancel()
        searchSession?.scope?.cancel()
        super.onDestroy()
    }

    private class SearchSession(
        val scope: CoroutineScope,
        val initializingMangaIds: MutableSet<Long> = ConcurrentHashMap.newKeySet(),
    )

    /**
     * Initializes the given manga.
     *
     * @param manga the manga to initialize.
     * @return The initialized manga.
     */
    private suspend fun getMangaDetails(manga: Manga, source: CatalogueSource): Manga {
        val networkManga = source.getMangaDetails(manga.copy())
        manga.copyFrom(networkManga)
        manga.initialized = true
        updateManga.await(manga.toMangaUpdate())
        return manga
    }

    /**
     * Returns a manga from the database for the given manga from network. It creates a new entry
     * if the manga is not yet in the database.
     *
     * @param sManga the manga from the source.
     * @return a manga from the database.
     */
    protected open suspend fun networkToLocalManga(sManga: SManga, sourceId: Long): Manga? {
        var localManga = getManga.awaitByUrlAndSource(sManga.url, sourceId)
        if (localManga == null) {
            val newManga =
                try {
                    Manga.create(sManga.url, sManga.title, sourceId)
                } catch (_: UninitializedPropertyAccessException) {
                    return null
                }
            newManga.copyFrom(sManga)
            newManga.id = insertManga.await(newManga)
            localManga = newManga
        } else if (!localManga.favorite) {
            // if the manga isn't a favorite, set its display title from source
            // if it later becomes a favorite, updated title will go to db
            localManga.title =
                try {
                    sManga.title
                } catch (_: UninitializedPropertyAccessException) {
                    return localManga
                }
        }
        return localManga
    }
}
