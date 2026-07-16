package eu.kanade.tachiyomi.ui.setting.controllers.database

import yokai.util.koin.get
import eu.kanade.tachiyomi.data.database.models.SourceIdMangaCount
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.withUIContext
import yokai.data.DatabaseHandler

class ClearDatabasePresenter : BaseCoroutinePresenter<ClearDatabaseController>() {

    private val handler = get<DatabaseHandler>()

    private val sourceManager = get<SourceManager>()

    var sortBy = SortSources.ALPHA
        private set

    var hasStubSources = false

    enum class SortSources {
        ALPHA,
        MOST_ENTRIES,
    }

    override fun onCreate() {
        super.onCreate()
        presenterScope.launchIO {
            getDatabaseSources()
        }
    }

    fun clearDatabaseForSourceIds(sources: List<Long>, keepReadManga: Boolean) {
        presenterScope.launchIO {
            handler.await(true) {
                if (keepReadManga) {
                    mangasQueries.deleteNotInLibraryAndNotReadBySourceIds(sources)
                } else {
                    mangasQueries.deleteNotInLibraryBySourceIds(sources)
                }
                historyQueries.deleteAllUnread()
            }
            getDatabaseSources()
        }
    }

    fun reorder(sortBy: SortSources) {
        this.sortBy = sortBy
        presenterScope.launchIO {
            getDatabaseSources()
        }
    }

    private suspend fun getDatabaseSources() {
        var containsStubSources = false
        val sources = handler.awaitList {
            mangasQueries.getSourceIdsOfNotInLibrary { source, count -> SourceIdMangaCount(source, count) }
        }
            .map {
                val sourceObj = sourceManager.getOrStub(it.source)
                containsStubSources = sourceObj is SourceManager.StubSource || containsStubSources
                ClearDatabaseSourceItem(sourceObj, it.count)
            }
            .sortedWith(
                compareBy(
                    {
                        when (sortBy) {
                            SortSources.ALPHA -> it.source.name
                            SortSources.MOST_ENTRIES -> Int.MAX_VALUE - it.mangaCount
                        }
                    },
                    { it.source.name },
                ),
            )
        withUIContext {
            this@ClearDatabasePresenter.hasStubSources = containsStubSources
            view?.setItems(sources)
        }
    }
}
