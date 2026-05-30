package eu.kanade.tachiyomi.data.preference

import androidx.annotation.StringRes
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import yokai.i18n.MR
import yokai.util.lang.getString
import dev.icerock.moko.resources.compose.stringResource

// Library
const val MANGA_NON_COMPLETED = "manga_ongoing"
const val MANGA_HAS_UNREAD = "manga_fully_read"
const val MANGA_NON_READ = "manga_started"
const val MANGA_OUTSIDE_RELEASE_PERIOD = "manga_outside_release_period"

// Device
const val DEVICE_ONLY_ON_WIFI = "wifi"
const val DEVICE_CHARGING = "ac"
const val DEVICE_BATTERY_NOT_LOW = "battery_not_low"

object PreferenceValues {
    enum class SecureScreenMode(val titleResId: StringResource) {
        ALWAYS(MR.strings.always),
        INCOGNITO(MR.strings.incognito_mode),
        NEVER(MR.strings.never),
    }

    enum class ReaderHideThreshold(val titleResId: StringResource, val threshold: Int) {
        HIGHEST(MR.strings.pref_highest, 5),
        HIGH(MR.strings.pref_high, 13),
        LOW(MR.strings.pref_low, 31),
        LOWEST(MR.strings.pref_lowest, 47),
    }

    enum class MigrationSourceOrder(val value: Int, val titleResId: StringResource) {
        Alphabetically(0, MR.strings.alphabetically),
        MostEntries(1, MR.strings.most_entries),
        Obsolete(2, MR.strings.obsolete),
        ;

        companion object {
            fun fromValue(preference: Int) = entries.find { it.value == preference } ?: Alphabetically
            fun fromPreference(pref: PreferencesHelper) = fromValue(pref.migrationSourceOrder().get())
        }
    }
}
