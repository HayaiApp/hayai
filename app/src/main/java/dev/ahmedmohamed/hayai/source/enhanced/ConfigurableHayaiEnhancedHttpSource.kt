package dev.ahmedmohamed.hayai.source.enhanced

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource

class ConfigurableHayaiEnhancedHttpSource(
    originalSource: HttpSource,
    definition: EnhancedSourceDefinition,
) : HayaiEnhancedHttpSource(originalSource, definition), ConfigurableSource {
    private val configurable = requireNotNull(originalSource as? ConfigurableSource)

    override fun getSourcePreferences() = configurable.getSourcePreferences()

    override fun setupPreferenceScreen(screen: PreferenceScreen) = configurable.setupPreferenceScreen(screen)
}
