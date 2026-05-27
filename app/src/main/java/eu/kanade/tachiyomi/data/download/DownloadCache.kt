package eu.kanade.tachiyomi.data.download

import yokai.util.koin.get
import android.app.Application
import android.content.Context
import android.net.Uri
import co.touchlab.kermit.Logger
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import hayai.novel.plugin.NovelPluginManager
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import eu.kanade.tachiyomi.util.system.extension
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchNonCancellableIO
import eu.kanade.tachiyomi.util.system.nameWithoutExtension
import java.io.File
import java.util.concurrent.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoBuf
import yokai.domain.storage.StorageManager

/**
 * Cache where we dump the downloads directory from the filesystem. This class is needed because
 * directory checking is expensive and it slowdowns the app. The cache is invalidated by the time
 * defined in [renewInterval] as we don't have any control over the filesystem and the user can
 * delete the folders at any time without the app noticing.
 *
 * @param context the application context.
 * @param provider the downloads directories provider.
 * @param sourceManager the source manager.
 * @param preferences the preferences of the app.
 */
class DownloadCache(
    private val context: Context,
    private val provider: DownloadProvider = get(),
    private val sourceManager: SourceManager = get(),
    private val storageManager: StorageManager = get(),
    private val extensionManager: ExtensionManager = get(),
    private val novelPluginManager: NovelPluginManager = get(),
) {

    val scope = CoroutineScope(Dispatchers.IO)

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .onStart { emit(Unit) }
        .shareIn(scope, SharingStarted.Lazily, 1)

    /**
     * The interval after which this cache should be invalidated. 1 hour shouldn't cause major
     * issues, as the cache is only used for UI feedback.
     */
    private val renewInterval = TimeUnit.HOURS.toMillis(1)

    /**
     * The last time the cache was refreshed.
     */
    private var lastRenew = 0L
    private var renewalJob: Job? = null

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing
        .debounce(1000L) // Don't notify if it finishes quickly enough
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val diskCacheFile: File
        get() = File(context.cacheDir, "dl_index_cache_v3")

    private val rootDownloadsDirLock = Mutex()
    private var rootDownloadsDir = RootDirectory(storageManager.getDownloadsDirectory())

    init {
        // Leave lastRenew at 0L so first access validates against the filesystem.
        scope.launch {
            rootDownloadsDirLock.withLock {
                try {
                    if (diskCacheFile.exists()) {
                        val diskCache = diskCacheFile.inputStream().use {
                            ProtoBuf.decodeFromByteArray<RootDirectory>(it.readBytes())
                        }
                        rootDownloadsDir = diskCache
                    }
                } catch (e: Throwable) {
                    Logger.e(e) { "Failed to initialize disk cache" }
                    diskCacheFile.delete()
                }
            }
        }

        storageManager.changes
            .onEach { forceRenewCache() } // invalidate cache
            .launchIn(scope)
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapter the chapter to check.
     * @param manga the manga of the chapter.
     * @param skipCache whether to skip the directory cache and check in the filesystem.
     */
    fun isChapterDownloaded(chapter: Chapter, manga: Manga, skipCache: Boolean): Boolean {
        if (skipCache) {
            val source = sourceManager.get(manga.source) ?: return false
            return provider.findChapterDir(chapter, manga, source) != null
        }

        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[manga.source]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga)]
            if (mangaDir != null) {
                return provider.getValidChapterDirNames(
                    chapter,
                ).any { it in mangaDir.chapterDirs }
            }
        }
        return false
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga, forceCheckFolder: Boolean = false): Int {
        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[manga.source]
        if (forceCheckFolder) {
            val source = sourceManager.get(manga.source) ?: return 0
            val mangaDir = provider.findMangaDir(manga, source)

            if (mangaDir != null) {
                val listFiles = mangaDir.listFiles { _, filename -> !filename.endsWith(Downloader.TMP_DIR_SUFFIX) }
                if (!listFiles.isNullOrEmpty()) {
                    return listFiles.size
                }
            }
            return 0
        } else {
            if (sourceDir != null) {
                val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga)]
                if (mangaDir != null) {
                    return mangaDir.chapterDirs.size
                }
            }
            return 0
        }
    }

    fun forceRenewCache() {
        lastRenew = 0L
        renewalJob?.cancel()
        diskCacheFile.delete()
        renewCache()
    }

    /**
     * Renews the downloads cache.
     */
    private fun renewCache() {
        if (lastRenew + renewInterval >= System.currentTimeMillis() || renewalJob?.isActive == true) {
            return
        }

        renewalJob = scope.launchIO {
            if (lastRenew == 0L) {
                _isInitializing.emit(true)
            }

            // Wait for sources before walking, else novels (QuickJS plugins) get dropped.
            var sources = emptyList<Source>()
            withTimeoutOrNull(30.seconds) {
                extensionManager.isInitialized.first { it }
                novelPluginManager.loadedFlow.first { it }
                sources = getSources()
            }

            val sourceMap = sources.associate { provider.getSourceDirName(it).lowercase() to it.id }

            rootDownloadsDirLock.withLock {
                val updatedRootDir = RootDirectory(storageManager.getDownloadsDirectory())

                updatedRootDir.sourceDirs = updatedRootDir.dir?.listFiles().orEmpty()
                    .filter { it.isDirectory && !it.name.isNullOrBlank() }
                    .mapNotNull { dir ->
                        val sourceId = sourceMap[dir.name!!.lowercase()]
                        sourceId?.let { it to SourceDirectory(dir) }
                    }
                    .toMap()

                updatedRootDir.sourceDirs.values
                    .map { sourceDir ->
                        async {
                            sourceDir.mangaDirs = sourceDir.dir?.listFiles().orEmpty()
                                .filter { it.isDirectory && !it.name.isNullOrBlank() }
                                .associate { it.name!! to MangaDirectory(it) }

                            sourceDir.mangaDirs.values.forEach { mangaDir ->
                                val chapterDirs = mangaDir.dir?.listFiles().orEmpty()
                                    .mapNotNull {
                                        when {
                                            // Ignore incomplete downloads
                                            it.name?.endsWith(Downloader.TMP_DIR_SUFFIX) == true -> null
                                            // Folder of images
                                            it.isDirectory -> it.name
                                            // CBZ files
                                            it.isFile && it.extension == "cbz" -> it.nameWithoutExtension
                                            // Anything else is irrelevant
                                            else -> null
                                        }
                                    }
                                    .toMutableSet()

                                mangaDir.chapterDirs = chapterDirs
                            }
                        }
                    }
                    .awaitAll()

                rootDownloadsDir = updatedRootDir
                _isInitializing.emit(false)
            }
        }.also {
            it.invokeOnCompletion(onCancelling = true) { exception ->
                if (exception != null && exception !is CancellationException) {
                    Logger.e(exception) { "DownloadCache: failed to create cache" }
                }
                lastRenew = System.currentTimeMillis()
                notifyChanges()
            }
        }

        // Mainly to notify the indexing notifier UI
        notifyChanges()
    }

    private fun getSources(): List<Source> {
        // CatalogueSources covers NovelSource too; HttpSource alone drops novel folders.
        return sourceManager.getCatalogueSources()
    }

    private fun notifyChanges() {
        scope.launchNonCancellableIO {
            _changes.send(Unit)
        }
        updateDiskCache()
    }

    private var updateDiskCacheJob: Job? = null
    private fun updateDiskCache() {
        updateDiskCacheJob?.cancel()
        updateDiskCacheJob = scope.launchIO {
            delay(1000)
            ensureActive()
            val bytes = ProtoBuf.encodeToByteArray(rootDownloadsDir)
            ensureActive()
            try {
                diskCacheFile.writeBytes(bytes)
            } catch (e: Throwable) {
                Logger.e(e) { "Failed to write disk cache file" }
            }
        }
    }

    /**
     * Adds a chapter that has just been download to this cache.
     *
     * @param chapterDirName the downloaded chapter's directory name.
     * @param mangaUniFile the directory of the manga.
     * @param manga the manga of the chapter.
     */
    suspend fun addChapter(chapterDirName: String, mangaUniFile: UniFile?, manga: Manga) {
        rootDownloadsDirLock.withLock {
            // Retrieve the cached source directory or cache a new one
            var sourceDir = rootDownloadsDir.sourceDirs[manga.source]
            if (sourceDir == null) {
                val source = sourceManager.get(manga.source) ?: return
                val sourceUniFile = provider.findSourceDir(source) ?: return
                sourceDir = SourceDirectory(sourceUniFile)
                rootDownloadsDir.sourceDirs += manga.source to sourceDir
            }

            // Retrieve the cached manga directory or cache a new one
            val mangaDirName = provider.getMangaDirName(manga)
            var mangaDir = sourceDir.mangaDirs[mangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
                sourceDir.mangaDirs += mangaDirName to mangaDir
            }

            // Save the chapter directory
            mangaDir.chapterDirs += chapterDirName
        }

        notifyChanges()
    }

    /**
     * Removes a list of chapters that have been deleted from this cache.
     *
     * @param chapters the list of chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapters(chapters: List<Chapter>, manga: Manga) {
        rootDownloadsDirLock.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga)] ?: return
            chapters.forEach { chapter ->
                provider.getValidChapterDirNames(chapter).forEach {
                    if (it in mangaDir.chapterDirs) {
                        mangaDir.chapterDirs -= it
                    }
                }
            }
        }

        notifyChanges()
    }

    suspend fun removeChapterFolders(folders: List<String>, manga: Manga) {
        rootDownloadsDirLock.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga)] ?: return

            folders.forEach { chapter ->
                if (chapter in mangaDir.chapterDirs) {
                    mangaDir.chapterDirs -= chapter
                }
            }
        }

        notifyChanges()
    }

/*fun renameFolder(from: String, to: String, source: Long) {
    val sourceDir = rootDir.files[source] ?: return
    val list = sourceDir.files.toMutableMap()
    val mangaFiles = sourceDir.files[DiskUtil.buildValidFilename(from)] ?: return
    val newFile = UniFile.fromFile(File(sourceDir.dir.filePath + "/" + DiskUtil
        .buildValidFilename(to))) ?: return
    val newDir = MangaDirectory(newFile)
    newDir.files = mangaFiles.files
    list.remove(DiskUtil.buildValidFilename(from))
    list[to] = newDir
    sourceDir.files = list
}*/

    /**
     * Removes a manga that has been deleted from this cache.
     *
     * @param manga the manga to remove.
     */
    suspend fun removeManga(manga: Manga) {
        rootDownloadsDirLock.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDirName = provider.getMangaDirName(manga)
            if (sourceDir.mangaDirs.containsKey(mangaDirName)) {
                sourceDir.mangaDirs -= mangaDirName
            }
        }

        notifyChanges()
    }

    suspend fun removeSource(source: Source) {
        rootDownloadsDirLock.withLock {
            rootDownloadsDir.sourceDirs -= source.id
        }

        notifyChanges()
    }

    /**
     * Returns a new map containing only the key entries of [transform] that are not null.
     */
    private inline fun <K, V, R> Map<out K, V>.mapNotNullKeys(transform: (Map.Entry<K?, V>) -> R?): Map<R, V> {
        val destination = LinkedHashMap<R, V>()
        forEach { element -> transform(element)?.let { destination.put(it, element.value) } }
        return destination
    }

    /**
     * Returns a map from a list containing only the key entries of [transform] that are not null.
     */
    private inline fun <T, K, V> Array<T>.associateNotNullKeys(transform: (T) -> Pair<K?, V>): Map<K, V> {
        val destination = LinkedHashMap<K, V>()
        for (element in this) {
            val (key, value) = transform(element)
            if (key != null) {
                destination[key] = value
            }
        }
        return destination
    }
}

/**
 * Class to store the files under the root downloads directory.
 */
@Serializable
private class RootDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var sourceDirs: Map<Long, SourceDirectory> = hashMapOf(),
)

/**
 * Class to store the files under a source directory.
 */
@Serializable
private class SourceDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var mangaDirs: Map<String, MangaDirectory> = hashMapOf(),
)

/**
 * Class to store the files under a manga directory.
 */
@Serializable
private class MangaDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var chapterDirs: MutableSet<String> = hashSetOf(),
)

private object UniFileAsStringSerializer : KSerializer<UniFile?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UniFile", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UniFile?) {
        return if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.uri.toString())
        }
    }

    override fun deserialize(decoder: Decoder): UniFile? {
        return if (decoder.decodeNotNullMark()) {
            UniFile.fromUri(get<Application>(), Uri.parse(decoder.decodeString()))
        } else {
            decoder.decodeNull()
        }
    }
}
