package dev.ahmedmohamed.hayai.novel.plugin

import android.content.Context
import android.content.SharedPreferences
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream

data class NovelPluginCatalog(
    val repositories: List<NovelPluginRepository> = emptyList(),
    val available: List<NovelPluginDescriptor> = emptyList(),
    val installed: List<InstalledNovelPlugin> = emptyList(),
    val sources: List<NovelPluginSource> = emptyList(),
    val repositoryErrors: Map<String, String> = emptyMap(),
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
    private var availableOrigins: Map<String, String> = emptyMap()
    private val _catalog = MutableStateFlow(loadLocal())
    val catalog: StateFlow<NovelPluginCatalog> = _catalog.asStateFlow()

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
                            result.exceptionOrNull()?.let { repository.url to safeError(it) }
                        }.toMap()
                val candidates = results.flatMap { (repository, result) -> result.getOrDefault(emptyList()).map { it to repository.url } }
                val availablePairs =
                    candidates
                        .groupBy { it.first.id }
                        .map { (_, versions) ->
                            versions.maxWith { left, right -> NovelPluginVersions.compare(left.first.version, right.first.version) }
                        }
                availableOrigins = availablePairs.associate { it.first.id to it.second }
                val available =
                    availablePairs
                        .map { it.first }
                        .sortedWith(compareBy(NovelPluginDescriptor::normalizedLanguage, NovelPluginDescriptor::name))
                _catalog.value = rebuild(_catalog.value.copy(available = available, repositoryErrors = errors, refreshing = false))
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
            require(name.isNotBlank() && name.length <= 256) { "Invalid repository name" }
            requireSafeUrl(url, allowLocalHttp = true)
            store.saveRepository(NovelPluginRepository(name.trim(), url.trim(), true))
            trustStore.trustUnsigned(url.trim())
            _catalog.value = rebuild(_catalog.value.copy(repositories = store.repositories()))
            _catalog.value
        }

    suspend fun setRepositoryEnabled(
        url: String,
        enabled: Boolean,
    ) = mutex.withLock {
        val repository = store.repositories().firstOrNull { it.url == url } ?: error("Repository not found")
        store.saveRepository(repository.copy(enabled = enabled))
        _catalog.value = rebuild(_catalog.value.copy(repositories = store.repositories()))
    }

    suspend fun removeRepository(url: String) =
        mutex.withLock {
            store.removeRepository(url)
            trustStore.revoke(url)
            _catalog.value = rebuild(_catalog.value.copy(repositories = store.repositories()))
        }

    suspend fun install(pluginId: String): InstalledNovelPlugin =
        mutex.withLock {
            val descriptor = _catalog.value.available.firstOrNull { it.id == pluginId } ?: error("Plugin is not available")
            val repositoryUrl = availableOrigins[pluginId] ?: error("Refresh repositories before installing this plugin")
            val bytes = download(descriptor.resolvedCodeUrl(repositoryUrl), NovelPluginStore.MAX_PLUGIN_BYTES)
            val installed = installValidated(descriptor, repositoryUrl, bytes, replacementPreferences = null)
            _catalog.value = rebuild(_catalog.value.copy(installed = store.installed()))
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
            _catalog.value =
                rebuild(
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
            _catalog.value = rebuild(_catalog.value.copy(installed = store.installed()))
        }

    suspend fun reloadLocalState() =
        mutex.withLock {
            _catalog.value = rebuild(_catalog.value.copy(repositories = store.repositories(), installed = store.installed()))
        }

    fun hasUpdate(pluginId: String): Boolean {
        val installed = _catalog.value.installed.firstOrNull { it.descriptor.id == pluginId } ?: return false
        val available = _catalog.value.available.firstOrNull { it.id == pluginId } ?: return false
        return NovelPluginVersions.compare(available.version, installed.descriptor.version) > 0
    }

    fun sourceName(sourceId: Long): String? = store.sourceName(sourceId)

    private fun loadLocal(): NovelPluginCatalog =
        rebuild(NovelPluginCatalog(repositories = store.repositories(), installed = store.installed()))

    private fun rebuild(state: NovelPluginCatalog): NovelPluginCatalog {
        val oldById = state.sources.associateBy(NovelPluginSource::pluginId)
        val sources =
            state.installed.mapNotNull { installed ->
                runCatching {
                    store.rememberSource(installed.descriptor)
                    oldById[installed.descriptor.id]?.takeIf { it.isSamePlugin(installed) }
                        ?: NovelPluginSource(installed, store.readCode(installed)).also { source ->
                            sourceScope.launch {
                                runCatching { source.warmUp() }
                            }
                        }
                }.getOrNull()
            }
        state.sources.filterNot { it in sources }.forEach(NovelPluginSource::close)
        return state.copy(sources = sources)
    }

    private suspend fun installValidated(
        descriptor: NovelPluginDescriptor,
        repositoryUrl: String,
        code: ByteArray,
        replacementPreferences: Map<String, String>?,
    ): InstalledNovelPlugin {
        descriptor.validate(repositoryUrl)
        require(code.size in 1..NovelPluginStore.MAX_PLUGIN_BYTES.toInt()) { "Plugin code is empty or too large" }
        val codeHash = sha256Hex(code)
        descriptor.sha256?.let { require(codeHash.equals(it, true)) { "Plugin checksum does not match repository metadata" } }
        trustStore.verify(repositoryUrl, descriptor, code)
        val preferences = appContext.getSharedPreferences("jsplugin_storage_${descriptor.id}", Context.MODE_PRIVATE)
        val previousPreferences = preferences.all.toMap()
        try {
            replacementPreferences?.let { check(replacePreferences(preferences, it)) { "Unable to restore plugin settings" } }
            val candidate = InstalledNovelPlugin(descriptor, repositoryUrl, System.currentTimeMillis(), codeHash)
            NovelPluginSource(candidate, code.toString(Charsets.UTF_8)).use { it.warmUp() }
            return store.install(descriptor, repositoryUrl, code)
        } catch (error: Exception) {
            runCatching { check(restorePreferences(preferences, previousPreferences)) { "Unable to roll back plugin settings" } }
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
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        val array =
            when (root) {
                is JsonArray -> root
                is JsonObject -> root["plugins"] as? JsonArray ?: root["sources"] as? JsonArray ?: error("Repository has no plugins array")
                else -> error("Invalid repository document")
            }
        require(array.size <= MAX_REPOSITORY_PLUGINS) { "Repository contains too many plugins" }
        val descriptors = array.map { json.decodeFromJsonElement<NovelPluginDescriptor>(it).validate(repository.url) }
        val signingKeys = descriptors.mapNotNull(NovelPluginDescriptor::signingKey).distinct()
        require(signingKeys.size <= 1) { "Repository mixes multiple signing keys" }
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
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body
                val length = body.contentLength()
                require(length <= maxBytes) { "Response is too large" }
                val input = body.byteStream()
                val output = ByteArrayOutputStream(if (length in 1..maxBytes) length.toInt() else 8192)
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "Response is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }

    private fun safeError(error: Throwable): String = (error.message ?: error.javaClass.simpleName).take(500)

    companion object {
        private const val MAX_REPOSITORY_BYTES = 4L * 1024 * 1024
        private const val MAX_REPOSITORY_PLUGINS = 10_000
    }
}
