package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter

internal object NovelReaderIntegration {
    fun pageLoaderOrNull(
        context: Context,
        manga: Manga,
        source: Source,
        chapter: ReaderChapter,
    ): PageLoader? =
        if (NovelReaderIdentity.isNovel(source)) {
            NovelChapterPageLoader(context, manga, source, chapter)
        } else {
            null
        }

    fun attachOrNull(
        activity: ReaderActivity,
        manga: Manga,
        source: Source,
    ): NovelReaderAttachment? =
        if (NovelReaderIdentity.isNovel(source)) {
            NovelReaderAttachment(activity, manga, source)
        } else {
            null
        }
}
