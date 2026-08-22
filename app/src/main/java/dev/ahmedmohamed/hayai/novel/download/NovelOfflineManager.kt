package dev.ahmedmohamed.hayai.novel.download

import android.content.Context
import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import dev.ahmedmohamed.hayai.novel.error.novelRequire
import dev.ahmedmohamed.hayai.novel.reader.NovelReaderSession
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.SourceManager
import kotlinx.coroutines.CancellationException
import java.io.File

class NovelOfflineManager(
    private val context: Context,
    private val database: DatabaseHelper,
    private val sourceManager: SourceManager,
    private val network: NetworkHelper,
) {
    private val store = NovelDownloadStore(File(context.filesDir, "hayai/novel-downloads"))

    fun downloadedChapterIds(
        manga: Manga,
        chapters: Collection<Chapter>,
    ): Set<Long> =
        chapters
            .asSequence()
            .filter { store.contains(manga.source, it.url) }
            .mapNotNull(Chapter::id)
            .toSet()

    suspend fun download(
        manga: Manga,
        chapters: Collection<Chapter>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): NovelOfflineBatchResult {
        val selected = chapters.distinctBy(Chapter::id)
        val failures = mutableListOf<NovelOfflineFailure>()
        var saved = 0
        var unavailableAssets = 0
        selected.forEachIndexed { index, chapter ->
            try {
                val session = NovelReaderSession(context, database, sourceManager, store, network)
                val loaded = session.initialize(requireNotNull(manga.id), requireNotNull(chapter.id), recordHistory = false)
                val result = session.saveOffline(loaded)
                novelRequire(store.contains(manga.source, chapter.url), NovelFailure.Code.OfflineSavedVerify)
                saved++
                unavailableAssets += result.unavailableAssetCount
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures += NovelOfflineFailure(chapter.id, chapter.name, context.novelFailureMessage(error, R.string.hayai_failure_offline_download))
            }
            onProgress(index + 1, selected.size)
        }
        return NovelOfflineBatchResult(saved, unavailableAssets, failures)
    }

    fun remove(
        manga: Manga,
        chapters: Collection<Chapter>,
    ): NovelOfflineBatchResult {
        val selected = chapters.distinctBy(Chapter::id)
        val failures = mutableListOf<NovelOfflineFailure>()
        var removed = 0
        selected.forEach { chapter ->
            try {
                if (!store.contains(manga.source, chapter.url)) return@forEach
                novelRequire(store.remove(manga.source, chapter.url), NovelFailure.Code.OfflineRemove)
                novelRequire(!store.contains(manga.source, chapter.url), NovelFailure.Code.OfflineRemovedStillPresent)
                removed++
            } catch (error: Exception) {
                failures += NovelOfflineFailure(chapter.id, chapter.name, context.novelFailureMessage(error, R.string.hayai_failure_offline_removal))
            }
        }
        return NovelOfflineBatchResult(removed, 0, failures)
    }
}

data class NovelOfflineBatchResult(
    val completed: Int,
    val unavailableAssets: Int,
    val failures: List<NovelOfflineFailure>,
) {
    val succeeded: Boolean
        get() = failures.isEmpty()
}

data class NovelOfflineFailure(
    val chapterId: Long?,
    val chapterName: String,
    val reason: String,
)
