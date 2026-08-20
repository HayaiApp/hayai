package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import android.content.Intent
import dev.ahmedmohamed.hayai.novel.source.NovelSource
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object ReaderLauncher {
    fun isNovel(
        manga: Manga,
        sourceManager: SourceManager = Injekt.get(),
    ): Boolean = sourceManager.get(manga.source) is NovelSource

    fun newIntent(
        context: Context,
        manga: Manga,
        chapter: Chapter,
        sourceManager: SourceManager = Injekt.get(),
    ): Intent =
        if (isNovel(manga, sourceManager)) {
            NovelReaderActivity.newIntent(context, requireNotNull(manga.id), requireNotNull(chapter.id))
        } else {
            ReaderActivity.newIntent(context, manga, chapter)
        }
}
