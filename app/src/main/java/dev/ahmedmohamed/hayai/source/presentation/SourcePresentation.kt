package dev.ahmedmohamed.hayai.source.presentation

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.novel.plugin.source.NovelPluginSource
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelSource
import dev.ahmedmohamed.hayai.source.enhanced.HayaiEnhancedHttpSource
import dev.ahmedmohamed.hayai.source.SourceCapability
import dev.ahmedmohamed.hayai.source.SourceCapabilityRegistry
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isNovelSource

enum class SourceBadge(
    val label: String,
) {
    Bundled("Bundled"),
    JavaScript("JS"),
    Novel("Novel"),
    Adult("Adult"),
    Enhanced("Enhanced"),
}

object SourcePresentation {
    fun badges(source: Source): List<SourceBadge> = buildList {
        when {
            source is NovelPluginSource -> add(SourceBadge.JavaScript)
            source.id == LocalNovelSource.ID || EhSite.entries.any { it.sourceId == source.id } -> add(SourceBadge.Bundled)
        }
        if (source.isNovelSource()) add(SourceBadge.Novel)
        if (SourceCapability.Adult in SourceCapabilityRegistry.descriptor(source).capabilities) add(SourceBadge.Adult)
        if (source is HayaiEnhancedHttpSource) add(SourceBadge.Enhanced)
    }

    fun badgeText(source: Source): String? =
        badges(source).takeIf { it.isNotEmpty() }?.joinToString(" · ") { it.label }
}
