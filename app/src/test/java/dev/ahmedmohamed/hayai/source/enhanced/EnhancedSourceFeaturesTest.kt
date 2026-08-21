package dev.ahmedmohamed.hayai.source.enhanced

import eu.kanade.tachiyomi.source.ConfigurableSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedSourceFeaturesTest {
    @Test
    fun `url import and previews match actual SY source capabilities`() {
        val nhentai = EnhancedSourceRegistry.wrap(EnhancedTestHttpSource(1, "NHentai", "https://nhentai.net")) as HayaiEnhancedHttpSource
        val lanraragi = HayaiEnhancedHttpSource(
            EnhancedTestHttpSource(2, "LANraragi", "https://lanraragi.local"),
            EnhancedSourceDefinitions.all.single { it.family == dev.ahmedmohamed.hayai.source.SourceFamily.Lanraragi },
        )
        val hBrowse = EnhancedSourceRegistry.wrap(EnhancedTestHttpSource(3, "HBrowse", "https://hbrowse.com")) as HayaiEnhancedHttpSource

        assertTrue(EnhancedSourceFeature.BatchAdd in requireNotNull(EnhancedSourceFeatureRegistry.features(nhentai)))
        assertTrue(EnhancedSourceFeature.PagePreview in requireNotNull(EnhancedSourceFeatureRegistry.features(nhentai)))
        assertTrue(EnhancedSourceFeature.PagePreview in requireNotNull(EnhancedSourceFeatureRegistry.features(lanraragi)))
        assertFalse(EnhancedSourceFeature.BatchAdd in requireNotNull(EnhancedSourceFeatureRegistry.features(lanraragi)))
        assertFalse(EnhancedSourceFeature.PagePreview in requireNotNull(EnhancedSourceFeatureRegistry.features(hBrowse)))
    }

    @Test
    fun `installed extension preferences and authentication settings remain authoritative`() {
        val original = EnhancedConfigurableTestSource(4, "LANraragi", "https://lanraragi.local")
        val wrapped = ConfigurableHayaiEnhancedHttpSource(
            original,
            EnhancedSourceDefinitions.all.single { it.family == dev.ahmedmohamed.hayai.source.SourceFamily.Lanraragi },
        )

        assertTrue(wrapped is ConfigurableSource)
        assertSame(original.preferences, (wrapped as ConfigurableSource).getSourcePreferences())
        assertTrue(
            EnhancedSourceFeature.SourceSettings in
                requireNotNull(EnhancedSourceFeatureRegistry.features(wrapped as HayaiEnhancedHttpSource)),
        )
    }
}
