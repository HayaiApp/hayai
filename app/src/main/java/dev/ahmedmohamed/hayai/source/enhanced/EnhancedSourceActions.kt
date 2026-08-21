package dev.ahmedmohamed.hayai.source.enhanced

import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedBatchFailure
import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedBatchEntry
import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedImportTarget
import dev.ahmedmohamed.hayai.source.enhanced.batch.EnhancedResolveResult
import eu.kanade.tachiyomi.source.model.SManga

sealed interface EnhancedSourceAction {
    data class OpenInApp(val target: EnhancedImportTarget) : EnhancedSourceAction
    data class OpenPagePreviews(val sourceId: Long, val mangaUrl: String) : EnhancedSourceAction
    data class OpenSourceSettings(val sourceId: Long) : EnhancedSourceAction
    data object OpenBatchAdd : EnhancedSourceAction
}

object EnhancedSourceActionCatalog {
    fun forManga(
        source: HayaiEnhancedHttpSource,
        manga: SManga,
    ): List<EnhancedSourceAction> {
        val features = EnhancedSourceFeatureRegistry.features(source) ?: return emptyList()
        return buildList {
            if (EnhancedSourceFeature.OpenInApp in features) {
                val external = runCatching { source.getMangaUrl(manga) }.getOrNull()
                val mapped = external?.let { EnhancedSourceUrlMapper.map(source.definition, source.baseUrl, it) }
                if (mapped != null) {
                    add(
                        EnhancedSourceAction.OpenInApp(
                            EnhancedImportTarget(source.id, source.name, source.definition.family, mapped, external),
                        ),
                    )
                }
            }
            if (EnhancedSourceFeature.PagePreview in features) {
                add(EnhancedSourceAction.OpenPagePreviews(source.id, manga.url))
            }
            if (EnhancedSourceFeature.SourceSettings in features) {
                add(EnhancedSourceAction.OpenSourceSettings(source.id))
            }
            if (EnhancedSourceFeature.BatchAdd in features) add(EnhancedSourceAction.OpenBatchAdd)
        }
    }
}

class EnhancedSourceLinkRouter(
    private val sources: () -> Iterable<HayaiEnhancedHttpSource>,
) {
    fun resolve(entry: EnhancedBatchEntry): EnhancedResolveResult {
        val matches = sources().mapNotNull { source ->
            val features = EnhancedSourceFeatureRegistry.features(source) ?: return@mapNotNull null
            if (EnhancedSourceFeature.DirectUrlImport !in features) return@mapNotNull null
            val mapped = EnhancedSourceUrlMapper.map(source.definition, source.baseUrl, entry.url) ?: return@mapNotNull null
            EnhancedImportTarget(source.id, source.name, source.definition.family, mapped, entry.url)
        }.distinctBy { it.sourceId }
        return when (matches.size) {
            0 -> EnhancedResolveResult.Failure(EnhancedBatchFailure.UnsupportedSource())
            1 -> EnhancedResolveResult.Match(matches.single())
            else -> EnhancedResolveResult.Failure(
                EnhancedBatchFailure.AmbiguousSource(matches.map { it.sourceName }.distinct().sorted()),
            )
        }
    }
}
