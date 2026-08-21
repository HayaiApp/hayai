package dev.ahmedmohamed.hayai.novel.tracker.services

import android.content.Context
import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import okio.ByteString.Companion.decodeBase64

/** Rebuilt from Tsundoku 547ddea3's NovelList behavior against J2K's TrackService contract. */
class NovelListTrackService(context: Context, id: Int) :
    HayaiNovelTrackService(
        context = context,
        id = id,
        trackerName = R.string.hayai_tracker_novel_list,
        trackerColor = Color.rgb(77, 91, 216),
        apiFactory = { client, token -> NovelListApi(NovelTrackerHttp(client), token) },
    ) {
    override fun remoteKey(track: Track): String = track.tracking_url.substringAfterLast('#', "").takeIf(::isUuid).orEmpty()
}

internal class NovelListApi(
    private val http: NovelTrackerHttp,
    private val token: () -> String,
    private val apiBaseUrl: String = "https://novellist-be-960019704910.asia-east1.run.app",
    private val websiteBaseUrl: String = "https://www.novellist.co",
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) : NovelTrackerApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    override suspend fun validateSession(username: String, secret: String) {
        val parts = secret.split('.')
        if (parts.size != 3) throw NovelTrackerFailure.InvalidCredentials("NovelList requires the access token from your NovelList session")
        val payload =
            try {
                val decoded = parts[1].decodeBase64()?.utf8() ?: throw NovelTrackerFailure.InvalidCredentials("NovelList access token is malformed")
                json.parseToJsonElement(decoded).asObject("NovelList access token")
            } catch (error: NovelTrackerFailure) {
                throw error
            } catch (error: Exception) {
                throw NovelTrackerFailure.InvalidCredentials("NovelList access token is malformed")
            }
        payload["exp"]?.jsonPrimitive?.longOrNull?.let { expiry ->
            if (expiry <= nowEpochSeconds()) throw NovelTrackerFailure.InvalidCredentials("NovelList access token has expired")
        }
    }

    override suspend fun search(query: String): List<NovelTrackerSearchItem> {
        val body =
            buildJsonObject {
                put("page", 1)
                put("sort_order", "MOST_TRENDING")
                put("title_search_query", query)
                put("language", "UNKNOWN")
                putJsonArray("label_ids") {}
                putJsonArray("excluded_label_ids") {}
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request =
            Request.Builder()
                .url("$apiBaseUrl/api/novels/filter")
                .post(body)
                .publicHeaders()
                .build()
        val root = parse(http.execute(request).body, "NovelList search")
        val entries =
            when (root) {
                is JsonArray -> root
                is JsonObject -> (root["results"] ?: root["novels"] ?: root["data"]) as? JsonArray ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
        return entries.take(MAX_RESULTS).mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val remoteKey = item.string("id")?.takeIf(::isUuid) ?: return@mapNotNull null
            val title = item.string("english_title") ?: item.string("raw_title") ?: item.string("title") ?: return@mapNotNull null
            val slug = item.string("slug") ?: remoteKey
            NovelTrackerSearchItem(
                remoteKey = remoteKey,
                title = title,
                url = "$websiteBaseUrl/novels/$slug#$remoteKey",
                coverUrl = item.string("cover_image_link") ?: item.string("image_url").orEmpty(),
                summary = item.string("description").orEmpty(),
                publishingStatus = item.string("status").orEmpty(),
                totalChapters = item.int("chapter_count")?.takeIf { it >= 0 },
            )
        }
    }

    override suspend fun bind(record: NovelTrackerRecord): NovelTrackerPatch {
        putRecord(record.copy(status = if (record.chapterRead > 0f) NovelReadingStatus.Reading else NovelReadingStatus.PlanToRead))
        return refresh(record)
    }

    override suspend fun update(record: NovelTrackerRecord): NovelTrackerPatch {
        putRecord(record)
        return NovelTrackerPatch()
    }

    override suspend fun refresh(record: NovelTrackerRecord): NovelTrackerPatch {
        val request = Request.Builder().url(recordUrl(record.remoteKey)).get().authenticatedHeaders().build()
        val item = parse(http.execute(request).body, "NovelList reading-list entry").asObject("NovelList reading-list entry")
        return NovelTrackerPatch(
            status = item.string("status")?.let(::statusFromApi),
            chapterRead = item.float("chapter_count")?.coerceAtLeast(0f),
            score = item.float("rating")?.coerceIn(0f, 10f),
            totalChapters = item.int("total_chapters")?.takeIf { it >= 0 },
        )
    }

    override suspend fun remove(record: NovelTrackerRecord) {
        val request = Request.Builder().url(recordUrl(record.remoteKey)).delete().authenticatedHeaders().build()
        http.execute(request)
    }

    private suspend fun putRecord(record: NovelTrackerRecord) {
        val body =
            buildJsonObject {
                put("status", statusToApi(record.status))
                put("chapter_count", record.chapterRead.toInt().coerceAtLeast(0))
                if (record.score > 0f) put("rating", record.score.toInt().coerceIn(1, 10))
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(recordUrl(record.remoteKey)).put(body).authenticatedHeaders().build()
        http.execute(request)
    }

    private fun recordUrl(remoteKey: String): String {
        if (!isUuid(remoteKey)) throw NovelTrackerFailure.InvalidResponse("NovelList entry has an invalid remote identifier")
        return "$apiBaseUrl/api/users/current/reading-list/$remoteKey"
    }

    private fun Request.Builder.publicHeaders(): Request.Builder =
        header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Origin", websiteBaseUrl)
            .header("Referer", "$websiteBaseUrl/")

    private fun Request.Builder.authenticatedHeaders(): Request.Builder =
        publicHeaders().header("Authorization", "Bearer ${requireSafeCredential(token(), "NovelList access token")}")

    private fun parse(body: String, label: String): JsonElement =
        try {
            json.parseToJsonElement(body)
        } catch (error: Exception) {
            throw NovelTrackerFailure.InvalidResponse("$label returned invalid JSON", error)
        }

    private fun JsonElement.asObject(label: String): JsonObject =
        this as? JsonObject ?: throw NovelTrackerFailure.InvalidResponse("$label did not return an object")

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.float(key: String): Float? = this[key]?.jsonPrimitive?.floatOrNull

    private fun statusToApi(status: NovelReadingStatus): String =
        when (status) {
            NovelReadingStatus.Reading -> "IN_PROGRESS"
            NovelReadingStatus.Completed -> "COMPLETED"
            NovelReadingStatus.Dropped -> "DROPPED"
            NovelReadingStatus.OnHold, NovelReadingStatus.PlanToRead, NovelReadingStatus.Other -> "PLANNED"
        }

    private fun statusFromApi(status: String): NovelReadingStatus =
        when (status.uppercase()) {
            "IN_PROGRESS" -> NovelReadingStatus.Reading
            "COMPLETED" -> NovelReadingStatus.Completed
            "DROPPED" -> NovelReadingStatus.Dropped
            "PLANNED" -> NovelReadingStatus.PlanToRead
            else -> throw NovelTrackerFailure.InvalidResponse("NovelList returned an unknown reading status")
        }

    private companion object {
        const val MAX_RESULTS = 100
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value); true }.getOrDefault(false)
