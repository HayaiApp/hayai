package eu.kanade.tachiyomi.data.track.anilist

import android.content.Context
import android.graphics.Color
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.anilist.dto.ALCharacterMetadata
import eu.kanade.tachiyomi.data.track.anilist.dto.ALManga
import eu.kanade.tachiyomi.data.track.anilist.dto.ALOAuth
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.updateNewTrackInfo
import eu.kanade.tachiyomi.util.lang.htmlDecode
import eu.kanade.tachiyomi.util.system.e
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import yokai.domain.series.model.MetadataProviderType
import yokai.domain.series.model.SeriesMetadataField
import yokai.domain.series.model.SeriesMetadataValue
import yokai.util.koin.injectLazy
import yokai.i18n.MR
import yokai.util.lang.getString

class Anilist(private val context: Context, id: Long) : TrackService(id) {

    companion object {
        const val READING = 1
        const val COMPLETED = 2
        const val ON_HOLD = 3
        const val DROPPED = 4
        const val PLAN_TO_READ = 5
        const val REREADING = 6

        const val DEFAULT_STATUS = READING
        const val DEFAULT_SCORE = 0

        const val POINT_100 = "POINT_100"
        const val POINT_10 = "POINT_10"
        const val POINT_10_DECIMAL = "POINT_10_DECIMAL"
        const val POINT_5 = "POINT_5"
        const val POINT_3 = "POINT_3"

        private const val MAX_CHARACTER_METADATA = 25
    }

    private val json: Json by injectLazy()

    private val interceptor by lazy { AnilistInterceptor(this, getPassword()) }

    private val api by lazy { AnilistApi(client, interceptor) }

    override val supportsReadingDates: Boolean = true

    private val scorePreference = trackPreferences.anilistScoreType()

    init {
        // If the preference is an int from APIv1, logout user to force using APIv2
        try {
            scorePreference.get()
        } catch (e: ClassCastException) {
            logout()
            scorePreference.delete()
        }
    }

    override fun nameRes() = MR.strings.anilist

    override fun getLogo() = R.drawable.ic_tracker_anilist

    override fun getTrackerColor() = Color.rgb(2, 169, 255)

    override fun getLogoColor() = Color.rgb(18, 25, 35)

    override fun getStatusList() = listOf(READING, PLAN_TO_READ, COMPLETED, REREADING, ON_HOLD, DROPPED)

    override fun isCompletedStatus(index: Int) = getStatusList()[index] == COMPLETED

    override fun completedStatus() = COMPLETED
    override fun readingStatus() = READING
    override fun planningStatus() = PLAN_TO_READ

    override fun getStatus(status: Int): String = with(context) {
        when (status) {
            READING -> getString(MR.strings.reading)
            PLAN_TO_READ -> getString(MR.strings.plan_to_read)
            COMPLETED -> getString(MR.strings.completed)
            ON_HOLD -> getString(MR.strings.paused)
            DROPPED -> getString(MR.strings.dropped)
            REREADING -> getString(MR.strings.rereading)
            else -> ""
        }
    }

    override fun getGlobalStatus(status: Int): String = with(context) {
        when (status) {
            READING -> getString(MR.strings.reading)
            PLAN_TO_READ -> getString(MR.strings.plan_to_read)
            COMPLETED -> getString(MR.strings.completed)
            ON_HOLD -> getString(MR.strings.on_hold)
            DROPPED -> getString(MR.strings.dropped)
            REREADING -> getString(MR.strings.rereading)
            else -> ""
        }
    }

    override fun getScoreList(): ImmutableList<String> {
        return when (scorePreference.get()) {
            // 10 point
            POINT_10 -> IntRange(0, 10).map(Int::toString).toImmutableList()
            // 100 point
            POINT_100 -> IntRange(0, 100).map(Int::toString).toImmutableList()
            // 5 stars
            POINT_5 -> IntRange(0, 5).map { "$it ★" }.toImmutableList()
            // Smiley
            POINT_3 -> listOf("-", "😦", "😐", "😊").toImmutableList()
            // 10 point decimal
            POINT_10_DECIMAL -> IntRange(0, 100).map { (it / 10f).toString() }.toImmutableList()
            else -> throw Exception("Unknown score type")
        }
    }

