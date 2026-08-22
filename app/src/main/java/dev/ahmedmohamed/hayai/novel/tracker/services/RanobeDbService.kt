package dev.ahmedmohamed.hayai.novel.tracker.services

import android.content.Context
import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rebuilt from Tsundoku 547ddea3's RanobeDB behavior against J2K's TrackService contract. */
class RanobeDbTrackService(context: Context, id: Int) :
    HayaiNovelTrackService(
        context = context,
        id = id,
        trackerName = R.string.hayai_tracker_ranobe_db,
        trackerColor = Color.rgb(147, 51, 234),
        apiFactory = { client, cookie -> RanobeDbApi(NovelTrackerHttp(client), cookie) },
    ) {
    override fun remoteKey(track: Track): String =
        track.tracking_url.let { url ->
            Regex("/(?:book|series)/(\\d+)(?:[/?#]|$)").find(url)?.groupValues?.get(1)
                ?: track.media_id.takeIf { it > 0 }?.toString()
        }.orEmpty()
}

internal class RanobeDbApi(
    private val http: NovelTrackerHttp,
    private val sessionCookie: () -> String,
    private val baseUrl: String = "https://ranobedb.org",
    private val imagesBaseUrl: String = "https://images.ranobedb.org",
) : NovelTrackerApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun validateSession(username: String, secret: String) {
        val cookie = normalizedCookie(secret)
        if (!cookie.substringAfter("auth_session=", "").substringBefore(';').trim().any()) {
            throw NovelTrackerFailure.InvalidCredentials(NovelTrackerCredentialIssue.RanobeDbAuthCookieRequired)
        }
        val response = http.execute(Request.Builder().url("$baseUrl/api/v0/user/me").get().headers(secret = secret).build())
        parseObject(response.body)
    }

    override suspend fun search(query: String): List<NovelTrackerSearchItem> {
        val url = "$baseUrl/api/v0/books".toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        val response = http.execute(Request.Builder().url(url).get().headers(includeAuthentication = false).build())
        val root = parseObject(response.body)
        val books = root["books"] as? JsonArray ?: root["results"] as? JsonArray ?: JsonArray(emptyList())
        return books.take(MAX_RESULTS).mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.long("id")?.takeIf { it > 0 } ?: return@mapNotNull null
            val title = item.string("title") ?: item.string("romaji") ?: item.string("title_orig") ?: return@mapNotNull null
            val image = item["image"] as? JsonObject
            val imageName = image?.string("filename")
            NovelTrackerSearchItem(
                remoteKey = id.toString(),
                title = title,
                url = "$baseUrl/book/$id",
                coverUrl = imageName?.let { "$imagesBaseUrl/$it" }.orEmpty(),
                summary = item.string("description").orEmpty(),
                publishingStatus = item.string("status").orEmpty(),
            )
        }
    }

    override suspend fun bind(record: NovelTrackerRecord): NovelTrackerPatch {
        submitBook(record, ACTION_ADD)
        return refresh(record)
    }

    override suspend fun update(record: NovelTrackerRecord): NovelTrackerPatch {
        submitBook(record, ACTION_ADD)
        return NovelTrackerPatch()
    }

    override suspend fun refresh(record: NovelTrackerRecord): NovelTrackerPatch {
        val bookId = requirePositiveId(record.remoteKey)
        val response = http.execute(Request.Builder().url("$baseUrl/api/v0/book/$bookId").get().headers().build())
        val item = parseObject(response.body)
        val series = item["series"] as? JsonObject
        val totalBooks = (series?.get("books") as? JsonArray)?.size
        return NovelTrackerPatch(
            title = item.string("title") ?: item.string("romaji"),
            url = "$baseUrl/book/$bookId",
            totalChapters = totalBooks,
        )
    }

    override suspend fun remove(record: NovelTrackerRecord) {
        val bookId = requirePositiveId(record.remoteKey)
        val response = http.execute(Request.Builder().url("$baseUrl/api/v0/book/$bookId").get().headers().build())
        val item = parseObject(response.body)
        val seriesId = (item["series"] as? JsonObject)?.long("id")?.takeIf { it > 0 }
            ?: throw NovelTrackerFailure.InvalidResponse(NovelTrackerResponseIssue.RanobeDbMissingSeriesId)
        val payload = RanobeDbSuperForm.encodeSeries(record.status, record.score, ACTION_DELETE)
        submit("$baseUrl/api/i/user/series/$seriesId", payload)
    }

    private suspend fun submitBook(record: NovelTrackerRecord, action: String) {
        val bookId = requirePositiveId(record.remoteKey)
        val payload =
            RanobeDbSuperForm.encodeBook(
                status = record.status,
                score = record.score,
                started = record.startedAt.toIsoDate(),
                finished = record.finishedAt.toIsoDate(),
                action = action,
            )
        submit("$baseUrl/api/i/user/book/$bookId", payload)
    }

    private suspend fun submit(url: String, payload: String) {
        val body = FormBody.Builder().add("__superform_json", payload).add("__superform_id", SUPERFORM_ID).build()
        val request = Request.Builder().url(url).post(body).headers().build()
        http.execute(request)
    }

    private fun Request.Builder.headers(
        includeAuthentication: Boolean = true,
        secret: String? = null,
    ): Request.Builder =
        header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/")
            .apply { if (includeAuthentication) header("Cookie", normalizedCookie(secret ?: sessionCookie())) }

    private fun normalizedCookie(value: String): String {
        val safe = requireSafeCredential(value, NovelTrackerCredential.RanobeDbSessionCookie)
        return if (safe.contains("auth_session=")) safe else "auth_session=$safe"
    }

    private fun parseObject(body: String): JsonObject =
        try {
            json.parseToJsonElement(body) as? JsonObject
                ?: throw NovelTrackerFailure.InvalidResponse(NovelTrackerResponseIssue.RanobeDbExpectedObject)
        } catch (error: NovelTrackerFailure) {
            throw error
        } catch (_: Exception) {
            throw NovelTrackerFailure.InvalidResponse(NovelTrackerResponseIssue.RanobeDbInvalidJson)
        }

    private fun requirePositiveId(value: String): Long =
        value.toLongOrNull()?.takeIf { it > 0 }
            ?: throw NovelTrackerFailure.InvalidResponse(NovelTrackerResponseIssue.RanobeDbEntryInvalidRemoteId)

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun Long.toIsoDate(): String? =
        takeIf { it > 0 }?.let { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(it)) }

    private companion object {
        const val MAX_RESULTS = 100
        const val ACTION_ADD = "add"
        const val ACTION_DELETE = "delete"
        const val SUPERFORM_ID = "tsundoku"
        const val USER_AGENT = "Hayai/1 Android novel tracker"
    }
}

