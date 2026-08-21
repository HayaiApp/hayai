package dev.ahmedmohamed.hayai.novel.importer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExternalNovelImporterTest {
    @Test fun `lnreader dry run restores stable identities and categories`() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("NovelAndChapters/7.json"))
            zip.write("""{"id":7,"pluginId":"sample","name":"Novel","path":"/novel","inLibrary":1,"chapters":[{"id":8,"name":"One","path":"/one","unread":0,"progress":40}]}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Category.json"))
            zip.write("""[{"id":2,"name":"Reading","novelIds":[7]}]""".toByteArray())
            zip.closeEntry()
        }
        val plan = ExternalNovelImportParser().dryRun(ByteArrayInputStream(output.toByteArray()))
        assertEquals("ln:7", plan.novels.single().externalId)
        assertEquals(true, plan.novels.single().chapters.single().read)
        assertEquals(setOf("ln:7"), plan.categories.single().novelIds)
    }
    @Test fun `application is idempotent by plan id`() = runBlocking {
        val target = MemoryTarget(); val service = ExternalNovelImportService(target)
        val plan = NovelImportPlan("hash", ExternalNovelFormat.LNREADER, listOf(ExternalNovel("n", ExternalNovelSource(null, "plugin"), "/n", "Novel", null, null, null, true, listOf(ExternalNovelChapter("c", "/c", "Chapter", 1f, false, false, 0, null)))), emptyList(), emptyList(), 1)
        assertEquals(1, service.apply(plan).importedNovels)
        assertEquals(true, service.apply(plan).alreadyApplied)
    }
    @Test fun `missing source leaves plan retryable`() = runBlocking {
        val target = MemoryTarget(sourceId = null)
        val service = ExternalNovelImportService(target)
        val plan = NovelImportPlan("retry", ExternalNovelFormat.LNREADER, listOf(ExternalNovel("n", ExternalNovelSource(null, "missing"), "/n", "Novel", null, null, null, true, emptyList())), emptyList(), emptyList(), 1)
        assertEquals(1, service.apply(plan).skippedNovels)
        assertEquals(false, target.hasApplied(plan.id))
        target.sourceId = 42L
        assertEquals(1, service.apply(plan).importedNovels)
        assertEquals(true, target.hasApplied(plan.id))
    }
    private class MemoryTarget(var sourceId: Long? = 42L) : NovelImportTarget, NovelImportTransaction {
        val applied = mutableSetOf<String>()
        override suspend fun hasApplied(planId: String) = planId in applied
        override suspend fun <T> transaction(block: suspend NovelImportTransaction.() -> T) = block(this)
        override suspend fun resolveSource(source: ExternalNovelSource) = sourceId
        override suspend fun upsertNovel(sourceId: Long, novel: ExternalNovel) = 1L
        override suspend fun upsertChapter(mangaId: Long, chapter: ExternalNovelChapter) = Unit
        override suspend fun upsertCategory(category: ExternalNovelCategory) = 1L
        override suspend fun setCategory(mangaId: Long, categoryId: Long) = Unit
        override suspend fun markApplied(planId: String) { applied += planId }
    }
}