    override fun indexToScore(index: Int): Float {
        return when (scorePreference.get()) {
            // 10 point
            POINT_10 -> index * 10f
            // 100 point
            POINT_100 -> index.toFloat()
            // 5 stars
            POINT_5 -> when (index) {
                0 -> 0f
                else -> index * 20f - 10f
            }
            // Smiley
            POINT_3 -> when (index) {
                0 -> 0f
                else -> index * 25f + 10f
            }
            // 10 point decimal
            POINT_10_DECIMAL -> index.toFloat()
            else -> throw Exception("Unknown score type")
        }
    }

    override fun get10PointScore(score: Float) = score / 10

    override fun displayScore(track: Track): String {
        val score = track.score

        return when (scorePreference.get()) {
            POINT_5 -> when (score) {
                0f -> "0 ★"
                else -> "${((score + 10) / 20).toInt()} ★"
            }

            POINT_3 -> when {
                score == 0f -> "0"
                score <= 35 -> "😦"
                score <= 60 -> "😐"
                else -> "😊"
            }

            else -> track.toApiScore()
        }
    }

    override suspend fun add(track: Track): Track {
        track.score = DEFAULT_SCORE.toFloat()
        track.status = DEFAULT_STATUS
        updateNewTrackInfo(track)
        return api.addLibManga(track)
    }

    override suspend fun update(track: Track, setToRead: Boolean): Track {
        updateTrackStatus(track, setToRead, setToComplete = true, mustReadToComplete = true)
        // If user was using API v1 fetch library_id
        if (track.library_id == null || track.library_id!! == 0L) {
            val libManga = api.findLibManga(track, getUsername().toInt())
                ?: throw Exception("$track not found on user library")
            track.library_id = libManga.library_id
        }

        return api.updateLibraryManga(track)
    }

    override suspend fun bind(track: Track): Track {
        val remoteTrack = api.findLibManga(track, getUsername().toInt())

        return if (remoteTrack != null) {
            track.copyPersonalFrom(remoteTrack)
            track.library_id = remoteTrack.library_id
            update(track)
        } else {
            add(track)
        }
    }

    override fun canRemoveFromService(): Boolean = true

    override suspend fun removeFromService(track: Track): Boolean {
        return api.remove(track)
    }

    override suspend fun search(query: String) = api.search(query)

    override suspend fun refresh(track: Track): Track {
        val remoteTrack = api.getLibManga(track, getUsername().toInt())
        track.copyPersonalFrom(remoteTrack)
        track.title = remoteTrack.title
        track.total_chapters = remoteTrack.total_chapters
        return track
    }

    override suspend fun enrichedMetadataValues(
        context: Context,
        mangaId: Long,
        track: Track,
        now: Long,
    ): List<SeriesMetadataValue> =
        super.enrichedMetadataValues(context, mangaId, track, now) +
            detailMetadataValues(context, mangaId, track.media_id, now) +
            characterMetadataValues(context, mangaId, track.media_id, now)

    override suspend fun enrichedMetadataValues(
        context: Context,
        mangaId: Long,
        search: TrackSearch,
        now: Long,
    ): List<SeriesMetadataValue> =
        super.enrichedMetadataValues(context, mangaId, search, now) +
            detailMetadataValues(context, mangaId, search.media_id, now) +
            characterMetadataValues(context, mangaId, search.media_id, now)

    private suspend fun detailMetadataValues(
        context: Context,
        mangaId: Long,
        mediaId: Long,
        now: Long,
    ): List<SeriesMetadataValue> {
        if (mediaId <= 0L) return emptyList()
        val manga = runCatching { api.getMangaDetails(mediaId) }
            .onFailure { Logger.e(it) { "Failed to fetch AniList metadata for $mediaId" } }
            .getOrNull() ?: return emptyList()

        return manga.toMetadataValues(context, mangaId, now)
    }

