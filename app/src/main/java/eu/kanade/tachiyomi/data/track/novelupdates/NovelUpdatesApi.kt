package eu.kanade.tachiyomi.data.track.novelupdates

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * Network layer for [NovelUpdates]. Cookie-based, scrapes WordPress markup. Each method ports
 * the corresponding Tsundoku counterpart line-by-line, only adapting the logger
 * (`tachiyomi.core.common.util.system.logcat` → `co.touchlab.kermit.Logger`) and using
 * Hayai's `Track.media_id` (numeric) where Tsundoku uses `track.remote_id`.
 */
class NovelUpdatesApi(
    private val tracker: NovelUpdates,
    private val client: OkHttpClient,
) {

    private val baseUrl = "https://www.novelupdates.com"

    private fun authHeaders(): Headers {
        val cookies = tracker.run { trackPreferences.trackPassword(this).get() }
        return Headers.Builder()
            .add("Cookie", cookies)
            .add(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:141.0) Gecko/20100101 Firefox/141.0",
            )
            .add("Referer", "$baseUrl/")
            .build()
    }

    /** Numeric novel ID from a series page (`/series/<slug>/`). */
    suspend fun getNovelId(seriesUrl: String): String? {
        return try {
            val response = client.newCall(GET(seriesUrl, authHeaders())).awaitSuccess()
            val document = response.asJsoup()

            val shortlink = document.select("link[rel=shortlink]").attr("href")
            Regex("p=(\\d+)").find(shortlink)?.let { return it.groupValues[1] }

            val activityLink = document.select("a[href*=activity-stats]").attr("href")
            Regex("seriesid=(\\d+)").find(activityLink)?.let { return it.groupValues[1] }

            document.select("input#mypostid").attr("value").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Logger.e(e) { "NovelUpdates: getNovelId failed for $seriesUrl" }
            null
        }
    }

    /** Maps NovelUpdates list IDs to our internal status; null if not on any list. */
    suspend fun getReadingListStatus(novelId: String): Int? {
        return try {
            val response = client.newCall(
                GET("$baseUrl/series/?p=$novelId", authHeaders()),
            ).awaitSuccess()
            val document = response.asJsoup()
            val sticon = document.select("div.sticon")
            if (sticon.select("img[src*=addme.png]").isNotEmpty()) return null
            val listLink = sticon.select("span.sttitle a").attr("href")
            val listId = Regex("list=(\\d+)").find(listLink)?.groupValues?.get(1)?.toLongOrNull()
                ?: return null
            when (listId) {
                0L -> NovelUpdates.READING
                1L -> NovelUpdates.COMPLETED
                2L -> NovelUpdates.PLAN_TO_READ
                3L -> NovelUpdates.ON_HOLD
                4L, 5L -> NovelUpdates.DROPPED
                else -> NovelUpdates.READING
            }
        } catch (e: Exception) {
            Logger.e(e) { "NovelUpdates: getReadingListStatus failed" }
            null
        }
    }

    /** Chapter count parsed out of the user's notes for a series. */
    suspend fun getNotesProgress(novelId: String): Int {
        return try {
            val body = FormBody.Builder()
                .add("action", "wi_notestagsfic")
                .add("strSID", novelId)
                .build()
            val request = POST("$baseUrl/wp-admin/admin-ajax.php", authHeaders(), body)
            val response = client.newCall(request).awaitSuccess()
            val text = response.body.string().trim().replace(Regex("}\\s*0+$"), "}")
            val notes = Regex("\"notes\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return 0
            Regex("total\\s+chapters\\s+read:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(notes)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Logger.e(e) { "NovelUpdates: getNotesProgress failed" }
            0
        }
    }

    /** Update the user's notes to reflect the latest chapter read. */
    suspend fun updateNotesProgress(novelId: String, chapters: Int) {
        try {
            val getBody = FormBody.Builder()
                .add("action", "wi_notestagsfic")
                .add("strSID", novelId)
                .build()
            val getResponse = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", authHeaders(), getBody)).awaitSuccess()
            val text = getResponse.body.string().trim().replace(Regex("}\\s*0+$"), "}")
            val existingNotes = Regex("\"notes\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
            val existingTags = Regex("\"tags\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
            val pattern = Regex("total\\s+chapters\\s+read:\\s*\\d+", RegexOption.IGNORE_CASE)
            val replacement = "total chapters read: $chapters"
            val updatedNotes = if (pattern.containsMatchIn(existingNotes)) {
                existingNotes.replace(pattern, replacement)
            } else if (existingNotes.isEmpty()) {
                replacement
            } else {
                "$existingNotes<br/>$replacement"
            }
            val updateBody = FormBody.Builder()
                .add("action", "wi_rlnotes")
                .add("strSID", novelId)
                .add("strNotes", updatedNotes)
                .add("strTags", existingTags)
                .build()
            client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", authHeaders(), updateBody)).awaitSuccess()
        } catch (e: Exception) {
            Logger.e(e) { "NovelUpdates: updateNotesProgress failed" }
        }
    }

    /** Move a series between reading lists. */
    suspend fun moveToList(novelId: String, listId: Long) {
        try {
            client.newCall(
                GET("$baseUrl/updatelist.php?sid=$novelId&lid=$listId&act=move", authHeaders()),
            ).awaitSuccess()
        } catch (e: Exception) {
            Logger.e(e) { "NovelUpdates: moveToList failed" }
        }
    }

    /** Series-finder search; ports Tsundoku's parser line-by-line. */
    suspend fun search(query: String): List<TrackSearch> {
        val encoded = query.replace(" ", "+")
        val url = "$baseUrl/series-finder/?sf=1&sh=$encoded&sort=sdate&order=desc"
        return try {
            val response = client.newCall(GET(url, authHeaders())).awaitSuccess()
            val document = response.asJsoup()
            document.select("div.search_main_box_nu").map { element ->
                val track = TrackSearch.create(tracker.id)
                val titleElement = element.select("div.search_title a").first()
                    ?: element.select(".search_title a").first()
                track.title = titleElement?.text()?.trim() ?: ""
                track.tracking_url = titleElement?.attr("href") ?: ""
                val sidId = element.select("span[id^=sid]").first()?.attr("id")
                val numericId = sidId?.let { Regex("sid(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                val slug = Regex("series/([^/]+)/?").find(track.tracking_url)?.groupValues?.get(1) ?: ""
                track.media_id = numericId ?: slug.hashCode().toLong().let { if (it < 0) -it else it }
                val coverImg = element.select("div.search_img_nu img, .search_img_nu img").first()
                track.cover_url = coverImg?.attr("src")?.let {
                    if (it.startsWith("http")) it else "$baseUrl$it"
                } ?: ""
                val descContainer = element.select("div.search_body_nu").first()
                val hidden = descContainer?.select(".testhide")?.text() ?: ""
                val visible = descContainer?.text()?.replace(hidden, "")?.trim() ?: ""
                track.summary = (visible + " " + hidden)
                    .replace("... more>>", "")
                    .replace("<<less", "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                val genreText = element.select("div.search_genre, .search_genre").text()
                track.publishing_status = when {
                    genreText.contains("Completed", ignoreCase = true) -> "Completed"
                    genreText.contains("Ongoing", ignoreCase = true) -> "Ongoing"
                    else -> ""
                }
                track
            }
        } catch (e: Exception) {
            Logger.e(e) { "NovelUpdates: search failed" }
            throw e
        }
    }
}
