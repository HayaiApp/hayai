package dev.ahmedmohamed.hayai.source.enhanced

import dev.ahmedmohamed.hayai.source.SourceFamily
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource

enum class EnhancedSourceFeature {
    DirectUrlImport,
    BatchAdd,
    CustomDescription,
    OpenInApp,
    PagePreview,
    SourceSettings,
}

data class EnhancedSourceFeatures(
    val family: SourceFamily,
    val features: Set<EnhancedSourceFeature>,
) {
    operator fun contains(feature: EnhancedSourceFeature): Boolean = feature in features
}

object EnhancedSourceFeatureRegistry {
    fun features(source: HttpSource): EnhancedSourceFeatures? {
        val enhanced = source as? HayaiEnhancedHttpSource ?: return null
        val family = enhanced.definition.family
        val features = buildSet {
            add(EnhancedSourceFeature.CustomDescription)
            add(EnhancedSourceFeature.OpenInApp)
            if (family in URL_IMPORT_FAMILIES) {
                add(EnhancedSourceFeature.DirectUrlImport)
                add(EnhancedSourceFeature.BatchAdd)
            }
            if (family in PAGE_PREVIEW_FAMILIES) add(EnhancedSourceFeature.PagePreview)
            if (enhanced.originalSource is ConfigurableSource) add(EnhancedSourceFeature.SourceSettings)
        }
        return EnhancedSourceFeatures(family, features)
    }

    private val URL_IMPORT_FAMILIES = setOf(
        SourceFamily.EightMuses,
        SourceFamily.HBrowse,
        SourceFamily.NHentai,
        SourceFamily.Pururin,
    )
    private val PAGE_PREVIEW_FAMILIES = setOf(SourceFamily.NHentai, SourceFamily.Lanraragi)
}
