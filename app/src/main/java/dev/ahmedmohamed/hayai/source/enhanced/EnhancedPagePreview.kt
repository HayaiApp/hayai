package dev.ahmedmohamed.hayai.source.enhanced

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Response
import java.io.IOException
import kotlin.math.ceil

data class EnhancedPagePreview(
    val index: Int,
    val imageUrl: String,
    val pageUrl: String?,
)

data class EnhancedPagePreviewPage(
    val page: Int,
    val previews: List<EnhancedPagePreview>,
    val hasNextPage: Boolean,
    val totalPages: Int,
)

interface EnhancedPagePreviewSource {
    suspend fun getPagePreviews(
        manga: SManga,
        chapters: List<SChapter>,
        page: Int,
    ): EnhancedPagePreviewPage

    suspend fun fetchPreviewImage(preview: EnhancedPagePreview, cacheControl: CacheControl? = null): Response
}

internal object EnhancedPagePreviewParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun nhentaiConfig(body: String): List<String> =
        json.decodeFromString<NHentaiConfig>(body).thumbServers.filter { it.startsWith("https://") }

    fun nhentai(
        body: String,
        baseUrl: String,
        thumbServers: List<String>,
    ): List<EnhancedPagePreview> {
        require(thumbServers.isNotEmpty())
        val gallery = json.decodeFromString<NHentaiGallery>(body)
        require(gallery.id > 0) { "NHentai returned an invalid gallery ID" }
        return gallery.pages.mapIndexedNotNull { index, item ->
            val path = item.thumbnail?.trim()?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            val server = thumbServers[Math.floorMod((gallery.mediaId.orEmpty() + index).hashCode(), thumbServers.size)].trimEnd('/')
            val url = if (path.startsWith("https://")) path else "$server/${path.trimStart('/')}"
            require(url.startsWith("https://")) { "NHentai returned an insecure preview URL" }
            EnhancedPagePreview(index + 1, url, "${baseUrl.trimEnd('/')}/g/${gallery.id}/${index + 1}/")
        }
    }

    fun lanraragi(
        body: String,
        baseUrl: String,
    ): List<EnhancedPagePreview> {
        val archive = json.decodeFromString<LanraragiArchive>(body)
        require(archive.arcid.matches(ARC_ID_REGEX)) { "LANraragi returned an invalid archive ID" }
        require(archive.pagecount in 1..MAX_GALLERY_PAGES) { "LANraragi returned an invalid page count" }
        val base = baseUrl.trimEnd('/')
        return (1..archive.pagecount).map { index ->
            val suffix = if (index == 1) "" else "?page=$index&no_fallback=true"
            EnhancedPagePreview(index, "$base/api/archives/${archive.arcid}/thumbnail$suffix", "$base/reader?id=${archive.arcid}#page=$index")
        }
    }

    fun lanraragiThumbnailJob(body: String): Int = json.decodeFromString<LanraragiThumbnailTask>(body).job
    fun lanraragiJobFinished(body: String): Boolean = json.decodeFromString<LanraragiTaskProgress>(body).state == "finished"

    fun paginate(
        previews: List<EnhancedPagePreview>,
        page: Int,
        pageSize: Int = EnhancedPagePreviewGateway.PREVIEWS_PER_PAGE,
    ): EnhancedPagePreviewPage {
        require(page > 0)
        require(pageSize in 1..100)
        val totalPages = maxOf(1, ceil(previews.size / pageSize.toDouble()).toInt())
        if (page > totalPages) return EnhancedPagePreviewPage(page, emptyList(), false, totalPages)
        val from = (page - 1) * pageSize
        return EnhancedPagePreviewPage(page, previews.drop(from).take(pageSize), page < totalPages, totalPages)
    }

    @Serializable
    private data class NHentaiConfig(
        @SerialName("thumb_servers") val thumbServers: List<String> = emptyList(),
    )

    @Serializable
    private data class NHentaiGallery(
        val id: Long,
        @SerialName("media_id") val mediaId: String? = null,
        val pages: List<NHentaiPage> = emptyList(),
    )

    @Serializable
    private data class NHentaiPage(val thumbnail: String? = null)

    @Serializable
    private data class LanraragiArchive(val arcid: String, val pagecount: Int)

    @Serializable
    private data class LanraragiThumbnailTask(val job: Int)

    @Serializable
    private data class LanraragiTaskProgress(val state: String)

    private const val MAX_GALLERY_PAGES = 10_000
    private val ARC_ID_REGEX = Regex("[A-Za-z0-9]{40}")
}

