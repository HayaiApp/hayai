package eu.kanade.tachiyomi.source

import android.content.Context
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.DelegatedHttpSource
import eu.kanade.tachiyomi.source.online.HttpSource
// EXH -->
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.source.online.all.Lanraragi
import eu.kanade.tachiyomi.source.online.all.MangaDex
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.source.online.all.NHentai
import eu.kanade.tachiyomi.source.online.english.EightMuses
import eu.kanade.tachiyomi.source.online.english.HBrowse
import eu.kanade.tachiyomi.source.online.english.Pururin
import eu.kanade.tachiyomi.source.online.english.Tsumino
import exh.source.BlacklistedSources
import exh.source.DelegatedHttpSource as ExhDelegatedHttpSource
import exh.source.EH_SOURCE_ID
import exh.source.EIGHTMUSES_SOURCE_ID
import exh.source.EXH_SOURCE_ID
import exh.source.EnhancedHttpSource
import exh.source.ExhPreferences
import exh.source.HBROWSE_SOURCE_ID
import exh.source.MERGED_SOURCE_ID
import exh.source.PURURIN_SOURCE_ID
import exh.source.TSUMINO_SOURCE_ID
import exh.source.handleSourceLibrary
// EXH <--
// NOVEL -->
import hayai.novel.plugin.NovelPluginManager
// NOVEL <--
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import yokai.util.koin.injectLazy
import yokai.i18n.MR
import yokai.util.lang.getString

