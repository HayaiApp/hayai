package eu.kanade.tachiyomi.extension.api

import android.content.Context
import dev.ahmedmohamed.hayai.extension.ExtensionCatalogReconciler
import dev.ahmedmohamed.hayai.novel.extension.NovelApkRepositoryRegistry
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.model.RepoMetadata
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.coroutines.cancellation.CancellationException

internal class ExtensionApi {
    private val json: Json by injectLazy()
    private val protoBuf: ProtoBuf by injectLazy()
    private val networkService: NetworkHelper by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    companion object {
        private const val LEGACY_INDEX_FILENAME = "/index.min.json"
    }

    suspend fun findExtensions(): List<Extension.Available> {
        return withIOContext {
            val repos = preferences.extensionRepos().get()
            if (repos.isEmpty()) {
                return@withIOContext emptyList()
            }
            val extensions = repos.flatMap { getExtensions(it) }

            if (extensions.isEmpty()) {
                throw Exception()
            }

            extensions
        }
    }

    /**
     * Validates a repo index URL by fetching it, throwing if the response can't be parsed as
     * any supported index format. A successful fetch also caches the repo's display metadata
     * (name/website/discord) as a side effect. Used when adding/renaming a repo so a bad URL
     * gets rejected outright, unlike [getExtensions] which silently skips repos that fail
     * during a routine sync.
     *
     * Returns the URL that should actually be saved: if [indexUrl] was a repo.json (or
     * resolved to one via the bare-base-URL fallback in [resolveExtensions]) with an
     * index_v2 pointer, that's the pointer's final destination rather than [indexUrl] itself -
     * so a manually entered repo.json link ends up stored the same way one discovered through
     * the old-format migration does, and future syncs fetch the real store directly.
     */
    suspend fun validateRepo(indexUrl: String): String = withIOContext { resolveExtensions(indexUrl).first }

    /**
     * Fetches and parses the extensions served at [indexUrl], silently returning an empty
     * list if anything goes wrong. Used during routine syncs where one bad repo shouldn't
     * block the rest. Also self-heals repos stored before this app version, which kept only
     * a bare base URL - see [resolveExtensions].
     */
    private suspend fun getExtensions(indexUrl: String): List<Extension.Available> =
        try {
            val (resolvedUrl, extensions) = resolveExtensions(indexUrl)
            if (resolvedUrl != indexUrl) migrateRepoUrl(indexUrl, resolvedUrl)
            extensions
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.e(e, "Failed to get extensions from $indexUrl")
            emptyList()
        }

