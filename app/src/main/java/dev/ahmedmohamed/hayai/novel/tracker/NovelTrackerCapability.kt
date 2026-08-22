package dev.ahmedmohamed.hayai.novel.tracker

import android.app.Application
import eu.kanade.tachiyomi.R
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class NovelTrackerDescriptor(val serviceId: Long, val name: String, val novelNative: Boolean)

data class NovelTrackSearchResult(
    val remoteId: Long,
    val title: String,
    val url: String,
    val totalChapters: Int?,
)

data class NovelTrackState(
    val serviceId: Long,
    val remoteId: Long,
    val chapterRead: Float,
    val totalChapters: Int,
    val status: Int,
    val score: Float,
    val startedAt: Long?,
    val finishedAt: Long?,
)

interface J2kNovelTrackingAdapter {
    fun services(): List<NovelTrackerDescriptor>
    suspend fun search(serviceId: Long, query: String): List<NovelTrackSearchResult>
    suspend fun bind(mangaId: Long, serviceId: Long, result: NovelTrackSearchResult): NovelTrackState
    suspend fun update(mangaId: Long, state: NovelTrackState, setToRead: Boolean = false): NovelTrackState
    suspend fun remove(mangaId: Long, serviceId: Long)
}

class NovelTrackerCapability(
    private val adapter: J2kNovelTrackingAdapter,
    private val stringResolver: (Int) -> String = { Injekt.get<Application>().getString(it) },
) {
    fun available(): List<NovelTrackerDescriptor> =
        adapter.services().filter { it.novelNative || it.name.normalized() in NOVEL_SERVICES }

    suspend fun search(serviceId: Long, query: String): List<NovelTrackSearchResult> {
        require(available().any { it.serviceId == serviceId }) { stringResolver(R.string.hayai_tracker_unavailable) }
        require(query.isNotBlank() && query.length <= MAXIMUM_QUERY_LENGTH) {
            stringResolver(R.string.hayai_tracker_search_query_invalid)
        }
        return adapter.search(serviceId, query.trim()).distinctBy { it.remoteId }.take(MAXIMUM_RESULTS)
    }

    suspend fun bind(mangaId: Long, serviceId: Long, result: NovelTrackSearchResult): NovelTrackState {
        require(mangaId > 0 && available().any { it.serviceId == serviceId }) { stringResolver(R.string.hayai_tracker_unavailable) }
        return adapter.bind(mangaId, serviceId, result)
    }

    suspend fun updateProgress(mangaId: Long, state: NovelTrackState, chapter: Float): NovelTrackState {
        require(mangaId > 0 && chapter >= 0f) { stringResolver(R.string.hayai_tracker_progress_invalid) }
        return adapter.update(mangaId, state.copy(chapterRead = maxOf(state.chapterRead, chapter)), setToRead = true)
    }

    suspend fun remove(mangaId: Long, serviceId: Long) = adapter.remove(mangaId, serviceId)

    private fun String.normalized() = lowercase().replace(Regex("[^a-z0-9]"), "")

    private companion object {
        const val MAXIMUM_QUERY_LENGTH = 512
        const val MAXIMUM_RESULTS = 100
        val NOVEL_SERVICES = setOf("novelupdates", "novellist", "ranobedb", "anilist", "myanimelist", "kitsu")
    }
}
