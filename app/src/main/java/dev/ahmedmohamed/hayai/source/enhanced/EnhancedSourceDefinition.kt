package dev.ahmedmohamed.hayai.source.enhanced

import dev.ahmedmohamed.hayai.source.SourceCapabilityRegistry
import dev.ahmedmohamed.hayai.source.SourceFamily
import eu.kanade.tachiyomi.source.online.HttpSource
import java.net.URI

data class EnhancedSourceDefinition(
    val family: SourceFamily,
    val fixedIds: Set<Long> = emptySet(),
    val classPrefixes: Set<String> = emptySet(),
    val hosts: Set<String>,
) {
    fun matches(source: HttpSource): Boolean {
        if (source.id in fixedIds) return true
        val className = source.javaClass.name
        if (classPrefixes.any(className::startsWith)) return true
        val descriptor = SourceCapabilityRegistry.descriptor(source)
        return descriptor.family == family && runCatching {
            URI(source.baseUrl).host?.lowercase() in hosts
        }.getOrDefault(false)
    }
}

object EnhancedSourceDefinitions {
    val all = listOf(
        EnhancedSourceDefinition(
            SourceFamily.EightMuses,
            fixedIds = setOf(SourceCapabilityRegistry.EIGHT_MUSES_SOURCE_ID),
            classPrefixes = setOf(
                "eu.kanade.tachiyomi.extension.en.eightmuses.",
                "eu.kanade.tachiyomi.extension.en.eromuse.",
            ),
            hosts = setOf("8muses.com", "www.8muses.com", "comics.8muses.com", "eromuse.com", "www.eromuse.com"),
        ),
        EnhancedSourceDefinition(
            SourceFamily.HBrowse,
            fixedIds = setOf(SourceCapabilityRegistry.HBROWSE_SOURCE_ID),
            classPrefixes = setOf("eu.kanade.tachiyomi.extension.en.hbrowse."),
            hosts = setOf("hbrowse.com", "www.hbrowse.com"),
        ),
        EnhancedSourceDefinition(
            SourceFamily.Pururin,
            fixedIds = setOf(SourceCapabilityRegistry.PURURIN_SOURCE_ID),
            classPrefixes = setOf("eu.kanade.tachiyomi.extension.en.pururin.", "eu.kanade.tachiyomi.extension.en.puruin."),
            hosts = setOf("pururin.to", "www.pururin.to", "pururin.me", "www.pururin.me", "puruin.com", "www.puruin.com"),
        ),
        EnhancedSourceDefinition(
            SourceFamily.NHentai,
            classPrefixes = setOf("eu.kanade.tachiyomi.extension.all.nhentai."),
            hosts = setOf("nhentai.net", "www.nhentai.net"),
        ),
        EnhancedSourceDefinition(
            SourceFamily.MangaDex,
            classPrefixes = setOf("eu.kanade.tachiyomi.extension.all.mangadex"),
            hosts = setOf("mangadex.org", "www.mangadex.org"),
        ),
        EnhancedSourceDefinition(
            SourceFamily.Lanraragi,
            classPrefixes = setOf("eu.kanade.tachiyomi.extension.all.lanraragi."),
            hosts = emptySet(),
        ),
    )
}
