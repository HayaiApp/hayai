package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object ReaderLauncher {
    const val EXTRA_INITIAL_PAGE = "dev.ahmedmohamed.hayai.reader.INITIAL_PAGE"

    fun isNovel(
        manga: Manga,
        sourceManager: SourceManager = Injekt.get(),
    ): Boolean = sourceManager.get(manga.source)?.isNovelSource() == true

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

    fun newIntent(
        context: Context,
        manga: Manga,
        chapter: Chapter,
        initialPage: Int,
        sourceManager: SourceManager = Injekt.get(),
    ): Intent {
        val page = requireInitialPage(initialPage)
        check(!isNovel(manga, sourceManager)) { "Novel chapters do not use image-page indices" }
        return ReaderActivity.newIntent(context, manga, chapter).putExtra(EXTRA_INITIAL_PAGE, page)
    }

    fun initialPage(intent: Intent): Int? =
        intent.takeIf { it.hasExtra(EXTRA_INITIAL_PAGE) }
            ?.getIntExtra(EXTRA_INITIAL_PAGE, 0)
            ?.coerceAtLeast(0)

    fun requireInitialPage(value: Int): Int = value.also { require(it >= 0) }
}
