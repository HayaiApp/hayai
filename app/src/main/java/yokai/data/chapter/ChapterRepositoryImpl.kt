package yokai.data.chapter

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.MangaChapter
import eu.kanade.tachiyomi.util.system.toInt
import kotlinx.coroutines.flow.Flow
import yokai.data.DatabaseHandler
import yokai.domain.chapter.ChapterRepository
import yokai.domain.chapter.models.ChapterUpdate

class ChapterRepositoryImpl(private val handler: DatabaseHandler) : ChapterRepository {
    override suspend fun getChapters(mangaId: Long, filterScanlators: Boolean): List<Chapter> =
        handler.awaitList { chaptersQueries.getChaptersByMangaId(mangaId, filterScanlators.toInt().toLong(), Chapter::mapper) }

    override fun getChaptersAsFlow(mangaId: Long, filterScanlators: Boolean): Flow<List<Chapter>> =
        handler.subscribeToList { chaptersQueries.getChaptersByMangaId(mangaId, filterScanlators.toInt().toLong(), Chapter::mapper) }

    override suspend fun getChapterById(id: Long): Chapter? =
        handler.awaitOneOrNull { chaptersQueries.getChaptersById(id, Chapter::mapper) }

    override suspend fun getChaptersByUrl(url: String, filterScanlators: Boolean): List<Chapter> =
        handler.awaitList { chaptersQueries.getChaptersByUrl(url, filterScanlators.toInt().toLong(), Chapter::mapper) }

    override suspend fun getChapterByUrl(url: String, filterScanlators: Boolean): Chapter? =
        handler.awaitFirstOrNull { chaptersQueries.getChaptersByUrl(url, filterScanlators.toInt().toLong(), Chapter::mapper) }

    override suspend fun getChaptersByUrlAndMangaId(
        url: String,
        mangaId: Long,
        filterScanlators: Boolean
    ): List<Chapter> =
        handler.awaitList {
            chaptersQueries.getChaptersByUrlAndMangaId(url, mangaId, filterScanlators.toInt().toLong(), Chapter::mapper)
        }

    override suspend fun getChapterByUrlAndMangaId(
        url: String,
        mangaId: Long,
        filterScanlators: Boolean
    ): Chapter? =
        handler.awaitFirstOrNull {
            chaptersQueries.getChaptersByUrlAndMangaId(url, mangaId, filterScanlators.toInt().toLong(), Chapter::mapper)
        }

    override suspend fun getUnread(mangaId: Long, filterScanlators: Boolean): List<Chapter> =
        handler.awaitList {
            chaptersQueries.findUnreadByMangaId(mangaId, filterScanlators.toInt().toLong(), Chapter::mapper)
        }

    override suspend fun getRecents(tab: Long, filterScanlators: Boolean, showHidden: Boolean, search: String, limit: Long, offset: Long): List<MangaChapter> =
        handler.awaitList {
            chaptersQueries.getRecents(
                tab,
                search,
                filterScanlators.toInt().toLong(),
                showHidden.toInt().toLong(),
                limit,
                offset,
                MangaChapter::mapper,
            )
        }

    override suspend fun getScanlatorsByChapter(mangaId: Long): List<String> =
        handler.awaitList { chaptersQueries.getScanlatorsByMangaId(mangaId) { it.orEmpty() } }

    override fun getScanlatorsByChapterAsFlow(mangaId: Long): Flow<List<String>> =
        handler.subscribeToList { chaptersQueries.getScanlatorsByMangaId(mangaId) { it.orEmpty() } }

    override suspend fun delete(chapter: Chapter) =
        try {
            partialDelete(chapter.id!!)
            true
        } catch (e: Exception) {
            Logger.e(e) { "Failed to delete chapter with id '${chapter.id}'" }
            false
        }

    override suspend fun deleteAllById(chapters: List<Long>) =
        try {
            partialDelete(*chapters.toLongArray())
            true
        } catch (e: Exception) {
            Logger.e(e) { "Failed to bulk delete chapters" }
            false
        }

    private suspend fun partialDelete(vararg chapterIds: Long) {
        if (chapterIds.isEmpty()) return
        // Single set-based DELETE instead of one statement per id.
        handler.await {
            chaptersQueries.deleteBulk(chapterIds.toList())
        }
    }

    override suspend fun update(update: ChapterUpdate): Boolean =
        try {
            partialUpdate(update)
            true
        } catch (e: Exception) {
            Logger.e(e) { "Failed to update chapter with id '${update.id}'" }
            false
        }

    override suspend fun updateAll(updates: List<ChapterUpdate>): Boolean =
        try {
            partialUpdate(*updates.toTypedArray())
            true
        } catch (e: Exception) {
            Logger.e(e) { "Failed to bulk update chapters" }
            false
        }

    private suspend fun partialUpdate(vararg updates: ChapterUpdate) {
        handler.await(inTransaction = true) {
            updates.forEach { update ->
                chaptersQueries.update(
                    chapterId = update.id,
                    mangaId = update.mangaId,
                    url = update.url,
                    name = update.name,
                    scanlator = update.scanlator,
                    read = update.read,
                    bookmark = update.bookmark,
                    lastPageRead = update.lastPageRead,
                    pagesLeft = update.pagesLeft,
                    chapterNumber = update.chapterNumber,
                    sourceOrder = update.sourceOrder,
                    dateFetch = update.dateFetch,
                    dateUpload = update.dateUpload
                )
            }
        }
    }

    override suspend fun insert(chapter: Chapter): Long? {
        if (chapter.manga_id == null) return null

        return handler.awaitOneOrNullExecutable(inTransaction = true) {
            chaptersQueries.insert(
                mangaId = chapter.manga_id!!,
                url = chapter.url,
                name = chapter.name,
                scanlator = chapter.scanlator,
                read = chapter.read,
                bookmark = chapter.bookmark,
                lastPageRead = chapter.last_page_read.toLong(),
                pagesLeft = chapter.pages_left.toLong(),
                chapterNumber = chapter.chapter_number.toDouble(),
                sourceOrder = chapter.source_order.toLong(),
                dateFetch = chapter.date_fetch,
                dateUpload = chapter.date_upload,
            )
            chaptersQueries.selectLastInsertedRowId()
        }
    }

    override suspend fun insertBulk(chapters: List<Chapter>) =
        handler.await(true) {
            chapters.map { chapter ->
                chaptersQueries.insert(
                    mangaId = chapter.manga_id!!,
                    url = chapter.url,
                    name = chapter.name,
                    scanlator = chapter.scanlator,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    lastPageRead = chapter.last_page_read.toLong(),
                    pagesLeft = chapter.pages_left.toLong(),
                    chapterNumber = chapter.chapter_number.toDouble(),
                    sourceOrder = chapter.source_order.toLong(),
                    dateFetch = chapter.date_fetch,
                    dateUpload = chapter.date_upload,
                )
                val lastInsertId = chaptersQueries.selectLastInsertedRowId().executeAsOne()
                chapter.copy().apply { id = lastInsertId }
            }
        }
}
