package eu.kanade.tachiyomi.source

import android.content.Context
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginManager
import dev.ahmedmohamed.hayai.adult.eh.source.EhSourceProvider
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelSource
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import dev.ahmedmohamed.hayai.source.AdultSourceVisibility
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.DelegatedHttpSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.Cubari
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

class SourceManager(
    private val context: Context,
    private val extensionManager: ExtensionManager,
    private val novelPluginManager: NovelPluginManager,
    private val ehSourceProvider: EhSourceProvider,
) {
    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    private val sourcesMapFlow = MutableStateFlow(ConcurrentHashMap<Long, Source>())
    private val hayaiPreferences = HayaiPreferences(Injekt.get())

    private val stubSourcesMap = ConcurrentHashMap<Long, StubSource>()

    val catalogueSources: Flow<List<CatalogueSource>> =
        sourcesMapFlow.map { it.values.filterIsInstance<CatalogueSource>() }
    val onlineSources: Flow<List<HttpSource>> = catalogueSources.map { it.filterIsInstance<HttpSource>() }
    val discoverableCatalogueSources: Flow<List<CatalogueSource>> =
        combine(
            sourcesMapFlow,
            hayaiPreferences.hentaiFeaturesEnabled.changes(),
            ehSourceProvider.discoveryChanges,
        ) { sources, adultEnabled, _ ->
            sources.values.filterIsInstance<CatalogueSource>().filter {
                AdultSourceVisibility.includes(it, adultEnabled) && ehSourceProvider.isDiscoverable(it.id)
            }
        }

    private val delegatedSources =
        listOf(
            DelegatedSource(
                "cubari.moe",
                6338219619148105941,
                Cubari(),
            ),
        ).associateBy { it.sourceId }

    init {
        scope.launch {
            combine(extensionManager.installedExtensionsFlow, novelPluginManager.catalog) { extensions, plugins ->
                extensions to
                    plugins.sources
            }.collectLatest { (extensions, pluginSources) ->
                val mutableMap =
                    ConcurrentHashMap<Long, Source>(
                        mapOf(
                            LocalSource.ID to LocalSource(context),
                            LocalNovelSource.ID to LocalNovelSource(context),
                        ) + ehSourceProvider.allSources().associateBy(Source::id),
                    )
                extensions.forEach { extension ->
                    extension.sources.forEach {
                        if (ehSourceProvider.owns(it.id)) {
                            Timber.e("Rejected extension source %s: source ID %d is reserved by Hayai", it.name, it.id)
                        } else {
                            mutableMap[it.id] = it
                            delegatedSources[it.id]?.delegatedHttpSource?.delegate = it as? HttpSource
                        }
//                            registerStubSource(it)
                    }
                }
                pluginSources.forEach { source ->
                    if (mutableMap.containsKey(source.id)) {
                        Timber.e("Rejected novel plugin %s: source ID %d is already registered", source.pluginId, source.id)
                    } else {
                        mutableMap[source.id] = source
                    }
                }
                sourcesMapFlow.value = mutableMap
            }
        }

//        scope.launch {
//            sourceRepository.subscribeAll()
//                .collectLatest { sources ->
//                    val mutableMap = stubSourcesMap.toMutableMap()
//                    sources.forEach {
//                        mutableMap[it.id] = StubSource(it)
//                    }
//                }
//        }
    }

    fun get(sourceKey: Long): Source? = sourcesMapFlow.value[sourceKey]

    fun getOrStub(sourceKey: Long): Source =
        sourcesMapFlow.value[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            runBlocking { StubSource(sourceKey) }
        }

    fun isDelegatedSource(source: Source): Boolean = delegatedSources.values.count { it.sourceId == source.id } > 0

    fun getDelegatedSource(urlName: String): DelegatedHttpSource? =
        delegatedSources.values.find { it.urlName == urlName }?.delegatedHttpSource

    fun getOnlineSources() = sourcesMapFlow.value.values.filterIsInstance<HttpSource>()

    fun getCatalogueSources() = sourcesMapFlow.value.values.filterIsInstance<CatalogueSource>()

    fun getDiscoverableOnlineSources() = getDiscoverableCatalogueSources().filterIsInstance<HttpSource>()

    fun getDiscoverableCatalogueSources() =
        sourcesMapFlow.value.values.filterIsInstance<CatalogueSource>().filter {
            AdultSourceVisibility.includes(it, hayaiPreferences.hentaiFeaturesEnabled.get()) && ehSourceProvider.isDiscoverable(it.id)
        }

    @Suppress("OverridingDeprecatedMember")
    inner class StubSource(
        override val id: Long,
    ) : Source {
        override val name: String
            get() = novelPluginManager.sourceName(id) ?: extensionManager.getStubSource(id)?.name ?: id.toString()

        override suspend fun getMangaDetails(manga: SManga): SManga = throw getSourceNotInstalledException()

        override suspend fun getChapterList(manga: SManga): List<SChapter> = throw getSourceNotInstalledException()

        override suspend fun getPageList(chapter: SChapter): List<Page> = throw getSourceNotInstalledException()

        override fun toString(): String = name

        private fun getSourceNotInstalledException(): Exception =
            SourceNotFoundException(
                context.getString(
                    R.string.source_not_installed_,
                    novelPluginManager.sourceName(id) ?: extensionManager.getStubSource(id)?.name ?: id.toString(),
                ),
                id,
            )

        override fun hashCode(): Int = id.hashCode()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as StubSource
            return id == other.id
        }
    }

    private data class DelegatedSource(
        val urlName: String,
        val sourceId: Long,
        val delegatedHttpSource: DelegatedHttpSource,
    )
}

class SourceNotFoundException(
    message: String,
    val id: Long,
) : Exception(message)
