package eu.kanade.tachiyomi.ui.reader

import yokai.util.koin.get
import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.ColorInt
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.database.models.defaultReaderType
import eu.kanade.tachiyomi.data.database.models.isNovel
import eu.kanade.tachiyomi.data.database.models.orientationType
import eu.kanade.tachiyomi.data.database.models.readingModeType
import eu.kanade.tachiyomi.data.database.models.updateCoverLastModified
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import hayai.novel.source.TextSource
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterItem
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.settings.OrientationType
import eu.kanade.tachiyomi.ui.reader.settings.ReadingModeType
import eu.kanade.tachiyomi.util.chapter.ChapterFilter
import eu.kanade.tachiyomi.util.chapter.ChapterSort
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.chapter.updateTrackChapterRead
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchNonCancellableIO
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.withIOContext
import eu.kanade.tachiyomi.util.system.withUIContext
import java.util.Date
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import yokai.util.koin.injectLazy
import yokai.domain.category.interactor.GetCategories
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.chapter.interactor.InsertChapter
import yokai.domain.chapter.interactor.UpdateChapter
import yokai.domain.chapter.models.ChapterUpdate
import yokai.domain.download.DownloadPreferences
import yokai.domain.history.interactor.GetHistory
import yokai.domain.history.interactor.UpsertHistory
import yokai.domain.library.LibraryPreferences
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MangaUpdate
import yokai.domain.stats.interactor.SetChapterWordCount
import yokai.domain.storage.StorageManager
import yokai.domain.track.interactor.GetTrack
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Presenter used by the activity to perform background operations.
 */
