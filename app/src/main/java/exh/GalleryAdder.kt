package exh

import android.content.Context
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import exh.source.getMainSource
import yokai.util.koin.injectLazy
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MangaUpdate

class GalleryAdder {
    private val logger = Logger.withTag("GalleryAdder")

    private val getManga: GetManga by injectLazy()
    private val insertManga: InsertManga by injectLazy()
    private val updateManga: UpdateManga by injectLazy()
    private val getChapter: GetChapter by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    private val filters: Pair<Set<String>, Set<Long>> by lazy {
        preferences.enabledLanguages().get() to
            preferences.hiddenSources().get().map { it.toLong() }.toSet()
    }

    private val Pair<Set<String>, Set<Long>>.enabledLangs get() = first
    private val Pair<Set<String>, Set<Long>>.disabledSources get() = second

    fun pickSource(url: String): List<UrlImportableSource> {
        val uri = url.toUri()
        return sourceManager.getVisibleCatalogueSources()
            .mapNotNull { it.getMainSource<UrlImportableSource>() }
            .filter {
                it.lang in filters.enabledLangs &&
                    it.id !in filters.disabledSources &&
                    try {
                        it.matchesUri(uri)
                    } catch (e: Exception) {
                        false
                    }
            }
    }

    suspend fun addGallery(
        context: Context,
        url: String,
        fav: Boolean = false,
        forceSource: UrlImportableSource? = null,
        throttleFunc: suspend () -> Unit = {},
        retry: Int = 1,
    ): GalleryAddEvent {
        logger.d { "Importing gallery: $url, fav=$fav, forceSource=${forceSource?.toString().orEmpty()}" }
        try {
            val uri = url.toUri()

            // Find matching source
            val source = if (forceSource != null) {
                try {
                    if (forceSource.matchesUri(uri)) {
                        forceSource
                    } else {
                        return GalleryAddEvent.Fail.UnknownSource(url)
                    }
                } catch (e: Exception) {
                    logger.e(e) { "Source URI must match" }
                    return GalleryAddEvent.Fail.UnknownType(url)
                }
            } else {
                sourceManager.getVisibleCatalogueSources()
                    .mapNotNull { it.getMainSource<UrlImportableSource>() }
                    .find {
                        it.lang in filters.enabledLangs &&
                            it.id !in filters.disabledSources &&
                            try {
                                it.matchesUri(uri)
                            } catch (e: Exception) {
                                false
                            }
                    } ?: return GalleryAddEvent.Fail.UnknownSource(url)
            }

            val realChapterUrl = try {
                source.mapUrlToChapterUrl(uri)
            } catch (e: Exception) {
                logger.e(e) { "URI map to chapter error" }
                null
            }

            val cleanedChapterUrl = if (realChapterUrl != null) {
                try {
                    source.cleanChapterUrl(realChapterUrl)
                } catch (e: Exception) {
                    logger.e(e) { "URI clean error" }
                    null
                }
            } else {
                null
            }

            val chapterMangaUrl = if (realChapterUrl != null) {
                source.mapChapterUrlToMangaUrl(realChapterUrl.toUri())
            } else {
                null
            }

            // Map URL to manga URL
            val realMangaUrl = try {
                chapterMangaUrl ?: source.mapUrlToMangaUrl(uri)
            } catch (e: Exception) {
                logger.e(e) { "URI map to gallery error" }
                null
            } ?: return GalleryAddEvent.Fail.UnknownType(url)

            // Clean URL
            val cleanedMangaUrl = try {
                source.cleanMangaUrl(realMangaUrl)
            } catch (e: Exception) {
                logger.e(e) { "URI clean error" }
                null
            } ?: return GalleryAddEvent.Fail.UnknownType(url)

            // Use manga in DB if possible, otherwise create new entry
            var manga = getManga.awaitByUrlAndSource(cleanedMangaUrl, source.id)
            if (manga == null) {
                // Create a new Manga using the old interface for DB insertion
                val newManga = Manga.create(cleanedMangaUrl, "", source.id)
                newManga.id = insertManga.await(newManga)
                manga = getManga.awaitByUrlAndSource(cleanedMangaUrl, source.id)
                    ?: return GalleryAddEvent.Fail.Error(url, "Failed to insert manga into database (Gallery: $url)")
            }

            // Fetch and update manga details from source
            // manga is non-null here: either found in DB or inserted and re-fetched above
            val nonNullManga = manga
            val newMangaDetails = retry(retry) { source.getMangaDetails(nonNullManga) }
            nonNullManga.copyFrom(newMangaDetails)
            nonNullManga.initialized = true
            updateManga.await(nonNullManga.toMangaUpdate())
            val mangaId = nonNullManga.id ?: return GalleryAddEvent.Fail.Error(url, "Manga has no ID (Gallery: $url)")
            manga = getManga.awaitById(mangaId)
                ?: return GalleryAddEvent.Fail.Error(url, "Failed to reload manga after update (Gallery: $url)")

            if (fav) {
                updateManga.await(MangaUpdate(id = manga.id ?: mangaId, favorite = true))
                manga.favorite = true
            }

            // Fetch and sync chapters
            try {
                val chapterList = retry(retry) {
                    if (source is EHentai) {
                        source.getChapterList(manga, throttleFunc)
                    } else {
                        source.getChapterList(manga)
                    }
                }

                if (chapterList.isNotEmpty()) {
                    syncChaptersWithSource(chapterList, manga, source)
                }
            } catch (e: Exception) {
                logger.w(e) { "Chapter fetch error for ${manga.title}" }
                return GalleryAddEvent.Fail.Error(url, "Failed to fetch chapters (Gallery: $url)")
            }

            return if (cleanedChapterUrl != null) {
                val chapter = getChapter.awaitByUrlAndMangaId(cleanedChapterUrl, manga.id!!, false)
                if (chapter != null) {
                    GalleryAddEvent.Success(url, manga, chapter)
                } else {
                    GalleryAddEvent.Fail.Error(url, "Could not identify chapter: $url")
                }
            } else {
                GalleryAddEvent.Success(url, manga)
            }
        } catch (e: Exception) {
            logger.w(e) { "Could not add gallery: $url" }

            if (e is EHentai.GalleryNotFoundException) {
                return GalleryAddEvent.Fail.NotFound(url)
            }

            return GalleryAddEvent.Fail.Error(
                url,
                ((e.message ?: "Unknown error!") + " (Gallery: $url)").trim(),
            )
        }
    }

