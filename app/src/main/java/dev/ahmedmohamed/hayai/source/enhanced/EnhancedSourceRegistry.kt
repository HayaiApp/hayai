package dev.ahmedmohamed.hayai.source.enhanced

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.ConfigurableSource

object EnhancedSourceRegistry {
    fun wrap(source: HttpSource): HttpSource {
        if (source is HayaiEnhancedHttpSource) return source
        val definition = EnhancedSourceDefinitions.all.firstOrNull { it.matches(source) } ?: return source
        return if (source is ConfigurableSource) {
            ConfigurableHayaiEnhancedHttpSource(source, definition)
        } else {
            HayaiEnhancedHttpSource(source, definition)
        }
    }
}
