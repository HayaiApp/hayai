package dev.ahmedmohamed.hayai.source.metadata

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.persistence.HayaiEhPersistenceStore
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMangaIdentity
import dev.ahmedmohamed.hayai.adult.eh.settings.EhPreferences
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import dev.ahmedmohamed.hayai.source.enhanced.EnhancedSourceFeature
import dev.ahmedmohamed.hayai.source.enhanced.EnhancedSourceFeatureRegistry
import dev.ahmedmohamed.hayai.source.enhanced.HayaiEnhancedHttpSource
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.SourceManager

interface SourceMetadataProvider {
    fun owns(manga: Manga): Boolean

    suspend fun load(manga: Manga): SourceMetadataDocument
}

class SourceMetadataUnavailableException : IllegalStateException()

class SourceMetadataProviderRegistry(
    private val providers: List<SourceMetadataProvider>,
) : SourceMetadataProvider {
    override fun owns(manga: Manga): Boolean = providers.any { it.owns(manga) }

    override suspend fun load(manga: Manga): SourceMetadataDocument = provider(manga).load(manga)

    private fun provider(manga: Manga): SourceMetadataProvider =
        providers.singleOrNull { it.owns(manga) }
            ?: throw SourceMetadataUnavailableException()
}

class EhSourceMetadataProvider(
    private val sourceManager: SourceManager,
    private val store: HayaiEhPersistenceStore,
    private val preferences: HayaiPreferences,
    private val ehPreferences: EhPreferences,
) : SourceMetadataProvider {
    override fun owns(manga: Manga): Boolean =
        preferences.hentaiFeaturesEnabled.get() &&
            ehPreferences.enhancedView.get() &&
            EhSite.entries.any { it.sourceId == manga.source }

    override suspend fun load(manga: Manga): SourceMetadataDocument {
        val identity = SourceMangaIdentity(manga.source, manga.url)
        var stored = store.metadata(identity)
        if (stored == null) {
            val source = sourceManager.get(manga.source)
                ?: throw SourceMetadataUnavailableException()
            source.getMangaDetails(manga)
            stored = store.metadata(identity)
        }
        return SourceMetadataDocuments.fromEh(stored ?: throw SourceMetadataUnavailableException())
    }
}

class EnhancedSourceMetadataProvider(
    private val sourceManager: SourceManager,
    private val store: HayaiEhPersistenceStore,
) : SourceMetadataProvider {
    override fun owns(manga: Manga): Boolean {
        val source = sourceManager.get(manga.source) as? HayaiEnhancedHttpSource ?: return false
        return EnhancedSourceFeature.CustomDescription in requireNotNull(EnhancedSourceFeatureRegistry.features(source))
    }

    override suspend fun load(manga: Manga): SourceMetadataDocument {
        val identity = SourceMangaIdentity(manga.source, manga.url)
        store.metadata(identity)?.let(SourceMetadataDocuments::fromStoredEnhanced)?.let { return it }
        val source = sourceManager.get(manga.source) as? HayaiEnhancedHttpSource
            ?: throw SourceMetadataUnavailableException()
        val details = source.getEnhancedMetadata(manga)
            ?: throw SourceMetadataUnavailableException()
        val stored = SourceMetadataDocuments.toStoredEnhanced(identity, source.definition.family, details)
        store.replaceMetadata(stored)
        return SourceMetadataDocuments.fromEnhanced(source.definition.family, details)
    }
}
