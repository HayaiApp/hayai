package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import dev.ahmedmohamed.hayai.novel.integration.NovelContentIdentity
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal object NovelReaderIntegration {
    fun pageLoaderOrNull(
        context: Context,
        manga: Manga,
        source: Source,
        chapter: ReaderChapter,
    ): PageLoader? =
        if (Injekt.get<NovelContentIdentity>().isNovel(manga)) {
            NovelChapterPageLoader(context, manga, source, chapter)
        } else {
            null
        }

    fun attachOrNull(
        activity: ReaderActivity,
        manga: Manga,
        source: Source,
    ): NovelReaderAttachment? =
        if (Injekt.get<NovelContentIdentity>().isNovel(manga)) {
            NovelReaderAttachment(activity, manga, source)
        } else {
            null
        }
}
