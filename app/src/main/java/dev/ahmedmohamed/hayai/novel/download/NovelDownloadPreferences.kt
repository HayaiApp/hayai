package dev.ahmedmohamed.hayai.novel.download

import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferenceStore

class NovelDownloadPreferences(
    private val store: PreferenceStore,
) {
    val globalDelayMillis: Preference<Int> =
        store.getInt(KEY_GLOBAL_DELAY_MILLIS, DEFAULT_DELAY_MILLIS)

    fun sourceDelayOverrideMillis(sourceId: Long): Preference<Int> =
        store.getInt(sourceDelayKey(sourceId), INHERIT_GLOBAL_DELAY)

    fun delayMillisFor(sourceId: Long): Long =
        NovelDownloadDelayPolicy.resolve(
            globalDelayMillis = globalDelayMillis.get(),
            sourceDelayOverrideMillis = sourceDelayOverrideMillis(sourceId).get(),
        )

    companion object {
        const val KEY_GLOBAL_DELAY_MILLIS = "hayai_novel_download_delay_ms"
        const val DEFAULT_DELAY_MILLIS = 3_000
        const val INHERIT_GLOBAL_DELAY = -1

        fun sourceDelayKey(sourceId: Long): String =
            "hayai_novel_download_delay_source_$sourceId"
    }
}

internal object NovelDownloadDelayPolicy {
    fun resolve(
        globalDelayMillis: Int,
        sourceDelayOverrideMillis: Int,
    ): Long =
        (
            if (sourceDelayOverrideMillis >= 0) {
                sourceDelayOverrideMillis
            } else {
                globalDelayMillis
            }
        ).coerceAtLeast(0).toLong()
}
