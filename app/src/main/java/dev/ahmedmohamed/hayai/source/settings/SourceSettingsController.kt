package dev.ahmedmohamed.hayai.source.settings

import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import dev.ahmedmohamed.hayai.source.presentation.SourcePresentation
import eu.kanade.tachiyomi.data.preference.SharedPreferencesDataStore
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.setting.SettingsController
import timber.log.Timber
import uy.kohesive.injekt.injectLazy

class SourceSettingsController() : SettingsController() {
    private val sourceManager by injectLazy<SourceManager>()

    constructor(sourceId: Long) : this() {
        args.putLong(SOURCE_ID_KEY, sourceId)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen): PreferenceScreen = screen.apply {
        val source = sourceManager.get(args.getLong(SOURCE_ID_KEY))
        title = source?.name ?: "Source settings"
        val configurable = source as? ConfigurableSource
        if (configurable == null) {
            addUnavailablePreference("This source does not expose settings.")
            return@apply
        }

        preferenceManager.preferenceDataStore = SharedPreferencesDataStore(configurable.getSourcePreferences())
        runCatching { configurable.setupPreferenceScreen(this) }
            .onFailure { error ->
                Timber.e(error, "Unable to create settings for source %d", configurable.id)
                removeAll()
                addUnavailablePreference("The source settings could not be loaded. Reinstall or update the source and try again.")
            }

        if (preferenceCount == 0) {
            addUnavailablePreference(
                SourcePresentation.badgeText(source)?.let { "$it source has no configurable options." }
                    ?: "This source has no configurable options.",
            )
        }
    }

    private fun PreferenceScreen.addUnavailablePreference(message: String) {
        addPreference(
            Preference(context).apply {
                title = "No settings available"
                summary = message
                isSelectable = false
            },
        )
    }

    companion object {
        private const val SOURCE_ID_KEY = "hayai_source_id"
    }
}
