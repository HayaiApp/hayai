package dev.ahmedmohamed.hayai.source.enhanced.batch

import dev.ahmedmohamed.hayai.source.enhanced.EnhancedSourceLinkRouter
import dev.ahmedmohamed.hayai.source.enhanced.HayaiEnhancedHttpSource
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

class J2kEnhancedBatchLibraryGateway(
    private val sourceManager: SourceManager,
    private val database: DatabaseHelper,
) : EnhancedBatchLibraryGateway {
    private val linkRouter = EnhancedSourceLinkRouter {
        sourceManager.getDiscoverableOnlineSources().filterIsInstance<HayaiEnhancedHttpSource>()
    }

    override suspend fun resolve(entry: EnhancedBatchEntry): EnhancedResolveResult {
        return linkRouter.resolve(entry)
    }

    override suspend fun addToLibrary(target: EnhancedImportTarget): EnhancedBatchItemResult {
        val source = sourceManager.get(target.sourceId) as? HayaiEnhancedHttpSource
            ?: return EnhancedBatchItemResult.Failed(
                target.submittedUrl,
                EnhancedBatchFailure.UnsupportedSource("${target.sourceName} is no longer installed or enabled"),
            )
        val existing = database.getManga(target.mangaUrl, target.sourceId).executeAsBlocking()
        if (existing?.favorite == true) {
            return EnhancedBatchItemResult.AlreadyInLibrary(
                target.submittedUrl,
                target,
                requireNotNull(existing.id),
                existing.title,
            )
        }

        val seed = SManga.create().apply {
            url = target.mangaUrl
            title = existing?.title.orEmpty()
            if (existing != null) copyFrom(existing)
        }
        val knownChapters: List<SChapter> = existing?.let { database.getChapters(it).executeAsBlocking() }.orEmpty()
        val remote = try {
            source.getMangaUpdate(seed, knownChapters, fetchDetails = true, fetchChapters = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (network: IOException) {
            return EnhancedBatchItemResult.Failed(
                target.submittedUrl,
                EnhancedBatchFailure.Network(network.message ?: "Could not load gallery"),
            )
        } catch (invalid: IllegalArgumentException) {
            return EnhancedBatchItemResult.Failed(
                target.submittedUrl,
                EnhancedBatchFailure.InvalidGallery(invalid.message ?: "The gallery response was invalid"),
            )
        } catch (error: Exception) {
            return EnhancedBatchItemResult.Failed(
                target.submittedUrl,
                EnhancedBatchFailure.InvalidGallery(error.message ?: "The source could not parse this gallery"),
            )
        }
        if (remote.manga.title.isBlank()) {
            return EnhancedBatchItemResult.Failed(target.submittedUrl, EnhancedBatchFailure.InvalidGallery("The source returned no title"))
        }
        if (remote.chapters.isEmpty()) {
            return EnhancedBatchItemResult.Failed(target.submittedUrl, EnhancedBatchFailure.InvalidGallery("The source returned no chapters"))
        }

        currentCoroutineContext().ensureActive()
        return try {
            database.inTransactionReturn {
                val latest = database.getManga(target.mangaUrl, target.sourceId).executeAsBlocking()
                if (latest?.favorite == true) {
                    return@inTransactionReturn EnhancedBatchItemResult.AlreadyInLibrary(
                        target.submittedUrl,
                        target,
                        requireNotNull(latest.id),
                        latest.title,
                    )
                }
                val manga = latest ?: existing ?: Manga.create(target.mangaUrl, remote.manga.title, target.sourceId)
                manga.title = remote.manga.title
                manga.copyFrom(remote.manga)
                manga.url = target.mangaUrl
                manga.initialized = true
                manga.favorite = true
                if (manga.date_added == 0L) manga.date_added = System.currentTimeMillis()
                val result = database.insertManga(manga).executeAsBlocking()
                if (manga.id == null) manga.id = requireNotNull(result.insertedId())
                database.updateMangaFavorite(manga).executeAsBlocking()
                database.updateMangaAdded(manga).executeAsBlocking()
                syncChaptersWithSource(database, remote.chapters, manga, source)
                EnhancedBatchItemResult.Added(
                    target.submittedUrl,
                    target,
                    requireNotNull(manga.id),
                    manga.title,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            EnhancedBatchItemResult.Failed(
                target.submittedUrl,
                EnhancedBatchFailure.Persistence(error.message ?: "Could not save gallery atomically"),
            )
        }
    }
}
