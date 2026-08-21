package dev.ahmedmohamed.hayai.adult.eh.update

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source

class EhDownloadTreeMerger(
    private val provider: DownloadProvider,
) {
    fun copyDownloadedChapter(
        source: Source,
        fromManga: Manga,
        toManga: Manga,
        chapter: Chapter,
    ) {
        if (fromManga.id == toManga.id) return
        val sourceChapter = provider.findChapterDir(chapter, fromManga, source) ?: return
        val sourceDir = provider.findSourceDir(source) ?: error("E-Hentai download source directory disappeared")
        val targetMangaDir = sourceDir.createDirectory(provider.getMangaDirName(toManga))
        val finalName = requireNotNull(sourceChapter.name)
        if (targetMangaDir.findFile(finalName) != null) return
        val partialName = ".hayai-part-$finalName"
        targetMangaDir.findFile(partialName)?.delete()
        val partial = if (sourceChapter.isDirectory) targetMangaDir.createDirectory(partialName) else targetMangaDir.createFile(partialName)
        copy(sourceChapter, partial)
        check(partial.renameTo(finalName)) { "Unable to finalize copied E-Hentai download $finalName" }
    }

    fun removeMergedManga(source: Source, manga: Manga) {
        provider.findMangaDir(manga, source)?.delete()
    }

    private fun copy(source: UniFile, target: UniFile) {
        if (source.isFile) {
            source.openInputStream().use { input -> target.openOutputStream().use(input::copyTo) }
            return
        }
        source.listFiles().orEmpty().forEach { child ->
            val name = requireNotNull(child.name)
            val destination = if (child.isDirectory) target.createDirectory(name) else target.createFile(name)
            copy(child, destination)
        }
    }
}
