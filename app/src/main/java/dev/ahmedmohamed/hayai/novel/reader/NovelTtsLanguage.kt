package dev.ahmedmohamed.hayai.novel.reader

import java.util.Locale

internal object NovelTtsLanguage {
    fun resolve(override: String, displayed: String, source: String, system: String): String =
        sequenceOf(override, displayed, source, system).map(String::trim).firstOrNull { it.isNotBlank() && it !in setOf("auto", "all", "other") }.orEmpty()

    fun availableLocales(displayLocale: Locale): List<Locale> = Locale.getAvailableLocales()
        .filter { it.language.isNotBlank() && it.language != "und" }
        .distinctBy(Locale::toLanguageTag)
        .sortedBy { it.getDisplayName(displayLocale) }
}
