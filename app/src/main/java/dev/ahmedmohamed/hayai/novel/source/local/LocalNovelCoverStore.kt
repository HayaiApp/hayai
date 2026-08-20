package dev.ahmedmohamed.hayai.novel.source.local

import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.ImageUtil
import java.io.File
import java.io.InputStream

internal class LocalNovelCoverStore(
    private val catalog: LocalNovelCatalog,
) {
    fun find(novelUrl: String): File? =
        catalog.coverFile(novelUrl)
            ?: catalog.resolveNovel(novelUrl)?.takeIf(File::isFile)?.let(::findCachedSingleFileCover)

    fun update(
        novelUrl: String,
        suggestedExtension: String?,
        input: InputStream,
    ): File? {
        val novel = catalog.resolveNovel(novelUrl) ?: return input.use { null }
        val extension = suggestedExtension?.lowercase()?.takeIf { it in LocalNovelCatalog.IMAGE_EXTENSIONS } ?: "jpg"
        val target =
            if (novel.isDirectory) {
                find(novelUrl) ?: File(novel, "cover.$extension")
            } else {
                val coverDirectory = File(novel.parentFile, COVER_CACHE_DIRECTORY).apply { mkdirs() }
                File(coverDirectory, "${DiskUtil.hashKeyForDisk(novel.name)}.$extension")
            }

        target.parentFile?.mkdirs()
        File(target.parentFile, ".nomedia").createNewFile()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        return try {
            input.use { source -> temporary.outputStream().buffered().use(source::copyTo) }
            require(temporary.length() in 1..MAX_COVER_BYTES) { "Invalid cover size" }
            require(ImageUtil.isImage(target.name) { temporary.inputStream() }) { "EPUB cover is not a supported image" }
            if (!temporary.renameTo(target)) {
                temporary.inputStream().use { source -> target.outputStream().use(source::copyTo) }
                temporary.delete()
            }
            target
        } catch (_: Throwable) {
            temporary.delete()
            null
        }
    }

    private fun findCachedSingleFileCover(novel: File): File? {
        val directory = File(novel.parentFile, COVER_CACHE_DIRECTORY)
        val prefix = DiskUtil.hashKeyForDisk(novel.name) + "."
        return directory.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(prefix) }
    }

    companion object {
        private const val COVER_CACHE_DIRECTORY = ".hayai-covers"
        private const val MAX_COVER_BYTES = 20L * 1024 * 1024
    }
}
