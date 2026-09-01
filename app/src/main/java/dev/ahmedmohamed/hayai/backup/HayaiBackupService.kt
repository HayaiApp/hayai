package dev.ahmedmohamed.hayai.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.pushtorefresh.storio.sqlite.queries.InsertQuery
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import com.pushtorefresh.storio.sqlite.queries.UpdateQuery
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryAlias
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryIdentity
import dev.ahmedmohamed.hayai.adult.eh.persistence.HayaiEhPersistenceStore
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMangaIdentity
import dev.ahmedmohamed.hayai.novel.extension.NovelApkRepositoryRegistry
import dev.ahmedmohamed.hayai.novel.highlight.NovelHighlightStore
import dev.ahmedmohamed.hayai.novel.highlight.RestoredHighlightIdentity
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginDescriptor
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginManager
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginStore
import dev.ahmedmohamed.hayai.novel.source.builder.NovelCustomSourceDefinition
import dev.ahmedmohamed.hayai.novel.source.builder.NovelCustomSourceStore
import dev.ahmedmohamed.hayai.novel.translation.NovelTranslationLocator
import dev.ahmedmohamed.hayai.novel.translation.SqliteNovelTranslationStore
import dev.ahmedmohamed.hayai.novel.translation.StoredNovelTranslation
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HayaiBackupService(
    private val database: DatabaseHelper,
    private val context: Context? = null,
    private val pluginManager: NovelPluginManager? = null,
    private val novelApkRepositories: NovelApkRepositoryRegistry? = null,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun create(mangas: List<Manga>): HayaiBackupData {
        val identities = mangas.mapNotNull { manga -> manga.id?.let { it to MangaIdentity(manga.source, manga.url) } }.toMap()
        val quotes = mutableListOf<HayaiBackupQuote>()
        val stats = mutableListOf<HayaiBackupChapterStat>()
        identities.forEach { (mangaId, identity) ->
            query(
                "SELECT quote_id, novel_name, chapter_name, displayed_content, original_content, " +
                    "translated_content, language, timestamp FROM hayai_quotes WHERE manga_id = ? ORDER BY timestamp, quote_id",
                mangaId,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    quotes +=
                        HayaiBackupQuote(
                            id = cursor.getString(0),
                            sourceId = identity.sourceId,
                            mangaUrl = identity.mangaUrl,
                            novelName = cursor.getString(1),
                            chapterName = cursor.getString(2),
                            displayedContent = cursor.getString(3),
                            originalContent = cursor.stringOrNull(4),
                            translatedContent = cursor.stringOrNull(5),
                            language = cursor.stringOrNull(6),
                            timestamp = cursor.getLong(7),
                        )
                }
            }
            database.getChapters(mangaId).executeAsBlocking().forEach { chapter ->
                val chapterId = chapter.id ?: return@forEach
                query("SELECT word_count FROM hayai_novel_chapter_stats WHERE chapter_id = ?", chapterId).use { cursor ->
                    if (cursor.moveToFirst()) {
                        stats += HayaiBackupChapterStat(identity.sourceId, identity.mangaUrl, chapter.url, cursor.getLong(0))
                    }
                }
            }
        }
        return HayaiBackupData(
            version = HayaiBackupData.CURRENT_VERSION,
            quotes = quotes,
            novelRepositories = readNovelRepositories(),
            chapterStats = stats,
            ehFavorites = readEhFavorites(),
            novelPlugins = readNovelPlugins(),
            ehGalleryAliases = readEhGalleryAliases(),
            sourceMetadata = readSourceMetadata(mangas),
            ehCategoryMappings = readEhCategoryMappings(),
            novelHighlights = NovelHighlightStore(database, json).exportAll(),
            novelCustomSources = readNovelCustomSources(),
            novelApkRepositories = readNovelApkRepositories(),
            novelTranslations = readNovelTranslations(identities.values.toSet()),
        )
    }

    suspend fun restore(data: HayaiBackupData?): HayaiRestoreReport {
        if (data == null) return HayaiRestoreReport(0, 0, emptyList())
        val errors = HayaiBackupLimits.validate(data).toMutableList()
        if (errors.isNotEmpty()) return HayaiRestoreReport(0, data.itemCount(), errors)
        var restored = 0
        var skipped = 0
        val mangaCache = mutableMapOf<MangaIdentity, Manga?>()

        database.inTransaction {
            data.quotes.forEach { quote ->
                process("quote ${quote.id}", errors, { skipped++ }) {
                    val manga = findManga(quote.sourceId, quote.mangaUrl, mangaCache) ?: return@process false
                    restoreQuote(requireNotNull(manga.id), quote)
                }.also { if (it) restored++ }
            }
            data.novelRepositories.forEach { repository ->
                process("novel repository ${repository.baseUrl}", errors, { skipped++ }) {
                    upsert(
                        table = "hayai_novel_repos",
                        where = "base_url = ?",
                        whereArgs = arrayOf(repository.baseUrl),
                        values =
                            ContentValues(3).apply {
                                put("base_url", repository.baseUrl)
                                put("name", repository.name)
                                put("enabled", repository.enabled)
                            },
                    )
                }.also { if (it) restored++ }
            }
            data.chapterStats.forEach { stat ->
                process("chapter stat ${stat.chapterUrl}", errors, { skipped++ }) {
                    val manga = findManga(stat.sourceId, stat.mangaUrl, mangaCache) ?: return@process false
                    val chapter =
                        database.getChapters(manga).executeAsBlocking().firstOrNull { it.url == stat.chapterUrl } ?: return@process false
                    val chapterId = requireNotNull(chapter.id)
                    upsert(
                        table = "hayai_novel_chapter_stats",
                        where = "chapter_id = ?",
                        whereArgs = arrayOf(chapterId),
                        values =
                            ContentValues(2).apply {
                                put("chapter_id", chapterId)
                                put("word_count", stat.wordCount)
                            },
                    )
                }.also { if (it) restored++ }
            }
            val translationStore = SqliteNovelTranslationStore(database)
            data.novelTranslations.forEach { translation ->
                process("novel translation ${translation.chapterUrl}", errors, { skipped++ }) {
                    val manga = findManga(translation.sourceId, translation.mangaUrl, mangaCache) ?: return@process false
                    val chapter =
                        database.getChapters(manga).executeAsBlocking().singleOrNull { it.url == translation.chapterUrl }
                            ?: return@process false
                    translationStore.restoreCompleted(
                        StoredNovelTranslation(
                            locator =
                                NovelTranslationLocator(
                                    chapterId = requireNotNull(chapter.id),
                                    sourceId = translation.sourceId,
                                    mangaUrl = translation.mangaUrl,
                                    chapterUrl = translation.chapterUrl,
                                ),
                            sourceLanguage = translation.sourceLanguage,
                            targetLanguage = translation.targetLanguage,
                            sourceHash = translation.sourceHash,
                            translatedContent = translation.translatedContent,
                            contentFormat = translation.contentFormat,
                            engineId = translation.engineId,
                            detectedLanguage = translation.detectedLanguage,
                            createdAt = translation.createdAt,
                            updatedAt = translation.updatedAt,
                        ),
                    )
                    true
                }.also { if (it) restored++ }
            }
            data.ehFavorites.distinct().forEach { favorite ->
                process("E-Hentai favorite ${favorite.gid}", errors, { skipped++ }) {
                    upsert(
                        table = "hayai_eh_favorites",
                        where = "gid = ? AND token = ?",
                        whereArgs = arrayOf(favorite.gid, favorite.token),
                        values =
                            ContentValues(4).apply {
                                put("gid", favorite.gid)
                                put("token", favorite.token)
                                put("title", favorite.title)
                                put("category", favorite.category)
                            },
                    )
                }.also { if (it) restored++ }
            }
            data.ehGalleryAliases.distinct().forEach { alias ->
                process("E-Hentai gallery alias ${alias.alternateGid}", errors, { skipped++ }) {
                    restoreEhGalleryAlias(alias)
                }.also { if (it) restored++ }
            }
            data.ehCategoryMappings.distinct().forEach { mapping ->
                process("E-Hentai category mapping ${mapping.slot}", errors, { skipped++ }) {
                    val categoryId = query("SELECT _id FROM categories WHERE name = ?", mapping.localCategoryName).use { cursor ->
                        if (!cursor.moveToFirst()) return@process false
                        val id = cursor.getInt(0)
                        if (cursor.moveToNext()) return@process false
                        id
                    }
                    upsert(
                        table = "hayai_eh_category_map",
                        where = "slot = ?",
                        whereArgs = arrayOf(mapping.slot),
                        values = ContentValues(3).apply {
                            put("slot", mapping.slot)
                            put("category_id", categoryId)
                            put("remote_name", mapping.remoteName)
                        },
                    )
                }.also { if (it) restored++ }
            }
            data.sourceMetadata.distinct().forEach { metadata ->
                process("source metadata ${metadata.mangaUrl}", errors, { skipped++ }) {
                    val manga = findManga(metadata.sourceId, metadata.mangaUrl, mangaCache) ?: return@process false
                    restoreSourceMetadata(metadata, requireNotNull(manga.id))
                }.also { if (it) restored++ }
            }
            val highlightResult =
                NovelHighlightStore(database, json).restore(data.novelHighlights) { sourceId, mangaUrl, chapterUrl ->
                    val manga = findManga(sourceId, mangaUrl, mangaCache) ?: return@restore null
                    val chapter = database.getChapters(manga).executeAsBlocking().singleOrNull { it.url == chapterUrl }
                        ?: return@restore null
                    RestoredHighlightIdentity(requireNotNull(manga.id), requireNotNull(chapter.id))
                }
            restored += highlightResult.restored
            skipped += highlightResult.unresolved.size
            highlightResult.unresolved.take(MAX_RESTORE_ERROR_EXAMPLES).forEach { unresolved ->
                errors +=
                    "novel highlight ${unresolved.highlightId.take(128)}: matching core manga or chapter " +
                    "was not restored for source ${unresolved.sourceId}"
            }
            if (highlightResult.unresolved.size > MAX_RESTORE_ERROR_EXAMPLES) {
                errors +=
                    "${highlightResult.unresolved.size - MAX_RESTORE_ERROR_EXAMPLES} additional novel highlights " +
                    "could not be matched to restored core data"
            }
            if (
                data.ehFavorites.isNotEmpty() || data.ehGalleryAliases.isNotEmpty() ||
                data.ehCategoryMappings.isNotEmpty() || data.sourceMetadata.isNotEmpty()
            ) {
                database.lowLevel().executeSQL(
                    RawQuery.builder().query(
                        "UPDATE hayai_eh_sync_checkpoint SET requires_full_reconcile = 1 WHERE singleton = 1",
                    ).build(),
                )
            }
        }
        restoreNovelPlugins(data.novelPlugins, errors, { restored++ }, { skipped++ })
        restoreNovelCustomSources(data.novelCustomSources, errors, { restored++ }, { skipped++ })
        data.novelApkRepositories.distinct().forEach { repository ->
            process("novel APK repository $repository", errors, { skipped++ }) {
                val registry = novelApkRepositories ?: return@process false
                registry.add(repository)
                true
            }.also { if (it) restored++ }
        }
        return HayaiRestoreReport(restored, skipped, errors)
    }

    private fun readNovelCustomSources(): List<NovelCustomSourceDefinition> {
        val appContext = context ?: return emptyList()
        return appContext.getSharedPreferences("hayai_novel_custom_sources", Context.MODE_PRIVATE).all.values
            .mapNotNull { value ->
                (value as? String)?.let { encoded ->
                    runCatching { json.decodeFromString<NovelCustomSourceDefinition>(encoded).requireValid() }.getOrNull()
                }
            }
            .sortedBy(NovelCustomSourceDefinition::id)
    }

    private fun readNovelApkRepositories(): List<String> {
        val appContext = context ?: return emptyList()
        return appContext.getSharedPreferences("hayai_novel_apk_repositories", Context.MODE_PRIVATE)
            .getStringSet("urls_v1", emptySet()).orEmpty().sorted()
    }

    private fun readNovelTranslations(identities: Set<MangaIdentity>): List<HayaiBackupNovelTranslation> =
        SqliteNovelTranslationStore(database)
            .exportCompleted()
            .asSequence()
            .filter { translation ->
                MangaIdentity(translation.locator.sourceId, translation.locator.mangaUrl) in identities
            }.map { translation ->
                HayaiBackupNovelTranslation(
                    sourceId = translation.locator.sourceId,
                    mangaUrl = translation.locator.mangaUrl,
                    chapterUrl = translation.locator.chapterUrl,
                    sourceLanguage = translation.sourceLanguage,
                    targetLanguage = translation.targetLanguage,
                    sourceHash = translation.sourceHash,
                    translatedContent = translation.translatedContent,
                    contentFormat = translation.contentFormat,
                    engineId = translation.engineId,
                    detectedLanguage = translation.detectedLanguage,
                    createdAt = translation.createdAt,
                    updatedAt = translation.updatedAt,
                )
            }.toList()

    private suspend fun restoreNovelCustomSources(
        definitions: List<NovelCustomSourceDefinition>,
        errors: MutableList<String>,
        onRestored: () -> Unit,
        onSkipped: () -> Unit,
    ) {
        val appContext = context
        val manager = pluginManager
        if (appContext == null || manager == null) {
            if (definitions.isNotEmpty()) {
                errors += "Novel custom sources could not be restored because the plugin manager is unavailable"
                repeat(definitions.size) { onSkipped() }
            }
            return
        }
        val store = NovelCustomSourceStore(appContext, manager, json)
        definitions.distinctBy(NovelCustomSourceDefinition::id).forEach { definition ->
            runCatching { store.save(definition) }
                .onSuccess { onRestored() }
                .onFailure { failure ->
                    errors += "novel custom source ${definition.id}: ${failure.message ?: failure.javaClass.simpleName}"
                    onSkipped()
                }
        }
    }

    private fun restoreQuote(
        mangaId: Long,
        quote: HayaiBackupQuote,
    ): Boolean {
        val quoteId = resolveQuoteId(mangaId, quote) ?: return true
        val values =
            ContentValues(9).apply {
                put("quote_id", quoteId)
                put("manga_id", mangaId)
                put("novel_name", quote.novelName)
                put("chapter_name", quote.chapterName)
                put("displayed_content", quote.displayedContent)
                put("original_content", quote.originalContent)
                put("translated_content", quote.translatedContent)
                put("language", quote.language)
                put("timestamp", quote.timestamp)
            }
        check(database.lowLevel().insert(insertQuery("hayai_quotes"), values) >= 0)
        return true
    }

    /** Returns null when this exact quote is already present, otherwise a free stable ID. */
    private fun resolveQuoteId(
        mangaId: Long,
        quote: HayaiBackupQuote,
    ): String? {
        quoteAtId(quote.id)?.let { existing -> if (existing.matches(mangaId, quote)) return null } ?: return quote.id
        repeat(100) { attempt ->
            val candidate = HayaiQuoteRestoreIdentity.remappedId(quote, mangaId, attempt)
            quoteAtId(candidate)?.let { existing ->
                if (existing.matches(mangaId, quote)) return null
            } ?: return candidate
        }
        error("Unable to allocate a stable quote ID")
    }

    private fun quoteAtId(quoteId: String): StoredQuote? =
        query(
            "SELECT manga_id, novel_name, chapter_name, displayed_content, original_content, translated_content, language, timestamp " +
                "FROM hayai_quotes WHERE quote_id = ?",
            quoteId,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            StoredQuote(
                mangaId = cursor.getLong(0),
                novelName = cursor.getString(1),
                chapterName = cursor.getString(2),
                displayedContent = cursor.getString(3),
                originalContent = cursor.stringOrNull(4),
                translatedContent = cursor.stringOrNull(5),
                language = cursor.stringOrNull(6),
                timestamp = cursor.getLong(7),
            )
        }

    private fun upsert(
        table: String,
        where: String,
        whereArgs: Array<out Any>,
        values: ContentValues,
    ): Boolean {
        val exists = query("SELECT 1 FROM $table WHERE $where LIMIT 1", *whereArgs).use(Cursor::moveToFirst)
        if (exists) {
            check(
                database.lowLevel().update(
                    UpdateQuery
                        .builder()
                        .table(table)
                        .where(where)
                        .whereArgs(*whereArgs)
                        .build(),
                    values,
                ) > 0,
            )
        } else {
            check(database.lowLevel().insert(insertQuery(table), values) >= 0)
        }
        return true
    }

    private fun findManga(
        sourceId: Long,
        mangaUrl: String,
        cache: MutableMap<MangaIdentity, Manga?>,
    ): Manga? {
        val identity = MangaIdentity(sourceId, mangaUrl)
        return cache.getOrPut(identity) { database.getManga(mangaUrl, sourceId).executeAsBlocking() }
    }

    private fun readNovelRepositories(): List<HayaiBackupNovelRepository> =
        query("SELECT base_url, name, enabled FROM hayai_novel_repos ORDER BY base_url").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        HayaiBackupNovelRepository(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getInt(2) != 0,
                        ),
                    )
                }
            }
        }

    private fun readEhFavorites(): List<HayaiBackupEhFavorite> =
        query("SELECT gid, token, title, category FROM hayai_eh_favorites ORDER BY gid, token").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        HayaiBackupEhFavorite(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3)),
                    )
                }
            }
        }

    private fun readEhGalleryAliases(): List<HayaiBackupEhGalleryAlias> =
        HayaiEhPersistenceStore(database).aliases().map { alias ->
            HayaiBackupEhGalleryAlias(
                alias.canonical.gid,
                alias.canonical.token,
                alias.alternate.gid,
                alias.alternate.token,
            )
        }

    private fun readEhCategoryMappings(): List<HayaiBackupEhCategoryMapping> =
        query(
            "SELECT m.slot, m.remote_name, c.name FROM hayai_eh_category_map m JOIN categories c ON c._id = m.category_id ORDER BY m.slot",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(HayaiBackupEhCategoryMapping(cursor.getInt(0), cursor.getString(1), cursor.getString(2)))
            }
        }

    private fun readSourceMetadata(mangas: List<Manga>): List<HayaiBackupSourceMetadata> {
        val store = HayaiEhPersistenceStore(database)
        return mangas
            .asSequence()
            .map { SourceMangaIdentity(it.source, it.url) }
            .distinct()
            .mapNotNull(store::metadata)
            .map { metadata ->
                HayaiBackupSourceMetadata(
                    sourceId = metadata.identity.sourceId,
                    mangaUrl = metadata.identity.mangaUrl,
                    uploader = metadata.uploader,
                    extra = metadata.extra,
                    indexedExtra = metadata.indexedExtra,
                    extraVersion = metadata.extraVersion,
                    tags = metadata.tags.map { HayaiBackupSourceMetadataTag(it.namespace, it.name, it.type) },
                    titles = metadata.titles.map { HayaiBackupSourceMetadataTitle(it.title, it.type) },
                )
            }.toList()
    }

    private fun restoreEhGalleryAlias(alias: HayaiBackupEhGalleryAlias): Boolean {
        val value =
            EhGalleryAlias(
                EhGalleryIdentity(alias.canonicalGid, alias.canonicalToken),
                EhGalleryIdentity(alias.alternateGid, alias.alternateToken),
            )
        val conflict =
            query(
                "SELECT canonical_gid, canonical_token FROM hayai_eh_gallery_aliases WHERE alternate_gid = ? AND alternate_token = ? LIMIT 1",
                value.alternate.gid,
                value.alternate.token,
            ).use { cursor ->
                cursor.moveToFirst() &&
                    (cursor.getString(0) != value.canonical.gid || cursor.getString(1) != value.canonical.token)
            }
        check(!conflict) { "Alternate gallery belongs to another canonical gallery" }
        val exists =
            query(
                "SELECT 1 FROM hayai_eh_gallery_aliases WHERE canonical_gid = ? AND canonical_token = ? AND alternate_gid = ? AND alternate_token = ?",
                value.canonical.gid,
                value.canonical.token,
                value.alternate.gid,
                value.alternate.token,
            ).use(Cursor::moveToFirst)
        if (!exists) {
            check(
                database.lowLevel().insert(
                    insertQuery("hayai_eh_gallery_aliases"),
                    ContentValues(4).apply {
                        put("canonical_gid", value.canonical.gid)
                        put("canonical_token", value.canonical.token)
                        put("alternate_gid", value.alternate.gid)
                        put("alternate_token", value.alternate.token)
                    },
                ) >= 0,
            )
        }
        return true
    }

    private fun restoreSourceMetadata(
        metadata: HayaiBackupSourceMetadata,
        mangaId: Long,
    ): Boolean {
        val identity = SourceMangaIdentity(metadata.sourceId, metadata.mangaUrl)
        val currentManga = query("SELECT source, url FROM mangas WHERE _id = ?", mangaId).use { cursor ->
            check(cursor.moveToFirst()) { "Matching manga disappeared" }
            SourceMangaIdentity(cursor.getLong(0), cursor.getString(1))
        }
        check(currentManga == identity) { "Stable manga identity conflicts" }
        upsert(
            table = "hayai_source_metadata",
            where = "source_id = ? AND manga_url = ?",
            whereArgs = arrayOf<Any>(metadata.sourceId, metadata.mangaUrl),
            values =
                ContentValues(7).apply {
                    put("source_id", metadata.sourceId)
                    put("manga_url", metadata.mangaUrl)
                    put("uploader", metadata.uploader)
                    put("extra", metadata.extra)
                    put("indexed_extra", metadata.indexedExtra)
                    put("extra_version", metadata.extraVersion)
                    put("updated_at", System.currentTimeMillis())
                },
        )
        executeDelete("hayai_source_metadata_tags", metadata.sourceId, metadata.mangaUrl)
        executeDelete("hayai_source_metadata_titles", metadata.sourceId, metadata.mangaUrl)
        metadata.tags.distinct().forEach { tag ->
            check(
                database.lowLevel().insert(
                    insertQuery("hayai_source_metadata_tags"),
                    ContentValues(5).apply {
                        put("source_id", metadata.sourceId)
                        put("manga_url", metadata.mangaUrl)
                        put("namespace", tag.namespace.orEmpty())
                        put("name", tag.name)
                        put("type", tag.type)
                    },
                ) >= 0,
            )
        }
        metadata.titles.distinct().forEach { title ->
            check(
                database.lowLevel().insert(
                    insertQuery("hayai_source_metadata_titles"),
                    ContentValues(4).apply {
                        put("source_id", metadata.sourceId)
                        put("manga_url", metadata.mangaUrl)
                        put("title", title.title)
                        put("type", title.type)
                    },
                ) >= 0,
            )
        }
        return true
    }

    private fun executeDelete(
        table: String,
        sourceId: Long,
        mangaUrl: String,
    ) {
        database.lowLevel().executeSQL(
            RawQuery.builder().query("DELETE FROM $table WHERE source_id = ? AND manga_url = ?").args(sourceId, mangaUrl).build(),
        )
    }

    private fun readNovelPlugins(): List<HayaiBackupNovelPlugin> {
        val appContext = context?.applicationContext ?: return emptyList()
        val store = NovelPluginStore(appContext, database, json)
        return store.installed().mapNotNull { installed ->
            runCatching {
                HayaiBackupNovelPlugin(
                    descriptorJson = json.encodeToString(installed.descriptor),
                    repositoryUrl = installed.repositoryUrl,
                    code = store.readCode(installed).toByteArray(Charsets.UTF_8),
                    preferences =
                        appContext
                            .getSharedPreferences("jsplugin_storage_${installed.descriptor.id}", Context.MODE_PRIVATE)
                            .all
                            .mapNotNull { (key, value) -> (value as? String)?.let { HayaiBackupPluginPreference(key, it) } }
                            .sortedBy(HayaiBackupPluginPreference::key),
                )
            }.getOrNull()
        }
    }

    private suspend fun restoreNovelPlugins(
        plugins: List<HayaiBackupNovelPlugin>,
        errors: MutableList<String>,
        restored: () -> Unit,
        skipped: () -> Unit,
    ) {
        val appContext = context?.applicationContext
        if (plugins.isNotEmpty() && appContext == null) {
            repeat(plugins.size) { skipped() }
            errors += "novel plugins: app context is unavailable"
            return
        }
        val manager =
            pluginManager ?: run {
                if (plugins.isNotEmpty()) {
                    repeat(plugins.size) { skipped() }
                    errors += "novel plugins: plugin manager is unavailable"
                }
                return
            }
        plugins.forEachIndexed { index, plugin ->
            try {
                val descriptor = json.decodeFromString<NovelPluginDescriptor>(plugin.descriptorJson).validate(plugin.repositoryUrl)
                manager.restorePlugin(
                    descriptor = descriptor,
                    repositoryUrl = plugin.repositoryUrl,
                    code = plugin.code,
                    preferences = plugin.preferences.associate { it.key to it.value },
                )
                restored()
            } catch (error: Exception) {
                skipped()
                errors += "novel plugin ${index + 1}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun query(
        sql: String,
        vararg args: Any,
    ): Cursor =
        database.lowLevel().rawQuery(
            RawQuery
                .builder()
                .query(sql)
                .args(*args)
                .build(),
        )

    private fun insertQuery(table: String): InsertQuery = InsertQuery.builder().table(table).build()

    private inline fun process(
        label: String,
        errors: MutableList<String>,
        skipped: () -> Unit,
        block: () -> Boolean,
    ): Boolean =
        try {
            block().also {
                if (!it) {
                    skipped()
                    errors += "$label: matching core data was not restored or the stable ID conflicts"
                }
            }
        } catch (error: Exception) {
            skipped()
            errors += "$label: ${error.message ?: error.javaClass.simpleName}"
            false
        }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private companion object {
        const val MAX_RESTORE_ERROR_EXAMPLES = 20
    }

    private fun HayaiBackupData.itemCount() =
        quotes.size + novelRepositories.size + chapterStats.size + ehFavorites.size + novelPlugins.size +
            ehGalleryAliases.size + sourceMetadata.size + ehCategoryMappings.size + novelHighlights.size +
            novelCustomSources.size + novelApkRepositories.size + novelTranslations.size

    private data class MangaIdentity(
        val sourceId: Long,
        val mangaUrl: String,
    )

    private data class StoredQuote(
        val mangaId: Long,
        val novelName: String,
        val chapterName: String,
        val displayedContent: String,
        val originalContent: String?,
        val translatedContent: String?,
        val language: String?,
        val timestamp: Long,
    ) {
        fun matches(
            expectedMangaId: Long,
            quote: HayaiBackupQuote,
        ): Boolean =
            mangaId == expectedMangaId &&
                novelName == quote.novelName &&
                chapterName == quote.chapterName &&
                displayedContent == quote.displayedContent &&
                originalContent == quote.originalContent &&
                translatedContent == quote.translatedContent &&
                language == quote.language &&
                timestamp == quote.timestamp
    }
}

internal object HayaiBackupLimits {
    fun validate(data: HayaiBackupData): List<String> =
        buildList {
            if (data.version !in HayaiBackupData.MINIMUM_SUPPORTED_VERSION..HayaiBackupData.CURRENT_VERSION) {
                add("Unsupported Hayai backup version ${data.version}")
            }
            if (data.quotes.size > 100_000) add("Too many Hayai quotes")
            if (data.novelRepositories.size > 1_000) add("Too many novel repositories")
            if (data.chapterStats.size > 1_000_000) add("Too many novel chapter statistics")
            if (data.ehFavorites.size > 100_000) add("Too many E-Hentai favorites")
            if (data.ehGalleryAliases.size > 100_000) add("Too many E-Hentai gallery aliases")
            if (data.ehCategoryMappings.size > 10) add("Too many E-Hentai category mappings")
            if (data.sourceMetadata.size > 100_000) add("Too many source metadata records")
            if (data.sourceMetadata.sumOf { it.extra.length.toLong() } > 64L * 1024 * 1024) {
                add("Source metadata backup data is too large")
            }
            if (data.sourceMetadata.sumOf { it.tags.size.toLong() } > 2_000_000) add("Too many source metadata tags")
            if (data.sourceMetadata.sumOf { it.titles.size.toLong() } > 100_000) add("Too many source metadata titles")
            if (
                data.sourceMetadata.sumOf { metadata ->
                    metadata.tags.sumOf { (it.namespace?.length ?: 0).toLong() + it.name.length } +
                        metadata.titles.sumOf { it.title.length.toLong() }
                } > 128L * 1024 * 1024
            ) {
                add("Source metadata labels are too large")
            }
            if (data.novelPlugins.size > 500) add("Too many novel plugins")
            if (data.novelPlugins.sumOf { it.code.size.toLong() } > 64L * 1024 * 1024) add("Novel plugin backup data is too large")
            if (data.novelPlugins.sumOf { plugin -> plugin.preferences.sumOf { it.key.length.toLong() + it.value.length } } > 16L * 1024 * 1024) {
                add("Novel plugin settings backup data is too large")
            }
            if (data.novelHighlights.size > 100_000) add("Too many novel highlights")
            if (data.novelHighlights.map { it.id }.distinct().size != data.novelHighlights.size) {
                add("Duplicate novel highlight IDs")
            }
            if (
                data.novelHighlights.sumOf {
                    it.mangaUrl.length.toLong() + it.chapterUrl.length + (it.note?.length ?: 0) +
                        it.anchor.exact.length + it.anchor.prefix.length + it.anchor.suffix.length
                } > 64L * 1024 * 1024
            ) {
                add("Novel highlight backup data is too large")
            }
            if (data.novelCustomSources.size > 500) add("Too many visual novel sources")
            if (data.novelCustomSources.map { it.id }.distinct().size != data.novelCustomSources.size) {
                add("Duplicate visual novel source IDs")
            }
            if (data.novelCustomSources.sumOf { Json.encodeToString(it).length.toLong() } > 16L * 1024 * 1024) {
                add("Visual novel source backup data is too large")
            }
            if (data.novelApkRepositories.size > 100 || data.novelApkRepositories.distinct().size != data.novelApkRepositories.size) {
                add("Invalid novel APK repository list")
            }
            if (data.novelTranslations.size > 1_000_000) add("Too many completed novel translations")
            if (data.novelTranslations.sumOf { it.translatedContent.length.toLong() } > 512L * 1024 * 1024) {
                add("Completed novel translation backup data is too large")
            }
            if (
                data.novelTranslations
                    .groupBy { listOf(it.sourceId.toString(), it.mangaUrl, it.chapterUrl, it.targetLanguage) }
                    .values
                    .any { translations -> translations.distinct().size > 1 }
            ) {
                add("Conflicting duplicate completed novel translation")
            }
            data.quotes
                .firstOrNull {
                    it.id.length !in 1..128 ||
                        it.mangaUrl.length !in 1..8_192 ||
                        it.novelName.length !in 1..8_192 ||
                        it.chapterName.length !in 1..8_192 ||
                        it.displayedContent.length !in 1..20_000 ||
                        (it.originalContent?.length ?: 0) > 20_000 ||
                        (it.translatedContent?.length ?: 0) > 20_000 ||
                        (it.language?.length ?: 0) > 128
                }?.let { add("Invalid Hayai quote ${it.id.take(32)}") }
            data.novelRepositories
                .firstOrNull { it.baseUrl.length !in 1..8_192 || it.name.length !in 1..1_024 }
                ?.let { add("Invalid novel repository") }
            data.chapterStats
                .firstOrNull { it.mangaUrl.length !in 1..8_192 || it.chapterUrl.length !in 1..8_192 || it.wordCount < 0 }
                ?.let { add("Invalid novel chapter statistic") }
            data.ehFavorites
                .firstOrNull {
                    !validGalleryIdentity(it.gid, it.token) ||
                        it.title.length !in 1..8_192 ||
                        it.category !in 0..9
                }?.let { add("Invalid E-Hentai favorite") }
            validateEhDuplicates(data).forEach(::add)
            data.ehGalleryAliases
                .firstOrNull {
                    !validGalleryIdentity(it.canonicalGid, it.canonicalToken) ||
                        !validGalleryIdentity(it.alternateGid, it.alternateToken) ||
                        (it.canonicalGid == it.alternateGid && it.canonicalToken == it.alternateToken)
                }?.let { add("Invalid E-Hentai gallery alias") }
            data.ehCategoryMappings
                .firstOrNull { it.slot !in 0..9 || it.remoteName.length !in 1..255 || it.localCategoryName.length !in 1..255 }
                ?.let { add("Invalid E-Hentai category mapping") }
            data.sourceMetadata
                .firstOrNull {
                    it.mangaUrl.length !in 1..8_192 ||
                        it.extra.length !in 1..1_048_576 ||
                        (it.uploader?.length ?: 0) > 2_048 ||
                        (it.indexedExtra?.length ?: 0) > 2_048 ||
                        it.extraVersion < 0 ||
                        it.tags.size > 20_000 ||
                        it.titles.size > 1_000 ||
                        it.tags.any { tag ->
                            (tag.namespace?.length ?: 0) > 256 || tag.name.length !in 1..2_048
                        } ||
                        it.titles.any { title -> title.title.length !in 1..8_192 } ||
                        it.tags.distinct().size != it.tags.size ||
                        it.titles.distinct().size != it.titles.size
                }?.let { add("Invalid source metadata") }
            data.novelPlugins
                .firstOrNull {
                    it.descriptorJson.length !in 1..65_536 ||
                        it.repositoryUrl.length !in 1..8_192 ||
                        it.code.size !in 1..(8 * 1024 * 1024) ||
                        it.preferences.size > 10_000 ||
                        it.preferences.any { preference ->
                            preference.key.length !in 1..512 ||
                                preference.value.length > 1024 * 1024
                        } ||
                        it.preferences.map(HayaiBackupPluginPreference::key).distinct().size != it.preferences.size
                }?.let { add("Invalid novel plugin backup") }
            data.novelHighlights.firstOrNull {
                it.id.length !in 1..128 ||
                    it.mangaUrl.length !in 1..8_192 ||
                    it.chapterUrl.length !in 1..8_192 ||
                    (it.note?.length ?: 0) > 16_384 ||
                    it.createdAt < 0 ||
                    it.updatedAt < it.createdAt
            }?.let { add("Invalid novel highlight") }
            data.novelCustomSources.firstOrNull { definition -> definition.validate().isNotEmpty() }
                ?.let { add("Invalid visual novel source ${it.id.take(32)}") }
            data.novelApkRepositories.firstOrNull { repository ->
                repository.length !in 1..8_192 || runCatching {
                    val uri = java.net.URI(repository)
                    uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null
                }.getOrDefault(true)
            }?.let { add("Invalid novel APK repository") }
            data.novelTranslations.firstOrNull { translation ->
                translation.mangaUrl.length !in 1..8_192 ||
                    translation.chapterUrl.length !in 1..8_192 ||
                    translation.sourceLanguage.length !in 1..64 ||
                    translation.targetLanguage.length !in 1..64 ||
                    !translation.sourceHash.matches(Regex("[0-9a-f]{64}")) ||
                    translation.translatedContent.length !in 1..2_000_000 ||
                    translation.contentFormat != StoredNovelTranslation.CONTENT_FORMAT ||
                    translation.engineId.length !in 1..128 ||
                    (translation.detectedLanguage?.length ?: 0) > 64 ||
                    translation.createdAt < 0 ||
                    translation.updatedAt < translation.createdAt
            }?.let { add("Invalid completed novel translation") }
        }

    private fun validateEhDuplicates(data: HayaiBackupData): List<String> =
        buildList {
            if (data.ehFavorites.groupBy { it.gid to it.token }.values.any { it.distinct().size > 1 }) {
                add("Conflicting duplicate E-Hentai favorite")
            }
            val aliasesByAlternate = data.ehGalleryAliases.groupBy { it.alternateGid to it.alternateToken }
            if (aliasesByAlternate.values.any { aliases -> aliases.map { it.canonicalGid to it.canonicalToken }.distinct().size > 1 }) {
                add("Conflicting duplicate E-Hentai gallery alias")
            }
            if (data.sourceMetadata.groupBy { it.sourceId to it.mangaUrl }.values.any { metadata ->
                    metadata.map(::normalizedMetadata).distinct().size > 1
                }
            ) {
                add("Conflicting duplicate source metadata")
            }
        }

    private fun normalizedMetadata(metadata: HayaiBackupSourceMetadata): HayaiBackupSourceMetadata =
        metadata.copy(
            tags = metadata.tags.distinct().sortedWith(compareBy({ it.namespace }, { it.name }, { it.type })),
            titles = metadata.titles.distinct().sortedWith(compareBy({ it.type }, { it.title })),
        )

    private fun validGalleryIdentity(
        gid: String,
        token: String,
    ): Boolean = gid.toLongOrNull()?.let { it > 0 } == true && token.matches(Regex("[A-Za-z0-9_-]{1,128}"))
}
