package eu.kanade.tachiyomi.util.system

import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import yokai.i18n.MR
import yokai.util.lang.getString
import dev.icerock.moko.resources.compose.stringResource

@Suppress("unused")
enum class Themes(@field:StyleRes val styleRes: Int, val nightMode: Int, val nameRes: StringResource, altNameRes: StringResource? = null) {
    MONET(
        R.style.Theme_Tachiyomi_Monet,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.a_brighter_you,
        MR.strings.a_calmer_you,
    ),
    DEFAULT(
        R.style.Theme_Tachiyomi,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.white_theme,
        MR.strings.dark,
    ),
    SPRING_AND_DUSK(
        R.style.Theme_Tachiyomi_MidnightDusk,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.spring_blossom,
        MR.strings.midnight_dusk,
    ),
    STRAWBERRIES(
        R.style.Theme_Tachiyomi_Strawberries,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.strawberry_daiquiri,
        MR.strings.chocolate_strawberries,
    ),
    TEAL_AND_SAPPHIRE(
        R.style.Theme_Tachiyomi_SapphireDusk,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.teal_ocean,
        MR.strings.sapphire_dusk,
    ),
    LAVENDER(
        R.style.Theme_Tachiyomi_Lavender,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.lavender,
        MR.strings.violet,
    ),
    TAKO(
        R.style.Theme_Tachiyomi_Tako,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.tako,
    ),
    YIN_AND_YANG(
        R.style.Theme_Tachiyomi_YinYang,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.yang,
        MR.strings.yin,
    ),
    LIME(
        R.style.Theme_Tachiyomi_FlatLime,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.lime_time,
        MR.strings.flat_lime,
    ),
    YOTSUBA(
        R.style.Theme_Tachiyomi_Yotsuba,
        AppCompatDelegate.MODE_NIGHT_NO,
        MR.strings.yotsuba,
    ),
    DOKI(
        R.style.Theme_Tachiyomi_Doki,
        AppCompatDelegate.MODE_NIGHT_YES,
        MR.strings.doki,
    ),
    SAKURA(
        R.style.Theme_Tachiyomi_Sakura,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.sakura,
        MR.strings.moonlit_sakura,
    ),
    PINK_ROMANCE(
        R.style.Theme_Tachiyomi_PinkRomance,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.pink_romance,
        MR.strings.deep_wine,
    ),
    SUMI_E(
        R.style.Theme_Tachiyomi_SumiE,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.sumi_e,
        MR.strings.ink_night,
    ),
    KIMONO(
        R.style.Theme_Tachiyomi_Kimono,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.kimono,
        MR.strings.lantern_night,
    ),
    WAGASHI(
        R.style.Theme_Tachiyomi_Wagashi,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.wagashi,
        MR.strings.twilight_tearoom,
    ),
    NORDIC(
        R.style.Theme_Tachiyomi_Nordic,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.nordic_stone,
        MR.strings.fjord_night,
    ),
    ROSE(
        R.style.Theme_Tachiyomi_Rose,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        MR.strings.rose,
        MR.strings.rose_noir,
    )
    ;

    val isDarkTheme = nightMode == AppCompatDelegate.MODE_NIGHT_YES
    val followsSystem = nightMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    val darkNameRes: StringResource = altNameRes ?: nameRes
}