class SourceManager(
    private val context: Context,
    private val extensionManager: ExtensionManager,
) {

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    private val sourcesMapFlow = MutableStateFlow(ConcurrentHashMap<Long, Source>())

    private val stubSourcesMap = ConcurrentHashMap<Long, StubSource>()

    val catalogueSources: Flow<List<CatalogueSource>> = sourcesMapFlow.map { it.values.filterIsInstance<CatalogueSource>() }
    val onlineSources: Flow<List<HttpSource>> = catalogueSources.map { it.filterIsInstance<HttpSource>() }

    // EXH -->
    private val exhPreferences: ExhPreferences by injectLazy()
    // EXH <--

    // NOVEL -->
    private val novelPluginManager: NovelPluginManager by injectLazy()
    // NOVEL <--

    // FIXME: Delegated source, unused at the moment, J2K only delegate deep links
    private val delegatedSources = emptyList<DelegatedSource>().associateBy { it.sourceId }

    init {
        // Kick off novel-plugin loading in parallel — never await it from this init coroutine.
        // The combine flow below will re-emit when installedSourcesFlow updates, so plugins
        // get folded into sourcesMapFlow as soon as they're ready. Awaiting here previously
        // gated the first source-map emission on QuickJS metadata extraction (700ms+ per
        // plugin on cold start) and contributed to issue #14's splash hang when the recents
        // path tried to render rows before the map had any sources.
        scope.launch { novelPluginManager.ensureInstalledPluginsLoaded() }

        scope.launch {
            // Single writer to sourcesMapFlow. Any change to extensions, the exhentai pref,
            // or installed novel sources triggers one rebuild. Replaces two racing collectors
            // that were both doing read-modify-write on sourcesMapFlow and clobbering each
            // other's updates (causing extensions to randomly disappear from the browse list).
            combine(
                extensionManager.installedExtensionsFlow,
                exhPreferences.enableExhentai.changes(),
                novelPluginManager.installedSourcesFlow,
            ) { extensions, enableExhentai, novelSources ->
                Triple(extensions, enableExhentai, novelSources)
            }.collectLatest { (extensions, enableExhentai, novelSources) ->
                val mutableMap = ConcurrentHashMap<Long, Source>(mapOf(LocalSource.ID to LocalSource(context)))

                // EXH -->
                mutableMap[EH_SOURCE_ID] = EHentai(EH_SOURCE_ID, false, context)
                if (enableExhentai) {
                    mutableMap[EXH_SOURCE_ID] = EHentai(EXH_SOURCE_ID, true, context)
                }
                mutableMap[MERGED_SOURCE_ID] = MergedSource()
                // EXH <--

                extensions.forEach { extension ->
                    extension.sources.forEach {
                        // EXH -->
                        try {
                            val internalSource = it.toInternalSource()
                            if (internalSource != null) {
                                mutableMap[it.id] = internalSource
                            }
                        } catch (e: Throwable) {
                            Logger.e(e) { "Failed to initialize source ${it.name} (${it.id}) from ${extension.name}" }
                        }
                        // EXH <--
                    }
                }

                // NOVEL -->
                novelSources.forEach {
                    mutableMap[it.id] = it
                }
                // NOVEL <--

                sourcesMapFlow.value = mutableMap
            }
        }
    }

    fun get(sourceKey: Long): Source? {
        return sourcesMapFlow.value[sourceKey]
    }

    suspend fun awaitCatalogueSource(sourceKey: Long): CatalogueSource? {
        (get(sourceKey) as? CatalogueSource)?.let { return it }

        novelPluginManager.ensureInstalledPluginsLoaded()

        return (sourcesMapFlow.value[sourceKey] as? CatalogueSource)
            ?: novelPluginManager.installedSourcesFlow.value.firstOrNull { it.id == sourceKey }
    }

    /**
     * Suspends until the novel plugin manager has finished its initial installed-plugin
     * load (or [timeoutMs] elapses) before returning the resolved Source. Used by reader
     * code paths that previously called [getOrStub] on cold start / memory-restore and got
     * a StubSource because the fire-and-forget plugin loader hadn't run yet (issue #9).
     *
     * Returns:
     *  - the resolved (non-stub) Source if it is registered before the timeout, OR
     *  - the resolved Source if the registration already happened, OR
     *  - null if the timeout elapses with no resolution AND the plugin manager hasn't
     *    finished its initial load (caller should treat this as "source not ready" and
     *    surface a retry affordance).
     *
     * Manga sources (extensions) are typically already present in [sourcesMapFlow] by the
     * time this is called — the combine() in init writes the map synchronously after
     * extensionManager emits — so for those this returns immediately.
     */
    suspend fun awaitSource(sourceKey: Long, timeoutMs: Long = 5_000L): Source? {
        val existing = sourcesMapFlow.value[sourceKey]
        if (existing != null && existing !is StubSource) return existing

        val ready = withTimeoutOrNull(timeoutMs) {
            novelPluginManager.loadedFlow.first { it }
        }
        if (ready == null) return null

        return sourcesMapFlow.value[sourceKey]
            ?: novelPluginManager.installedSourcesFlow.value.firstOrNull { it.id == sourceKey }
    }

    fun getOrStub(sourceKey: Long): Source {
        sourcesMapFlow.value[sourceKey]?.let { return it }

        // Non-blocking lookup: if novel plugins are already loaded, use them immediately.
        // The single-writer combine in init will fold this source into sourcesMapFlow on the
        // next emission — we deliberately don't write the map here to avoid racing with it.
        val novelSource = novelPluginManager.installedSourcesFlow.value.firstOrNull { it.id == sourceKey }
        if (novelSource != null) {
            stubSourcesMap.remove(sourceKey)
            return novelSource
        }

        // Kick off plugin loading in the background; subsequent lookups will hit the cached map.
        scope.launch {
            novelPluginManager.ensureInstalledPluginsLoaded()
        }

        return stubSourcesMap.getOrPut(sourceKey) {
            StubSource(sourceKey)
        }
    }

    fun isDelegatedSource(source: Source): Boolean {
        return delegatedSources.values.count { it.sourceId == source.id } > 0
    }

    fun getDelegatedSource(urlName: String): DelegatedHttpSource? {
        return delegatedSources.values.find { it.urlName == urlName }?.delegatedHttpSource
    }

    fun getOnlineSources() = sourcesMapFlow.value.values.filterIsInstance<HttpSource>()

    fun getCatalogueSources() = sourcesMapFlow.value.values.filterIsInstance<CatalogueSource>()

    /**
     * Online sources excluding those flagged as user-hidden (e.g. MergedSource). Use this in
     * any UI that lists sources for the user to browse, search, migrate, or filter — only
     * settings screens that exist to manage hidden sources should call [getOnlineSources].
     */
    fun getVisibleOnlineSources() = sourcesMapFlow.value.values
        .filterIsInstance<HttpSource>()
        .filter { it.id !in BlacklistedSources.HIDDEN_SOURCES }

    /**
     * Catalogue sources excluding those flagged as user-hidden (e.g. MergedSource). Use this
     * in any UI that lists sources for the user to browse, search, migrate, or filter — only
     * settings screens that exist to manage hidden sources should call [getCatalogueSources].
     */
    fun getVisibleCatalogueSources() = sourcesMapFlow.value.values
        .filterIsInstance<CatalogueSource>()
        .filter { it.id !in BlacklistedSources.HIDDEN_SOURCES }

    @Suppress("OverridingDeprecatedMember")
    inner class StubSource(override val id: Long) : Source {

        override val name: String
            get() = extensionManager.getStubSource(id)?.name ?: id.toString()

        override val supportsLatest: Boolean = false

        override suspend fun getPopularManga(page: Int): MangasPage =
            throw getSourceNotInstalledException()

        override suspend fun getLatestUpdates(page: Int): MangasPage =
            throw getSourceNotInstalledException()

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            throw getSourceNotInstalledException()

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate =
            throw getSourceNotInstalledException()

        override suspend fun getPageList(chapter: SChapter): List<Page> =
            throw getSourceNotInstalledException()

        override fun toString(): String {
            return name
        }

        private fun getSourceNotInstalledException(): Exception {
            return SourceNotFoundException(
                context.getString(
                    MR.strings.source_not_installed_,
                    extensionManager.getStubSource(id)?.name ?: id.toString(),
                ),
                id,
            )
        }

        override fun hashCode(): Int {
            return id.hashCode()
        }

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

    // EXH -->
    /**
     * Wraps extension sources with EXH delegated sources when they match
     * the DELEGATED_SOURCES table. Returns null if source is blacklisted.
     */
    private fun Source.toInternalSource(): Source? {
        val sourceQName = this::class.qualifiedName
        val factories = DELEGATED_SOURCES.entries
            .filter { it.value.factory }
            .map { it.value.originalSourceQualifiedClassName }
        val delegate = if (sourceQName != null) {
            val matched = factories.find { sourceQName.startsWith(it) }
            if (matched != null) {
                DELEGATED_SOURCES[matched]
            } else {
                DELEGATED_SOURCES[sourceQName]
            }
        } else {
            null
        }
        val newSource = if (this is HttpSource && delegate != null) {
            try {
                val constructor = delegate.newSourceClass.constructors.find { it.parameters.size == 2 }
                if (constructor == null) {
                    // In debug builds we want this loud — a missing 2-param constructor means the
                    // delegate signature drifted from what toInternalSource expects, and silent
                    // fallback would mask the regression by continuing without metadata enrichment.
                    val msg = "No 2-param (HttpSource, Context) constructor found for delegate " +
                        "${delegate.newSourceClass.simpleName}; SourceManager cannot wrap ${this.name}"
                    if (BuildConfig.DEBUG) error(msg)
                    Logger.e { msg }
                    this
                } else {
                    val enhancedSource = EnhancedHttpSource(
                        this,
                        constructor.call(this, context),
                    )

                    currentDelegatedSources[enhancedSource.originalSource.id] = DelegatedSourceEntry(
                        enhancedSource.originalSource.name,
                        enhancedSource.originalSource.id,
                        enhancedSource.originalSource::class.qualifiedName ?: delegate.originalSourceQualifiedClassName,
                        (enhancedSource.enhancedSource as ExhDelegatedHttpSource)::class,
                        delegate.factory,
                    )
                    enhancedSource
                }
            } catch (e: Throwable) {
                Logger.e(e) { "Failed to wrap ${this.name} with delegated source, using original" }
                this
            }
        } else {
            this
        }

        return if (id in BlacklistedSources.BLACKLISTED_EXT_SOURCES) {
            null
        } else {
            newSource
        }
    }

    companion object {
        private const val fillInSourceId = Long.MAX_VALUE

        // Delegation table: maps extension qualified class names to EXH enhanced sources.
        // When an extension source matches, it gets wrapped with EnhancedHttpSource
        // for metadata handling. Factory sources match by prefix (e.g., multi-lang).
        // Mirrors TachiyomiSY's AndroidSourceManager.DELEGATED_SOURCES — keep in sync if a
        // new metadata-aware delegate is added.
        val DELEGATED_SOURCES = listOf(
            DelegatedSourceEntry(
                "Pururin",
                PURURIN_SOURCE_ID,
                "eu.kanade.tachiyomi.extension.en.pururin.Pururin",
                Pururin::class,
            ),
            DelegatedSourceEntry(
                "Tsumino",
                TSUMINO_SOURCE_ID,
                "eu.kanade.tachiyomi.extension.en.tsumino.Tsumino",
                Tsumino::class,
            ),
            DelegatedSourceEntry(
                "MangaDex",
                fillInSourceId,
                "eu.kanade.tachiyomi.extension.all.mangadex",
                MangaDex::class,
                true,
            ),
            DelegatedSourceEntry(
                "HBrowse",
                HBROWSE_SOURCE_ID,
                "eu.kanade.tachiyomi.extension.en.hbrowse.HBrowse",
                HBrowse::class,
            ),
            DelegatedSourceEntry(
                "8Muses",
                EIGHTMUSES_SOURCE_ID,
                "eu.kanade.tachiyomi.extension.en.eightmuses.EightMuses",
                EightMuses::class,
            ),
            DelegatedSourceEntry(
                "NHentai",
                fillInSourceId,
                "eu.kanade.tachiyomi.extension.all.nhentai.NHentai",
                NHentai::class,
                true,
            ),
            DelegatedSourceEntry(
                "LANraragi",
                fillInSourceId,
                "eu.kanade.tachiyomi.extension.all.lanraragi.LANraragi",
                Lanraragi::class,
                true,
            ),
        ).associateBy { it.originalSourceQualifiedClassName }

        // ListenMutableMap fires handleSourceLibrary() on every put/putAll/remove/clear so the
        // dynamic id lists in DomainSourceHelpers (metadataDelegatedSourceIds, nHentaiSourceIds,
        // mangaDexSourceIds, lanraragiSourceIds, LIBRARY_UPDATE_EXCLUDED_SOURCES) stay in sync
        // with what's actually been delegated. Without this they silently stay at compile-time
        // defaults and isMdBasedSource() / isMetadataSource() etc. return wrong answers.
        val currentDelegatedSources: MutableMap<Long, DelegatedSourceEntry> =
            ListenMutableMap(mutableMapOf(), ::handleSourceLibrary)

        data class DelegatedSourceEntry(
            val sourceName: String,
            val sourceId: Long,
            val originalSourceQualifiedClassName: String,
            val newSourceClass: KClass<out ExhDelegatedHttpSource>,
            val factory: Boolean = false,
        )

        private class ListenMutableMap<K, V>(
            private val internalMap: MutableMap<K, V>,
            private val listener: () -> Unit,
        ) : MutableMap<K, V> by internalMap {
            override fun clear() {
                internalMap.clear()
                listener()
            }

            override fun put(key: K, value: V): V? {
                val previous = internalMap.put(key, value)
                if (previous == null) listener()
                return previous
            }

            override fun putAll(from: Map<out K, V>) {
                internalMap.putAll(from)
                listener()
            }

            override fun remove(key: K): V? {
                val removed = internalMap.remove(key)
                if (removed != null) listener()
                return removed
            }
        }
    }
    // EXH <--
}

class SourceNotFoundException(message: String, val id: Long) : Exception(message)
