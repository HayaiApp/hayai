package dev.ahmedmohamed.hayai.adult.eh.uconfig

import dev.ahmedmohamed.hayai.adult.eh.domain.EhCategory
import dev.ahmedmohamed.hayai.adult.eh.settings.EhLanguage
import dev.ahmedmohamed.hayai.adult.eh.settings.EhRemoteSettings
import okhttp3.FormBody

object EhUConfigFormCodec {
    fun entries(settings: EhRemoteSettings, perks: EhHathPerks): List<Pair<String, String>> = buildList {
        add("xr" to settings.imageQuality.serverValue)
        add("uh" to settings.hentaiAtHome.serverValue)
        add("tl" to if (settings.japaneseTitles) "1" else "0")
        add("oi" to if (settings.originalImages) "1" else "0")
        add("tr" to perks.thumbnailRowsValue)
        add("rc" to perks.resultCountValue)
        add("dm" to "2")
        add("qb" to "0")
        add("pp" to "1")
        add("ft" to settings.tagFilterThreshold.toString())
        add("wt" to settings.tagWatchingThreshold.toString())

        EhLanguage.entries.forEach { language ->
            val selection = settings.languages.getValue(language)
            language.originalCode?.let { add("xl_$it" to selection.original.checkedValue()) }
            val base = language.originalCode ?: 0
            add("xl_${base + 1024}" to selection.translated.checkedValue())
            add("xl_${base + 2048}" to selection.rewritten.checkedValue())
        }

        CATEGORY_KEYS.forEach { (category, key) -> add(key to if (category in settings.excludedCategories) "1" else "0") }
        add("apply" to "Apply")
    }

    fun form(settings: EhRemoteSettings, perks: EhHathPerks): FormBody =
        FormBody.Builder().apply { entries(settings, perks).forEach { (key, value) -> add(key, value) } }.build()

    private fun Boolean.checkedValue(): String = if (this) "checked" else ""

    private val CATEGORY_KEYS =
        linkedMapOf(
            EhCategory.Doujinshi to "ct_doujinshi",
            EhCategory.Manga to "ct_manga",
            EhCategory.ArtistCg to "ct_artistcg",
            EhCategory.GameCg to "ct_gamecg",
            EhCategory.Western to "ct_western",
            EhCategory.NonH to "ct_non-h",
            EhCategory.ImageSet to "ct_imageset",
            EhCategory.Cosplay to "ct_cosplay",
            EhCategory.AsianPorn to "ct_asianporn",
            EhCategory.Misc to "ct_misc",
        )
}