internal class EnhancedPagePreviewGateway(
    private val source: HayaiEnhancedHttpSource,
) {
    @Volatile private var nhentaiConfig: NHentaiConfig? = null

    fun supportsPreviews(): Boolean = source.definition.family in PREVIEW_FAMILIES

    suspend fun page(
        manga: SManga,
        page: Int,
    ): EnhancedPagePreviewPage {
        require(page > 0) { "Preview page must be positive" }
        return when (source.definition.family) {
            dev.ahmedmohamed.hayai.source.SourceFamily.NHentai -> nhentaiPage(manga, page)
            dev.ahmedmohamed.hayai.source.SourceFamily.Lanraragi -> lanraragiPage(manga, page)
            else -> error("${source.name} does not expose page previews")
        }
    }

    suspend fun image(preview: EnhancedPagePreview, cacheControl: CacheControl?): Response {
        val request = if (cacheControl != null) {
            GET(preview.imageUrl, headers = source.headers, cache = cacheControl)
        } else {
            GET(preview.imageUrl, headers = source.headers)
        }
        var response = source.client.newCall(request).awaitSuccessOrAccepted()
        if (source.definition.family != dev.ahmedmohamed.hayai.source.SourceFamily.Lanraragi || response.code != 202) {
            return response
        }
        val task = response.use { EnhancedPagePreviewParser.lanraragiThumbnailJob(it.body.stringBounded(MAX_METADATA_BYTES)) }
        repeat(LANRARAGI_JOB_POLLS) { attempt ->
            if (attempt > 0) delay(LANRARAGI_JOB_POLL_MILLIS)
            if (lanraragiJobFinished(task)) {
                response = source.client.newCall(request).awaitSuccessOrAccepted()
                if (response.code != 202) return response
                response.close()
            }
        }
        throw IOException("LANraragi did not finish generating the page thumbnail")
    }

    private suspend fun nhentaiPage(manga: SManga, page: Int): EnhancedPagePreviewPage {
        val response = source.client.newCall(source.originalSource.mangaDetailsRequest(manga)).awaitSuccess()
        val body = response.use { it.body.stringBounded(MAX_METADATA_BYTES) }
        val config = nhentaiConfig ?: loadNhentaiConfig().also { nhentaiConfig = it }
        val servers = config.thumbServers.ifEmpty { FALLBACK_NHENTAI_THUMB_SERVERS }
        val all = EnhancedPagePreviewParser.nhentai(body, source.baseUrl, servers)
        return EnhancedPagePreviewParser.paginate(all, page)
    }

    private suspend fun loadNhentaiConfig(): NHentaiConfig = try {
        source.client.newCall(GET("https://nhentai.net/api/v2/config", headers = source.headers)).awaitSuccess().use {
            NHentaiConfig(EnhancedPagePreviewParser.nhentaiConfig(it.body.stringBounded(MAX_METADATA_BYTES)))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        NHentaiConfig(thumbServers = FALLBACK_NHENTAI_THUMB_SERVERS)
    }

    private suspend fun lanraragiPage(manga: SManga, page: Int): EnhancedPagePreviewPage {
        val response = source.client.newCall(source.originalSource.mangaDetailsRequest(manga)).awaitSuccess()
        val all = response.use {
            EnhancedPagePreviewParser.lanraragi(it.body.stringBounded(MAX_METADATA_BYTES), source.baseUrl)
        }
        return EnhancedPagePreviewParser.paginate(all, page)
    }

    private suspend fun lanraragiJobFinished(jobId: Int): Boolean {
        require(jobId >= 0) { "LANraragi returned an invalid thumbnail job" }
        val url = "${source.baseUrl.trimEnd('/')}/api/minion/$jobId"
        return source.client.newCall(GET(url, headers = source.headers)).awaitSuccess().use {
            EnhancedPagePreviewParser.lanraragiJobFinished(it.body.stringBounded(MAX_METADATA_BYTES))
        }
    }

    private suspend fun okhttp3.Call.awaitSuccessOrAccepted(): Response {
        val response = awaitSuccess()
        if (response.code in 200..299) return response
        response.close()
        throw IOException("Preview request failed with HTTP ${response.code}")
    }

    private fun okhttp3.ResponseBody.stringBounded(limit: Long): String {
        val declared = contentLength()
        require(declared < 0 || declared <= limit) { "Preview metadata exceeded the size limit" }
        val bytes = byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream(minOf(limit, 16 * 1024L).toInt())
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "Preview metadata exceeded the size limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private data class NHentaiConfig(val thumbServers: List<String>)

    companion object {
        const val PREVIEWS_PER_PAGE = 12
        private const val MAX_METADATA_BYTES = 4L * 1024L * 1024L
        private const val LANRARAGI_JOB_POLLS = 4
        private const val LANRARAGI_JOB_POLL_MILLIS = 200L
        private val PREVIEW_FAMILIES = setOf(
            dev.ahmedmohamed.hayai.source.SourceFamily.NHentai,
            dev.ahmedmohamed.hayai.source.SourceFamily.Lanraragi,
        )
        private val FALLBACK_NHENTAI_THUMB_SERVERS = (1..4).map { "https://t$it.nhentai.net" }
    }
}
