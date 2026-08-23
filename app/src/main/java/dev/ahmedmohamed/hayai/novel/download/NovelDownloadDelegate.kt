package dev.ahmedmohamed.hayai.novel.download

import android.content.Context
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.online.HttpSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

internal enum class ChapterDownloadStorage {
    J2kImages,
    HayaiNovel,
}

internal object ChapterDownloadRouting {
    fun storageFor(source: Source): ChapterDownloadStorage =
        if (source.isNovelSource()) ChapterDownloadStorage.HayaiNovel else ChapterDownloadStorage.J2kImages
}

internal object NovelDownloadPresentationState {
    fun merge(
        current: Download.State,
        isOffline: Boolean,
    ): Download.State =
        when {
            isOffline -> Download.State.DOWNLOADED
            current == Download.State.DOWNLOADED -> Download.State.NOT_DOWNLOADED
            else -> current
        }
}

internal object NovelDownloadQueueSource {
    private val adapters = ConcurrentHashMap<Long, HttpSource>()

    fun from(source: Source): HttpSource? =
        when {
            source is HttpSource -> source
            !source.isNovelSource() -> null
            else ->
                adapters.computeIfAbsent(source.id) {
                    if (source is UnmeteredSource) {
                        UnmeteredNovelDownloadSource(source)
                    } else {
                        NovelDownloadSource(source)
                    }
                }
        }

    private open class NovelDownloadSource(source: Source) : HttpSource() {
        override val id: Long = source.id
        override val name: String = source.name
        override val lang: String = source.lang
        override val supportsLatest: Boolean = false
        override val baseUrl: String = "https://invalid.invalid"
        override val isNovelSource: Boolean = true
    }

    private class UnmeteredNovelDownloadSource(source: Source) :
        NovelDownloadSource(source),
        UnmeteredSource
}

internal class NovelDownloadDelegate(
    context: Context,
    sourceManager: SourceManager,
    database: DatabaseHelper = Injekt.get(),
    network: NetworkHelper = Injekt.get(),
) {
    private val offline = NovelOfflineManager(context, database, sourceManager, network)

    fun handles(source: Source): Boolean = ChapterDownloadRouting.storageFor(source) == ChapterDownloadStorage.HayaiNovel

    fun isDownloaded(
        manga: Manga,
        chapter: Chapter,
    ): Boolean = offline.isDownloaded(manga, chapter)

    suspend fun save(
        manga: Manga,
        chapter: Chapter,
    ) = offline.saveChapter(manga, chapter)
}