internal object RanobeDbSuperForm {
    fun encodeBook(
        status: NovelReadingStatus,
        score: Float,
        started: String?,
        finished: String?,
        action: String,
    ): String =
        Json.encodeToString(
            JsonArray.serializer(),
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("labels", 1)
                        put("selectedCustLabels", 5)
                        put("readingStatus", 6)
                        put("score", 7)
                        put("started", 8)
                        put("finished", 9)
                        put("notes", 10)
                        put("type", 11)
                    },
                )
                add(buildJsonArray { add(JsonPrimitive(2)) })
                add(buildJsonObject { put("id", 3); put("label", 4) })
                add(JsonPrimitive(status.labelId()))
                add(JsonPrimitive(status.apiName()))
                add(buildJsonArray {})
                add(JsonPrimitive(status.apiName()))
                add(score.jsonScore())
                add(JsonPrimitive(started.orEmpty()))
                add(JsonPrimitive(finished.orEmpty()))
                add(JsonPrimitive(""))
                add(JsonPrimitive(action))
            },
        )

    fun encodeSeries(status: NovelReadingStatus, score: Float, action: String): String =
        Json.encodeToString(
            JsonArray.serializer(),
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("labels", 1)
                        put("notify_book", 5)
                        put("notify_when_released", 6)
                        put("show_upcoming", 7)
                        put("volumes_read", 8)
                        put("selectedCustLabels", 9)
                        put("langs", 10)
                        put("formats", 11)
                        put("readingStatus", 12)
                        put("score", 13)
                        put("started", 14)
                        put("finished", 15)
                        put("notes", 16)
                        put("type", 17)
                    },
                )
                add(buildJsonArray { add(JsonPrimitive(2)) })
                add(buildJsonObject { put("id", 3); put("label", 4) })
                add(JsonPrimitive(status.labelId()))
                add(JsonPrimitive(status.apiName()))
                add(JsonPrimitive(false))
                add(JsonPrimitive(false))
                add(JsonPrimitive(false))
                add(JsonPrimitive(0))
                repeat(3) { add(buildJsonArray {}) }
                add(JsonPrimitive(status.apiName()))
                add(score.jsonScore())
                add(JsonPrimitive(""))
                add(JsonPrimitive(""))
                add(JsonPrimitive(""))
                add(JsonPrimitive(action))
            },
        )

    private fun NovelReadingStatus.apiName(): String =
        when (this) {
            NovelReadingStatus.Reading -> "Reading"
            NovelReadingStatus.Completed -> "Finished"
            NovelReadingStatus.OnHold -> "Stalled"
            NovelReadingStatus.Dropped -> "Dropped"
            NovelReadingStatus.PlanToRead -> "Plan to read"
            NovelReadingStatus.Other -> "Other"
        }

    private fun NovelReadingStatus.labelId(): Int =
        when (this) {
            NovelReadingStatus.Reading -> 1
            NovelReadingStatus.Completed -> 2
            NovelReadingStatus.PlanToRead -> 3
            NovelReadingStatus.OnHold -> 4
            NovelReadingStatus.Dropped -> 5
            NovelReadingStatus.Other -> 6
        }

    private fun Float.jsonScore(): JsonElement =
        toInt().takeIf { it in 1..10 }?.let(::JsonPrimitive) ?: JsonNull
}
