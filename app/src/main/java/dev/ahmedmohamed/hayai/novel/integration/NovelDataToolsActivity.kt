package dev.ahmedmohamed.hayai.novel.integration

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.ahmedmohamed.hayai.novel.download.NovelAssetReferences
import dev.ahmedmohamed.hayai.novel.export.NovelEpubAsset
import dev.ahmedmohamed.hayai.novel.export.NovelEpubBook
import dev.ahmedmohamed.hayai.novel.export.NovelEpubChapter
import dev.ahmedmohamed.hayai.novel.export.NovelEpubExporter
import dev.ahmedmohamed.hayai.novel.export.NovelEpubMetadata
import dev.ahmedmohamed.hayai.novel.export.NovelEpubNaming
import dev.ahmedmohamed.hayai.novel.importer.ExternalNovelImportParser
import dev.ahmedmohamed.hayai.novel.importer.ExternalNovelImportService
import dev.ahmedmohamed.hayai.novel.importer.J2kNovelImportTarget
import dev.ahmedmohamed.hayai.novel.importer.NovelImportPlan
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginManager
import dev.ahmedmohamed.hayai.novel.source.NovelAssetProvider
import dev.ahmedmohamed.hayai.novel.source.NovelContentType
import dev.ahmedmohamed.hayai.novel.source.NovelSource
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.isNovelSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class NovelDataToolsActivity : AppCompatActivity() {
    private val database by lazy { Injekt.get<DatabaseHelper>() }
    private val sources by lazy { Injekt.get<SourceManager>() }
    private val parser = ExternalNovelImportParser()
    private var job: Job? = null
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var cancel: Button
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::inspectImport) }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri -> uri?.let(::exportTo) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Novel data tools"
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            status = TextView(context)
            addView(status)
            progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            addView(progress)
            addView(Button(context).apply {
                text = "Import Tsundoku or LNReader"
                setOnClickListener {
                    importPicker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-protobuf"))
                }
            })
            addView(Button(context).apply {
                text = "Export novel as EPUB"
                isEnabled = intent.getLongExtra(EXTRA_MANGA_ID, -1) > 0
                setOnClickListener {
                    val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1)
                    val manga = database.getManga(mangaId).executeAsBlocking()
                    if (manga != null) exportPicker.launch(NovelEpubNaming.safeFileName(manga.title))
                }
            })
            cancel = Button(context).apply {
                text = "Cancel"
                isEnabled = false
                setOnClickListener { job?.cancel() }
            }
            addView(cancel)
        })
    }

    private fun inspectImport(uri: Uri) = startJob {
        update("Inspecting import without changing data", 0)
        val plan = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri).use { input -> parser.dryRun(requireNotNull(input)) }
        }
        val warnings = plan.issues.joinToString("\n") { "${it.severity}: ${it.location}: ${it.message}" }
        AlertDialog.Builder(this)
            .setTitle("Import preview")
            .setMessage("Format: ${plan.format}\nNovels: ${plan.novels.size}\nChapters: ${plan.novels.sumOf { it.chapters.size }}\nCategories: ${plan.categories.size}\n\n${warnings.ifBlank { "No validation warnings." }}")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Apply") { _, _ -> applyImport(plan) }
            .show()
        update("Dry run complete. No source data was changed.", 100)
    }

    private fun applyImport(plan: NovelImportPlan) = startJob {
        update("Applying import atomically", 10)
        val missing = unresolvedSources(plan)
        val target = J2kNovelImportTarget(database, sources, Injekt.get<NovelPluginManager>())
        val result = withContext(Dispatchers.IO) { ExternalNovelImportService(target).apply(plan) }
        val message = if (result.alreadyApplied) {
            "This exact import was already applied. No duplicate data was written."
        } else {
            "Imported ${result.importedNovels} novels, ${result.importedChapters} chapters, and ${result.importedCategories} categories. " +
                "${result.skippedNovels} novels were skipped. Missing sources: ${missing.joinToString().ifBlank { "none" }}"
        }
        update(message, 100)
    }

    private fun exportTo(uri: Uri) = startJob(cleanupDestination = uri) {
        val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1)
        val manga = withContext(Dispatchers.IO) {
            requireNotNull(database.getManga(mangaId).executeAsBlocking())
        }
        val source = requireNotNull(sources.get(manga.source)) as? NovelSource
            ?: error("The source does not provide novel text")
        val chapters = withContext(Dispatchers.IO) {
            database.getChapters(manga).executeAsBlocking().sortedBy { it.chapter_number }
        }
        require(chapters.isNotEmpty())
        val epubChapters = mutableListOf<NovelEpubChapter>()
        val assets = mutableListOf<NovelEpubAsset>()
        var assetBytes = 0L
        var chapterChars = 0L
        val coverUrl = manga.thumbnail_url?.let { thumbnail ->
            absolute(thumbnail, (source as? HttpSource)?.baseUrl)
        }
        val cover = coverUrl?.takeIf { mediaType(it).startsWith("image/") }?.let { url ->
            withContext(Dispatchers.IO) { loadHttpAsset(source, url) }?.let { bytes ->
                assetBytes += bytes.size
                require(assetBytes <= MAX_EXPORT_ASSETS)
                NovelEpubAsset(assetFileName(url, url), mediaType(url), bytes, url)
            }
        }
        chapters.forEachIndexed { index, chapter ->
            coroutineContext.ensureActive()
            update("Preparing ${chapter.name}", index * 90 / chapters.size)
            val document = withContext(Dispatchers.IO) { source.getChapterDocument(chapter) }
            val html = when (document.contentType) {
                NovelContentType.Html -> document.content
                else -> "<pre>${escape(document.content)}</pre>"
            }
            chapterChars += html.length
            require(chapterChars <= MAX_EXPORT_CHARS) { "Novel text is too large for in-memory export" }
            epubChapters += NovelEpubChapter(chapter.name, html, document.baseUrl)
            NovelAssetReferences.extract(document).forEach { reference ->
                val sourceUrl = absolute(reference, document.baseUrl)
                if (assets.any { it.sourceUrl == sourceUrl } || cover?.sourceUrl == sourceUrl) return@forEach
                val bytes = withContext(Dispatchers.IO) {
                    loadAsset(source, chapter.url, reference, document.baseUrl)
                } ?: return@forEach
                assetBytes += bytes.size
                require(assetBytes <= MAX_EXPORT_ASSETS)
                assets += NovelEpubAsset(assetFileName(sourceUrl, reference), mediaType(reference), bytes, sourceUrl)
            }
        }
        val language = source.lang.takeUnless { it.isBlank() || it == "all" } ?: "en"
        val book = NovelEpubBook(
            metadata = NovelEpubMetadata(
                title = manga.title,
                authors = listOfNotNull(manga.author),
                language = language,
                identifier = "hayai:${manga.source}:${manga.url}",
                description = manga.description,
                cover = cover,
            ),
            chapters = epubChapters,
            assets = assets,
        )
        update("Writing EPUB", 95)
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri, "w").use { output ->
                NovelEpubExporter().write(book, requireNotNull(output))
            }
        }
        update("EPUB export complete", 100)
    }

    private fun startJob(cleanupDestination: Uri? = null, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = lifecycleScope.launch {
            cancel.isEnabled = true
            runCatching { block() }.onFailure { error ->
                val removed = cleanupDestination?.let { destination ->
                    runCatching { contentResolver.delete(destination, null, null) > 0 }.getOrDefault(false)
                } ?: false
                if (error is CancellationException) {
                    update(
                        if (removed) "Operation canceled. The incomplete destination was removed." else "Operation canceled.",
                        0,
                    )
                } else {
                    update(error.message ?: "Operation failed", 0)
                }
            }
            cancel.isEnabled = false
        }
    }

    private fun update(message: String, value: Int) {
        status.text = message
        progress.progress = value
    }

    private suspend fun loadAsset(
        source: NovelSource,
        chapterUrl: String,
        reference: String,
        base: String?,
    ): ByteArray? {
        val providerPath = NovelAssetReferences.providerPath(reference) ?: reference
        val input = (source as? NovelAssetProvider)?.getChapterAsset(chapterUrl, providerPath)
        if (input != null) return input.use { it.readBounded(MAX_SINGLE_ASSET) }
        val url = absolute(reference, base).toHttpUrlOrNull() ?: return null
        val response = (source as? HttpSource)?.getImage(Page(0, url.toString(), url.toString())) ?: return null
        return response.use {
            if (!it.isSuccessful) return null
            it.body.byteStream().readBounded(MAX_SINGLE_ASSET)
        }
    }

    private suspend fun loadHttpAsset(source: NovelSource, url: String): ByteArray? {
        val http = source as? HttpSource ?: return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        return http.getImage(Page(0, parsed.toString(), parsed.toString())).use { response ->
            if (!response.isSuccessful) null else response.body.byteStream().readBounded(MAX_SINGLE_ASSET)
        }
    }

    private fun java.io.InputStream.readBounded(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= max) { "Embedded asset is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun unresolvedSources(plan: NovelImportPlan): List<String> {
        val pluginSources = Injekt.get<NovelPluginManager>().catalog.value.sources.associateBy { it.pluginId }
        return plan.novels.mapNotNull { novel ->
            val reference = novel.source
            val resolved = when {
                reference.isLocal -> sources.get(1L)?.isNovelSource() == true
                reference.sourceId != null -> sources.get(reference.sourceId)?.isNovelSource() == true
                reference.pluginId != null -> pluginSources[reference.pluginId] != null
                else -> false
            }
            if (resolved) {
                null
            } else {
                reference.pluginId ?: reference.sourceId?.toString() ?: if (reference.isLocal) "local novel source" else "unknown"
            }
        }.distinct().sorted()
    }

    private fun absolute(value: String, base: String?) = runCatching {
        URI(base.orEmpty()).resolve(value).toASCIIString()
    }.getOrDefault(value)

    private fun assetFileName(sourceUrl: String, reference: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val raw = reference.substringBefore('?')
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeLast(96)
            .ifBlank { "asset.bin" }
        return "$digest-$raw"
    }

    private fun mediaType(value: String) = when (value.substringBefore('?').substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    companion object {
        const val EXTRA_MANGA_ID = "hayai.manga_id"
        private const val MAX_SINGLE_ASSET = 8 * 1024 * 1024
        private const val MAX_EXPORT_ASSETS = 32L * 1024 * 1024
        private const val MAX_EXPORT_CHARS = 16L * 1024 * 1024
    }
}