    /**
     * Fetches [indexUrl] as given. If that fails and [indexUrl] doesn't already look like it
     * points at a specific file (no dot in its last path segment), it's treated as one of the
     * bare base URLs every repo was stored as before this app version - back when the app
     * reconstructed the actual index path itself - and retried once against
     * "$indexUrl/repo.json", the ecosystem's stable entry point. This is a one-time
     * back-compat shim for our own old storage format, not a filename assumption applied to
     * URLs a user enters or a repo points us to going forward.
     */
    private suspend fun resolveExtensions(indexUrl: String): Pair<String, List<Extension.Available>> =
        try {
            fetchExtensions(indexUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (indexUrl.substringAfterLast('/').contains('.')) throw e
            fetchExtensions("$indexUrl/repo.json")
        }

    /**
     * Replaces a repo's stored URL with the one that actually worked, so future syncs hit it
     * directly instead of re-discovering it through [resolveExtensions] every time. When the
     * resolved URL came from following a repo.json's index_v2 pointer, this is the final
     * protobuf location, not repo.json itself - which also means future syncs pick up fields
     * like the Discord link that only the protobuf store carries.
     */
    private fun migrateRepoUrl(
        oldUrl: String,
        newUrl: String,
    ) {
        val repos = preferences.extensionRepos().get()
        if (oldUrl !in repos) return
        Injekt.get<NovelApkRepositoryRegistry>().migrate(oldUrl, newUrl)
        preferences.extensionRepos().set(repos - oldUrl + newUrl)
        val metadata = preferences.extensionRepoMetadata().get()
        metadata[oldUrl]?.let { oldMetadata ->
            preferences.extensionRepoMetadata().set(metadata - oldUrl + (newUrl to oldMetadata))
        }
    }

    /**
     * Fetches whatever [indexUrl] points to and figures out how to parse it from the response
     * itself (protobuf, a legacy JSON array, or a repo.json pointer), rather than assuming any
     * particular filename or path layout. Returns the URL that actually served the extensions
     * alongside them: for a direct fetch that's just [indexUrl], but a repo.json pointer
     * resolves through to wherever its index_v2 URL ultimately leads, however many hops that
     * takes and whatever it's named - never assumed, only followed.
     */
    private suspend fun fetchExtensions(
        indexUrl: String,
        fallbackMeta: RepoJsonMeta? = null,
    ): Pair<String, List<Extension.Available>> {
        val response = networkService.client.newCall(GET(indexUrl)).awaitSuccess()
        val bytes = response.body.bytes().gunzipIfNeeded()

        return when (bytes.firstOrNull()?.toInt()) {
            '['.code -> getLegacyExtensions(indexUrl, bytes)
            '{'.code -> getIndexV2Extensions(indexUrl, bytes)
            else -> indexUrl to bytes.toAvailableExtensionsFromProtobufStore(indexUrl, fallbackMeta)
        }
    }

    /**
     * The legacy array format carries no absolute URLs for icons/APKs, so those have to be
     * derived from wherever [LEGACY_INDEX_FILENAME] itself was served - the one place a fixed
     * filename is unavoidable, since it's part of the legacy index's own contract, not an
     * assumption layered on top of it.
     *
     * Before settling for [bytes] as-is, check the sibling repo.json for an index_v2 pointer:
     * a repo that's since moved to the richer protobuf store (which carries fields like a
     * Discord link the legacy format has no room for) typically keeps index.min.json around
     * only for old clients, same as mihon assumes. Only fall back to parsing [bytes] itself
     * when there's no pointer to follow, or repo.json can't be reached at all.
     */
    private suspend fun getLegacyExtensions(
        indexUrl: String,
        bytes: ByteArray,
    ): Pair<String, List<Extension.Available>> {
        if (!indexUrl.endsWith(LEGACY_INDEX_FILENAME)) {
            throw IllegalStateException("Legacy extension index must be served at a path ending in $LEGACY_INDEX_FILENAME")
        }
        val repoBaseUrl = indexUrl.removeSuffix(LEGACY_INDEX_FILENAME)

        val repoJson =
            try {
                val repoJsonResponse = networkService.client.newCall(GET("$repoBaseUrl/repo.json")).awaitSuccess()
                with(json) { repoJsonResponse.parseAs<RepoJsonObject>() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }

        val indexV2Url = repoJson?.indexV2
        if (indexV2Url != null) {
            return fetchExtensions(indexV2Url, fallbackMeta = repoJson.meta)
        }

        repoJson?.meta?.let { saveRepoMetadata(indexUrl, it.name, it.website) }

        return indexUrl to
            json
                .decodeFromString<List<ExtensionJsonObject>>(bytes.decodeToString())
                .toExtensions(repoBaseUrl)
    }

    /**
     * A repo.json pointer: no extensions of its own, just metadata plus an index_v2 URL that
     * can live at any path or filename - it's followed exactly as given, never guessed. The
     * resolved URL returned to the caller is whatever [fetchExtensions] on index_v2 itself
     * resolves to, so a chain of pointers bottoms out at the real, final location - and that's
     * also the only URL metadata ends up saved under, so [meta] is passed down as a fallback
     * rather than saved here: a protobuf store often doesn't duplicate name/website into
     * itself when repo.json already carries them, and saving under this intermediate URL
     * would just orphan good data under a key nothing looks up again.
     */
    private suspend fun getIndexV2Extensions(
        indexUrl: String,
        bytes: ByteArray,
    ): Pair<String, List<Extension.Available>> {
        val repoJson =
            try {
                json.decodeFromString<RepoJsonObject>(bytes.decodeToString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw IllegalStateException("$indexUrl is not a recognized extension index format", e)
            }

        val indexV2Url = repoJson.indexV2 ?: throw IllegalStateException("$indexUrl has no index_v2 pointer")

        return fetchExtensions(indexV2Url, fallbackMeta = repoJson.meta)
    }

    /**
     * [fallbackMeta] backfills name/website when the protobuf store leaves its own copies of
     * those fields blank (relying on repo.json instead) - see [getIndexV2Extensions]. The
     * Discord link only ever comes from the protobuf's contact info; repo.json has no field
     * for it.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun ByteArray.toAvailableExtensionsFromProtobufStore(
        repoUrl: String,
        fallbackMeta: RepoJsonMeta? = null,
    ): List<Extension.Available> {
        val store = protoBuf.decodeFromByteArray<ExtensionStoreProtoObject>(this)
        saveRepoMetadata(
            repoUrl,
            name = store.name.ifBlank { fallbackMeta?.name },
            website = store.contact?.website?.ifBlank { null } ?: fallbackMeta?.website,
            discordUrl = store.contact?.discord,
        )
        return store.extensionList
            ?.extensions
            .orEmpty()
            .toAvailableExtensions(repoUrl)
    }

    /**
     * Repos may publish index.pb gzip-compressed at the file level (distinct from any HTTP
     * Content-Encoding, which OkHttp already handles transparently). Detect it by magic
     * number and decompress before handing the bytes to the protobuf decoder.
     */
    private fun ByteArray.gunzipIfNeeded(): ByteArray {
        if (size < 2 || this[0] != 0x1F.toByte() || this[1] != 0x8B.toByte()) return this
        return GZIPInputStream(ByteArrayInputStream(this)).use { it.readBytes() }
    }

    private fun saveRepoMetadata(
        indexUrl: String,
        name: String?,
        website: String?,
        discordUrl: String? = null,
    ) {
        if (name.isNullOrBlank() || website.isNullOrBlank()) return
        val metadata = RepoMetadata(name = name, website = website, discordUrl = discordUrl?.takeIf { it.isNotBlank() })
        preferences.extensionRepoMetadata().set(preferences.extensionRepoMetadata().get() + (indexUrl to metadata))
    }

    suspend fun checkForUpdates(
        context: Context,
        prefetchedExtensions: List<Extension.Available>? = null,
    ): List<Extension.Available> =
        withIOContext {
            val extensions = ExtensionCatalogReconciler.newestCopies(prefetchedExtensions ?: findExtensions())

            val extensionManager: ExtensionManager = Injekt.get()
            val installedExtensions =
                extensionManager.installedExtensionsFlow.value.ifEmpty {
                    ExtensionLoader
                        .loadExtensionAsync(context)
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

    private fun List<ExtensionJsonObject>.toExtensions(repoUrl: String): List<Extension.Available> =
        this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
            }.map {
                Extension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    isNovel = it.pkg.startsWith(NOVEL_EXTENSION_PACKAGE_PREFIX),
                    sources = it.sources ?: emptyList(),
                    apkName = it.apk,
                    iconUrl = "$repoUrl/icon/${it.pkg}.png",
                    repoUrl = repoUrl,
                )
            }

    private fun List<ExtensionProtoObject>.toAvailableExtensions(repoUrl: String): List<Extension.Available> =
        this
            .filter {
                val libVersion = it.extensionLib.toDoubleOrNull() ?: return@filter false
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
            }.map {
                val langs = it.sources.map { source -> source.language }.toSet()
                Extension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.packageName,
                    versionName = it.versionName,
                    versionCode = it.versionCode,
                    libVersion = it.extensionLib.toDouble(),
                    lang = if (langs.size == 1) langs.first() else "all",
                    isNsfw = it.contentWarning >= ExtensionProtoObject.ContentWarning.MIXED,
                    isNovel = it.isNovel || it.packageName.startsWith(NOVEL_EXTENSION_PACKAGE_PREFIX),
                    sources =
                        it.sources.map { source ->
                            Extension.AvailableSource(
                                name = source.name,
                                id = source.id,
                                lang = source.language,
                                baseUrl = source.homeUrl,
                            )
                        },
                    apkName = it.resources.apkUrl,
                    iconUrl = it.resources.iconUrl,
                    repoUrl = repoUrl,
                )
            }

    fun getApkUrl(extension: ExtensionManager.ExtensionInfo): String =
        if (extension.apkName.startsWith("http")) {
            extension.apkName
        } else {
            "${extension.repoUrl}/apk/${extension.apkName}"
        }

    private fun ExtensionJsonObject.extractLibVersion(): Double = version.substringBeforeLast('.').toDouble()
}

@Serializable
private data class RepoJsonObject(
    @SerialName("index_v2") val indexV2: String? = null,
    val meta: RepoJsonMeta? = null,
)

@Serializable
private data class RepoJsonMeta(
    val name: String,
    val website: String,
)

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<Extension.AvailableSource>?,
)

@Serializable
private data class ExtensionStoreProtoObject(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(4) val contact: ContactProtoObject? = null,
    @ProtoNumber(101) val extensionList: ExtensionListProtoObject? = null,
)

@Serializable
private data class ContactProtoObject(
    @ProtoNumber(1) val website: String = "",
    @ProtoNumber(2) val discord: String? = null,
)

@Serializable
private data class ExtensionListProtoObject(
    @ProtoNumber(1) val extensions: List<ExtensionProtoObject> = emptyList(),
)

@Serializable
private data class ExtensionProtoObject(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val packageName: String,
    @ProtoNumber(3) val resources: ResourcesProtoObject,
    @ProtoNumber(4) val extensionLib: String,
    @ProtoNumber(5) val versionCode: Long,
    @ProtoNumber(6) val versionName: String,
    @ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
    @ProtoNumber(8) val sources: List<SourceProtoObject> = emptyList(),
    @ProtoNumber(8000) val isNovel: Boolean = false,
) {
    @Serializable
    enum class ContentWarning {
        @ProtoNumber(0)
        UNSPECIFIED,

        @ProtoNumber(1)
        SAFE,

        @ProtoNumber(2)
        MIXED,

        @ProtoNumber(3)
        NSFW,
    }
}

@Serializable
private data class ResourcesProtoObject(
    @ProtoNumber(1) val apkUrl: String,
    @ProtoNumber(2) val iconUrl: String,
)

@Serializable
private data class SourceProtoObject(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val language: String,
    @ProtoNumber(4) val homeUrl: String = "",
)

private const val NOVEL_EXTENSION_PACKAGE_PREFIX = "eu.kanade.tachiyomi.novelextension"
