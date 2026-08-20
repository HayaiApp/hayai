package dev.ahmedmohamed.hayai.adult.eh.settings

import dev.ahmedmohamed.hayai.adult.eh.domain.EhCategory
import java.security.MessageDigest

enum class EhImageQuality(
    val preferenceValue: String,
    val serverValue: String,
) {
    Auto("auto", "0"),
    Size2400("ovrs_2400", "5"),
    Size1600("ovrs_1600", "4"),
    Size1280("high", "3"),
    Size980("med", "2"),
    Size780("low", "1"),
    ;

    companion object {
        fun fromPreference(value: String): EhImageQuality = entries.find { it.preferenceValue == value } ?: Auto
    }
}

enum class EhHentaiAtHome(
    val preferenceValue: Int,
    val serverValue: String,
) {
    Any(0, "0"),
    DefaultOnly(1, "1"),
    Never(2, "2"),
    ;

    companion object {
        fun fromPreference(value: Int): EhHentaiAtHome = entries.find { it.preferenceValue == value } ?: Any
    }
}

enum class EhLanguage(
    val displayName: String,
    val originalCode: Int?,
) {
    Japanese("Japanese", null),
    English("English", 1),
    Chinese("Chinese", 10),
    Dutch("Dutch", 20),
    French("French", 30),
    German("German", 40),
    Hungarian("Hungarian", 50),
    Italian("Italian", 60),
    Korean("Korean", 70),
    Polish("Polish", 80),
    Portuguese("Portuguese", 90),
    Russian("Russian", 100),
    Spanish("Spanish", 110),
    Thai("Thai", 120),
    Vietnamese("Vietnamese", 130),
    NotAvailable("N/A", 254),
    Other("Other", 255),
}

data class EhLanguageSelection(
    val original: Boolean = false,
    val translated: Boolean = false,
    val rewritten: Boolean = false,
)

data class EhRemoteSettings(
    val imageQuality: EhImageQuality,
    val hentaiAtHome: EhHentaiAtHome,
    val japaneseTitles: Boolean,
    val originalImages: Boolean,
    val tagFilterThreshold: Int,
    val tagWatchingThreshold: Int,
    val languages: Map<EhLanguage, EhLanguageSelection>,
    val excludedCategories: Set<EhCategory>,
) {
    init {
        require(tagFilterThreshold in -9999..0) { "Tag filtering threshold must be between -9999 and 0." }
        require(tagWatchingThreshold in 0..9999) { "Tag watching threshold must be between 0 and 9999." }
        require(languages.keys == EhLanguage.entries.toSet()) { "Every E-Hentai language must have a selection." }
        require(languages.getValue(EhLanguage.Japanese).original.not()) { "Japanese original filtering is not available." }
    }

    fun fingerprint(): String {
        val canonical = buildString {
            append(imageQuality.preferenceValue).append('|')
            append(hentaiAtHome.preferenceValue).append('|')
            append(japaneseTitles).append('|').append(originalImages).append('|')
            append(tagFilterThreshold).append('|').append(tagWatchingThreshold).append('|')
            EhLanguage.entries.forEach { language ->
                val value = languages.getValue(language)
                append(language.name).append('=')
                append(value.original).append(',').append(value.translated).append(',').append(value.rewritten).append(';')
            }
            append('|')
            EhCategory.entries.forEach { append(it.name).append('=').append(it in excludedCategories).append(';') }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

