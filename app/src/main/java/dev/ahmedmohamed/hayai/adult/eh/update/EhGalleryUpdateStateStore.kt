package dev.ahmedmohamed.hayai.adult.eh.update

import android.content.Context
import dev.ahmedmohamed.hayai.adult.eh.persistence.HayaiEhPersistenceStore
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMangaIdentity
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
    fun encode(value: EhGalleryUpdaterStats): String = JSONObject()
        .put("startedAt", value.startedAt)
        .put("finishedAt", value.finishedAt)
        .put("eligible", value.eligible)
        .put("attempted", value.attempted)
        .put("updated", value.updated)
        .put("newRevisions", value.newRevisions)
        .put("aged", value.aged)
        .put("notFound", value.notFound)
        .put("authenticationSkipped", value.authenticationSkipped)
        .put("transientFailures", value.transientFailures)
        .put("permanentFailures", value.permanentFailures)
        .put("stoppedAtFailureCutoff", value.stoppedAtFailureCutoff)
        .toString()

    fun decode(value: String): EhGalleryUpdaterStats {
        val json = JSONObject(value)
        return EhGalleryUpdaterStats(
            startedAt = json.getLong("startedAt"),
            finishedAt = json.getLong("finishedAt"),
            eligible = json.getInt("eligible"),
            attempted = json.getInt("attempted"),
            updated = json.getInt("updated"),
            newRevisions = json.getInt("newRevisions"),
            aged = json.getInt("aged"),
            notFound = json.getInt("notFound"),
            authenticationSkipped = json.getInt("authenticationSkipped"),
            transientFailures = json.getInt("transientFailures"),
            permanentFailures = json.getInt("permanentFailures"),
            stoppedAtFailureCutoff = json.getBoolean("stoppedAtFailureCutoff"),
        )
    }
}
