package eu.kanade.tachiyomi.ui.migration

import dev.ahmedmohamed.hayai.novel.integration.ContentKind
import dev.ahmedmohamed.hayai.novel.integration.NovelMigrationPolicy
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferenceValues
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.executeOnIO
import eu.kanade.tachiyomi.util.system.withUIContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

abstract class BaseMigrationPresenter<T : BaseMigrationInterface>(
    protected val sourceManager: SourceManager = Injekt.get(),
    protected val db: DatabaseHelper = Injekt.get(),
    val preferences: PreferencesHelper = Injekt.get(),
    private val novelMigrationPolicy: NovelMigrationPolicy = Injekt.get(),
) : BaseCoroutinePresenter<T>() {
    private var selectedSource: Pair<String, Long>? = null
    var sourceItems = emptyList<SourceItem>()
        protected set

    var mangaItems = hashMapOf<Long, List<MangaItem>>()
        protected set
    protected val extensionManager: ExtensionManager by injectLazy()

    fun refreshMigrations() {
        presenterScope.launch {
            val favs = db.getFavoriteMangas().executeOnIO()
            sourceItems = findSourcesWithManga(favs)
            mangaItems =
                HashMap(
                    sourceItems.associate {
                        it.source.id to libraryToMigrationItem(favs, it.source.id)
                    },
                )
            withContext(Dispatchers.Main) {
                if (selectedSource != null) {
                    view?.setMigrationManga(selectedSource!!.first, mangaItems[selectedSource!!.second])
                } else {
                    view?.setMigrationSources(sourceItems)
                }
            }
        }
    }

    private fun findSourcesWithManga(library: List<Manga>): List<SourceItem> {
        val sourceGroup = library.groupBy { it.source }
        val sortOrder = PreferenceValues.MigrationSourceOrder.fromPreference(preferences)
        val extensions = extensionManager.installedExtensionsFlow.value
        val obsoleteSources =
            extensions
                .filter { it.isObsolete }
                .map { it.sources }
                .flatten()
                .map { it.id }

        val rows =
            sourceGroup.mapNotNull { (sourceId, mangas) ->
                if (sourceId == LocalSource.ID) return@mapNotNull null
                MigrationSourceGroup(
                    source = sourceManager.getOrStub(sourceId),
                    count = mangas.size,
                    contentKind = novelMigrationPolicy.contentKind(mangas.first()),
                )
            }
        val headers =
            mapOf(
                ContentKind.Manga to SelectionHeader(R.string.manga),
                ContentKind.Novel to SelectionHeader(R.string.hayai_novels),
            )
        val comparator =
            compareBy<MigrationSourceGroup>(
                {
                    when (sortOrder) {
                        PreferenceValues.MigrationSourceOrder.Alphabetically -> it.source.name
                        PreferenceValues.MigrationSourceOrder.MostEntries -> Long.MAX_VALUE - it.count
                        PreferenceValues.MigrationSourceOrder.Obsolete ->
                            it.source !is SourceManager.StubSource &&
                                it.source.id !in obsoleteSources
                    }
                },
                { it.source.name },
            )

        return ContentKind.entries.flatMap { kind ->
            rows
                .filter { it.contentKind == kind }
                .sortedWith(comparator)
                .map {
                    SourceItem(
                        it.source,
                        headers.getValue(kind),
                        it.count,
                        it.source is SourceManager.StubSource,
                        it.source.id in obsoleteSources,
                    )
                }
        }
    }

    private fun libraryToMigrationItem(
        library: List<Manga>,
        sourceId: Long,
    ): List<MangaItem> =
        library
            .filter {
                it.source == sourceId
            }.map(::MangaItem)

    protected suspend fun firstTimeMigration() {
        val favs = db.getFavoriteMangas().executeOnIO()
        sourceItems = findSourcesWithManga(favs)
        mangaItems =
            HashMap(
                sourceItems.associate {
                    it.source.id to
                        libraryToMigrationItem(
                            favs,
                            it.source.id,
                        )
                },
            )
        withContext(Dispatchers.Main) {
            if (selectedSource != null) {
                view?.setMigrationManga(selectedSource!!.first, mangaItems[selectedSource!!.second])
            } else {
                view?.setMigrationSources(sourceItems)
            }
        }
    }

    fun setSelectedSource(source: Source) {
        selectedSource = source.name to source.id
        presenterScope.launch {
            withUIContext { view?.setMigrationManga(source.name, mangaItems[source.id]) }
        }
    }

    fun deselectSource() {
        selectedSource = null
        presenterScope.launch {
            withUIContext { view?.setMigrationSources(sourceItems) }
        }
    }
}

private data class MigrationSourceGroup(
    val source: Source,
    val count: Int,
    val contentKind: ContentKind,
)

interface BaseMigrationInterface {
    fun setMigrationManga(
        title: String,
        manga: List<MangaItem>?,
    )

    fun setMigrationSources(sources: List<SourceItem>)
}
