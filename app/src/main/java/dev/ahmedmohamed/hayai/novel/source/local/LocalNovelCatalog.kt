package dev.ahmedmohamed.hayai.novel.source.local

import java.io.File

internal class LocalNovelCatalog(
    private val roots: List<File>,
) {
    fun entries(): List<File> =
        roots
            .asSequence()
            .flatMap { it.listFiles().orEmpty().asSequence() }
            .filterNot { it.name.startsWith('.') }
            .filter { it.isDirectory || it.isSupportedChapterFile() }
            .distinctBy { it.name.lowercase() }
            .toList()

    fun chapters(novelUrl: String): List<File> {
        val entry = resolve(novelUrl) ?: return emptyList()
        return if (entry.isDirectory) {
            entry
                .listFiles()
                .orEmpty()
                .filterNot { it.name.startsWith('.') }
                .filter { it.isSupportedChapterFile() || (it.isDirectory && it.hasSupportedTextFiles()) }
        } else {
            listOf(entry)
        }
    }

    fun metadataFile(novelUrl: String): File? =
        resolve(novelUrl)
            ?.takeIf(File::isDirectory)
            ?.listFiles()
            ?.firstOrNull { it.isFile && it.extension.equals("json", ignoreCase = true) }

    fun comicInfoFile(novelUrl: String): File? =
        resolve(novelUrl)
            ?.takeIf(File::isDirectory)
            ?.listFiles()
            ?.firstOrNull { it.isFile && it.name.equals("ComicInfo.xml", ignoreCase = true) }

    fun coverFile(novelUrl: String): File? =
        resolve(novelUrl)
            ?.takeIf(File::isDirectory)
            ?.listFiles()
            ?.firstOrNull {
                it.isFile &&
                    it.nameWithoutExtension.equals("cover", ignoreCase = true) &&
                    it.extension.lowercase() in IMAGE_EXTENSIONS
            }

    fun resolveNovel(novelUrl: String): File? = resolve(novelUrl)

    fun resolveChapter(chapterUrl: String): File? = resolve(chapterUrl.substringBefore('#'))

    fun resolveRelativeAsset(
        chapterUrl: String,
        assetPath: String,
    ): File? {
        val chapter = resolveChapter(chapterUrl) ?: return null
        val parent = if (chapter.isDirectory) chapter else chapter.parentFile ?: return null
        val candidate = File(parent, assetPath).canonicalFile
        return candidate.takeIf { it.isInside(parent.canonicalFile) && it.isFile }
    }

    private fun resolve(relativePath: String): File? {
        if (relativePath.isBlank() || File(relativePath).isAbsolute) return null
        return roots.firstNotNullOfOrNull { root ->
            val canonicalRoot = root.canonicalFile
            val candidate = File(canonicalRoot, relativePath.replace('/', File.separatorChar)).canonicalFile
            candidate.takeIf { it.isInside(canonicalRoot) && it.exists() }
        }
    }

    private fun File.isInside(root: File): Boolean = path == root.path || path.startsWith(root.path + File.separator)

    private fun File.hasSupportedTextFiles(): Boolean = listFiles().orEmpty().any { it.isSupportedTextFile() }

    companion object {
        val PLAIN_TEXT_EXTENSIONS = setOf("txt", "text")
        val MARKDOWN_EXTENSIONS = setOf("md", "markdown")
        val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")
        val EPUB_EXTENSIONS = setOf("epub")
        val ARCHIVE_EXTENSIONS = setOf("zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt")
        val TEXT_EXTENSIONS = PLAIN_TEXT_EXTENSIONS + MARKDOWN_EXTENSIONS + HTML_EXTENSIONS
        val SUPPORTED_EXTENSIONS = TEXT_EXTENSIONS + EPUB_EXTENSIONS + ARCHIVE_EXTENSIONS
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")

        fun File.isSupportedTextFile(): Boolean = isFile && extension.lowercase() in TEXT_EXTENSIONS

        fun File.isSupportedChapterFile(): Boolean = isFile && extension.lowercase() in SUPPORTED_EXTENSIONS
    }
}
