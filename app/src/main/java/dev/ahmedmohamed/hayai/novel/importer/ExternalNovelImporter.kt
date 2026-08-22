@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.ahmedmohamed.hayai.novel.importer

import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.novel.error.novelRequire
import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

enum class ExternalNovelFormat { TSUNDOKU, LNREADER }
enum class NovelImportSeverity { WARNING, ERROR }
enum class NovelImportIssueCode { MissingCategoryAssignments }
data class NovelImportIssue(
    val severity: NovelImportSeverity,
    val location: String,
    val code: NovelImportIssueCode,
    val count: Int,
)
data class ExternalNovelSource(
    val sourceId: Long?,
    val pluginId: String?,
    val isLocal: Boolean = false,
)
data class ExternalNovelChapter(
    val externalId: String,
    val url: String,
    val title: String,
    val number: Float?,
    val read: Boolean,
    val bookmarked: Boolean,
    val progress: Int,
    val lastReadAt: Long?,
)
data class ExternalNovel(
    val externalId: String,
    val source: ExternalNovelSource,
    val url: String,
    val title: String,
    val author: String?,
    val description: String?,
    val coverUrl: String?,
    val favorite: Boolean,
    val chapters: List<ExternalNovelChapter>,
)
data class ExternalNovelCategory(
    val externalId: String,
    val name: String,
    val order: Int,
    val novelIds: Set<String>,
)
data class NovelImportPlan(
    val id: String,
    val format: ExternalNovelFormat,
    val novels: List<ExternalNovel>,
    val categories: List<ExternalNovelCategory>,
    val issues: List<NovelImportIssue>,
    val inputBytes: Long,
) {
    val canApply get() = issues.none { it.severity == NovelImportSeverity.ERROR }
}
data class NovelImportResult(
    val planId: String,
    val alreadyApplied: Boolean,
    val importedNovels: Int,
    val importedChapters: Int,
    val importedCategories: Int,
    val skippedNovels: Int,
)

interface NovelImportTarget {
    suspend fun hasApplied(planId: String): Boolean
    suspend fun <T> transaction(block: suspend NovelImportTransaction.() -> T): T
}
interface NovelImportTransaction {
    suspend fun resolveSource(source: ExternalNovelSource): Long?
    suspend fun upsertNovel(sourceId: Long, novel: ExternalNovel): Long
    suspend fun upsertChapter(mangaId: Long, chapter: ExternalNovelChapter)
    suspend fun upsertCategory(category: ExternalNovelCategory): Long
    suspend fun setCategory(mangaId: Long, categoryId: Long)
    suspend fun markApplied(planId: String)
}

class ExternalNovelImportService(private val target: NovelImportTarget) {
    suspend fun apply(plan: NovelImportPlan): NovelImportResult {
        novelRequire(plan.canApply, NovelFailure.Code.ImportPlanInvalid)
        if (target.hasApplied(plan.id)) return NovelImportResult(plan.id, true, 0, 0, 0, 0)
        return target.transaction {
            val categoryIds = plan.categories.associate { it.externalId to upsertCategory(it) }
            val imported = mutableMapOf<String, Long>()
            var chapters = 0
            var skipped = 0
            plan.novels.forEach { novel ->
                val sourceId = resolveSource(novel.source)
                if (sourceId == null) {
                    skipped++
                    return@forEach
                }
                val mangaId = upsertNovel(sourceId, novel)
                imported[novel.externalId] = mangaId
                novel.chapters.forEach {
                    upsertChapter(mangaId, it)
                    chapters++
                }
            }
            plan.categories.forEach { category ->
                category.novelIds.forEach { externalId ->
                    imported[externalId]?.let { mangaId ->
                        setCategory(mangaId, requireNotNull(categoryIds[category.externalId]))
                    }
                }
            }
            if (skipped == 0) markApplied(plan.id)
            NovelImportResult(plan.id, false, imported.size, chapters, categoryIds.size, skipped)
        }
    }
}

