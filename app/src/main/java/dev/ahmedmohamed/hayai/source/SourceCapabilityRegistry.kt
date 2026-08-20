package dev.ahmedmohamed.hayai.source

import eu.kanade.tachiyomi.source.Source

@JvmInline
value class HayaiSourceId(
    val value: Long,
)

enum class SourceCapability {
    Adult,
    BatchAdd,
    CustomDescription,
    EhFavorites,
    InAppOpen,
    Metadata,
    NovelText,
    Recommendations,
    RelatedEntries,
}

enum class SourceFamily {
    EHentai,
    ExHentai,
    EightMuses,
    HBrowse,
    MangaDex,
    NHentai,
    Pururin,
    Lanraragi,
    Novel,
    Other,
}

data class SourceDescriptor(
    val id: HayaiSourceId,
    val family: SourceFamily,
    val capabilities: Set<SourceCapability>,
)

object SourceCapabilityRegistry {
    const val EH_SOURCE_ID = 6901L
    const val EXH_SOURCE_ID = 6902L
    const val PURURIN_SOURCE_ID = 2221515250486218861L
    const val EIGHT_MUSES_SOURCE_ID = 1802675169972965535L
    const val HBROWSE_SOURCE_ID = 1401584337232758222L

    private val adultMetadata =
        setOf(
            SourceCapability.Adult,
            SourceCapability.CustomDescription,
            SourceCapability.InAppOpen,
            SourceCapability.Metadata,
        )

    private val fixed =
        mapOf(
            EH_SOURCE_ID to
                SourceDescriptor(HayaiSourceId(EH_SOURCE_ID), SourceFamily.EHentai, adultMetadata + SourceCapability.EhFavorites),
            EXH_SOURCE_ID to
                SourceDescriptor(HayaiSourceId(EXH_SOURCE_ID), SourceFamily.ExHentai, adultMetadata + SourceCapability.EhFavorites),
            PURURIN_SOURCE_ID to SourceDescriptor(HayaiSourceId(PURURIN_SOURCE_ID), SourceFamily.Pururin, adultMetadata),
            EIGHT_MUSES_SOURCE_ID to
                SourceDescriptor(HayaiSourceId(EIGHT_MUSES_SOURCE_ID), SourceFamily.EightMuses, adultMetadata + SourceCapability.BatchAdd),
            HBROWSE_SOURCE_ID to SourceDescriptor(HayaiSourceId(HBROWSE_SOURCE_ID), SourceFamily.HBrowse, adultMetadata),
        )

    fun descriptor(source: Source): SourceDescriptor {
        fixed[source.id]?.let { return it }
        val family = familyFromName(source.name)
        val capabilities =
            when (family) {
                SourceFamily.MangaDex ->
                    setOf(
                        SourceCapability.CustomDescription,
                        SourceCapability.Metadata,
                        SourceCapability.Recommendations,
                        SourceCapability.RelatedEntries,
                    )
                SourceFamily.NHentai -> adultMetadata + SourceCapability.BatchAdd
                SourceFamily.Lanraragi ->
                    setOf(
                        SourceCapability.BatchAdd,
                        SourceCapability.CustomDescription,
                        SourceCapability.InAppOpen,
                        SourceCapability.Metadata,
                    )
                SourceFamily.Novel -> setOf(SourceCapability.NovelText)
                SourceFamily.EightMuses -> adultMetadata + SourceCapability.BatchAdd
                SourceFamily.HBrowse, SourceFamily.Pururin -> adultMetadata
                SourceFamily.EHentai, SourceFamily.ExHentai -> adultMetadata + SourceCapability.EhFavorites
                SourceFamily.Other -> emptySet()
            }
        return SourceDescriptor(HayaiSourceId(source.id), family, capabilities)
    }

    private fun familyFromName(name: String): SourceFamily {
        val normalized = name.lowercase()
        return when {
            normalized.contains("exhentai") -> SourceFamily.ExHentai
            normalized.contains("e-hentai") || normalized.contains("ehentai") -> SourceFamily.EHentai
            normalized.contains("8muses") || normalized.contains("eromuse") -> SourceFamily.EightMuses
            normalized.contains("hbrowse") -> SourceFamily.HBrowse
            normalized.contains("mangadex") -> SourceFamily.MangaDex
            normalized.contains("nhentai") -> SourceFamily.NHentai
            normalized.contains("pururin") || normalized.contains("puruin") -> SourceFamily.Pururin
            normalized.contains("lanraragi") -> SourceFamily.Lanraragi
            normalized.contains("novel") -> SourceFamily.Novel
            else -> SourceFamily.Other
        }
    }
}
