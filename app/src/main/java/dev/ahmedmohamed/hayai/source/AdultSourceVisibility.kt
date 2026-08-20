package dev.ahmedmohamed.hayai.source

import eu.kanade.tachiyomi.source.Source

object AdultSourceVisibility {
    fun includes(
        source: Source,
        adultFeaturesEnabled: Boolean,
    ): Boolean =
        adultFeaturesEnabled ||
            SourceCapability.Adult !in SourceCapabilityRegistry.descriptor(source).capabilities
}
