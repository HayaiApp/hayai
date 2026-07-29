package eu.kanade.tachiyomi.ui.source.browse

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.MetadataMangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A general pager for source requests (latest updates, popular, search)
 */
abstract class Pager(var currentPage: Int = 1) {

    var hasNextPage = true
        private set

    var nextCursor: Long? = null
        private set

    // A source response can complete before the presenter collector starts, especially for
    // cached responses and in-process novel sources. Keep the latest page so that result is not
    // silently discarded while the collector is being installed.
    protected val results = MutableSharedFlow<Pair<Int, List<SManga>>>(replay = 1)

    fun asFlow(): SharedFlow<Pair<Int, List<SManga>>> {
        return results.asSharedFlow()
    }

    abstract suspend fun requestNextPage()

    suspend fun onPageReceived(mangasPage: MangasPage) {
        val page = currentPage
        currentPage++
        if (mangasPage is MetadataMangasPage) {
            nextCursor = mangasPage.nextKey
        }
        hasNextPage = mangasPage.hasNextPage && mangasPage.mangas.isNotEmpty()
        results.emit(Pair(page, mangasPage.mangas))
    }
}
