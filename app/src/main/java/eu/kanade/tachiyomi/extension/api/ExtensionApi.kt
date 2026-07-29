package eu.kanade.tachiyomi.extension.api

import yokai.util.koin.get
import android.content.Context
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.system.withIOContext
import exh.source.BlacklistedSources
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import okio.BufferedSource
import okio.buffer
import okio.gzip
import yokai.util.koin.injectLazy
import yokai.domain.extension.repo.interactor.GetExtensionRepo
import yokai.domain.extension.repo.interactor.UpdateExtensionRepo
import yokai.domain.extension.repo.model.ExtensionRepo
import yokai.domain.extension.repo.service.ExtensionRepoService

internal class ExtensionApi {

    private val networkService: NetworkHelper by injectLazy()
    private val getExtensionRepo: GetExtensionRepo by injectLazy()
    private val updateExtensionRepo: UpdateExtensionRepo by injectLazy()
    private val extensionRepoService by lazy { ExtensionRepoService(networkService.client) }

    suspend fun findExtensions(): List<Extension.Available> {
        return withIOContext {
            getExtensionRepo.getAll()
                .map { async { getExtensions(it) } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun getExtensions(
        repo: ExtensionRepo,
    ): List<Extension.Available> {
        val repoBaseUrl = repo.baseUrl
        return try {
            val indexV2Url = extensionRepoService.fetchIndexV2Url(repoBaseUrl)
            val storeExtensions = indexV2Url?.let { fetchStoreExtensions(it, repoBaseUrl) }
            (storeExtensions ?: fetchLegacyExtensions(repoBaseUrl))
                .filter { it.libVersion in ExtensionLoader.SUPPORTED_LIB_VERSIONS }
        } catch (e: Throwable) {
            Logger.e(e) { "Failed to get extensions from $repoBaseUrl" }
            emptyList()
        }
    }

    private suspend fun fetchLegacyExtensions(repoBaseUrl: String): List<Extension.Available> {
        val response = networkService.client
            .newCall(GET("$repoBaseUrl/index.min.json"))
            .awaitSuccess()

        return response
            .parseAs<List<ExtensionJsonObject>>()
            .toExtensions(repoBaseUrl)
    }

    /**
     * Fetches the newer ExtensionStore index. The response can be the legacy JSON array (if
     * [indexUrl] turned out not to be one after all), a JSON object, or protobuf; detected by
     * peeking at the first byte, same as Mihon does. Returns null (falls back to the legacy
     * index.min.json) if anything about this path fails.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun fetchStoreExtensions(indexUrl: String, repoBaseUrl: String): List<Extension.Available>? {
        return try {
            val store = networkService.client.newCall(GET(indexUrl)).awaitSuccess().body.source()
                .decompressIfGzipped().use { source ->
                when (source.peek().readByte()) {
                    // "[..." - somehow still the legacy array despite index_v2 being set
                    0x5B.toByte() -> return fetchLegacyExtensions(repoBaseUrl)
                    // "{..."
                    0x7B.toByte() -> get<Json>().decodeFromBufferedSource<NetworkExtensionStore>(source)
                    else -> get<ProtoBuf>().decodeFromByteArray<NetworkExtensionStore>(source.readByteArray())
                }
            }

            val extensionList = store.extensionList
                ?: store.extensionListUrl?.let { fetchExtensionList(it) }
                ?: return null

            extensionList.toExtensions(repoBaseUrl)
        } catch (e: Throwable) {
            Logger.e(e) { "Failed to get store extensions from $indexUrl" }
            null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun fetchExtensionList(listUrl: String): NetworkExtensionStore.ExtensionList {
        return networkService.client.newCall(GET(listUrl)).awaitSuccess().body.source()
            .decompressIfGzipped().use { source: BufferedSource ->
            when (source.peek().readByte()) {
                0x7B.toByte() -> get<Json>().decodeFromBufferedSource<NetworkExtensionStoreList>(source)
                    .let { NetworkExtensionStore.ExtensionList(it.extensions) }
                else -> NetworkExtensionStore.ExtensionList(
                    get<ProtoBuf>().decodeFromByteArray<NetworkExtensionStoreList>(source.readByteArray()).extensions,
                )
            }
        }
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() == 0x1f8b
            } catch (_: Exception) {
                false
            }
        }
        return if (isGzip) gzip().buffer() else this
    }

    suspend fun checkForUpdates(context: Context, prefetchedExtensions: List<Extension.Available>? = null): List<Extension.Available> {
        return withIOContext {
            val extensions = prefetchedExtensions ?: findExtensions()

            // Update extension repo details
            updateExtensionRepo.awaitAll()

            val extensionManager: ExtensionManager = get()
            val installedExtensions = extensionManager.installedExtensionsFlow.value.ifEmpty {
                ExtensionLoader.loadExtensionAsync(context)
                    .filterIsInstance<LoadResult.Success>()
                    .map { it.extension }
            }

            val extensionsWithUpdate = mutableListOf<Extension.Available>()
            for (installedExt in installedExtensions) {
                val pkgName = installedExt.pkgName
                val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
                val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
                val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
                val hasUpdate = hasUpdatedVer || hasUpdatedLib
                if (hasUpdate) {
                    extensionsWithUpdate.add(availableExt)
                }
            }

            extensionsWithUpdate
        }
    }

    private fun List<ExtensionJsonObject>.toExtensions(repoUrl: String): List<Extension.Available> {
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion in ExtensionLoader.SUPPORTED_LIB_VERSIONS
            }
            // Drop EXH-blacklisted extensions (e.g. eHentai) at the API layer so they never
            // appear as installable in Browse, never count toward update badges, and never
            // get installed only to be no-op'd inside SourceManager.toInternalSource().
            .filterNot { it.pkg in BlacklistedSources.BLACKLISTED_EXTENSIONS }
            .map {
                Extension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    sources = it.sources ?: emptyList(),
                    apkName = it.apk,
                    iconUrl = "$repoUrl/icon/${it.pkg}.png",
                    repoUrl = repoUrl,
                    isNovel = it.isNovel,
                )
            }
    }

    fun getApkUrl(extension: ExtensionManager.ExtensionInfo): String {
        // The newer ExtensionStore index gives a full apk URL directly; the legacy index.min.json
        // only gives the file name, relative to the repo's /apk/ directory.
        if (extension.apkName.startsWith("http://") || extension.apkName.startsWith("https://")) {
            return extension.apkName
        }
        return "${extension.repoUrl}/apk/${extension.apkName}"
    }

    private fun ExtensionJsonObject.extractLibVersion(): Double {
        return version.substringBeforeLast('.').toDouble()
    }
}

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val hasReadme: Int = 0,
    val hasChangelog: Int = 0,
    val sources: List<Extension.AvailableSource>?,
    val isNovel: Boolean = false,
)
