package dev.ahmedmohamed.hayai.novel.download

import android.content.Context
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.ui.setting.SettingsController
import eu.kanade.tachiyomi.ui.setting.defaultValue
import eu.kanade.tachiyomi.ui.setting.infoPreference
import eu.kanade.tachiyomi.ui.setting.intListPreference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.sliderPreference
import eu.kanade.tachiyomi.ui.setting.summaryRes
import eu.kanade.tachiyomi.ui.setting.titleRes
import uy.kohesive.injekt.injectLazy

class NovelDownloadSettingsController : SettingsController() {
    private val sourceManager: SourceManager by injectLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) =
        screen.apply {
            titleRes = R.string.hayai_novel_download_pacing

            preferenceCategory {
                titleRes = R.string.hayai_novel_download_pacing_global

                sliderPreference {
                    key = NovelDownloadPreferences.KEY_GLOBAL_DELAY_MILLIS
                    defaultValue = NovelDownloadPreferences.DEFAULT_DELAY_MILLIS
                    titleRes = R.string.hayai_novel_download_delay
                    summaryRes = R.string.hayai_novel_download_delay_summary
                    entryValues = GLOBAL_DELAY_VALUES
                    valueFormatter = { formatDelay(context, it) }
                }

                infoPreference(R.string.hayai_novel_download_pacing_scope)
            }

            preferenceCategory {
                titleRes = R.string.hayai_novel_download_per_plugin

                val meteredNovelSources =
                    sourceManager
                        .getCatalogueSources()
                        .filter { it.isNovelSource() && it !is UnmeteredSource }
                        .sortedBy { it.name.lowercase() }

                if (meteredNovelSources.isEmpty()) {
                    infoPreference(R.string.hayai_novel_download_no_metered_plugins)
                } else {
                    meteredNovelSources.forEach { source ->
                        intListPreference(activity) {
                            key = NovelDownloadPreferences.sourceDelayKey(source.id)
                            defaultValue = NovelDownloadPreferences.INHERIT_GLOBAL_DELAY
                            title = source.name
                            entries =
                                listOf(context.getString(R.string.hayai_novel_download_use_global)) +
                                SOURCE_OVERRIDE_VALUES.drop(1).map { formatDelay(context, it) }
                            entryValues = SOURCE_OVERRIDE_VALUES
                        }
                    }
                }

                infoPreference(R.string.hayai_novel_download_unmetered_bypass)
            }
        }

    private fun formatDelay(
        context: Context,
        delayMillis: Int,
    ): String =
        if (delayMillis == 0) {
            context.getString(R.string.hayai_novel_download_no_delay)
        } else {
            val seconds = delayMillis / 1_000
            context.resources.getQuantityString(R.plurals.hayai_novel_download_seconds, seconds, seconds)
        }

    private companion object {
        val GLOBAL_DELAY_VALUES = (0..120).map { it * 1_000 }
        val SOURCE_OVERRIDE_VALUES = listOf(-1, 0, 1_000, 2_000, 3_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000)
    }
}
