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
import kotlinx.serialization.Serializable
import yokai.util.koin.injectLazy
import yokai.domain.extension.repo.interactor.GetExtensionRepo
import yokai.domain.extension.repo.interactor.UpdateExtensionRepo
import yokai.domain.extension.repo.model.ExtensionRepo

internal class ExtensionApi {

    private val networkService: NetworkHelper by injectLazy()
    private val getExtensionRepo: GetExtensionRepo by injectLazy()
    private val updateExtensionRepo: UpdateExtensionRepo by injectLazy()

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
            val response = networkService.client
                .newCall(GET("$repoBaseUrl/index.min.json"))
                .awaitSuccess()

            response
                .parseAs<List<ExtensionJsonObject>>()
                .toExtensions(repoBaseUrl)
        } catch (e: Throwable) {
            Logger.e(e) { "Failed to get extensions from $repoBaseUrl" }
            emptyList()
        }
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
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
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
