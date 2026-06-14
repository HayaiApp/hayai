package exh.recs.batch

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.annotation.StringRes
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import exh.log.xLog
import exh.recs.sources.NoResultsException
import exh.recs.sources.RecommendationPagingSource
import exh.recs.sources.TrackerRecommendationPagingSource
import exh.source.ExhPreferences
import exh.util.ThrottleManager
import exh.util.createPartialWakeLock
import exh.util.createWifiLock
import exh.util.ignore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import yokai.domain.manga.interactor.GetLibraryManga
import eu.kanade.tachiyomi.domain.manga.models.Manga
import yokai.util.koin.injectLazy
import java.io.Serializable
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RecommendationSearchHelper(val context: Context) {
    private val getLibraryManga: GetLibraryManga by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private val exhPreferences: ExhPreferences by injectLazy()

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val logger by lazy { xLog() }

    val status: MutableStateFlow<SearchStatus> = MutableStateFlow(SearchStatus.Idle)

    var searchFlags: Int
        get() = exhPreferences.recommendationSearchFlags.get()
        set(value) { exhPreferences.recommendationSearchFlags.set(value) }

    @Synchronized
    fun runSearch(scope: CoroutineScope, mangaList: List<Manga>): Job? {
        if (status.value !is SearchStatus.Idle) {
            return null
        }

        status.value = SearchStatus.Initializing

        return scope.launch(Dispatchers.IO) { beginSearch(mangaList) }
    }

    private suspend fun beginSearch(mangaList: List<Manga>) {
        val flags = searchFlags
        val libraryManga = getLibraryManga.await()

        // Trackers such as MAL need to be throttled more strictly
        val stricterThrottling = SearchFlags.hasIncludeTrackers(flags)

        val throttleManager =
            ThrottleManager(
                max = 3.seconds,
                inc = 50.milliseconds,
                initial = if (stricterThrottling) 2.seconds else 0.seconds,
            )

        try {
            // Take wake + wifi locks
            ignore { wakeLock?.release() }
            wakeLock = ignore { context.createPartialWakeLock("hayai:RecommendationSearchWakelock") }
            ignore { wifiLock?.release() }
            wifiLock = ignore { context.createWifiLock("hayai:RecommendationSearchWifiLock") }

            // Map of results grouped by recommendation source
            val resultsMap = java.util.Collections.synchronizedMap(mutableMapOf<String, SearchResults>())

            mangaList.forEachIndexed { index, sourceManga ->
                // Check if the job has been cancelled
                coroutineContext.ensureActive()

                val sManga = SManga.create().also {
                    it.url = sourceManga.url
                    it.title = sourceManga.title
                    it.thumbnail_url = sourceManga.thumbnail_url
                }
                status.value = SearchStatus.Processing(sManga, index + 1, mangaList.size)

                val source = sourceManager.get(sourceManga.source) as? CatalogueSource ?: return@forEachIndexed

                val jobs = RecommendationPagingSource.createSources(
                    sourceManga,
                    source,
                ).mapNotNull { recSource ->
                    // Apply source filters
                    if (recSource is TrackerRecommendationPagingSource && !SearchFlags.hasIncludeTrackers(flags)) {
                        return@mapNotNull null
                    }

                    if (recSource.associatedSourceId != null && !SearchFlags.hasIncludeSources(flags)) {
                        return@mapNotNull null
                    }

                    // Parallelize fetching recommendations from all sources in the current context
                    CoroutineScope(coroutineContext).async(Dispatchers.IO) {
                        val recSourceId = recSource::class.qualifiedName!!

                        try {
                            val page = recSource.requestNextPage(1)

                            val mangas = if (SearchFlags.hasHideLibraryResults(flags)) {
                                page.mangas.filterNot { manga ->
                                    libraryManga.any { lib ->
                                        lib.manga.title.equals(manga.title, ignoreCase = true)
                                    }
                                }
                            } else {
                                page.mangas
                            }

                            // Add or update the result collection for the current source
                            resultsMap.getOrPut(recSourceId) {
                                SearchResults(
                                    recSourceName = recSource.name,
                                    recSourceCategoryResId = recSource.category.resourceId,
                                    recAssociatedSourceId = recSource.associatedSourceId,
                                    results = mutableListOf(),
                                )
                            }.results.addAll(mangas)
                        } catch (_: NoResultsException) {
                        } catch (e: Exception) {
                            logger.e(e) { "Error while fetching recommendations for $recSourceId" }
                        }
                    }
                }
                jobs.awaitAll()

                // Continuously slow down the search to avoid hitting rate limits
                throttleManager.throttle()
            }

            val rankedMap = resultsMap.map {
                RankedSearchResults(
                    recSourceName = it.value.recSourceName,
                    recSourceCategoryResId = it.value.recSourceCategoryResId,
                    recAssociatedSourceId = it.value.recAssociatedSourceId,
                    results = it.value.results
                        // Group by URL and count occurrences
                        .groupingBy(SManga::url)
                        .eachCount()
                        .entries
                        // Sort by occurrences desc
                        .sortedByDescending(Map.Entry<String, Int>::value)
                        // Resolve SManga instances from URL keys
                        .associate { (url, count) ->
                            val manga = it.value.results.first { m -> m.url == url }
                            manga to count
                        },
                )
            }

            status.value = when {
                rankedMap.isNotEmpty() -> SearchStatus.Finished.WithResults(rankedMap)
                else -> SearchStatus.Finished.WithoutResults
            }
        } catch (_: CancellationException) {
        } catch (e: Exception) {
            status.value = SearchStatus.Error(e.message.orEmpty())
            logger.e(e) { "Error during recommendation search" }
            return
        } finally {
            // Release wake + wifi locks
            ignore {
                wakeLock?.release()
                wakeLock = null
            }
            ignore {
                wifiLock?.release()
                wifiLock = null
            }
        }
    }
}

// Contains the search results for a single source
private typealias SearchResults = Results<MutableList<SManga>>

// Contains the ranked search results for a single source
typealias RankedSearchResults = Results<Map<SManga, Int>>

data class Results<T>(
    val recSourceName: String,
    @field:StringRes val recSourceCategoryResId: Int,
    val recAssociatedSourceId: Long?,
    val results: T,
) : Serializable

sealed interface SearchStatus {
    data object Idle : SearchStatus
    data object Initializing : SearchStatus
    data class Processing(val manga: SManga, val current: Int, val total: Int) : SearchStatus
    data class Error(val message: String) : SearchStatus
    data object Cancelling : SearchStatus

    sealed interface Finished : SearchStatus {
        data class WithResults(val results: List<RankedSearchResults>) : Finished
        data object WithoutResults : Finished
    }
}
