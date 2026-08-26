package dev.ahmedmohamed.hayai.theme

import android.app.Activity
import androidx.preference.PreferenceGroup
import dev.ahmedmohamed.hayai.novel.reader.NovelColorPickerDialog
import dev.ahmedmohamed.hayai.novel.reader.NovelThemeColors
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.setting.defaultValue
import eu.kanade.tachiyomi.ui.setting.onChange
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.summaryRes
import eu.kanade.tachiyomi.ui.setting.switchPreference
import eu.kanade.tachiyomi.ui.setting.titleRes
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object HayaiThemeSettings {
    fun addTo(
        group: PreferenceGroup,
        activity: Activity?,
    ) {
        val preferences = HayaiThemePreferences(Injekt.get<PreferenceStore>())
        val supported = HayaiThemeSeedApplicator.isSupported()

        group.switchPreference {
            key = HayaiThemePreferences.KEY_CUSTOM_SEED_ENABLED
            titleRes = R.string.hayai_custom_theme_seed
            summaryRes =
                if (supported) {
                    R.string.hayai_custom_theme_seed_summary
                } else {
                    R.string.hayai_custom_theme_seed_unsupported
                }
            defaultValue = false
            isEnabled = supported

            onChange {
                activity.recreateForThemeChange()
                true
            }
        }
        group.preference {
            key = HayaiThemePreferences.KEY_CUSTOM_SEED_COLOR
            titleRes = R.string.hayai_custom_theme_seed_color
            summary = NovelThemeColors.formatRgb(preferences.customSeedColor.get())
            isEnabled = supported && preferences.customSeedEnabled.get()

            onClick {
                NovelColorPickerDialog.show(
                    context = context,
                    titleRes = R.string.hayai_custom_theme_seed_color,
                    initialColor = preferences.customSeedColor.get(),
                    defaultColor = HayaiThemePreferences.DEFAULT_SEED_COLOR,
                    onConfirm = { seed ->
                        preferences.customSeedColor.set(seed)
                        activity.recreateForThemeChange()
                    },
                    onDefault = {
                        preferences.customSeedColor.delete()
                        activity.recreateForThemeChange()
                    },
                )
            }
        }
    }

    private fun Activity?.recreateForThemeChange() {
        (this as? MainActivity)?.recreateFully() ?: this?.recreate()
    }
}