class ExternalNovelImportParser(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val protobuf: ProtoBuf = ProtoBuf,
) {
    fun dryRun(input: InputStream): NovelImportPlan {
        val bytes = input.readBounded(MAX_INPUT_BYTES)
        novelRequire(bytes.isNotEmpty(), NovelFailure.Code.ImportEmpty)
        return if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            parseLnReader(bytes)
        } else {
            parseTsundoku(bytes)
        }
    }

    private fun parseTsundoku(original: ByteArray): NovelImportPlan {
        val payload = if (original.size >= 2 && original[0] == 0x1f.toByte() && original[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(original)).readBounded(MAX_UNCOMPRESSED_BYTES)
        } else {
            original
        }
        val backup = runCatching { protobuf.decodeFromByteArray<Backup>(payload) }
            .getOrElse { throw NovelFailure(NovelFailure.Code.ImportInvalidTsundoku, cause = it) }
        novelRequire(backup.backupManga.size <= MAX_NOVELS, NovelFailure.Code.ImportTooManyNovels)
        novelRequire(backup.backupCategories.size <= MAX_CATEGORIES, NovelFailure.Code.ImportTooManyCategories)
        val novels = backup.backupManga.mapIndexed { index, manga ->
            novelRequire(manga.chapters.size <= MAX_CHAPTERS_PER_NOVEL, NovelFailure.Code.ImportTooManyChapters)
            ExternalNovel(
                externalId = "ts:$index:${manga.source}:${manga.url}",
                source = ExternalNovelSource(manga.source, null),
                url = bounded(manga.url, 8_192, NovelFailure.ImportField.MangaUrl),
                title = bounded(manga.title, 1_024, NovelFailure.ImportField.Title),
                author = manga.author?.take(1_024),
                description = manga.description?.take(MAX_DESCRIPTION),
                coverUrl = manga.thumbnailUrl?.take(8_192),
                favorite = manga.favorite,
                chapters = manga.chapters.mapIndexed { chapterIndex, chapter ->
                    ExternalNovelChapter(
                        externalId = "ts:$index:$chapterIndex:${chapter.url}",
                        url = bounded(chapter.url, 8_192, NovelFailure.ImportField.ChapterUrl),
                        title = bounded(chapter.name, 1_024, NovelFailure.ImportField.ChapterTitle),
                        number = chapter.chapterNumber,
                        read = chapter.read,
                        bookmarked = chapter.bookmark,
                        progress = chapter.lastPageRead.coerceIn(0, 100),
                        lastReadAt = manga.history.firstOrNull { it.url == chapter.url }?.lastRead,
                    )
                },
            )
        }
        novelRequire(novels.sumOf { it.chapters.size.toLong() } <= MAX_TOTAL_CHAPTERS, NovelFailure.Code.ImportTooManyChapters)
        val categories = backup.backupCategories.mapIndexed { index, category ->
            ExternalNovelCategory(
                externalId = "ts-cat:$index",
                name = bounded(category.name, 256, NovelFailure.ImportField.Category),
                order = category.order,
                novelIds = novels.filterIndexed { novelIndex, _ ->
                    category.order in backup.backupManga[novelIndex].categories
                }.mapTo(mutableSetOf(), ExternalNovel::externalId),
            )
        }
        return NovelImportPlan(hash(original), ExternalNovelFormat.TSUNDOKU, novels, categories, emptyList(), original.size.toLong())
    }

    private fun parseLnReader(original: ByteArray): NovelImportPlan {
        val novels = mutableListOf<ExternalNovel>()
        var categories = emptyList<ExternalNovelCategory>()
        var entries = 0
        var total = 0L
        ZipInputStream(ByteArrayInputStream(original)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                novelRequire(entries <= MAX_ZIP_ENTRIES, NovelFailure.Code.ImportTooManyFiles)
                if (entry.isDirectory) continue
                val bytes = zip.readBounded(MAX_ENTRY_BYTES)
                total += bytes.size
                novelRequire(total <= MAX_UNCOMPRESSED_BYTES, NovelFailure.Code.ImportExpandedTooLarge)
                val safeName = entry.name.replace('\\', '/')
                novelRequire(!safeName.startsWith('/') && safeName.split('/').none { it == ".." }, NovelFailure.Code.ImportUnsafeZipPath)
                when {
                    safeName.equals("Category.json", true) -> categories = parseLnCategories(bytes)
                    safeName.startsWith("NovelAndChapters/", true) && safeName.endsWith(".json", true) -> {
                        novelRequire(novels.size < MAX_NOVELS, NovelFailure.Code.ImportTooManyNovels)
                        novels += parseLnNovel(novels.size, json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
                    }
                }
            }
        }
        novelRequire(novels.sumOf { it.chapters.size.toLong() } <= MAX_TOTAL_CHAPTERS, NovelFailure.Code.ImportTooManyChapters)
        val known = novels.mapTo(mutableSetOf(), ExternalNovel::externalId)
        val checkedCategories = categories.map { it.copy(novelIds = it.novelIds.intersect(known)) }
        val missing = categories.sumOf { (it.novelIds - known).size }
        val issues = if (missing > 0) {
            listOf(
                NovelImportIssue(
                    NovelImportSeverity.WARNING,
                    "Category.json",
                    NovelImportIssueCode.MissingCategoryAssignments,
                    missing,
                ),
            )
        } else {
            emptyList()
        }
        return NovelImportPlan(hash(original), ExternalNovelFormat.LNREADER, novels, checkedCategories, issues, original.size.toLong())
    }

    private fun parseLnNovel(index: Int, obj: JsonObject): ExternalNovel {
        val numericId = obj.int("id", index)
        val externalId = "ln:$numericId"
        val chapters = obj["chapters"]?.jsonArray ?: JsonArray(emptyList())
        novelRequire(chapters.size <= MAX_CHAPTERS_PER_NOVEL, NovelFailure.Code.ImportTooManyChapters)
        return ExternalNovel(
            externalId = externalId,
            source = ExternalNovelSource(null, obj.string("pluginId"), obj.bool("isLocal")),
            url = bounded(obj.string("path"), 8_192, NovelFailure.ImportField.NovelPath),
            title = bounded(obj.string("name"), 1_024, NovelFailure.ImportField.NovelName),
            author = obj.optional("author")?.take(1_024),
            description = obj.optional("summary")?.take(MAX_DESCRIPTION),
            coverUrl = obj.optional("cover")?.take(8_192),
            favorite = obj.bool("inLibrary"),
            chapters = chapters.mapIndexed { chapterIndex, element ->
                val chapter = element.jsonObject
                val url = bounded(chapter.string("path"), 8_192, NovelFailure.ImportField.ChapterPath)
                val progress = chapter["progress"]?.jsonPrimitive?.intOrNull
                    ?: chapter["position"]?.jsonPrimitive?.intOrNull
                    ?: 0
                ExternalNovelChapter(
                    externalId = "$externalId:${chapter.int("id", chapterIndex)}:$url",
                    url = url,
                    title = bounded(chapter.string("name"), 1_024, NovelFailure.ImportField.ChapterName),
                    number = chapter["chapterNumber"]?.jsonPrimitive?.floatOrNull,
                    read = !chapter.bool("unread", true),
                    bookmarked = chapter.bool("bookmark"),
                    progress = progress.coerceIn(0, 100),
                    lastReadAt = parseTimestamp(chapter.optional("readTime")),
                )
            },
        )
    }

    private fun parseLnCategories(bytes: ByteArray): List<ExternalNovelCategory> {
        val array = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray
        novelRequire(array.size <= MAX_CATEGORIES, NovelFailure.Code.ImportTooManyCategories)
        return array.mapIndexed { index, element ->
            val obj = element.jsonObject
            ExternalNovelCategory(
                externalId = "ln-cat:${obj.int("id", index)}",
                name = bounded(obj.string("name"), 256, NovelFailure.ImportField.Category),
                order = obj.int("sort", index),
                novelIds = (obj["novelIds"]?.jsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { item -> item.jsonPrimitive.intOrNull?.let { id -> "ln:$id" } }
                    .toSet(),
            )
        }
    }

    private fun JsonObject.string(name: String) = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.optional(name: String) = string(name).takeIf(String::isNotBlank)
    private fun JsonObject.int(name: String, fallback: Int) = this[name]?.jsonPrimitive?.intOrNull ?: fallback
    private fun JsonObject.bool(name: String, fallback: Boolean = false): Boolean =
        this[name]?.jsonPrimitive?.let { primitive ->
            primitive.booleanOrNull ?: primitive.intOrNull?.let { number -> number != 0 }
        } ?: fallback
    private fun parseTimestamp(value: String?): Long? = value?.toLongOrNull()?.takeIf { it > 0 }
    private fun bounded(value: String, max: Int, label: NovelFailure.ImportField): String {
        novelRequire(value.isNotBlank() && value.length <= max, NovelFailure.Code.ImportInvalidField, label)
        return value
    }
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun InputStream.readBounded(max: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            novelRequire(total <= max, NovelFailure.Code.ImportDataTooLarge, max / 1024 / 1024)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_INPUT_BYTES = 32L * 1024 * 1024
        const val MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 4L * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 12_000
        const val MAX_NOVELS = 10_000
        const val MAX_CATEGORIES = 2_000
        const val MAX_CHAPTERS_PER_NOVEL = 20_000
        const val MAX_TOTAL_CHAPTERS = 250_000L
        const val MAX_DESCRIPTION = 64_000
    }
}
