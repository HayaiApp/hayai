package dev.ahmedmohamed.hayai.source.presentation

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.novel.plugin.source.NovelPluginSource
import dev.ahmedmohamed.hayai.novel.source.local.LocalNovelSource
import eu.kanade.tachiyomi.source.Source

enum class SourceBadge(
    val label: String,
) {
    Bundled("Bundled"),
    JavaScript("JS"),
    Novel("Novel"),
    Adult("Adult"),
}

object SourcePresentation {
    fun badges(source: Source): List<SourceBadge> = buildList {
        when {
            source is NovelPluginSource -> add(SourceBadge.JavaScript)
            source.id == LocalNovelSource.ID || EhSite.entries.any { it.sourceId == source.id } -> add(SourceBadge.Bundled)
        }
        if (source.isNovelSource) add(SourceBadge.Novel)
        if (EhSite.entries.any { it.sourceId == source.id }) add(SourceBadge.Adult)
    }

    fun badgeText(source: Source): String? =
        badges(source).takeIf { it.isNotEmpty() }?.joinToString(" · ") { it.label }
}
