package dev.ahmedmohamed.hayai.adult.eh.update

import dev.ahmedmohamed.hayai.adult.eh.domain.EhFailure
import dev.ahmedmohamed.hayai.adult.eh.domain.EhRevision
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import dev.ahmedmohamed.hayai.adult.eh.network.EhHttpGateway
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryAlias
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryIdentity
import dev.ahmedmohamed.hayai.adult.eh.persistence.HayaiEhPersistenceStore
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMangaIdentity
import dev.ahmedmohamed.hayai.adult.eh.source.EhentaiSource
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

interface EhGalleryUpdateOperations {
    suspend fun candidates(): List<EhGalleryUpdateCandidate>
    suspend fun update(candidate: EhGalleryUpdateCandidate): EhGalleryUpdateResult
}

class EhGalleryUpdateRuntime(
    private val database: DatabaseHelper,
    private val sourceManager: SourceManager,
    private val gateway: EhHttpGateway,
    private val persistence: HayaiEhPersistenceStore,
    private val stateStore: EhGalleryUpdateStateStore,
    private val downloadManager: DownloadManager,
    private val renameJournal: EhDownloadRenameJournal,
    private val downloadTreeMerger: EhDownloadTreeMerger,
    private val clock: () -> Long = System::currentTimeMillis,
) : EhGalleryUpdateOperations {
    override suspend fun candidates(): List<EhGalleryUpdateCandidate> = withContext(Dispatchers.IO) {
        val now = clock()
        database.getFavoriteMangas().executeAsBlocking()
            .asSequence()
            .filter { manga -> manga.source in SUPPORTED_SOURCE_IDS }
            .mapNotNull { manga ->
                val id = manga.id ?: return@mapNotNull null
                val identity = SourceMangaIdentity(manga.source, manga.url)
                val state = stateStore.state(identity)
                if (state.aged || now - state.checkedAt < MIN_CHECK_INTERVAL_MILLIS) return@mapNotNull null
                EhGalleryUpdateCandidate(id, manga.source, manga.title, manga.url, state)
            }
            .sortedWith(compareBy({ it.state.checkedAt }, { it.mangaId }))
            .take(MAX_GALLERIES_PER_RUN)
            .toList()
    }

    override suspend fun update(candidate: EhGalleryUpdateCandidate): EhGalleryUpdateResult {
        val manga = withContext(Dispatchers.IO) { database.getManga(candidate.mangaId).executeAsBlocking() }
            ?: return EhGalleryUpdateResult(EhGalleryUpdateDisposition.Unchanged, candidate.title)
        val source = sourceManager.get(candidate.sourceId) as? EhentaiSource
            ?: return EhGalleryUpdateResult(EhGalleryUpdateDisposition.PermanentFailure, candidate.title, failure = "E-Hentai source is unavailable")
        val identity = SourceMangaIdentity(candidate.sourceId, candidate.mangaUrl)
        val now = clock()

        return try {
            val start = GalleryKey.parse(candidate.mangaUrl)
            val firstDetails = gateway.details(candidate.site(), start)
            val newest = (firstDetails.newerRevisions + firstDetails.metadata.asRevision())
                .maxWithOrNull(compareBy<EhRevision>({ it.postedAtMillis ?: 0 }, { it.key.id.value }))
                ?.key ?: start
            val remoteDetails = source.getMangaDetails(SManga.create().apply { url = newest.normalizedPath })
            val chain = gateway.revisionChain(candidate.site(), newest)
            check(chain.isNotEmpty()) { "E-Hentai returned an empty revision chain" }
            val remote = chain.map { revision ->
                EhRemoteRevision(revision.key, revision.title, revision.postedAtMillis ?: 0)
            }
            val revisionKeys = remote.mapTo(mutableSetOf()) { it.key }
            val roots = withContext(Dispatchers.IO) {
                database.getFavoriteMangas().executeAsBlocking().filter { root ->
                    root.source == candidate.sourceId && runCatching { GalleryKey.parse(root.url) }.getOrNull() in revisionKeys
                }.ifEmpty { listOf(manga) }
            }
            val merge = mergeIntoJ2k(roots, source, remoteDetails, remote)
            persistAliases(remote)
            val postedAt = chain.last().postedAtMillis
            val aged = postedAt != null && now - postedAt >= GALLERY_AGE_MILLIS
            val completedState = EhGalleryUpdateState(now, now.takeIf { aged }, null)
            roots.forEach { root -> stateStore.record(SourceMangaIdentity(root.source, root.url), completedState) }
            EhGalleryUpdateResult(
                disposition = when {
                    aged -> EhGalleryUpdateDisposition.Aged
                    merge.newRevisionCount > 0 -> EhGalleryUpdateDisposition.Updated
                    else -> EhGalleryUpdateDisposition.Unchanged
                },
                title = roots.minBy { requireNotNull(it.id) }.title,
                newRevisionCount = merge.newRevisionCount,
            )
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            when (failure) {
                is EhFailure.AuthenticationRequired -> EhGalleryUpdateResult(EhGalleryUpdateDisposition.AuthenticationSkipped, candidate.title)
                is EhFailure.GalleryNotFound -> {
                    stateStore.record(identity, EhGalleryUpdateState(now, now, now))
                    EhGalleryUpdateResult(EhGalleryUpdateDisposition.NotFound, candidate.title)
                }
                is EhFailure.Network, is EhFailure.RateLimited ->
                    EhGalleryUpdateResult(EhGalleryUpdateDisposition.TransientFailure, candidate.title, failure = failure.message)
                else -> EhGalleryUpdateResult(EhGalleryUpdateDisposition.PermanentFailure, candidate.title, failure = failure.message)
            }
        }
    }

    private suspend fun mergeIntoJ2k(
        roots: List<Manga>,
        source: EhentaiSource,
        remoteDetails: SManga,
        remote: List<EhRemoteRevision>,
    ): EhRevisionMerge = withContext(Dispatchers.IO) {
        require(roots.isNotEmpty())
        val manga = roots.minBy { requireNotNull(it.id) }
        val discarded = roots.filterNot { it.id == manga.id }
        val chaptersByManga = roots.associateWith { database.getChapters(it).executeAsBlocking() }
        val local = chaptersByManga.values.flatten()
        val existingById = local.associateBy { it.id }
        val pendingRenames = renameJournal.pending(requireNotNull(manga.id))
        pendingRenames.forEach { pending ->
            val chapter = existingById[pending.chapterId] ?: return@forEach
            val after = chapter.copyForRename().apply { name = pending.newName }
            when {
                downloadManager.isChapterDownloaded(after, manga, skipCache = true) -> Unit
                downloadManager.isChapterDownloaded(chapter, manga, skipCache = true) -> {
                    downloadManager.renameChapter(source, manga, chapter, after)
                    check(downloadManager.isChapterDownloaded(after, manga, skipCache = true)) {
                        "Unable to recover E-Hentai download rename for chapter ${pending.chapterId}"
                    }
                }
            }
        }
        val pendingRenameIds = pendingRenames.map { it.chapterId }.toSet()
        val history = roots.flatMap { database.getHistoryByMangaId(requireNotNull(it.id)).executeAsBlocking() }.associateBy(History::chapter_id)
        val mangaById = roots.associateBy { it.id }
        val localModels = local.map { chapter ->
            val itemHistory = history[chapter.id]
            EhLocalRevision(
                id = requireNotNull(chapter.id),
                url = chapter.url,
                title = chapter.name,
                read = chapter.read,
                bookmark = chapter.bookmark,
                lastPageRead = chapter.last_page_read,
                pagesLeft = chapter.pages_left,
                downloaded = downloadManager.isChapterDownloaded(chapter, requireNotNull(mangaById[chapter.manga_id]), skipCache = true),
                historyLastRead = itemHistory?.last_read ?: 0,
                historyTimeRead = itemHistory?.time_read ?: 0,
            )
        }
        val merge = EhRevisionMerger.merge(remote, localModels)
        val localById = existingById
        val preparedRenames = mutableSetOf<Long>()
        val renames = mutableListOf<Pair<Chapter, Chapter>>()
        val chapters = merge.mutations.mapIndexed { index, mutation ->
            val existing = mutation.localId?.let(localById::get)
            val chapter = existing ?: Chapter.create().apply {
                manga_id = manga.id
                url = mutation.remote.key.normalizedPath
                date_fetch = clock()
            }
            chapter.manga_id = manga.id
            val before = existing?.copyForRename()
            chapter.name = "v${index + 1} · ${mutation.remote.title}"
            chapter.chapter_number = index + 1f
            chapter.source_order = merge.mutations.lastIndex - index
            chapter.date_upload = mutation.remote.postedAt
            chapter.read = mutation.read
            chapter.bookmark = mutation.bookmark
            chapter.last_page_read = mutation.lastPageRead
            chapter.pages_left = mutation.pagesLeft
            if (before != null && mutation.downloaded && before.name != chapter.name) {
                renames += before to chapter
            }
            chapter
        }

        val stableUrl = manga.url
        val stableTitle = manga.title
        manga.copyFrom(remoteDetails)
        manga.url = stableUrl
        manga.title = stableTitle
        manga.last_update = clock()
        manga.favorite = roots.any(Manga::favorite)
        manga.date_added = roots.map(Manga::date_added).filter { it > 0 }.minOrNull() ?: manga.date_added
        discarded.forEach { fromManga ->
            chaptersByManga.getValue(fromManga).filter { downloadManager.isChapterDownloaded(it, fromManga, skipCache = true) }.forEach { chapter ->
                downloadTreeMerger.copyDownloadedChapter(source, fromManga, manga, chapter)
            }
        }
        renames.forEach { (before, after) ->
            renameJournal.prepare(EhPendingDownloadRename(requireNotNull(manga.id), requireNotNull(before.id), before.name, after.name))
            preparedRenames += requireNotNull(before.id)
        }
        renames.forEach { (before, after) ->
            downloadManager.renameChapter(source, manga, before, after)
            check(downloadManager.isChapterDownloaded(after, manga, skipCache = true)) {
                "Unable to preserve downloaded E-Hentai revision ${before.id}"
            }
        }
        val selectedIds = merge.mutations.mapNotNullTo(mutableSetOf(), EhRevisionMutation::localId)
        val unmatched = local.filter { chapter ->
            val chapterId = chapter.id
            (chapterId == null || chapterId !in selectedIds) &&
                merge.mutations.none { it.remote.key == runCatching { GalleryKey.parse(chapter.url) }.getOrNull() }
        }.onEach { it.manga_id = manga.id }
        val categories = roots.flatMap { database.getCategoriesForManga(it).executeAsBlocking() }.distinctBy { it.id }
        database.inTransaction {
            database.insertManga(manga).executeAsBlocking()
            database.insertChapters(chapters + unmatched).executeAsBlocking()
            merge.mutations.forEach { mutation ->
                val chapterId = mutation.localId ?: return@forEach
                if (mutation.historyLastRead <= 0 && mutation.historyTimeRead <= 0) return@forEach
                database.upsertHistoryLastRead(
                    History.create(requireNotNull(chapters.firstOrNull { it.id == chapterId })).apply {
                        last_read = mutation.historyLastRead
                        time_read = mutation.historyTimeRead
                    },
                ).executeAsBlocking()
            }
            database.deleteOldMangasCategories(roots).executeAsBlocking()
            database.insertMangasCategories(
                categories.map { category -> MangaCategory.create(manga, category) },
            ).executeAsBlocking()
            discarded.forEach { database.deleteManga(it).executeAsBlocking() }
        }
        renameJournal.complete(requireNotNull(manga.id), pendingRenameIds + preparedRenames)
        discarded.forEach { downloadTreeMerger.removeMergedManga(source, it) }
        downloadManager.refreshCache()
        merge
    }

    private fun persistAliases(remote: List<EhRemoteRevision>) {
        val canonical = remote.minWith(compareBy<EhRemoteRevision>({ it.postedAt }, { it.key.id.value })).key.toPersistence()
        remote.asSequence().map { it.key.toPersistence() }.filter { it != canonical }.forEach { alternate ->
            runCatching { persistence.addAlias(EhGalleryAlias(canonical, alternate)) }
                .onFailure { Timber.w(it, "Unable to persist E-Hentai revision alias %s", alternate.gid) }
        }
    }

    private fun Chapter.copyForRename(): Chapter = Chapter.create().also { it.copyFrom(this) }

    private fun dev.ahmedmohamed.hayai.adult.eh.domain.EhGalleryMetadata.asRevision() =
        EhRevision(key, title, postedAtMillis)

    private fun GalleryKey.toPersistence() = EhGalleryIdentity(id.value, token.value)

    companion object {
        const val MAX_GALLERIES_PER_RUN = 50
        const val FAILURE_CUTOFF = 5
        const val MIN_CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1_000
        const val GALLERY_AGE_MILLIS = 365L * 24 * 60 * 60 * 1_000
        private val SUPPORTED_SOURCE_IDS = dev.ahmedmohamed.hayai.adult.eh.domain.EhSite.entries.map { it.sourceId }.toSet()
    }
}