    private fun ALManga.toMetadataValues(
        context: Context,
        mangaId: Long,
        now: Long,
    ): List<SeriesMetadataValue> {
        val providerId = this@Anilist.id.toString()
        val providerName = context.getString(nameRes())
        fun value(field: SeriesMetadataField, text: String?, confidence: Double = 0.85): SeriesMetadataValue? =
            text?.trim()?.takeIf { it.isNotBlank() }?.let {
                SeriesMetadataValue(
                    mangaId = mangaId,
                    field = field.key,
                    providerType = MetadataProviderType.TRACKER,
                    providerId = providerId,
                    providerName = providerName,
                    value = it,
                    extraJson = null,
                    confidence = confidence,
                    userLocked = false,
                    updatedAt = now,
                )
            }

        val tagsAndGenres = (genres + tags)
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinctBy { it.lowercase() }
            .joinToString(", ")
        val links = (listOfNotNull(siteUrl, AnilistApi.mangaUrl(remoteId)) + externalLinks)
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("\n")

        return listOfNotNull(
            value(SeriesMetadataField.TITLE, title),
            value(SeriesMetadataField.COVER, imageUrl),
            value(SeriesMetadataField.BANNER, bannerImage),
            value(SeriesMetadataField.DESCRIPTION, description?.htmlDecode()),
            value(SeriesMetadataField.STATUS, publishingStatus),
            value(SeriesMetadataField.GENRES, tagsAndGenres, confidence = 0.8),
            value(SeriesMetadataField.EXTERNAL_LINKS, links, confidence = 0.85),
        )
    }

    private suspend fun characterMetadataValues(
        context: Context,
        mangaId: Long,
        mediaId: Long,
        now: Long,
    ): List<SeriesMetadataValue> {
        if (mediaId <= 0L) return emptyList()
        val characters = runCatching { api.getCharacters(mediaId) }
            .onFailure { Logger.e(it) { "Failed to fetch AniList characters for $mediaId" } }
            .getOrDefault(emptyList())
            .take(MAX_CHARACTER_METADATA)
        if (characters.isEmpty()) return emptyList()

        return listOf(
            SeriesMetadataValue(
                mangaId = mangaId,
                field = SeriesMetadataField.CHARACTERS.key,
                providerType = MetadataProviderType.TRACKER,
                providerId = id.toString(),
                providerName = context.getString(nameRes()),
                value = characters.joinToString(", ") { it.name },
                extraJson = characters.toExtraJson(),
                confidence = 0.8,
                userLocked = false,
                updatedAt = now,
            ),
        )
    }

    private fun List<ALCharacterMetadata>.toExtraJson(): String =
        buildJsonArray {
            forEach { character ->
                add(
                    buildJsonObject {
                        character.id?.let { put("id", it) }
                        put("name", character.name)
                        character.role?.trim()?.takeIf { it.isNotBlank() }?.let { put("role", it) }
                        character.imageUrl?.trim()?.takeIf { it.isNotBlank() }?.let { put("imageUrl", it) }
                        character.gender?.trim()?.takeIf { it.isNotBlank() }?.let { put("gender", it) }
                        character.description?.trim()?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                        character.siteUrl?.trim()?.takeIf { it.isNotBlank() }?.let {
                            put("siteUrl", it)
                            put("url", it)
                        }
                    },
                )
            }
        }.toString()

    override suspend fun login(username: String, password: String) = login(password)

    suspend fun login(token: String): Boolean {
        return try {
            val oauth = api.createOAuth(token)
            interceptor.setAuth(oauth)
            val (username, scoreType) = api.getCurrentUser()
            scorePreference.set(scoreType)
            saveCredentials(username.toString(), oauth.accessToken)
            true
        } catch (e: Exception) {
            Logger.e(e)
            logout()
            false
        }
    }

    suspend fun updatingScoring(): Pair<Boolean, Exception?> {
        return try {
            val (_, scoreType) = api.getCurrentUser()
            scorePreference.set(scoreType)
            true to null
        } catch (e: Exception) {
            Logger.e(e) { "Failed to update scoring" }
            false to e
        }
    }

    override fun logout() {
        super.logout()
        trackPreferences.trackToken(this).delete()
        interceptor.setAuth(null)
    }

    fun saveOAuth(alOAuth: ALOAuth?) {
        trackPreferences.trackToken(this).set(json.encodeToString(alOAuth))
    }

    fun loadOAuth(): ALOAuth? {
        return try {
            json.decodeFromString<ALOAuth>(trackPreferences.trackToken(this).get())
        } catch (e: Exception) {
            Logger.e(e) { "Unable to load token" }
            null
        }
    }
}
