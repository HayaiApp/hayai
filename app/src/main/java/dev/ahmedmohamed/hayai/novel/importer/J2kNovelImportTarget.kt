package dev.ahmedmohamed.hayai.novel.importer

import com.pushtorefresh.storio.sqlite.queries.RawQuery
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginManager
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.isNovelSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class J2kNovelImportTarget(
    private val database: DatabaseHelper,
    private val sources: SourceManager,
    private val plugins: NovelPluginManager,
) : NovelImportTarget {
    private val mutex = Mutex()

    override suspend fun hasApplied(planId: String): Boolean = database.lowLevel().rawQuery(
        RawQuery.builder()
            .query("SELECT 1 FROM hayai_novel_import_runs WHERE plan_id=? AND status='complete'")
            .args(planId)
            .build(),
    ).use { it.moveToFirst() }

    override suspend fun <T> transaction(block: suspend NovelImportTransaction.() -> T): T = mutex.withLock {
        val lowLevel = database.lowLevel()
        lowLevel.beginTransaction()
        try {
            val transaction = Transaction()
            val result = transaction.block()
            lowLevel.setTransactionSuccessful()
            result
        } finally {
            lowLevel.endTransaction()
        }
    }

    private inner class Transaction : NovelImportTransaction {
        private val mangas = mutableMapOf<Long, Manga>()
        private val categories = mutableMapOf<Long, Category>()

        override suspend fun resolveSource(source: ExternalNovelSource): Long? {
            if (source.isLocal) return sources.get(1L)?.takeIf { it.isNovelSource() }?.id
            source.sourceId?.let { id -> sources.get(id)?.takeIf { it.isNovelSource() }?.let { return id } }
            val pluginId = source.pluginId ?: return null
            return plugins.catalog.value.sources.firstOrNull { it.pluginId == pluginId }?.id
        }

        override suspend fun upsertNovel(sourceId: Long, novel: ExternalNovel): Long {
            val existing = database.getManga(novel.url, sourceId).executeAsBlocking()
            if (existing != null && existing.title.isNotBlank() && !existing.title.equals(novel.title, true)) {
                error("Identity conflict for ${novel.url}. Existing title '${existing.title}' does not match '${novel.title}'.")
            }
            val manga = existing ?: Manga.create(novel.url, novel.title, sourceId)
            manga.url = novel.url
            manga.title = novel.title
            manga.author = novel.author
            manga.description = novel.description
            manga.thumbnail_url = novel.coverUrl
            manga.favorite = manga.favorite || novel.favorite
            manga.initialized = true
            if (manga.favorite && manga.date_added == 0L) manga.date_added = System.currentTimeMillis()
            val result = database.insertManga(manga).executeAsBlocking()
            if (manga.id == null) manga.id = requireNotNull(result.insertedId())
            mangas[requireNotNull(manga.id)] = manga
            return requireNotNull(manga.id)
        }

        override suspend fun upsertChapter(mangaId: Long, chapter: ExternalNovelChapter) {
            val manga = mangas[mangaId] ?: requireNotNull(database.getManga(mangaId).executeAsBlocking())
            val existing = database.getChapters(manga).executeAsBlocking().firstOrNull { it.url == chapter.url }
            if (existing != null && existing.name.isNotBlank() && !existing.name.equals(chapter.title, true)) error("Chapter identity conflict for ${chapter.url}")
            val target = existing ?: Chapter.create().apply { manga_id = mangaId; url = chapter.url }
            target.name = chapter.title
            target.chapter_number = chapter.number ?: target.chapter_number
            target.read = target.read || chapter.read
            target.bookmark = target.bookmark || chapter.bookmarked
            target.last_page_read = maxOf(target.last_page_read, chapter.progress)
            val result = database.insertChapter(target).executeAsBlocking()
            if (target.id == null) target.id = requireNotNull(result.insertedId())
            chapter.lastReadAt?.let { database.upsertHistoryLastRead(History.create(target).apply { last_read = it }).executeAsBlocking() }
        }

        override suspend fun upsertCategory(category: ExternalNovelCategory): Long {
            val existing = database.getCategories().executeAsBlocking().firstOrNull { it.name.equals(category.name, true) }
            val target = existing ?: Category.create(category.name).apply { order = category.order }
            if (target.id == null) target.id = requireNotNull(database.insertCategory(target).executeAsBlocking().insertedId()).toInt()
            categories[requireNotNull(target.id).toLong()] = target
            return requireNotNull(target.id).toLong()
        }

        override suspend fun setCategory(mangaId: Long, categoryId: Long) {
            val manga = mangas[mangaId] ?: requireNotNull(database.getManga(mangaId).executeAsBlocking())
            val category = categories[categoryId] ?: database.getCategories().executeAsBlocking().first { it.id?.toLong() == categoryId }
            val alreadyAssigned = database.lowLevel().rawQuery(
                RawQuery.builder()
                    .query("SELECT 1 FROM mangas_categories WHERE manga_id=? AND category_id=?")
                    .args(mangaId, categoryId)
                    .build(),
            ).use { it.moveToFirst() }
            if (!alreadyAssigned) database.insertMangaCategory(MangaCategory.create(manga, category)).executeAsBlocking()
        }

        override suspend fun markApplied(planId: String) {
            database.lowLevel().executeSQL(
                RawQuery.builder()
                    .query("INSERT OR REPLACE INTO hayai_novel_import_runs(plan_id,status,completed_at) VALUES (?,'complete',?)")
                    .args(planId, System.currentTimeMillis())
                    .build(),
            )
        }
    }
}
