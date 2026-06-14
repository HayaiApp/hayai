package eu.kanade.tachiyomi.ui.reader.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import yokai.i18n.MR
import yokai.util.lang.getString
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.util.lang.next

private const val SHIFT = 0x00000000

enum class ReadingModeType(val prefValue: Int, val stringRes: StringResource, @field:DrawableRes val iconRes: Int) {
    DEFAULT(0, MR.strings.default_value, R.drawable.ic_reader_default_24dp),
    LEFT_TO_RIGHT(1, MR.strings.left_to_right_viewer, R.drawable.ic_reader_ltr_24dp),
    RIGHT_TO_LEFT(2, MR.strings.right_to_left_viewer, R.drawable.ic_reader_rtl_24dp),
    VERTICAL(3, MR.strings.vertical_viewer, R.drawable.ic_reader_vertical_24dp),
    LONG_STRIP(4, MR.strings.long_strip, R.drawable.ic_reader_webtoon_24dp),
    CONTINUOUS_VERTICAL(5, MR.strings.continuous_vertical, R.drawable.ic_reader_continuous_vertical_24dp),
    // NOVEL -->
    NOVEL(6, MR.strings.novel_reader, R.drawable.ic_book_24dp),
    // NOVEL <--
    ;

    val flagValue = prefValue shl SHIFT

    companion object {
        fun fromPreference(preference: Int): ReadingModeType = entries.find { it.flagValue == preference } ?: DEFAULT
        const val MASK = 7 shl SHIFT

        fun getNextReadingMode(preference: Int): ReadingModeType {
            val current = fromPreference(preference)
            return current.next()
        }

        fun isPagerType(preference: Int): Boolean {
            val mode = fromPreference(preference)
            return mode == LEFT_TO_RIGHT || mode == RIGHT_TO_LEFT || mode == VERTICAL
        }

        fun isWebtoonType(preference: Int): Boolean {
            val mode = fromPreference(preference)
            return mode == LONG_STRIP || mode == CONTINUOUS_VERTICAL
        }

        // NOVEL -->
        fun isNovelType(preference: Int): Boolean {
            return fromPreference(preference) == NOVEL
        }
        // NOVEL <--

        fun fromSpinner(position: Int?) = entries.find { value -> value.prefValue == position } ?: DEFAULT
    }
}
