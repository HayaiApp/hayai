package dev.ahmedmohamed.hayai.adult.eh.update

import android.content.Context
import dev.ahmedmohamed.hayai.adult.eh.persistence.HayaiEhPersistenceStore
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMangaIdentity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.security.MessageDigest

class EhGalleryUpdateStateStore(
    context: Context,
    private val persistence: HayaiEhPersistenceStore,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun state(identity: SourceMangaIdentity): EhGalleryUpdateState {
        val metadata = persistence.metadata(identity) ?: return fallbackState(identity)
        val update = runCatching { JSONObject(metadata.extra).optJSONObject(UPDATE_FIELD) }.getOrNull()
            ?: return EhGalleryUpdateState()
        return EhGalleryUpdateState(
            checkedAt = update.optLong("checkedAt", 0),
            agedAt = update.nullableLong("agedAt"),
            notFoundAt = update.nullableLong("notFoundAt"),
        )
    }

    fun record(identity: SourceMangaIdentity, state: EhGalleryUpdateState) {
        val metadata = persistence.metadata(identity)
        if (metadata == null) {
            preferences.edit().putString(fallbackKey(identity), state.toJson().toString()).apply()
            return
        }
        val root = runCatching { JSONObject(metadata.extra) }.getOrElse { JSONObject() }
        root.put(
            UPDATE_FIELD,
            JSONObject()
                .put("checkedAt", state.checkedAt)
                .put("agedAt", state.agedAt ?: JSONObject.NULL)
                .put("notFoundAt", state.notFoundAt ?: JSONObject.NULL),
        )
        persistence.replaceMetadata(metadata.copy(extra = root.toString()))
        preferences.edit().remove(fallbackKey(identity)).apply()
    }

    fun policy(): EhGalleryUpdatePolicy = EhGalleryUpdatePolicy(
        intervalHours = preferences.getInt("intervalHours", 24),
        wifiOnly = preferences.getBoolean("wifiOnly", false),
        requiresCharging = preferences.getBoolean("requiresCharging", false),
    )

    fun setPolicy(policy: EhGalleryUpdatePolicy) {
        preferences.edit()
            .putInt("intervalHours", policy.intervalHours)
            .putBoolean("wifiOnly", policy.wifiOnly)
            .putBoolean("requiresCharging", policy.requiresCharging)
            .apply()
    }

    fun stats(): EhGalleryUpdaterStats? = preferences.getString("stats", null)?.let(EhGalleryUpdaterStatsCodec::decode)

    fun recordStats(stats: EhGalleryUpdaterStats) {
        preferences.edit().putString("stats", EhGalleryUpdaterStatsCodec.encode(stats)).apply()
    }

    private fun JSONObject.nullableLong(name: String): Long? = if (isNull(name) || !has(name)) null else getLong(name)

    private fun fallbackState(identity: SourceMangaIdentity): EhGalleryUpdateState {
        val value = preferences.getString(fallbackKey(identity), null) ?: return EhGalleryUpdateState()
        return runCatching {
            val json = JSONObject(value)
            EhGalleryUpdateState(json.optLong("checkedAt", 0), json.nullableLong("agedAt"), json.nullableLong("notFoundAt"))
        }.getOrDefault(EhGalleryUpdateState())
    }

    private fun EhGalleryUpdateState.toJson(): JSONObject = JSONObject()
        .put("checkedAt", checkedAt)
        .put("agedAt", agedAt ?: JSONObject.NULL)
        .put("notFoundAt", notFoundAt ?: JSONObject.NULL)

    private fun fallbackKey(identity: SourceMangaIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("${identity.sourceId}\u0000${identity.mangaUrl}".toByteArray())
        return "fallback." + bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFERENCES = "hayai.eh.gallery_updater"
        const val UPDATE_FIELD = "hayaiUpdater"
    }
}

object EhGalleryUpdaterStatsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(value: EhGalleryUpdaterStats): String = json.encodeToString(value)

    fun decode(value: String): EhGalleryUpdaterStats = json.decodeFromString(value)
}
