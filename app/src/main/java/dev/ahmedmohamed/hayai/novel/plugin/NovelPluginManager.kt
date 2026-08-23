package dev.ahmedmohamed.hayai.novel.plugin

import android.content.Context
import android.content.SharedPreferences
import dev.ahmedmohamed.hayai.extension.managed.ExtensionRepositoryDefaults
import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.novel.error.novelFailure
import dev.ahmedmohamed.hayai.novel.error.novelRequire
import dev.ahmedmohamed.hayai.novel.plugin.source.NovelPluginSource
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream

data class NovelPluginCatalog(
    val repositories: List<NovelPluginRepository> = emptyList(),
    val available: List<NovelPluginDescriptor> = emptyList(),
    val installed: List<InstalledNovelPlugin> = emptyList(),
    val sources: List<NovelPluginSource> = emptyList(),
    val availableOrigins: Map<String, String> = emptyMap(),
    val repositoryErrors: Map<String, Throwable> = emptyMap(),
    val runtimeErrors: Map<String, Throwable> = emptyMap(),
    val refreshing: Boolean = false,
)

class NovelPluginManager(
    context: Context,
    database: DatabaseHelper,
    network: NetworkHelper,
) {
    private val appContext = context.applicationContext
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    private val store = NovelPluginStore(context, database, json)
    private val trustStore = NovelPluginTrustStore(context)
    private val client: OkHttpClient =
        network.client
            .newBuilder()
            .followSslRedirects(true)
            .followRedirects(true)
            .build()
    private val mutex = Mutex()
    private val sourceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _catalog = MutableStateFlow(NovelPluginCatalog(repositories = repositoriesWithFirstRunDefault(), installed = store.installed()))
    val catalog: StateFlow<NovelPluginCatalog> = _catalog.asStateFlow()

    init {
        rebuildAndPublish(_catalog.value)
    }

    private fun repositoriesWithFirstRunDefault(): List<NovelPluginRepository> {
        val bootstrap = appContext.getSharedPreferences(BOOTSTRAP_PREFERENCES, Context.MODE_PRIVATE)
        if (!bootstrap.getBoolean(DEFAULT_REPOSITORY_SEEDED, false)) {
            val url = ExtensionRepositoryDefaults.LNREADER_NOVELS
            if (store.repositories().none { it.url == url }) {
                store.saveRepository(NovelPluginRepository("LNReader", url, enabled = true))
                trustStore.trustUnsigned(url)
            }
            bootstrap.edit().putBoolean(DEFAULT_REPOSITORY_SEEDED, true).commit()
        }
        return store.repositories()
    }

    suspend fun refresh(): NovelPluginCatalog =
        mutex.withLock {
            _catalog.value = _catalog.value.copy(repositories = store.repositories(), refreshing = true, repositoryErrors = emptyMap())
            try {
                val enabled = _catalog.value.repositories.filter(NovelPluginRepository::enabled)
                val results =
                    coroutineScope {
                        enabled
                            .map { repository ->
                                async(Dispatchers.IO) { repository to runCatching { fetchRepository(repository) } }
                            }.awaitAll()
                    }
                val errors =
                    results
                        .mapNotNull { (repository, result) ->
                            result.exceptionOrNull()?.let { repository.url to it }
                        }.toMap()
                val candidates = results.flatMap { (repository, result) -> result.getOrDefault(emptyList()).map { it to repository.url } }
                val availablePairs =
                    candidates
                        .groupBy { it.first.id }
                        .map { (_, versions) ->
                            versions.maxWith { left, right -> NovelPluginVersions.compare(left.first.version, right.first.version) }
                        }
                val availableOrigins = availablePairs.associate { it.first.id to it.second }
                val available =
                    availablePairs
                        .map { it.first }
                        .sortedWith(compareBy(NovelPluginDescriptor::normalizedLanguage, NovelPluginDescriptor::name))
                rebuildAndPublish(
                    _catalog.value.copy(
                        available = available,
                        availableOrigins = availableOrigins,
                        repositoryErrors = errors,
                        refreshing = false,
                    ),
                )
                _catalog.value
            } finally {
                if (_catalog.value.refreshing) _catalog.value = _catalog.value.copy(refreshing = false)
            }
        }

    suspend fun addRepository(
        name: String,
        url: String,
    ): NovelPluginCatalog =
        mutex.withLock {
            novelRequire(name.isNotBlank() && name.length <= 256, NovelFailure.Code.PluginRepositoryName)
            requireSafeUrl(url, allowLocalHttp = true)
            store.saveRepository(NovelPluginRepository(name.trim(), url.trim(), true))
            trustStore.trustUnsigned(url.trim())
            rebuildAndPublish(_catalog.value.copy(repositories = store.repositories()))
            _catalog.value
        }

    suspend fun setRepositoryEnabled(
        url: String,
        enabled: Boolean,
    ) = mutex.withLock {
        val repository = store.repositories().firstOrNull { it.url == url } ?: novelFailure(NovelFailure.Code.PluginRepositoryMissing)
        store.saveRepository(repository.copy(enabled = enabled))
        rebuildAndPublish(_catalog.value.copy(repositories = store.repositories()))
    }

    suspend fun removeRepository(url: String) =
        mutex.withLock {
            store.removeRepository(url)
            trustStore.revoke(url)
            rebuildAndPublish(_catalog.value.copy(repositories = store.repositories()))
        }

    suspend fun install(pluginId: String): InstalledNovelPlugin =
        mutex.withLock {
            val descriptor = _catalog.value.available.firstOrNull { it.id == pluginId } ?: novelFailure(NovelFailure.Code.PluginUnavailable)
            val repositoryUrl = _catalog.value.availableOrigins[pluginId] ?: novelFailure(NovelFailure.Code.PluginRefreshRequired)
            val bytes = download(descriptor.resolvedCodeUrl(repositoryUrl), NovelPluginStore.MAX_PLUGIN_BYTES)
            val installed = installValidated(descriptor, repositoryUrl, bytes, replacementPreferences = null)
            rebuildAndPublish(_catalog.value.copy(installed = store.installed()))
            installed
        }

    suspend fun restorePlugin(
        descriptor: NovelPluginDescriptor,
        repositoryUrl: String,
        code: ByteArray,
        preferences: Map<String, String>,
    ): InstalledNovelPlugin =
        mutex.withLock {
            if (descriptor.signingKey == null) trustStore.trustUnsigned(repositoryUrl) else trustStore.observeSigningKey(repositoryUrl, descriptor.signingKey)
            val installed = installValidated(descriptor, repositoryUrl, code, preferences)
            val state = _catalog.value
            val replacedSources = state.sources.filter { it.pluginId == descriptor.id }
            rebuildAndPublish(
                state.copy(
                    installed = store.installed(),
                    sources = state.sources.filterNot { it.pluginId == descriptor.id },
                ),
            )
            replacedSources.forEach(NovelPluginSource::close)
            installed
        }

    suspend fun update(pluginId: String): InstalledNovelPlugin {
        if (_catalog.value.available.none { it.id == pluginId }) refresh()
        return install(pluginId)
    }

    suspend fun uninstall(pluginId: String) =
        mutex.withLock {
            store.uninstall(pluginId)
            rebuildAndPublish(_catalog.value.copy(installed = store.installed()))
        }

    suspend fun reloadLocalState() =
        mutex.withLock {
            rebuildAndPublish(_catalog.value.copy(repositories = store.repositories(), installed = store.installed()))
        }

    fun hasUpdate(pluginId: String): Boolean {
        val installed = _catalog.value.installed.firstOrNull { it.descriptor.id == pluginId } ?: return false
        val available = _catalog.value.available.firstOrNull { it.id == pluginId } ?: return false
        return NovelPluginVersions.compare(available.version, installed.descriptor.version) > 0
    }

    fun sourceName(sourceId: Long): String? = store.sourceName(sourceId)

    private fun rebuildAndPublish(state: NovelPluginCatalog): NovelPluginCatalog {
        val result = rebuild(state)
        _catalog.value = result.catalog
        result.sourcesToWarm.forEach(::warmUp)
        return result.catalog
    }

    private fun rebuild(state: NovelPluginCatalog): RebuildResult {
        val oldById = state.sources.associateBy(NovelPluginSource::pluginId)
        val sourcesToWarm = mutableListOf<NovelPluginSource>()
        val results =
            state.installed.map { installed ->
                installed.descriptor.id to
                    runCatching {
                        store.rememberSource(installed.descriptor)
                        oldById[installed.descriptor.id]?.takeIf { it.isSamePlugin(installed) }
                            ?: NovelPluginSource(appContext, installed, store.readCode(installed)).also { source ->
                                sourcesToWarm += source
                            }
                    }
            }
        val sources = results.mapNotNull { it.second.getOrNull() }
        val installedIds = state.installed.mapTo(mutableSetOf()) { it.descriptor.id }
        val runtimeErrors =
            state.runtimeErrors.filterKeys { it in installedIds } +
                results.mapNotNull { (id, result) -> result.exceptionOrNull()?.let { id to it } }.toMap()
        state.sources.filterNot { it in sources }.forEach(NovelPluginSource::close)
        return RebuildResult(state.copy(sources = sources, runtimeErrors = runtimeErrors), sourcesToWarm)
    }

    private fun warmUp(source: NovelPluginSource) {
        sourceScope.launch {
            val failure = runCatching { source.warmUp() }.exceptionOrNull()
            mutex.withLock {
                val current = _catalog.value
                if (current.sources.none { it === source }) return@withLock
                val runtimeErrors =
                    if (failure == null) {
                        current.runtimeErrors - source.pluginId
                    } else {
                        current.runtimeErrors + (source.pluginId to failure)
                    }
                if (runtimeErrors != current.runtimeErrors) {
                    _catalog.value = current.copy(runtimeErrors = runtimeErrors)
                }
            }
        }
    }

    private data class RebuildResult(
        val catalog: NovelPluginCatalog,
        val sourcesToWarm: List<NovelPluginSource>,
    )

    private suspend fun installValidated(
        descriptor: NovelPluginDescriptor,
        repositoryUrl: String,
        code: ByteArray,
        replacementPreferences: Map<String, String>?,
    ): InstalledNovelPlugin {
        descriptor.validate(repositoryUrl)
        novelRequire(code.size in 1..NovelPluginStore.MAX_PLUGIN_BYTES.toInt(), NovelFailure.Code.PluginCodeSize)
        val codeHash = sha256Hex(code)
        descriptor.sha256?.let { novelRequire(codeHash.equals(it, true), NovelFailure.Code.PluginChecksum) }
        trustStore.verify(repositoryUrl, descriptor, code)
        val preferences = appContext.getSharedPreferences("jsplugin_storage_${descriptor.id}", Context.MODE_PRIVATE)
        val previousPreferences = preferences.all.toMap()
        try {
            replacementPreferences?.let { novelRequire(replacePreferences(preferences, it), NovelFailure.Code.PluginRestoreSettings) }
            val candidate = InstalledNovelPlugin(descriptor, repositoryUrl, System.currentTimeMillis(), codeHash)
            NovelPluginSource(appContext, candidate, code.toString(Charsets.UTF_8)).use { it.warmUp() }
            return store.install(descriptor, repositoryUrl, code)
        } catch (error: Exception) {
            runCatching { novelRequire(restorePreferences(preferences, previousPreferences), NovelFailure.Code.PluginRollbackSettings) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    private fun replacePreferences(
        preferences: SharedPreferences,
        values: Map<String, String>,
    ): Boolean {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) -> editor.putString(key, value) }
        return editor.commit()
    }

    private fun restorePreferences(
        preferences: SharedPreferences,
        values: Map<String, *>,
    ): Boolean {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        return editor.commit()
    }

    private suspend fun fetchRepository(repository: NovelPluginRepository): List<NovelPluginDescriptor> {
        val bytes = download(repository.url, MAX_REPOSITORY_BYTES)
        val document = NovelPluginRepositoryDocument.decode(json, bytes.toString(Charsets.UTF_8))
        novelRequire(document.plugins.size <= MAX_REPOSITORY_PLUGINS, NovelFailure.Code.PluginRepositoryTooMany)
        val descriptors = document.plugins.map { it.validate(repository.url) }
        val signingKeys = descriptors.mapNotNull(NovelPluginDescriptor::signingKey).distinct()
        novelRequire(signingKeys.size <= 1, NovelFailure.Code.PluginRepositoryMixedKeys)
        signingKeys.singleOrNull()?.let { trustStore.observeSigningKey(repository.url, it) }
        return descriptors
    }

    private suspend fun download(
        url: String,
        maxBytes: Long,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            requireSafeUrl(url, allowLocalHttp = true)
            client.newCall(GET(url)).execute().use { response ->
                novelRequire(response.isSuccessful, NovelFailure.Code.PluginHttp, response.code)
                val body = response.body
                val length = body.contentLength()
                novelRequire(length <= maxBytes, NovelFailure.Code.PluginResponseTooLarge)
                val input = body.byteStream()
                val output = ByteArrayOutputStream(if (length in 1..maxBytes) length.toInt() else 8192)
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    novelRequire(total <= maxBytes, NovelFailure.Code.PluginResponseTooLarge)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }

    companion object {
        private const val MAX_REPOSITORY_BYTES = 4L * 1024 * 1024
        private const val MAX_REPOSITORY_PLUGINS = 10_000
        private const val BOOTSTRAP_PREFERENCES = "hayai_novel_plugin_bootstrap"
        private const val DEFAULT_REPOSITORY_SEEDED = "default_lnreader_repository_seeded"
    }
}