class ReaderViewModel(
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val sourceManager: SourceManager = get(),
    private val downloadManager: DownloadManager = get(),
    private val coverCache: CoverCache = get(),
    private val preferences: PreferencesHelper = get(),
    private val chapterFilter: ChapterFilter = get(),
    private val storageManager: StorageManager = get(),
    private val downloadPreferences: DownloadPreferences = get(),
    private val libraryPreferences: LibraryPreferences = get(),
) : ViewModel() {
    private val getCategories: GetCategories by injectLazy()
    private val getChapter: GetChapter by injectLazy()
    private val insertChapter: InsertChapter by injectLazy()
    private val updateChapter: UpdateChapter by injectLazy()
    private val getManga: GetManga by injectLazy()
    private val insertManga: InsertManga by injectLazy()
    private val updateManga: UpdateManga by injectLazy()
    private val getHistory: GetHistory by injectLazy()
    private val upsertHistory: UpsertHistory by injectLazy()
    // Lazy: only used on the novel mark-read path to record per-chapter word counts for stats.
    private val setChapterWordCount: SetChapterWordCount by injectLazy()
    private val getTrack: GetTrack by injectLazy()
    // Lazy because we only need it for novel-specific paths (orientation default, etc.).
    private val readerPreferences: yokai.domain.ui.settings.ReaderPreferences by injectLazy()

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    private val downloadProvider = DownloadProvider(preferences.context)

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    /**
     * The currently loaded source. Returns null (not a StubSource) when the source isn't
     * registered — typically before [init] has resolved it on cold start / memory restore.
     * Callers downstream of the reader (NovelPageLoader, etc.) prefer null over a stub so
     * a missing plugin surfaces as a clean error instead of failing silently through the
     * stub's "source not installed" path (issue #9).
     */
    val source: Source?
        get() = manga?.source?.let { sourceManager.get(it) }

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * In-session per-chapter flag: set true when the *last* chapter in the list reaches
     * `NOVEL_LAST_CHAPTER_READ_THRESHOLD_PERCENT` while the user is actively scrolling.
     * Only the final chapter relies on this fallback — earlier chapters mark-read via
     * forward transition in [setNovelVisibleChapter]. Gates the threshold-based path so
     * teardown paths (pause/destroy) and "restore at saved %" can't trip mark-read for
     * a chapter the user never scrolled to completion this session.
     */
    private val sessionReachedThreshold: MutableMap<Long, Boolean> = mutableMapOf()

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    /**
     * Relay used when loading prev/next chapter needed to lock the UI (with a dialog).
     */
    private var finished = false
    private var chapterToDownload: Download? = null

    private val unfilteredChapterList by lazy {
        val manga = manga!!
        runBlocking { getChapter.awaitAll(manga, filterScanlators = false) }
    }

    private lateinit var chapterList: List<ReaderChapter>

    private var chapterItems = emptyList<ReaderChapterItem>()

    private var hasTrackers: Boolean = false
    private suspend fun checkTrackers(manga: Manga) = getTrack.awaitAllByMangaId(manga.id).isNotEmpty()

    init {
        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            // Skip the first emission: ChapterLoader has already set requestedPage for the
            // initial chapter (using the intent page or last_page_read). Re-applying
            // last_page_read here would race with the activity's setChapters observer and
            // overwrite an explicit starting page (e.g. when opened from a page preview).
            .drop(1)
            .onEach { currentChapter ->
                chapterId = currentChapter.chapter.id!!
                if (source !is TextSource) {
                    currentChapter.requestedPage = currentChapter.chapter.last_page_read
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onBackPressed() {
        if (finished) return
        finished = true
        deletePendingChapters()
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
    }

    /**
     * Whether this presenter is initialized yet.
     */
    fun needsInit(): Boolean {
        return manga == null || !this::chapterList.isInitialized
    }

    /**
     * Initializes this presenter with the given [mangaId] and [initialChapterId]. This method will
     * fetch the manga from the database and initialize the initial chapter.
     */
    suspend fun init(mangaId: Long, initialChapterId: Long, page: Int? = null): Result<Boolean> {
        if (!needsInit()) return Result.success(true)
        return withIOContext {
            try {
                val manga = getManga.awaitById(mangaId)
                if (manga != null) {
                    mutableState.update { it.copy(manga = manga) }
                    if (chapterId == -1L) {
                        chapterId = initialChapterId
                    }

                    hasTrackers = checkTrackers(manga)

                    NotificationReceiver.dismissNotification(
                        preferences.context,
                        manga.id!!.hashCode(),
                        Notifications.ID_NEW_CHAPTERS,
                    )

                    // Wait for the (novel) plugin manager to finish its initial load before
                    // resolving the source. On cold boot / memory-restore the previous
                    // getOrStub() returned a StubSource if the manager hadn't run yet,
                    // and the reader silently failed downstream (issue #9). Manga sources
                    // are populated synchronously by extensionManager so awaitSource
                    // returns immediately for them.
                    val resolvedSource = sourceManager.awaitSource(manga.source, SOURCE_AWAIT_TIMEOUT_MS)
                    if (resolvedSource == null) {
                        Logger.w { "ReaderViewModel.init: source ${manga.source} not ready after ${SOURCE_AWAIT_TIMEOUT_MS}ms" }
                        // Reset state so a retry can call init again. Without this the
                        // needsInit() short-circuit at the top of init would return true
                        // forever (chapterList stays uninitialized) yet manga is set, so
                        // we'd be wedged between "needs init" and "has state".
                        mutableState.update { it.copy(manga = null) }
                        eventChannel.send(Event.SourceNotReady(manga.source))
                        return@withIOContext Result.failure(SourceNotReadyException(manga.source))
                    }
                    val context = get<Application>()
                    loader = ChapterLoader(context, downloadManager, downloadProvider, manga, resolvedSource)

                    chapterList = getChapterList()
                    // Warm the lazy unfiltered list off-main so adjacentChapterResult()'s first
                    // touch (which runs on Main) never blocks the UI thread on a SQLDelight read.
                    unfilteredChapterList
                    loadChapter(loader!!, chapterList!!.first { chapterId == it.chapter.id }, page)
                    Result.success(true)
                } else {
                    // Unlikely but okay
                    Result.success(false)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                Result.failure(e)
            }
        }
    }

    private suspend fun getChapterList(): List<ReaderChapter> {
        val manga = manga!!
        val dbChapters = getChapter.awaitAll(manga.id!!, true)

        val selectedChapter = dbChapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader =
            chapterFilter.filterChaptersForReader(dbChapters, manga, selectedChapter)
        val chapterSort = ChapterSort(manga, chapterFilter, preferences)
        return chaptersForReader.sortedWith(chapterSort.sortComparator(true)).map(::ReaderChapter)
    }

    suspend fun getChapters(): List<ReaderChapterItem> {
        val manga = manga ?: return emptyList()
        chapterItems = withContext(Dispatchers.IO) {
            val chapterSort = ChapterSort(manga, chapterFilter, preferences)
            val dbChapters = getChapter.awaitAll(manga)
            chapterSort.getChaptersSorted(
                dbChapters,
                filterForReader = true,
                currentChapter = getCurrentChapter()?.chapter,
            ).map {
                ReaderChapterItem(
                    it,
                    manga,
                    it.id == (getCurrentChapter()?.chapter?.id ?: chapterId),
                )
            }
        }

        return chapterItems
    }

    fun canLoadUrl(uri: Uri): Boolean {
        val host = uri.host ?: return false
        val delegatedSource = sourceManager.getDelegatedSource(host) ?: return false
        return delegatedSource.canOpenUrl(uri)
    }

    fun intentPageNumber(url: Uri): Int? {
        val host = url.host ?: return null
        val delegatedSource = sourceManager.getDelegatedSource(host) ?: error(
            preferences.context.getString(MR.strings.source_not_installed),
        )
        return delegatedSource.pageNumber(url)?.minus(1)
    }

    // FIXME: Unused at the moment, handles J2K's delegated deep link, refactor or remove later
    suspend fun loadChapterURL(url: Uri) {
        val host = url.host ?: return
        val context = get<Application>()
        val delegatedSource = sourceManager.getDelegatedSource(host) ?: error(
            context.getString(MR.strings.source_not_installed),
        )
        val chapterUrl = delegatedSource.chapterUrl(url)
        val sourceId = delegatedSource.delegate.id
        if (chapterUrl != null) {
            val dbChapter = getChapter.awaitAllByUrl(chapterUrl, false).find {
                val source = getManga.awaitById(it.manga_id!!)?.source ?: return@find false
                if (source == sourceId) {
                    true
                } else {
                    val httpSource = sourceManager.getOrStub(source) as? HttpSource
                    val domainName = delegatedSource.domainName
                    httpSource?.baseUrl?.contains(domainName) == true
                }
            }
            if (dbChapter?.manga_id?.let { init(it, dbChapter.id!!).isSuccess } == true) {
                return
            }
        }
        val info = delegatedSource.fetchMangaFromChapterUrl(url)
        if (info != null) {
            val (sChapter, sManga, chapters) = info
            val manga = Manga.create(sManga.url, sManga.title, sourceId).apply { copyFrom(sManga) }
            val chapter = Chapter.create().apply { copyFrom(sChapter) }
            val id = insertManga.await(manga)
            manga.id = id ?: manga.id
            chapter.manga_id = manga.id
            val matchingChapterId =
                getChapter.awaitAll(manga.id!!, false).find { it.url == chapter.url }?.id
            if (matchingChapterId != null) {
                withContext(Dispatchers.Main) {
                    this@ReaderViewModel.init(manga.id!!, matchingChapterId)
                }
            } else {
                val chapterId: Long
                if (chapters.isNotEmpty()) {
                    val newChapters = syncChaptersWithSource(
                        chapters,
                        manga,
                        delegatedSource.delegate!!,
                    ).first
                    chapterId = newChapters.find { it.url == chapter.url }?.id
                        ?: error(context.getString(MR.strings.chapter_not_found))
                } else {
                    chapter.date_fetch = Date().time
                    chapterId = insertChapter.await(chapter) ?: error(
                        context.getString(MR.strings.unknown_error),
                    )
                }
                withContext(Dispatchers.Main) {
                    init(manga.id!!, chapterId)
                }
            }
        } else {
            error(context.getString(MR.strings.unknown_error))
        }
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        viewModelScope.launchIO {
            Logger.d { "Loading ${chapter.chapter.url}" }

            flushReadTimer()
            restartReadTimer()

            try {
                loadChapter(loader, chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                Logger.e(e) { "Unable to load new chapter" }
            }
        }
    }

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter,
        page: Int? = null,
    ): ViewerChapters {
        loader.loadChapter(chapter, page)

        val chapterPos = chapterList.indexOf(chapter) ?: -1
        val newChapters = ViewerChapters(
            chapter,
            chapterList.getOrNull(chapterPos - 1),
            chapterList.getOrNull(chapterPos + 1),
        )

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = deleteChapterFromDownloadQueue(newChapters.currChapter)
                it.copy(viewerChapters = newChapters)
            }
        }
        return newChapters
    }

    /**
     * Called when the user is going to load the prev/next chapter through the menu button.
     */
    suspend fun loadChapter(chapter: ReaderChapter): Int? {
        val loader = loader ?: return -1

        Logger.d { "Loading adjacent ${chapter.chapter.url}" }
        val isTextSource = source is TextSource
        var lastPage: Int? = if (isTextSource) 0 else if (chapter.chapter.pages_left <= 1) 0 else chapter.chapter.last_page_read
        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            Logger.e(e) { "Unable to load adjacent chapter" }
            lastPage = null
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
        return lastPage
    }

    fun toggleRead(chapter: Chapter) {
        chapter.read = !chapter.read
        val lastPageToSave = if (chapter.read) chapter.last_page_read.toLong() else 0L
        viewModelScope.launchNonCancellableIO {
            updateChapter.await(
                ChapterUpdate(
                    id = chapter.id!!,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    lastPageRead = lastPageToSave,
                    pagesLeft = if (chapter.read) 0 else chapter.pages_left.toLong()
                )
            )
        }
    }
    fun toggleBookmark(chapter: Chapter) {
        chapter.bookmark = !chapter.bookmark
        viewModelScope.launchNonCancellableIO {
            updateChapter.await(
                ChapterUpdate(
                    id = chapter.id!!,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    lastPageRead = chapter.last_page_read.toLong(),
                    pagesLeft = chapter.pages_left.toLong(),
                )
            )
        }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    private suspend fun preload(chapter: ReaderChapter) {
        if (chapter.pageLoader is HttpPageLoader) {
            val manga = manga ?: return
            val isDownloaded = downloadManager.isChapterDownloaded(chapter.chapter, manga)
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        Logger.d { "Preloading ${chapter.chapter.url}" }

        val loader = loader ?: return
        withIOContext {
            try {
                loader.loadChapter(chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                return@withIOContext
            }
            eventChannel.trySend(Event.ReloadViewerChapters)
        }
    }

    fun adjacentChapter(next: Boolean): ReaderChapter? {
        if (!::chapterList.isInitialized) {
            val chapters = state.value.viewerChapters
            return if (next) chapters?.nextChapter else chapters?.prevChapter
        }
        val currentId = getCurrentChapter()?.chapter?.id ?: chapterId
        val index = chapterList.indexOfFirst { it.chapter.id == currentId }
        if (index == -1) {
            val chapters = state.value.viewerChapters
            return if (next) chapters?.nextChapter else chapters?.prevChapter
        }
        val targetIndex = if (next) index + 1 else index - 1
        return chapterList.getOrNull(targetIndex)
    }

    /**
     * Why a chapter is (or isn't) available in the given direction. The reader needs the
     * reason — not just presence — so a boundary toast can say "skipped N read chapters"
     * vs. "no more chapters" instead of collapsing every case into one wrong message.
     */
    sealed interface AdjacentChapterResult {
        data class Found(val chapter: ReaderChapter) : AdjacentChapterResult
        /** [count] consecutive chapters were skipped by the skip-read filter. */
        data class SkippedRead(val count: Int) : AdjacentChapterResult
        /** [count] consecutive chapters were removed by skip-filtered / skip-dupe. */
        data class SkippedFiltered(val count: Int) : AdjacentChapterResult
        /** No chapter exists in this direction at all. */
        data object EndOfList : AdjacentChapterResult
    }

    /**
     * Resolves adjacency against BOTH the filtered [chapterList] and the full unfiltered
     * chapter list so a boundary can be explained. When the filtered list has a neighbour we
     * return it ([AdjacentChapterResult.Found]); otherwise we walk the unfiltered list in the
     * same sort order and classify the gap by the dominant skip reason.
     */
    fun adjacentChapterResult(next: Boolean, fromChapterId: Long? = null): AdjacentChapterResult {
        if (!::chapterList.isInitialized) return AdjacentChapterResult.EndOfList
        val manga = manga ?: return AdjacentChapterResult.EndOfList

        val currentId = fromChapterId ?: getCurrentChapter()?.chapter?.id ?: chapterId
        // When anchored to an explicit chapter (infinite-scroll append/prepend), resolve the
        // filtered neighbour relative to that chapter rather than the active one.
        val filteredIndex = chapterList.indexOfFirst { it.chapter.id == currentId }
        if (filteredIndex != -1) {
            val neighbour = chapterList.getOrNull(if (next) filteredIndex + 1 else filteredIndex - 1)
            if (neighbour != null) return AdjacentChapterResult.Found(neighbour)
        } else {
            val found = adjacentChapter(next)
            if (found != null) return AdjacentChapterResult.Found(found)
        }
        // Sort the unfiltered chapters identically to chapterList (ascending, ignoreAsc=true).
        val sortComparator = ChapterSort(manga, chapterFilter, preferences).sortComparator<Chapter>(true)
        val fullSorted = unfilteredChapterList.sortedWith(sortComparator)
        val currentIndex = fullSorted.indexOfFirst { it.id == currentId }
        if (currentIndex == -1) return AdjacentChapterResult.EndOfList

        val filteredIds = chapterList.mapNotNull { it.chapter.id }.toHashSet()
        val skipReadOn = preferences.skipRead().get()
        var skippedRead = 0
        var skippedFiltered = 0
        var idx = if (next) currentIndex + 1 else currentIndex - 1
        val step = if (next) 1 else -1
        // Walk until we either reach a chapter the reader would actually show (would have
        // been Found above, so this only guards against races) or run off the list end.
        while (idx in fullSorted.indices) {
            val candidate = fullSorted[idx]
            if (candidate.id != null && filteredIds.contains(candidate.id)) break
            if (skipReadOn && candidate.read) skippedRead++ else skippedFiltered++
            idx += step
        }
        return when {
            skippedRead == 0 && skippedFiltered == 0 -> AdjacentChapterResult.EndOfList
            skippedRead >= skippedFiltered -> AdjacentChapterResult.SkippedRead(skippedRead + skippedFiltered)
            else -> AdjacentChapterResult.SkippedFiltered(skippedRead + skippedFiltered)
        }
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage, hasExtraPage: Boolean) {
        val currentChapters = state.value.viewerChapters ?: return

        val selectedChapter = page.chapter

        // Save last page read and mark as read if needed
        viewModelScope.launchNonCancellableIO {
            saveChapterProgress(selectedChapter, page, hasExtraPage)
        }

        if (selectedChapter != currentChapters.currChapter) {
            Logger.d { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter)
        }

        val pages = page.chapter.pages ?: return
        val inDownloadRange = page.number.toDouble() / pages.size > 0.2
        if (inDownloadRange) {
            downloadNextChapters()
        }
    }

    fun onNovelScrollProgress(page: ReaderPage) {
        if (source !is TextSource) return
        viewModelScope.launchNonCancellableIO {
            saveChapterProgress(page.chapter, page, hasExtraPage = false, sessionScrollAdvance = true)
        }
    }

    /**
     * Called from teardown paths (pause/stop/destroy) to flush the last known scroll
     * percent without ever flipping `chapter.read = true`. The `sessionScrollAdvance=false`
     * flag short-circuits the mark-read gate inside `saveChapterProgress`.
     */
    fun onNovelTeardownProgress(page: ReaderPage) {
        if (source !is TextSource) return
        viewModelScope.launchNonCancellableIO {
            saveChapterProgress(page.chapter, page, hasExtraPage = false, sessionScrollAdvance = false)
        }
    }

    /**
     * Pushes the chapter the user is now looking at, so [State.viewerChapters] tracks the
     * visible chapter rather than the entry chapter through infinite-scroll transitions.
     *
     * Invariant: `state.viewerChapters.currChapter` is the *single* source of truth for
     * "what novel chapter is on screen right now". Every history write, progress save, and
     * saved-state restore reads from it. Don't add parallel trackers (`novelActiveChapterId`,
     * `currentChapterIndex`, etc.) — if they drift from this field, history will start
     * writing to the wrong chapter and recents will show stale data, silently. That class
     * of bug used to be the default; keep it impossible.
     */
    fun setNovelVisibleChapter(readerChapter: ReaderChapter) {
        if (!::chapterList.isInitialized) return
        val newChapterId = readerChapter.chapter.id ?: return
        val newIndex = chapterList.indexOfFirst { it.chapter.id == newChapterId }
        if (newIndex < 0) {
            Logger.w {
                "setNovelVisibleChapter: chapter $newChapterId not in chapterList " +
                    "(size=${chapterList.size}); advancing without siblings rather than dropping."
            }
        }
        if (chapterId == newChapterId) {
            viewModelScope.launchIO { preload(readerChapter) }
            return
        }
        val previousChapterId = chapterId
        if (newIndex >= 0) {
            val oldIndex = chapterList.indexOfFirst { it.chapter.id == previousChapterId }
            if (oldIndex in 0 until newIndex) {
                val leftBehind = chapterList[oldIndex]
                if (
                    !leftBehind.chapter.read &&
                    leftBehind.chapter.last_page_read >= NOVEL_TRANSITION_READ_GUARD_PERCENT
                ) {
                    viewModelScope.launchNonCancellableIO {
                        markChapterReadOnTransition(leftBehind)
                    }
                }
            }
        }
        sessionReachedThreshold.remove(previousChapterId)
        chapterId = newChapterId

        val newChapters = ViewerChapters(
            readerChapter,
            if (newIndex >= 0) chapterList.getOrNull(newIndex - 1) else null,
            if (newIndex >= 0) chapterList.getOrNull(newIndex + 1) else null,
        )
        mutableState.update { state ->
            newChapters.ref()
            state.viewerChapters?.unref()
            state.copy(viewerChapters = newChapters)
        }
        flushReadTimer(activeChapter = readerChapter)
        restartReadTimer()

        eventChannel.trySend(Event.NovelVisibleChapterChanged(readerChapter))
        viewModelScope.launchIO { preload(readerChapter) }
    }

    private suspend fun markChapterReadOnTransition(readerChapter: ReaderChapter) {
        val incognito = preferences.incognitoMode().get()
        val shouldTrack = !incognito || hasTrackers
        if (!shouldTrack) return
        val id = readerChapter.chapter.id ?: return
        Logger.d { "markChapterReadOnTransition chapter=$id (forward transition)" }
        onChapterReadComplete(readerChapter)
        updateChapter.await(
            ChapterUpdate(
                id = id,
                read = true,
                bookmark = readerChapter.chapter.bookmark,
                lastPageRead = readerChapter.chapter.last_page_read.toLong(),
                pagesLeft = 0L,
            )
        )
    }

    /**
     * Prepares the next chapter for seamless infinite-scroll appending.
     * Loads pages for the chapter immediately after [anchor] in [chapterList] without flipping
     * state to it, so the user keeps reading [anchor] while the next chapter is spliced in by
     * the viewer. Returns the prepared [ReaderChapter], or null if there is no next chapter
     * (or pages could not be loaded).
     */
    suspend fun prepareNextChapterForInfiniteScroll(
        anchor: ReaderChapter,
    ): ReaderChapter? {
        if (!::chapterList.isInitialized) return null
        val loader = loader ?: return null
        val anchorIndex = chapterList.indexOf(anchor).takeIf { it >= 0 } ?: return null
        val next = chapterList.getOrNull(anchorIndex + 1) ?: return null
        return try {
            withIOContext { loader.loadChapter(next, page = 0) }
            next
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Logger.e(e) { "Failed to prepare next chapter for infinite scroll" }
            null
        }
    }

    /**
     * Mirror of [prepareNextChapterForInfiniteScroll] for backward (prepend) infinite scroll.
     */
    suspend fun preparePreviousChapterForInfiniteScroll(
        anchor: ReaderChapter,
    ): ReaderChapter? {
        if (!::chapterList.isInitialized) return null
        val loader = loader ?: return null
        val anchorIndex = chapterList.indexOf(anchor).takeIf { it >= 0 } ?: return null
        val prev = chapterList.getOrNull(anchorIndex - 1) ?: return null
        return try {
            withIOContext { loader.loadChapter(prev, page = 0) }
            prev
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Logger.e(e) { "Failed to prepare previous chapter for infinite scroll" }
            null
        }
    }

    /**
     * Edit-mode hook from Tsundoku's WebView novel viewer. Edit mode is out of scope for
     * the initial Hayai port; this is a no-op stub so the viewer compiles unchanged.
     */
    fun setHasUnsavedChanges(unsaved: Boolean) {
        // No-op — chapter editing is a Tsundoku-only feature, unimplemented here.
    }

    /**
     * Edit-mode hook from Tsundoku's WebView novel viewer. Edit mode is out of scope for
     * the initial Hayai port; this is a no-op stub so the viewer compiles unchanged.
     */
    fun saveEditedChapterContent(json: String) {
        // No-op — chapter editing is a Tsundoku-only feature, unimplemented here.
    }

    /**
     * Reload-from-source hook used by Tsundoku's WebView edit mode.
     * Edit mode is out of scope for the initial Hayai port; this no-ops.
     */
    fun reloadChapter(fromSource: Boolean) {
        // No-op — full chapter-reload reuses [setChapters] in Hayai's flow.
    }

    private fun downloadNextChapters() {
        val manga = manga ?: return
        viewModelScope.launchNonCancellableIO {
            if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return@launchNonCancellableIO
            val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return@launchNonCancellableIO
            val chaptersNumberToDownload = preferences.autoDownloadWhileReading().get()
            if (chaptersNumberToDownload == 0 || !manga.favorite) return@launchNonCancellableIO
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(nextChapter, manga)
            if (isNextChapterDownloaded) {
                downloadAutoNextChapters(chaptersNumberToDownload, nextChapter.id)
            }
        }
    }

    private suspend fun downloadAutoNextChapters(choice: Int, nextChapterId: Long?) {
        val chaptersToDownload = getNextUnreadChaptersSorted(nextChapterId).take(choice - 1)
        if (chaptersToDownload.isNotEmpty()) {
            downloadChapters(chaptersToDownload)
        }
    }

    private suspend fun getNextUnreadChaptersSorted(nextChapterId: Long?): List<ChapterItem> {
        val chapterSort = ChapterSort(manga!!, chapterFilter, preferences)
        return chapterList.map { ChapterItem(it.chapter, manga!!) }
            .filter { !it.read || it.id == nextChapterId }
            .sortedWith(chapterSort.sortComparator(true))
            .takeLastWhile { it.id != nextChapterId }
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    private fun downloadChapters(chapters: List<ChapterItem>) {
        downloadManager.downloadChapters(manga!!, chapters.filter { !it.isDownloaded })
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun deleteChapterFromDownloadQueue(currentChapter: ReaderChapter): Download? {
        return downloadManager.getChapterDownloadOrNull(currentChapter.chapter)?.apply {
            downloadManager.deletePendingDownloads(this)
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        viewModelScope.launchNonCancellableIO {
            // Determine which chapter should be deleted and enqueue
            val currentChapterPosition = chapterList.indexOf(currentChapter)
            val removeAfterReadSlots = preferences.removeAfterReadSlots().get()
            val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

            if (removeAfterReadSlots != 0 && chapterToDownload != null) {
                downloadManager.addDownloadsToStartOfQueue(listOf(chapterToDownload!!))
            } else {
                chapterToDownload = null
            }
            // Check if deleting option is enabled and chapter exists
            if (removeAfterReadSlots != -1 && chapterToDelete != null) {
                val excludedCategories = preferences.removeExcludeCategories().get().map(String::toInt)
                if (excludedCategories.any()) {
                    val categories = getCategories.awaitByMangaId(manga!!.id!!)
                        .mapNotNull { it.id }
                        .ifEmpty { listOf(0) }

                    if (categories.any { it in excludedCategories }) return@launchNonCancellableIO
                }

                enqueueDeleteReadChapters(chapterToDelete)
            }
        }
    }

    /**
     * Saves this [readerChapter]'s progress (last read page and whether it's read).
     * If incognito mode isn't on or has at least 1 tracker
     *
     * [sessionScrollAdvance] — true for live scroll callbacks (the only path that may
     * mark a chapter read); false for teardown flushes (pause/stop/destroy) so the
     * read flag never flips during exit.
     */
    private suspend fun saveChapterProgress(
        readerChapter: ReaderChapter,
        page: ReaderPage,
        hasExtraPage: Boolean,
        sessionScrollAdvance: Boolean = true,
    ) {
        val isTextSource = source is TextSource
        val chapterIdLog = readerChapter.chapter.id
        val incognito = preferences.incognitoMode().get()
        val shouldTrack = !incognito || hasTrackers
        val progressPercentLog = readerChapter.chapter.last_page_read.coerceIn(0, 100)
        val readBefore = readerChapter.chapter.read
        Logger.d {
            "saveChapterProgress chapter=$chapterIdLog progress=$progressPercentLog read=$readBefore " +
                "incognito=$incognito hasTrackers=$hasTrackers shouldTrack=$shouldTrack " +
                "sessionAdvance=$sessionScrollAdvance isText=$isTextSource"
        }
        // Use the page index we're saving, not the chapter's *previous* last_page_read.
        // PagerViewer.setChaptersDoubleShift calls moveToPage twice — once via
        // setChaptersInternal (first layout) and once in its own !hasMoved block — and
        // the first moveToPage triggers onPageSelected → here on Dispatchers.IO. If we
        // overwrite requestedPage with the stale last_page_read value before the second
        // moveToPage reads it back on the UI thread, the second navigation snaps back
        // to that stale page (typically 0), undoing a page-preview launch.
        readerChapter.requestedPage = if (isTextSource) 0 else page.index
        getChapter.awaitById(readerChapter.chapter.id!!)?.let { dbChapter ->
            readerChapter.chapter.bookmark = dbChapter.bookmark
        }

        if (shouldTrack && page.status !is Page.State.Error) {
            if (isTextSource) {
                val progressPercent = readerChapter.chapter.last_page_read.coerceIn(0, 100)
                readerChapter.chapter.last_page_read = progressPercent
                readerChapter.chapter.pages_left = (100 - progressPercent).coerceIn(0, 100)

                val chapterId = readerChapter.chapter.id ?: -1L
                // Mark-read threshold is the user's novelMarkAsReadThreshold pref (a 0..100
                // percentage; ported verbatim from tsundoku). A legacy 0 falls back to 98.
                val markReadThreshold = readerPreferences.novelMarkAsReadThreshold.get()
                    .let { if (it <= 0) 98 else it }
                if (
                    sessionScrollAdvance &&
                    progressPercent >= markReadThreshold
                ) {
                    sessionReachedThreshold[chapterId] = true
                }
                val reachedThisSession = sessionReachedThreshold[chapterId] == true
                Logger.d {
                    "saveChapterProgress text gate chapter=$chapterId reachedThisSession=$reachedThisSession " +
                        "sessionAdvance=$sessionScrollAdvance progress=$progressPercent threshold=$markReadThreshold"
                }
                if (
                    !readerChapter.chapter.read &&
                    sessionScrollAdvance &&
                    reachedThisSession &&
                    progressPercent >= markReadThreshold
                ) {
                    Logger.d { "saveChapterProgress onChapterReadComplete fired chapter=$chapterId" }
                    onChapterReadComplete(readerChapter)
                }
            } else {
                readerChapter.chapter.last_page_read = page.index
                readerChapter.chapter.pages_left = (readerChapter.pages?.size ?: page.index) - page.index
                // For double pages, check if the second to last page is doubled up
                if (
                    (readerChapter.pages?.lastIndex == page.index && page.firstHalf != true) ||
                    (hasExtraPage && readerChapter.pages?.lastIndex?.minus(1) == page.index)
                ) {
                    onChapterReadComplete(readerChapter)
                }
            }

            updateChapter.await(
                ChapterUpdate(
                    id = readerChapter.chapter.id!!,
                    read = readerChapter.chapter.read,
                    bookmark = readerChapter.chapter.bookmark,
                    lastPageRead = readerChapter.chapter.last_page_read.toLong(),
                    pagesLeft = readerChapter.chapter.pages_left.toLong(),
                )
            )
        }
    }

    private suspend fun onChapterReadComplete(readerChapter: ReaderChapter) {
        readerChapter.chapter.read = true
        recordNovelWordCount(readerChapter)
        updateTrackChapterAfterReading(readerChapter)
        deleteChapterIfNeeded(readerChapter)

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead().get()
            .contains(LibraryPreferences.MARK_DUPLICATE_READ_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return

        val duplicateUnreadChapters = unfilteredChapterList
            .mapNotNull { chapter ->
                if (
                    !chapter.read &&
                    chapter.isRecognizedNumber &&
                    chapter.chapter_number == readerChapter.chapter.chapter_number
                ) {
                    ChapterUpdate(id = chapter.id!!, read = true)
                } else {
                    null
                }
            }
        updateChapter.awaitAll(duplicateUnreadChapters)
    }

    /**
     * Persists the word count of a just-read novel chapter for reading stats. Counted once here,
     * off-main-thread, from the already-loaded chapter text (no re-fetch, no re-read on render);
     * stats later SUM the recorded values via SQL. No-op for image sources or unloaded text.
     */
    private suspend fun recordNovelWordCount(readerChapter: ReaderChapter) {
        if (source !is TextSource) return
        val chapterId = readerChapter.chapter.id ?: return
        val html = readerChapter.pages?.firstOrNull()?.text ?: return
        if (html.isBlank()) return
        val wordCount = countWords(html)
        if (wordCount <= 0) return
        setChapterWordCount.await(chapterId, wordCount.toLong())
    }

    /** Strips HTML tags/entities then counts whitespace-delimited tokens. */
    private fun countWords(html: String): Int {
        val text = html
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&[a-zA-Z]+;|&#\\d+;"), " ")
        var count = 0
        var inWord = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }

    fun restartReadTimer() {
        chapterReadStartTime = Date().time
    }

    /**
     * Writes a history row for the currently-visible chapter.
     *
     * For novels, callers can pass [activeChapter] explicitly when the writer knows the
     * visible chapter (e.g. mid-transition) — this bypasses the `chapterId`-based
     * resolution so we never write to a stale chapter while state propagates.
     *
     * For manga, [activeChapter] is null and we fall back to `getCurrentChapter()`, which
     * resolves via `chapterId` kept in sync by `onPageSelected`.
     */
    fun flushReadTimer(activeChapter: ReaderChapter? = null) {
        val chapter = activeChapter
            ?: state.value.viewerChapters?.currChapter?.takeIf { source is TextSource }
            ?: getCurrentChapter()
            ?: return
        viewModelScope.launchNonCancellableIO {
            saveChapterHistory(chapter)
        }
    }

    /**
     * Saves this [readerChapter] last read history.
     */
    private suspend fun saveChapterHistory(readerChapter: ReaderChapter) {
        if (preferences.incognitoMode().get()) return

        val endTime = Date().time
        val sessionReadDuration = chapterReadStartTime?.let { endTime - it } ?: 0
        val history = History.create(readerChapter.chapter).apply {
            last_read = endTime
            time_read = sessionReadDuration
        }
        upsertHistory.await(history)
        chapterReadStartTime = null
    }

    /**
     * Called from the activity to preload the given [chapter].
     */
    suspend fun preloadChapter(chapter: ReaderChapter) {
        preload(chapter)
    }

    /**
     * Returns the currently active chapter.
     */
    fun getCurrentChapter(): ReaderChapter? {
        val current = state.value.viewerChapters?.currChapter
        val currentId = chapterId.takeIf { it != -1L } ?: return current
        if (current?.chapter?.id == currentId) return current
        if (!::chapterList.isInitialized) return current
        return chapterList.firstOrNull { it.chapter.id == currentId } ?: current
    }

    fun getChapterUrl(mainChapter: Chapter? = null): String? {
        val manga = manga ?: return null
        val source = source ?: return null
        val chapter = mainChapter ?: getCurrentChapter()?.chapter ?: return null
        val chapterUrl = try { source.getChapterUrl(chapter) } catch (_: Exception) { null }
        return chapterUrl.takeIf { !it.isNullOrBlank() }
            ?: try { source.getChapterUrl(manga, chapter) } catch (_: Exception) { null }
    }

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaReadingMode(): Int {
        val default = preferences.defaultReadingMode().get()
        val manga = manga ?: return default

        // viewer_flags == -1 is the "never opened" sentinel; with all bits set, masked
        // accessors return their MASK (orientationType → REVERSE_LANDSCAPE), so normalise
        // before any early-return.
        val firstOpen = manga.viewer_flags == -1
        if (firstOpen) {
            manga.viewer_flags = 0
        }

        // NOVEL -->
        // Auto-detect novel sources and force novel reading mode
        val source = sourceManager.get(manga.source)
        if (source is hayai.novel.source.TextSource) {
            if (firstOpen) {
                viewModelScope.launchIO { updateManga.await(MangaUpdate(manga.id!!, viewerFlags = manga.viewer_flags)) }
            }
            return ReadingModeType.NOVEL.flagValue
        }
        // NOVEL <--

        val readerType = manga.defaultReaderType()
        if (firstOpen) {
            val cantSwitchToLTR =
                (
                    readerType == ReadingModeType.LEFT_TO_RIGHT.flagValue &&
                        default != ReadingModeType.RIGHT_TO_LEFT.flagValue
                    )
            manga.readingModeType = if (cantSwitchToLTR) 0 else readerType
            viewModelScope.launchIO { updateManga.await(MangaUpdate(manga.id!!, viewerFlags = manga.viewer_flags)) }
        }
        return if (manga.readingModeType == 0) default else manga.readingModeType
    }

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingModeType: Int) {
        val manga = manga ?: return

        viewModelScope.launchIO {
            manga.readingModeType = readingModeType
            updateManga.await(MangaUpdate(manga.id!!, viewerFlags = manga.viewer_flags))
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = if (source is TextSource) 0 else currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.awaitById(manga.id!!),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.ReloadMangaAndChapters)
            }
        }
    }

    /**
     * Returns the orientation type used by this manga/novel or the type-appropriate default.
     *
     * Novels and manga share the per-series override (`Manga.orientationType` packed into
     * `viewer_flags`), but each picks a different fallback preference so users can run e.g.
     * locked-portrait for novels while leaving manga on free rotation.
     */
    fun getMangaOrientationType(): Int {
        val default = if (manga?.isNovel() == true) {
            readerPreferences.novelDefaultOrientationType.get()
        } else {
            preferences.defaultOrientationType().get()
        }
        return when (manga?.orientationType) {
            OrientationType.DEFAULT.flagValue -> default
            else -> manga?.orientationType ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(rotationType: Int) {
        val manga = manga ?: return
        this.manga?.orientationType = rotationType

        Logger.i { "Manga orientation is ${manga.orientationType}" }

        viewModelScope.launchIO {
            updateManga.await(MangaUpdate(manga.id!!, viewerFlags = manga.viewer_flags))
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                mutableState.update {
                    it.copy(
                        manga = getManga.awaitById(manga.id!!),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.SetOrientation(getMangaOrientationType()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    /**
     * Saves the image of this [page] in the given [directory] and returns the file location.
     */
    private fun saveImage(page: ReaderPage, directory: UniFile, manga: Manga): UniFile {
        val stream = page.stream!!
        val type = ImageUtil.findImageType(stream) ?: throw Exception("Not an image")
        val context = get<Application>()

        val chapter = page.chapter.chapter

        // Build destination file.
        val filename = DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.preferredChapterName(context, manga, preferences)}".take(225),
        ) + (if (downloadPreferences.downloadWithId().get()) " (${chapter.id})" else "") + " - ${page.number}.${type.extension}"

        val destFile = directory.createFile(filename)!!
        stream().use { input ->
            destFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destFile
    }

    /**
     * Saves the image of [page1] and [page2] in the given [directory] and returns the file location.
     */
    private fun saveImages(page1: ReaderPage, page2: ReaderPage, isLTR: Boolean, @ColorInt bg: Int, directory: UniFile, manga: Manga): UniFile {
        val stream1 = page1.stream!!
        ImageUtil.findImageType(stream1) ?: throw Exception("Not an image")
        val stream2 = page2.stream!!
        ImageUtil.findImageType(stream2) ?: throw Exception("Not an image")
        val imageBytes = stream1().readBytes()
        val imageBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        val imageBytes2 = stream2().readBytes()
        val imageBitmap2 = BitmapFactory.decodeByteArray(imageBytes2, 0, imageBytes2.size)

        val stream = ImageUtil.mergeBitmaps(imageBitmap, imageBitmap2, isLTR, bg).inputStream()

        val chapter = page1.chapter.chapter
        val context = get<Application>()

        // Build destination file.
        val filename = DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.preferredChapterName(context, manga, preferences)}".take(225),
        ) + (if (downloadPreferences.downloadWithId().get()) " (${chapter.id})" else "") + " - ${page1.number}-${page2.number}.jpg"

        val destFile = directory.createFile(filename)!!
        stream.use { input ->
            destFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        stream.close()
        return destFile
    }

    /**
     * Saves the image of this [page] on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage(page: ReaderPage) {
        if (page.status !is Page.State.Ready) return
        val manga = manga ?: return
        val context = get<Application>()

        // Pictures directory.
        val baseDir = storageManager.getPagesDirectory() ?: return
        val destDir = if (preferences.folderPerManga().get()) {
            baseDir.createDirectory(DiskUtil.buildValidFilename(manga.title))
        } else {
            baseDir
        } ?: return

        val notifier = SaveImageNotifier(context.localeContext)
        notifier.onClear()

        // Copy file in background.
        viewModelScope.launchNonCancellableIO {
            try {
                val file = saveImage(page, destDir, manga)
                DiskUtil.scanMedia(context, file)
                notifier.onComplete(file)
                eventChannel.send(Event.SavedImage(SaveImageResult.Success(file)))
            } catch (e: Exception) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    fun saveImages(firstPage: ReaderPage, secondPage: ReaderPage, isLTR: Boolean, @ColorInt bg: Int) {
        viewModelScope.launch {
            if (firstPage.status !is Page.State.Ready) return@launch
            if (secondPage.status !is Page.State.Ready) return@launch
            val manga = manga ?: return@launch
            val context = get<Application>()

            // Pictures directory.
            val baseDir = storageManager.getPagesDirectory() ?: return@launch
            val destDir = if (preferences.folderPerManga().get()) {
                baseDir.findFile(DiskUtil.buildValidFilename(manga.title))
            } else {
                baseDir
            } ?: return@launch

            val notifier = SaveImageNotifier(context.localeContext)
            notifier.onClear()

            try {
                val file = saveImages(firstPage, secondPage, isLTR, bg, destDir, manga)
                DiskUtil.scanMedia(context, file)
                notifier.onComplete(file)
                eventChannel.send(Event.SavedImage(SaveImageResult.Success(file)))
            } catch (e: Exception) {
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    /**
     * Shares the image of this [page] and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompresssed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(page: ReaderPage) {
        if (page.status !is Page.State.Ready) return
        val manga = manga ?: return
        val context = get<Application>()

        val destDir = UniFile.fromFile(context.cacheDir)!!.createDirectory("shared_image")!!

        viewModelScope.launchNonCancellableIO {
            val file = saveImage(page, destDir, manga)
            eventChannel.send(Event.ShareImage(file, page))
        }
    }

    fun shareImages(firstPage: ReaderPage, secondPage: ReaderPage, isLTR: Boolean, @ColorInt bg: Int) {
        viewModelScope.launch {
            if (firstPage.status !is Page.State.Ready) return@launch
            if (secondPage.status !is Page.State.Ready) return@launch
            val manga = manga ?: return@launch
            val context = get<Application>()

            try {
                val destDir = UniFile.fromFile(context.cacheDir)!!.findFile("shared_image")!!
                val file = saveImages(firstPage, secondPage, isLTR, bg, destDir, manga)
                eventChannel.send(Event.ShareImage(file, firstPage, secondPage))
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Sets the image of this [page] as cover and notifies the UI of the result.
     */
    fun setAsCover(page: ReaderPage) {
        if (page.status !is Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellableIO {
            val result = try {
                if (manga.isLocal()) {
                    coverCache.deleteFromCache(manga)
                    LocalSource.updateCover(manga, stream())
                    manga.updateCoverLastModified()
                    MR.strings.cover_updated
                    SetAsCoverResult.Success
                } else {
                    if (manga.favorite) {
                        coverCache.setCustomCoverToCache(manga, stream())
                        manga.updateCoverLastModified()
                        SetAsCoverResult.Success
                    } else {
                        SetAsCoverResult.AddToLibraryFirst
                    }
                }
            } catch (e: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    /**
     * Results of the set as cover feature.
     */
    enum class SetAsCoverResult {
        Success, AddToLibraryFirst, Error
    }

    /**
     * Results of the save image feature.
     */
    sealed class SaveImageResult {
        class Success(val file: UniFile) : SaveImageResult()
        class Error(val error: Throwable) : SaveImageResult()
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterAfterReading(readerChapter: ReaderChapter) {
        if (!preferences.autoUpdateTrack().get()) return

        launchIO {
            val newChapterRead = readerChapter.chapter.chapter_number
            val errors = updateTrackChapterRead(preferences, manga?.id, newChapterRead, true)
            if (errors.isNotEmpty()) {
                eventChannel.send(Event.ShareTrackingError(errors))
            }
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        val manga = manga ?: return

        viewModelScope.launchNonCancellableIO {
            downloadManager.enqueueDeleteChapters(listOf(chapter.chapter), manga)
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellableIO {
            downloadManager.deletePendingChapters()
        }
    }

    data class State(
        val manga: Manga? = null,
        val viewerChapters: ViewerChapters? = null,
        val isLoadingAdjacentChapter: Boolean = false,
        val lastPage: Int? = null,
    )

    sealed class Event {
        object ReloadViewerChapters : Event()
        object ReloadMangaAndChapters : Event()
        data class SetOrientation(val orientation: Int) : Event()
        data class SetCoverResult(val result: SetAsCoverResult) : Event()
        data class NovelVisibleChapterChanged(val chapter: ReaderChapter) : Event()
        /**
         * Emitted from [init] when [SourceManager.awaitSource] times out without resolving the
         * manga's source. The activity surfaces this as an inline retry overlay so the user
         * can re-trigger init once the plugin manager has finished its background load
         * (issue #9). [sourceId] is included for diagnostics / future error-text hooks.
         */
        data class SourceNotReady(val sourceId: Long) : Event()

        data class SavedImage(val result: SaveImageResult) : Event()
        data class ShareImage(val file: UniFile, val page: ReaderPage, val extraPage: ReaderPage? = null) : Event()
        data class ShareTrackingError(val errors: List<Pair<TrackService, String?>>) : Event()
    }

    companion object {
        /**
         * How long the reader will wait for the (novel) plugin manager to finish its initial
         * load before giving up and surfacing [Event.SourceNotReady]. Manga sources resolve
         * synchronously so this only kicks in on novel cold start / process restore. Tuned
         * to 5s based on user reports that ~700ms per plugin is the QuickJS cost on cold
         * boot; 5s covers ~7 installed plugins comfortably.
         */
        private const val SOURCE_AWAIT_TIMEOUT_MS = 5_000L
    }
}

/**
 * Marker exception thrown out of [ReaderViewModel.init] when the source couldn't be
 * resolved before the await timeout. The activity uses [Event.SourceNotReady] for the UI
 * (overlay + retry); this exception just keeps the Result.failure branch typed so the
 * activity-side init error handler can skip the generic "Unknown error" toast / finish().
 */
class SourceNotReadyException(val sourceId: Long) : Exception("Source $sourceId not ready")

// Forward-transition guard: minimum scroll % the user must have reached on a chapter
// before the act of moving past it counts as "read it". Lower than the last-chapter
// fallback because the transition itself is the strong "I'm done" signal — the
// percent is just a sanity check against tap-to-peek navigation from low %.
private const val NOVEL_TRANSITION_READ_GUARD_PERCENT = 70

// Last-chapter fallback: when there's no chapter after this one, transition-based
// mark-read can't fire, so fall back to a high % threshold. Kept conservative
// because this is the only auto signal available for the very last chapter — users
// who want it sooner can mark manually from the chapter list.
private const val NOVEL_LAST_CHAPTER_READ_THRESHOLD_PERCENT = 100
