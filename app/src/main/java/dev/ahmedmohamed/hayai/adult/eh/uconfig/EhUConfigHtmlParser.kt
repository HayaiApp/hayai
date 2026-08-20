package dev.ahmedmohamed.hayai.adult.eh.uconfig

import org.jsoup.Jsoup
import java.util.Locale

object EhUConfigHtmlParser {
    fun profiles(html: String): List<EhRemoteProfile> {
        require(html.length <= MAX_DOCUMENT_CHARS) { "E-Hentai profile response is too large." }
        val document = Jsoup.parse(html)
        val selector = document.selectFirst("[name=profile_set]")
        require(selector != null) { "E-Hentai did not return a settings profile page." }
        val profiles = selector
            .select(":root > option")
            .mapNotNull { option ->
                val slot = option.attr("value").toIntOrNull()?.takeIf { it in 1..3 } ?: return@mapNotNull null
                EhRemoteProfile(EhProfileSlot(slot), option.text().trim().take(MAX_PROFILE_NAME_LENGTH))
            }
        require(profiles.groupBy(EhRemoteProfile::slot).values.none { entries -> entries.map(EhRemoteProfile::name).distinct().size > 1 }) {
            "E-Hentai returned conflicting settings profiles."
        }
        return profiles.distinctBy(EhRemoteProfile::slot)
    }

    fun hathPerks(html: String): EhHathPerks {
        require(html.length <= MAX_DOCUMENT_CHARS) { "E-Hentai H@H perks response is too large." }
        val purchased = linkedSetOf<String>()
        val rows = Jsoup.parse(html).select(".stuffbox tr")
        require(rows.isNotEmpty()) { "E-Hentai did not return the H@H perks page." }
        rows.forEach { row ->
            val cells = row.children()
            if (cells.size < 3 || cells[2].getElementsByTag("form").isNotEmpty()) return@forEach
            purchased += cells[0].text().trim().lowercase(Locale.ROOT)
        }
        return EhHathPerks(
            moreThumbs = "more thumbs" in purchased,
            thumbsUp = "thumbs up" in purchased,
            allThumbs = "all thumbs" in purchased,
            pagingI = "paging enlargement i" in purchased,
            pagingII = "paging enlargement ii" in purchased,
            pagingIII = "paging enlargement iii" in purchased,
        )
    }

    fun settingsValues(
        html: String,
        expectedKeys: Set<String>,
    ): Map<String, String> {
        require(html.length <= MAX_DOCUMENT_CHARS) { "E-Hentai settings response is too large." }
        val document = Jsoup.parse(html)
        require(document.selectFirst("[name=profile_set]") != null) { "E-Hentai did not return a settings profile page." }
        val values = linkedMapOf<String, String>()
        document.select("input[name], select[name]").forEach { field ->
            val name = field.attr("name")
            if (name !in expectedKeys) return@forEach
            values[name] =
                when (field.tagName()) {
                    "select" -> field.selectFirst("option[selected]")?.attr("value") ?: field.`val`()
                    else -> if (field.attr("type").equals("checkbox", true)) {
                        if (field.hasAttr("checked")) "checked" else ""
                    } else {
                        field.attr("value")
                    }
                }
        }
        return values
    }

    const val MAX_DOCUMENT_CHARS = 2 * 1024 * 1024
    private const val MAX_PROFILE_NAME_LENGTH = 128
}

object EhRemoteCookieParser {
    fun parse(setCookieHeaders: List<String>): EhRemoteCookies {
        val values = linkedMapOf<String, String>()
        setCookieHeaders.forEach { header ->
            val pair = header.substringBefore(';')
            val separator = pair.indexOf('=')
            if (separator <= 0) return@forEach
            val name = pair.substring(0, separator).trim().lowercase(Locale.ROOT)
            if (name !in COOKIE_NAMES) return@forEach
            val value = pair.substring(separator + 1).trim()
            val previous = values.putIfAbsent(name, value)
            require(previous == null || previous == value) { "Conflicting $name cookies were returned." }
        }
        return EhRemoteCookies(
            settingsKey = values["sk"],
            session = values["s"],
            hathPerks = values["hath_perks"],
        )
    }

    private val COOKIE_NAMES = setOf("sk", "s", "hath_perks")
}
