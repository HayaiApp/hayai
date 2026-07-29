package eu.kanade.tachiyomi.source.online.all

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Response
import yokai.util.koin.injectLazy
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.GetMergedReferencesById
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MergedMangaReference

class MergedSource : HttpSource() {
    private val getManga: GetManga by injectLazy()
    private val getMergedReferencesById: GetMergedReferencesById by injectLazy()
    private val insertManga: InsertManga by injectLazy()
    private val updateManga: UpdateManga by injectLazy()
    private val sourceManager: SourceManager by injectLazy()

    private val logger = Logger.withTag("MergedSource")

    override val id: Long = MERGED_SOURCE_ID

    override val baseUrl = ""

    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ) = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()
    override fun chapterPageParse(response: Response) = throw UnsupportedOperationException()
    override fun pageListParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    @Deprecated("Use the 1.x API instead", replaceWith = ReplaceWith("getChapterList"))
    override fun fetchChapterList(manga: SManga) = throw UnsupportedOperationException()
    override suspend fun getChapterList(manga: SManga) = throw UnsupportedOperationException()
    override suspend fun getImage(page: Page, existingSize: Long): Response = throw UnsupportedOperationException()

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getImageUrl"))
    override fun fetchImageUrl(page: Page) = throw UnsupportedOperationException()
    override suspend fun getImageUrl(page: Page) = throw UnsupportedOperationException()

    @Deprecated("Use the 1.x API instead", replaceWith = ReplaceWith("getPageList"))
    override fun fetchPageList(chapter: SChapter) = throw UnsupportedOperationException()
    override suspend fun getPageList(chapter: SChapter) = throw UnsupportedOperationException()

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int) = throw UnsupportedOperationException()
    override suspend fun getLatestUpdates(page: Int) = throw UnsupportedOperationException()

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPopularManga"))
    override fun fetchPopularManga(page: Int) = throw UnsupportedOperationException()
    override suspend fun getPopularManga(page: Int) = throw UnsupportedOperationException()

    override suspend fun getMangaDetails(manga: SManga): SManga {
        return withContext(Dispatchers.IO) {
            val mergedManga = requireNotNull(getManga.awaitByUrlAndSource(manga.url, id)) { "merged manga not in db" }
            val mangaReferences = getMergedReferencesById.await(mergedManga.id!!)
                .apply {
                    require(isNotEmpty()) { "Manga references are empty, info unavailable, merge is likely corrupted" }
                    require(!(size == 1 && first().mangaSourceId == MERGED_SOURCE_ID)) {
                        "Manga references contain only the merged reference, merge is likely corrupted"
                    }
                }

            val mangaInfoReference = mangaReferences.firstOrNull { it.isInfoManga }
                ?: mangaReferences.firstOrNull { it.mangaId != it.mergeId }
            val dbManga: Manga? = mangaInfoReference?.run {
                getManga.awaitByUrlAndSource(mangaUrl, mangaSourceId)
            }
            // The old Manga interface extends SManga, so we can cast directly
            val result: SManga = dbManga ?: mergedManga
            SManga.create().apply {
                url = manga.url
                title = result.title
                artist = result.artist
                author = result.author
                description = result.description
                genre = result.genre
                status = result.status
                thumbnail_url = result.thumbnail_url
                initialized = result.initialized
            }
        }
    }

    suspend fun fetchChaptersForMergedManga(manga: Manga) {
        fetchChaptersAndSync(manga)
    }

    suspend fun fetchChaptersAndSync(manga: Manga): List<Chapter> {
        val mangaReferences = getMergedReferencesById.await(manga.id!!)
        require(mangaReferences.isNotEmpty()) {
            "Manga references are empty, chapters unavailable, merge is likely corrupted"
        }

        val semaphore = Semaphore(5)
        var exception: Exception? = null
        return supervisorScope {
            mangaReferences
                .groupBy(MergedMangaReference::mangaSourceId)
                .minus(MERGED_SOURCE_ID)
                .map { (_, values) ->
                    async {
                        semaphore.withPermit {
                            values.flatMap { ref ->
                                try {
                                    val (source, loadedManga, reference) = ref.load()
                                    if (loadedManga != null && reference.getChapterUpdates) {
                                        val chapterList = source.getChapterList(loadedManga)
                                        val (added, _) = syncChaptersWithSource(chapterList, loadedManga, source)
                                        added
                                    } else {
                                        emptyList()
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    logger.e(e) { "Error fetching chapters for merged reference" }
                                    exception = e
                                    emptyList()
                                }
                            }
                        }
                    }
                }
                .awaitAll()
                .flatten()
        }.also {
            exception?.let { throw it }
        }
    }

    private suspend fun MergedMangaReference.load(): LoadedMangaSource {
        var manga = getManga.awaitByUrlAndSource(mangaUrl, mangaSourceId)
        val source = sourceManager.getOrStub(manga?.source ?: mangaSourceId)
        if (manga == null) {
            val newManga = Manga.create(mangaUrl, "", mangaSourceId)
            newManga.id = insertManga.await(newManga)
            val sMangaDetails = source.getMangaDetails(SManga.create().apply {
                url = mangaUrl
                title = ""
            })
            manga = getManga.awaitByUrlAndSource(mangaUrl, mangaSourceId)
            if (manga != null) {
                manga.copyFrom(sMangaDetails)
                manga.initialized = true
                updateManga.await(manga.toMangaUpdate())
                manga = getManga.awaitById(manga.id!!)
            }
        }
        return LoadedMangaSource(source, manga, this)
    }

    data class LoadedMangaSource(
        val source: Source,
        val manga: Manga?,
        val reference: MergedMangaReference,
    )

    override val lang = "all"
    override val supportsLatest = false
    override val name = "MergedSource"
}
