package dev.ahmedmohamed.hayai.novel.source.local

import android.content.Context
import dev.ahmedmohamed.hayai.novel.archive.ArchiveReader
import dev.ahmedmohamed.hayai.novel.archive.EpubReader
import dev.ahmedmohamed.hayai.novel.source.NovelAssetProvider
import dev.ahmedmohamed.hayai.novel.source.NovelSource
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelCatalog.Companion.ARCHIVE_EXTENSIONS
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelCatalog.Companion.EPUB_EXTENSIONS
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelCatalog.Companion.TEXT_EXTENSIONS
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelCatalog.Companion.isSupportedTextFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.chapter.ChapterRecognition
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class LocalNovelSource(
    private val context: Context,
) : NovelSource,
    NovelAssetProvider,
    UnmeteredSource {
    private val baseDirectories = getBaseDirectories(context)
    private val catalog = LocalNovelCatalog(baseDirectories)
    private val covers = LocalNovelCoverStore(catalog)
    private val json = Json { ignoreUnknownKeys = true }

    override val id = ID
    override val name: String = context.getString(R.string.local_novel_source)
    override val lang = "other"
    override val supportsLatest = true

    override fun toString(): String = name

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchManga(page, "", popularFilters)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchManga(page, "", latestFilters)

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = withContext(Dispatchers.IO) {
        val latestCutoff = if (filters === latestFilters) System.currentTimeMillis() - LATEST_THRESHOLD else 0L
        var entries =
            catalog
                .entries()
                .asSequence()
                .filter { it.displayName().contains(query, ignoreCase = true) }
                .filter { latestCutoff == 0L || it.lastModified() >= latestCutoff }

        val selection = ((if (filters.isEmpty()) popularFilters else filters)[0] as OrderBy).state
        entries =
            when (selection?.index) {
                1 -> if (selection.ascending) entries.sortedBy(File::lastModified) else entries.sortedByDescending(File::lastModified)
                else -> {
                    val comparator = compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName() }
                    if (selection?.ascending != false) entries.sortedWith(comparator) else entries.sortedWith(comparator.reversed())
                }
            }

        MangasPage(
            entries.map { entry ->
                SManga.create().apply {
                    title = entry.displayName()
                    url = entry.name
                    thumbnail_url = covers.find(url)?.absolutePath
                }
            }.toList(),
            false,
        )
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        covers.find(manga.url)?.let { manga.thumbnail_url = it.absolutePath }
        runCatching {
            when {
                catalog.comicInfoFile(manga.url) != null -> {
                    catalog.comicInfoFile(manga.url)!!.inputStream().use(ComicInfoMetadata::parse).applyTo(manga)
                }
                catalog.metadataFile(manga.url) != null -> applyJsonMetadata(manga, catalog.metadataFile(manga.url)!!)
            }

            firstEpubFor(manga.url)?.let { epubFile ->
                ArchiveReader.open(epubFile).use { archive ->
                    EpubReader(archive).use { epub ->
                        val chapter = SChapter.create().apply { name = epubFile.nameWithoutExtension }
                        epub.fillMetadata(manga, chapter)
                        if (manga.title.isBlank()) manga.title = chapter.name
                        extractCover(epub, manga, force = false)
                    }
                }
            }
        }.onFailure { Timber.e(it, "Unable to read local novel metadata for ${manga.url}") }
        manga
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val novelEntry = catalog.resolveNovel(manga.url) ?: return@withContext emptyList()
        val chapterFiles =
            catalog
                .chapters(manga.url)
                .sortedWith { first, second -> first.name.compareToCaseInsensitiveNaturalOrder(second.name) }
        val hasMultipleEpubFiles = chapterFiles.count { it.extension.lowercase() in EPUB_EXTENSIONS } > 1
        val allChapters = mutableListOf<SChapter>()
        var runningChapterNumber = 0f

        chapterFiles.forEach { chapterFile ->
            val countBefore = allChapters.size
            val chapterUrl = if (novelEntry.isFile) manga.url else "${manga.url}/${chapterFile.name}"
            if (chapterFile.extension.lowercase() in EPUB_EXTENSIONS) {
                runCatching {
                    ArchiveReader.open(chapterFile).use { archive ->
                        EpubReader(archive).use { epub ->
                            extractCover(epub, manga, force = false)
                            val epubChapters =
                                buildEpubChaptersFromToc(
                                    chapterFileUrl = chapterUrl,
                                    chapterFileNameWithoutExtension = chapterFile.nameWithoutExtension,
                                    chapterLastModified = chapterFile.lastModified(),
                                    tocChapters = epub.getNormalizedTableOfContents(),
                                    spinePageHrefs = epub.getSpinePageHrefs(),
                                    hasMultipleEpubFiles = hasMultipleEpubFiles,
                                    chapterNumberOffset = if (hasMultipleEpubFiles) runningChapterNumber else 0f,
                                )
                            if (epubChapters.isNotEmpty()) {
                                allChapters += epubChapters
                            } else {
                                allChapters += createSimpleChapter(manga, chapterFile, chapterUrl).also {
                                    if (hasMultipleEpubFiles) it.chapter_number = runningChapterNumber + 1f
                                    epub.fillMetadata(manga, it)
                                }
                            }
                        }
                    }
                }.onFailure { error ->
                    Timber.e(error, "Unable to parse EPUB ${chapterFile.path}")
                    allChapters += createSimpleChapter(manga, chapterFile, chapterUrl).also {
                        if (hasMultipleEpubFiles) it.chapter_number = runningChapterNumber + 1f
                    }
                }
            } else {
                allChapters += createSimpleChapter(manga, chapterFile, chapterUrl).also {
                    if (hasMultipleEpubFiles) it.chapter_number = runningChapterNumber + 1f
                }
            }

            if (hasMultipleEpubFiles) runningChapterNumber += (allChapters.size - countBefore).toFloat()
        }

        allChapters.sortedWith { first, second ->
            val numberOrder = second.chapter_number.compareTo(first.chapter_number)
            if (numberOrder == 0) second.name.compareToCaseInsensitiveNaturalOrder(first.name) else numberOrder
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String = withContext(Dispatchers.IO) {
        val chapterFile = catalog.resolveChapter(page.url) ?: error(context.getString(R.string.chapter_not_found))
        val fragment = page.url.substringAfter('#', "").takeIf(String::isNotBlank)
        when {
            chapterFile.isDirectory -> readTextDirectory(chapterFile)
            chapterFile.extension.lowercase() in EPUB_EXTENSIONS -> {
                ArchiveReader.open(chapterFile).use { archive ->
                    EpubReader(archive).use { epub ->
                        fragment?.let(epub::getChapterContent) ?: epub.getTextContent()
                    }
                }
            }
            chapterFile.extension.lowercase() in ARCHIVE_EXTENSIONS -> readTextArchive(chapterFile)
            chapterFile.isSupportedTextFile() -> {
                NovelAssetRewriter.rewrite(chapterFile.readText(), chapterFile.extension, NovelAssetRewriter::relativeScheme)
            }
            else -> error(context.getString(R.string.local_invalid_format))
        }
    }

    override suspend fun getChapterAsset(
        chapterUrl: String,
        assetPath: String,
    ): InputStream? = withContext(Dispatchers.IO) {
        runCatching {
            val chapterFile = catalog.resolveChapter(chapterUrl) ?: return@runCatching null
            when {
                chapterFile.extension.lowercase() in EPUB_EXTENSIONS -> {
                    ArchiveReader.open(chapterFile).use { archive ->
                        EpubReader(archive).use { epub -> epub.getInputStream(assetPath)?.readLimitedBytes()?.let(::ByteArrayInputStream) }
                    }
                }
                chapterFile.extension.lowercase() in ARCHIVE_EXTENSIONS -> {
                    ArchiveReader.open(chapterFile).use { archive ->
                        archive.getInputStream(assetPath)?.use { it.readLimitedBytes() }?.let(::ByteArrayInputStream)
                    }
                }
                else -> catalog.resolveRelativeAsset(chapterUrl, assetPath)?.inputStream()
            }
        }.onFailure { Timber.e(it, "Unable to load local novel asset $assetPath") }.getOrNull()
    }

    suspend fun refreshCover(manga: SManga): String? = withContext(Dispatchers.IO) {
        val epubFile = firstEpubFor(manga.url) ?: return@withContext null
        val refreshed =
            runCatching {
                ArchiveReader.open(epubFile).use { archive ->
                    EpubReader(archive).use { extractCover(it, manga, force = true) }
                }
            }.getOrDefault(false)
        if (refreshed) covers.find(manga.url)?.absolutePath else null
    }

    fun getFormat(chapter: SChapter): LocalNovelFormat {
        val file = catalog.resolveChapter(chapter.url) ?: error(context.getString(R.string.chapter_not_found))
        return when {
            file.isDirectory -> LocalNovelFormat.Directory
            file.extension.lowercase() in EPUB_EXTENSIONS -> LocalNovelFormat.Epub
            file.extension.lowercase() in ARCHIVE_EXTENSIONS -> LocalNovelFormat.Archive
            file.extension.lowercase() in LocalNovelCatalog.HTML_EXTENSIONS -> LocalNovelFormat.Html
            file.extension.lowercase() in LocalNovelCatalog.MARKDOWN_EXTENSIONS -> LocalNovelFormat.Markdown
            file.extension.lowercase() in LocalNovelCatalog.PLAIN_TEXT_EXTENSIONS -> LocalNovelFormat.PlainText
            else -> error(context.getString(R.string.local_invalid_format))
        }
    }

    fun getLocalSourceDirectories(): List<File> = baseDirectories.toList()

    override fun getFilterList(): FilterList = popularFilters

    private fun createSimpleChapter(
        manga: SManga,
        chapterFile: File,
        chapterUrl: String,
    ): SChapter =
        SChapter.create().apply {
            url = chapterUrl
            name = chapterFile.displayName()
            date_upload = chapterFile.lastModified()
            ChapterRecognition.parseChapterNumber(this, manga)
            if (chapterFile.isDirectory) {
                chapterFile
                    .listFiles()
                    ?.firstOrNull { it.name.equals("ComicInfo.xml", ignoreCase = true) }
                    ?.let { metadata -> runCatching { metadata.inputStream().use(ComicInfoMetadata::parse).applyTo(this) } }
            }
        }

    private fun applyJsonMetadata(
        manga: SManga,
        metadata: File,
    ) {
        metadata.inputStream().use { json.decodeFromStream<NovelInfo>(it) }.run {
            title?.let { manga.title = it }
            author?.let { manga.author = it }
            artist?.let { manga.artist = it }
            description?.let { manga.description = it }
            genre?.let { manga.genre = it.joinToString(", ") }
            status?.let { manga.status = it }
        }
    }

    private fun firstEpubFor(novelUrl: String): File? {
        val novel = catalog.resolveNovel(novelUrl) ?: return null
        return when {
            novel.extension.lowercase() in EPUB_EXTENSIONS -> novel
            novel.isDirectory -> catalog.chapters(novelUrl).firstOrNull { it.extension.lowercase() in EPUB_EXTENSIONS }
            else -> null
        }
    }

    private fun extractCover(
        epub: EpubReader,
        manga: SManga,
        force: Boolean,
    ): Boolean {
        if (!force && covers.find(manga.url) != null) return true
        val coverPath = epub.getCoverImage() ?: return false
        val stream =
            if (coverPath.startsWith("http://") || coverPath.startsWith("https://")) {
                downloadCover(coverPath)?.let(::ByteArrayInputStream)
            } else {
                epub.getInputStream(coverPath)
            } ?: return false
        val extension = coverPath.substringBefore('?').substringAfterLast('.', "")
        val cover = covers.update(manga.url, extension, stream) ?: return false
        manga.thumbnail_url = cover.absolutePath
        return true
    }

    private fun downloadCover(url: String): ByteArray? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readLimitedBytes() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readTextDirectory(directory: File): String {
        val textFiles = directory.listFiles().orEmpty().filter { it.isSupportedTextFile() }.sortedBy { it.name.lowercase() }
        require(textFiles.isNotEmpty()) { "No supported text files found in ${directory.name}" }
        return textFiles.joinToString("\n\n") { file ->
            NovelAssetRewriter.rewrite(file.readText(), file.extension, NovelAssetRewriter::relativeScheme)
        }
    }

    private fun readTextArchive(file: File): String =
        ArchiveReader.open(file).use { archive ->
            val entries =
                archive.useEntries { sequence ->
                    sequence
                        .filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS }
                        .sortedBy { it.name.lowercase() }
                        .toList()
                }
            require(entries.isNotEmpty()) { "No supported text files found in ${file.name}" }
            entries.joinToString("\n\n") { entry ->
                val text = archive.getInputStream(entry.name)?.use { it.reader().readText() }.orEmpty()
                val baseDirectory = entry.name.substringBeforeLast('/', "")
                NovelAssetRewriter.rewrite(text, entry.name.substringAfterLast('.', "")) {
                    NovelAssetRewriter.archiveScheme(baseDirectory, it)
                }
            }
        }

    private fun InputStream.readLimitedBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_ASSET_BYTES) { "Novel asset exceeds the size limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private val popularFilters = FilterList(OrderBy(context))
    private val latestFilters = FilterList(OrderBy(context).apply { state = Filter.Sort.Selection(1, false) })

    private class OrderBy(
        context: Context,
    ) : Filter.Sort(
            context.getString(R.string.order_by),
            arrayOf(context.getString(R.string.title), context.getString(R.string.date)),
            Selection(0, true),
        )

    @Serializable
    private data class NovelInfo(
        val title: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        val status: Int? = null,
    )

    enum class LocalNovelFormat {
        Directory,
        Epub,
        Archive,
        Html,
        Markdown,
        PlainText,
    }

    companion object {
        const val ID = 1L
        const val HELP_URL = "https://tsundoku-otaku.github.io/docs/guides/local-source/novels"
        private val LATEST_THRESHOLD = TimeUnit.MILLISECONDS.convert(7, TimeUnit.DAYS)
        private const val MAX_ASSET_BYTES = 50L * 1024 * 1024
        private const val STORAGE_DIRECTORY = "Hayai"

        private fun getBaseDirectories(context: Context): List<File> {
            val relativePath = STORAGE_DIRECTORY + File.separator + "localnovels"
            return DiskUtil.getExternalStorages(context).map { File(it, relativePath).apply { mkdirs() } }
        }
    }
}

private fun File.displayName(): String = if (isDirectory) name else nameWithoutExtension
