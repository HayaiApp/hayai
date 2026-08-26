package dev.ahmedmohamed.hayai.theme

import android.app.Activity
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal enum class HayaiThemeSeedDecision {
    Disabled,
    Unsupported,
    Apply,
}

internal object HayaiThemeSeedPolicy {
    fun decide(
        enabled: Boolean,
        runtimeColorsAvailable: Boolean,
    ): HayaiThemeSeedDecision =
        when {
            !enabled -> HayaiThemeSeedDecision.Disabled
            !runtimeColorsAvailable -> HayaiThemeSeedDecision.Unsupported
            else -> HayaiThemeSeedDecision.Apply
        }
}

object HayaiThemeSeedApplicator {
    private val preferences by lazy { HayaiThemePreferences(Injekt.get<PreferenceStore>()) }

    fun isSupported(): Boolean = DynamicColors.isDynamicColorAvailable()

    fun apply(activity: Activity): Boolean = apply(activity, preferences)

    internal fun apply(
        activity: Activity,
        preferences: HayaiThemePreferences,
    ): Boolean {
        if (
            HayaiThemeSeedPolicy.decide(
                enabled = preferences.customSeedEnabled.get(),
                runtimeColorsAvailable = isSupported(),
            ) != HayaiThemeSeedDecision.Apply
        ) {
            return false
        }

        var applied = false
        val options =
            DynamicColorsOptions
                .Builder()
                .setContentBasedSource(preferences.customSeedColor.get())
                .setOnAppliedCallback { applied = true }
                .build()
        DynamicColors.applyToActivityIfAvailable(activity, options)
        return applied
    }
}
