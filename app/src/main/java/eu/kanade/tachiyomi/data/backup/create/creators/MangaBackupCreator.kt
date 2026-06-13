package eu.kanade.tachiyomi.data.backup.create.creators

import android.content.Context
import hayai.novel.reader.quote.QuoteManager
import yokai.util.koin.get
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovelQuote
import eu.kanade.tachiyomi.data.backup.models.BackupSeriesKnowledge
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import yokai.data.DatabaseHandler
import yokai.domain.category.interactor.GetCategories
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.history.interactor.GetHistory
import yokai.domain.series.SeriesKnowledgeRepository
import yokai.domain.track.interactor.GetTrack

class MangaBackupCreator(
    private val context: Context = get(),
    private val customMangaManager: CustomMangaManager = get(),
    private val handler: DatabaseHandler = get(),
    private val getCategories: GetCategories = get(),
    private val getChapter: GetChapter = get(),
    private val getHistory: GetHistory = get(),
    private val getTrack: GetTrack = get(),
    private val seriesKnowledgeRepository: SeriesKnowledgeRepository = get(),
    private val quoteManagerFactory: (Context) -> QuoteManager = ::QuoteManager,
) {
    suspend operator fun invoke(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        return mangas.map {
            backupManga(it, options)
        }
    }

    /**
     * Convert a manga to Json
     *
     * @param manga manga that gets converted
     * @param options options for the backup
     * @return [BackupManga] containing manga in a serializable form
     */
    private suspend fun backupManga(manga: Manga, options: BackupOptions): BackupManga {
        // Entry for this manga
        val mangaObject = BackupManga.copyFrom(manga, if (options.customInfo) customMangaManager else null)

        // Check if user wants chapter information in backup
        if (options.chapters) {
            // Backup all the chapters
            val chapters = manga.id?.let {
                handler.awaitList {
                    chaptersQueries.getChaptersByMangaId(
                        it,
                        0,  // We want all chapters, so ignore scanlator filter
                        BackupChapter::mapper)
                }
            }.orEmpty()
            if (chapters.isNotEmpty()) {
                mangaObject.chapters = chapters
            }
        }

        // Check if user wants category information in backup
        if (options.categories) {
            // Backup categories for this manga
            val categoriesForManga = manga.id?.let {
                getCategories.awaitByMangaId(it)
            }.orEmpty()
            if (categoriesForManga.isNotEmpty()) {
                mangaObject.categories = categoriesForManga.mapNotNull { it.order }
            }
        }

        // Check if user wants track information in backup
        if (options.tracking) {
            val tracks = manga.id?.let {
                getTrack.awaitAllByMangaId(it)
            }.orEmpty()
            if (tracks.isNotEmpty()) {
                mangaObject.tracking = tracks.map { BackupTracking.copyFrom(it) }
            }
        }

        // Check if user wants history information in backup
        if (options.history) {
            val historyForManga = manga.id?.let {
                getHistory.awaitAllByMangaId(it)
            }.orEmpty()
            if (historyForManga.isNotEmpty()) {
                val history = historyForManga.mapNotNull { history ->
                    val url = getChapter.awaitById(history.chapter_id)?.url
                    url?.let { BackupHistory(url, history.last_read, history.time_read) }
                }
                if (history.isNotEmpty()) {
                    mangaObject.history = history
                }
            }
        }

        // EXH -->
        // Backup EXH metadata if available
        // When the metadata repository is wired:
        // manga.id?.let { mangaId ->
        //     val metadata = metadataRepository.getMetadataForManga(mangaId)
        //     if (metadata != null) {
        //         mangaObject.flatMetadata = metadata.toByteArray()
        //     }
        // }
        // EXH <--

        manga.id?.let { mangaId ->
            val knowledge = BackupSeriesKnowledge.copyFrom(seriesKnowledgeRepository.get(mangaId))
            if (!knowledge.isEmpty()) {
                mangaObject.seriesKnowledge = knowledge
            }
            val quotes = quoteManagerFactory(context).getQuotes(mangaId)
            if (quotes.isNotEmpty()) {
                mangaObject.novelQuotes = quotes.map(BackupNovelQuote::copyFrom)
            }
        }

        return mangaObject
    }
}
