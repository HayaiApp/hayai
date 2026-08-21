package dev.ahmedmohamed.hayai.novel.tracker.services

import android.content.Context
import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Rebuilt from Tsundoku 547ddea3's NovelUpdates behavior against J2K's TrackService contract. */
class NovelUpdatesTrackService(context: Context, id: Int) :
    HayaiNovelTrackService(
        context = context,
        id = id,
        trackerName = R.string.hayai_tracker_novel_updates,
        trackerColor = Color.rgb(76, 112, 68),
        apiFactory = { client, cookie -> NovelUpdatesApi(NovelTrackerHttp(client), cookie) },
    ) {
    override fun remoteKey(track: Track): String =
        track.tracking_url.substringAfter("#$REMOTE_FRAGMENT=", "").substringBefore('&').trim()

    override fun getScoreList(): List<String> = listOf("-")

    override fun indexToScore(index: Int): Float = 0f

    override fun displayScore(track: Track): String = "-"

    companion object {
        internal const val REMOTE_FRAGMENT = "hayai-nu"
    }
}

internal class NovelUpdatesApi(
    private val http: NovelTrackerHttp,
    private val sessionCookie: () -> String,
    private val baseUrl: String = "https://www.novelupdates.com",
) : NovelTrackerApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    override suspend fun validateSession(username: String, secret: String) {
        val response = http.execute(Request.Builder().url("$baseUrl/reading-list/").headers(secret).get().build())
        val document = Jsoup.parse(response.body, response.finalUrl)
        val redirectedToLogin = response.finalUrl.contains("wp-login", ignoreCase = true)
        val loginFormPresent = document.select("form#loginform, input#user_login, input[name=log]").isNotEmpty()
        if (redirectedToLogin || loginFormPresent) throw NovelTrackerFailure.InvalidCredentials("NovelUpdates session cookies are expired")
    }

    override suspend fun search(query: String): List<NovelTrackerSearchItem> {
        val url =
            "$baseUrl/series-finder/".toHttpUrl().newBuilder()
                .addQueryParameter("sf", "1")
                .addQueryParameter("sh", query)
                .addQueryParameter("sort", "sdate")
                .addQueryParameter("order", "desc")
                .build()
        val response = http.execute(Request.Builder().url(url).headers().get().build())
        val document = Jsoup.parse(response.body, response.finalUrl)
        return document.select("div.search_main_box_nu").take(MAX_RESULTS).mapNotNull { element ->
            val titleLink = element.selectFirst("div.search_title a, .search_title a") ?: return@mapNotNull null
            val title = titleLink.text().trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val pageUrl = titleLink.absUrl("href").ifBlank { titleLink.attr("href") }.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val numericId = element.selectFirst("span[id^=sid]")?.id()?.removePrefix("sid")?.toLongOrNull()
            val slug = Regex("/series/([^/?#]+)", RegexOption.IGNORE_CASE).find(pageUrl)?.groupValues?.get(1)
            val remoteKey = numericId?.toString() ?: slug?.let { "slug:$it" } ?: return@mapNotNull null
            val hiddenText = element.select("div.search_body_nu .testhide").text()
            val summary =
                element.selectFirst("div.search_body_nu")?.text().orEmpty()
                    .replace(hiddenText, "")
                    .plus(" $hiddenText")
                    .replace("... more>>", "")
                    .replace("<<less", "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            NovelTrackerSearchItem(
                remoteKey = remoteKey,
                title = title,
                url = "${pageUrl.substringBefore('#')}#${NovelUpdatesTrackService.REMOTE_FRAGMENT}=$remoteKey",
                coverUrl = element.selectFirst("div.search_img_nu img, .search_img_nu img")?.absUrl("src").orEmpty(),
                summary = summary,
                publishingStatus = element.select("div.search_genre, .search_genre").text(),
            )
        }
    }

    override suspend fun bind(record: NovelTrackerRecord): NovelTrackerPatch {
        val id = resolveNovelId(record)
        val currentStatus = readingListStatus(id)
        val desiredStatus = currentStatus ?: if (record.chapterRead > 0f) NovelReadingStatus.Reading else NovelReadingStatus.PlanToRead
        if (currentStatus == null) moveToList(id, desiredStatus)
        val progress = readNotes(id).progress
        return NovelTrackerPatch(
            url = canonicalTrackingUrl(record.url, id),
            status = desiredStatus,
            chapterRead = progress,
            score = 0f,
        )
    }

    override suspend fun update(record: NovelTrackerRecord): NovelTrackerPatch {
        val id = resolveNovelId(record)
        moveToList(id, record.status)
        writeProgress(id, record.chapterRead)
        return NovelTrackerPatch(url = canonicalTrackingUrl(record.url, id), score = 0f)
    }

    override suspend fun refresh(record: NovelTrackerRecord): NovelTrackerPatch {
        val id = resolveNovelId(record)
        return NovelTrackerPatch(
            url = canonicalTrackingUrl(record.url, id),
            status = readingListStatus(id) ?: NovelReadingStatus.PlanToRead,
            chapterRead = readNotes(id).progress,
            score = 0f,
        )
    }

    override suspend fun remove(record: NovelTrackerRecord) {
        val id = resolveNovelId(record)
        val page = seriesDocument(id)
        if (page.select("div.sticon img[src*=addme.png]").isNotEmpty()) return
        val removeUrl =
            page.select("div.sticon a[href*=updatelist.php]")
                .map { link -> link.absUrl("href").ifBlank { link.attr("href") } }
                .firstOrNull { url ->
                    url.contains("act=del", ignoreCase = true) ||
                        url.contains("act=remove", ignoreCase = true) ||
                        url.contains("act=delete", ignoreCase = true)
                } ?: throw NovelTrackerFailure.InvalidResponse("NovelUpdates did not expose a reading-list remove action")
        http.execute(Request.Builder().url(resolveUrl(removeUrl)).headers().get().build())
    }

    private suspend fun resolveNovelId(record: NovelTrackerRecord): Long {
        record.remoteKey.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        val pageUrl = record.url.substringBefore('#')
        if (!record.remoteKey.startsWith("slug:") || pageUrl.isBlank()) {
            throw NovelTrackerFailure.InvalidResponse("NovelUpdates entry has an invalid remote identifier")
        }
        val response = http.execute(Request.Builder().url(resolveUrl(pageUrl)).headers().get().build())
        val document = Jsoup.parse(response.body, response.finalUrl)
        document.selectFirst("link[rel=shortlink]")?.attr("href")?.let { link ->
            Regex("[?&]p=(\\d+)").find(link)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        }
        document.selectFirst("a[href*=activity-stats]")?.attr("href")?.let { link ->
            Regex("[?&]seriesid=(\\d+)").find(link)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        }
        document.selectFirst("input#mypostid")?.attr("value")?.toLongOrNull()?.let { return it }
        throw NovelTrackerFailure.InvalidResponse("NovelUpdates did not expose the novel identifier")
    }

    private suspend fun readingListStatus(id: Long): NovelReadingStatus? {
        val document = seriesDocument(id)
        val statusIcon = document.selectFirst("div.sticon") ?: return null
        if (statusIcon.select("img[src*=addme.png]").isNotEmpty()) return null
        val listId =
            statusIcon.selectFirst("span.sttitle a[href*=list=]")?.attr("href")
                ?.let { Regex("[?&]list=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: throw NovelTrackerFailure.InvalidResponse("NovelUpdates returned an unknown reading-list state")
        return when (listId) {
            0 -> NovelReadingStatus.Reading
            1 -> NovelReadingStatus.Completed
            2 -> NovelReadingStatus.PlanToRead
            3 -> NovelReadingStatus.OnHold
            4, 5 -> NovelReadingStatus.Dropped
            else -> throw NovelTrackerFailure.InvalidResponse("NovelUpdates returned an unknown reading-list identifier")
        }
    }

    private suspend fun seriesDocument(id: Long): Document {
        val url = "$baseUrl/series/".toHttpUrl().newBuilder().addQueryParameter("p", id.toString()).build()
        val response = http.execute(Request.Builder().url(url).headers().get().build())
        return Jsoup.parse(response.body, response.finalUrl)
    }

    private suspend fun moveToList(id: Long, status: NovelReadingStatus) {
        val listId =
            when (status) {
                NovelReadingStatus.Reading -> 0
                NovelReadingStatus.Completed -> 1
                NovelReadingStatus.PlanToRead -> 2
                NovelReadingStatus.OnHold -> 3
                NovelReadingStatus.Dropped, NovelReadingStatus.Other -> 4
            }
        val url =
            "$baseUrl/updatelist.php".toHttpUrl().newBuilder()
                .addQueryParameter("sid", id.toString())
                .addQueryParameter("lid", listId.toString())
                .addQueryParameter("act", "move")
                .build()
        http.execute(Request.Builder().url(url).headers().get().build())
    }

    private suspend fun readNotes(id: Long): Notes {
        val body = FormBody.Builder().add("action", "wi_notestagsfic").add("strSID", id.toString()).build()
        val response = http.execute(Request.Builder().url("$baseUrl/wp-admin/admin-ajax.php").headers().post(body).build())
        val cleaned = response.body.trim().replace(Regex("}\\s*0+$"), "}")
        val root =
            try {
                json.parseToJsonElement(cleaned) as? JsonObject
                    ?: throw NovelTrackerFailure.InvalidResponse("NovelUpdates notes did not return an object")
            } catch (error: NovelTrackerFailure) {
                throw error
            } catch (error: Exception) {
                throw NovelTrackerFailure.InvalidResponse("NovelUpdates notes returned invalid JSON", error)
            }
        val notes = root["notes"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val tags = root["tags"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val progress = PROGRESS_PATTERN.find(notes)?.groupValues?.get(1)?.toFloatOrNull()?.coerceAtLeast(0f)
        return Notes(notes, tags, progress)
    }

    private suspend fun writeProgress(id: Long, progress: Float) {
        val existing = readNotes(id)
        val normalized = progress.coerceAtLeast(0f).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
        val marker = "total chapters read: $normalized"
        val updatedNotes =
            if (PROGRESS_PATTERN.containsMatchIn(existing.notes)) {
                existing.notes.replace(PROGRESS_PATTERN, marker)
            } else {
                listOf(existing.notes, marker).filter(String::isNotBlank).joinToString("<br/>")
            }
        val body =
            FormBody.Builder()
                .add("action", "wi_rlnotes")
                .add("strSID", id.toString())
                .add("strNotes", updatedNotes)
                .add("strTags", existing.tags)
                .build()
        http.execute(Request.Builder().url("$baseUrl/wp-admin/admin-ajax.php").headers().post(body).build())
    }

    private fun Request.Builder.headers(secret: String = sessionCookie()): Request.Builder = headers(authHeaders(secret))

    private fun authHeaders(secret: String): Headers {
        val cookie = requireSafeCredential(secret, "NovelUpdates session cookies")
        if (!cookie.contains('=')) throw NovelTrackerFailure.InvalidCredentials("NovelUpdates requires the Cookie header from an authenticated session")
        return Headers.Builder()
            .add("Cookie", cookie)
            .add("User-Agent", USER_AGENT)
            .add("Referer", "$baseUrl/")
            .add("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            .build()
    }

    private fun canonicalTrackingUrl(url: String, id: Long): String =
        "${url.substringBefore('#')}#${NovelUpdatesTrackService.REMOTE_FRAGMENT}=$id"

    private fun resolveUrl(url: String): String = if (url.startsWith("http://") || url.startsWith("https://")) url else "$baseUrl/${url.removePrefix("/")}"

    private data class Notes(val notes: String, val tags: String, val progress: Float?)

    private companion object {
        const val MAX_RESULTS = 100
        const val USER_AGENT = "Hayai/1 Android novel tracker"
        val PROGRESS_PATTERN = Regex("total\\s+chapters\\s+read:\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    }
}
