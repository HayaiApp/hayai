package eu.kanade.tachiyomi.data.track

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import yokai.domain.series.model.SeriesMetadataValue

interface TrackerMetadataProvider {
    fun metadataValues(
        context: Context,
        mangaId: Long,
        track: Track,
        now: Long = System.currentTimeMillis(),
    ): List<SeriesMetadataValue>

    fun metadataValues(
        context: Context,
        mangaId: Long,
        search: TrackSearch,
        now: Long = System.currentTimeMillis(),
    ): List<SeriesMetadataValue>

    suspend fun enrichedMetadataValues(
        context: Context,
        mangaId: Long,
        track: Track,
        now: Long = System.currentTimeMillis(),
    ): List<SeriesMetadataValue> = metadataValues(context, mangaId, track, now)

    suspend fun enrichedMetadataValues(
        context: Context,
        mangaId: Long,
        search: TrackSearch,
        now: Long = System.currentTimeMillis(),
    ): List<SeriesMetadataValue> = metadataValues(context, mangaId, search, now)
}
