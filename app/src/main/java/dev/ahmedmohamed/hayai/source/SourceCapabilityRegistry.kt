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
    NonHentaiGenreOverride,
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
                SourceDescriptor(
                    HayaiSourceId(EH_SOURCE_ID),
                    SourceFamily.EHentai,
                    adultMetadata + SourceCapability.EhFavorites + SourceCapability.NonHentaiGenreOverride,
                ),
            EXH_SOURCE_ID to
                SourceDescriptor(
                    HayaiSourceId(EXH_SOURCE_ID),
                    SourceFamily.ExHentai,
                    adultMetadata + SourceCapability.EhFavorites + SourceCapability.NonHentaiGenreOverride,
                ),
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
                SourceFamily.NHentai ->
                    adultMetadata + SourceCapability.BatchAdd + SourceCapability.NonHentaiGenreOverride
                SourceFamily.Lanraragi ->
                    setOf(
                        SourceCapability.CustomDescription,
                        SourceCapability.InAppOpen,
                        SourceCapability.Metadata,
                    )
                SourceFamily.Novel -> setOf(SourceCapability.NovelText)
                SourceFamily.EightMuses -> adultMetadata + SourceCapability.BatchAdd
                SourceFamily.HBrowse, SourceFamily.Pururin -> adultMetadata
                SourceFamily.EHentai, SourceFamily.ExHentai ->
                    adultMetadata + SourceCapability.EhFavorites + SourceCapability.NonHentaiGenreOverride
                SourceFamily.Other ->
                    if (source.id in LEGACY_ADULT_SOURCE_IDS || isAdultSourceName(source.name)) adultMetadata else emptySet()
            }
        return SourceDescriptor(HayaiSourceId(source.id), family, capabilities)
    }

    private fun isAdultSourceName(name: String): Boolean {
        val normalized = name.lowercase()
        return ADULT_SOURCE_NAMES.any(normalized::contains)
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

    private val LEGACY_ADULT_SOURCE_IDS = 6905L..6913L
    private val ADULT_SOURCE_NAMES =
        setOf(
            "allporncomic",
            "hentai cafe",
            "hentai2read",
            "hentaifox",
            "hentainexus",
            "manhwahentai.me",
            "milftoon",
            "myhentaicomics",
            "myhentaigallery",
            "ninehentai",
            "pururin",
            "simply hentai",
            "tsumino",
            "8muses",
            "hbrowse",
            "nhentai",
            "erofus",
            "luscious",
            "doujins",
            "multporn",
            "vcp",
            "vmp",
            "hentai",
        )
}