    private inline fun <T : Any> retry(retryCount: Int, block: () -> T): T {
        var result: T? = null
        var lastError: Exception? = null

        for (i in 1..retryCount) {
            try {
                result = block()
                break
            } catch (e: Exception) {
                if (e is EHentai.GalleryNotFoundException) {
                    throw e
                }
                lastError = e
            }
        }

        if (lastError != null) {
            throw lastError
        }

        return result ?: error("retry() called with retryCount=$retryCount but block never executed")
    }
}

sealed class GalleryAddEvent {
    abstract val logMessage: String
    abstract val galleryUrl: String
    open val galleryTitle: String? = null

    class Success(
        override val galleryUrl: String,
        val manga: Manga,
        val chapter: eu.kanade.tachiyomi.data.database.models.Chapter? = null,
    ) : GalleryAddEvent() {
        override val galleryTitle = manga.title
        override val logMessage = "Successfully added gallery: $galleryTitle"
    }

    sealed class Fail : GalleryAddEvent() {
        class UnknownType(override val galleryUrl: String) : Fail() {
            override val logMessage = "Unknown gallery type: $galleryUrl"
        }

        open class Error(
            override val galleryUrl: String,
            override val logMessage: String,
        ) : Fail()

        class NotFound(galleryUrl: String) :
            Error(galleryUrl, "Gallery does not exist: $galleryUrl")

        class UnknownSource(override val galleryUrl: String) : Fail() {
            override val logMessage = "Unknown source for gallery: $galleryUrl"
        }
    }
}
