package yokai.presentation.theme

import yokai.util.koin.get
import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for the user's "Reduce motion" preference (Appearance → Motion).
 *
 * - Compose code: read [LocalReducedMotion] / [isReducedMotion] (provided by [YokaiTheme]).
 * - Non-Compose code (Conductor change handlers, Coil image loader, Activities): call
 *   [ReducedMotion.isEnabled].
 *
 * No call site should reach into `PreferencesHelper.reducedMotion()` directly — route through
 * here so the lookup stays consistent and the preference key isn't sprinkled across the codebase.
 */
object ReducedMotion {
    private val preferences: PreferencesHelper by lazy { get() }
    private val pref get() = preferences.reducedMotion()

    /**
     * Synchronous read suitable for any thread. Android's system-wide "Remove animations"
     * setting is part of the contract too; app motion must never override that accessibility
     * choice just because Hayai's own preference is off.
     */
    fun isEnabled(): Boolean = pref.get() ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())

    /** Cold flow that emits whenever the preference changes. */
    fun changes(): Flow<Boolean> = pref.changes()

}

/**
 * Whether the user has opted into reduced motion. Compose only — outside [YokaiTheme] this
 * falls back to `false`. Within the theme, the value is observed and recomposes when the
 * preference changes.
 */
val LocalReducedMotion = compositionLocalOf { false }

val isReducedMotion: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalReducedMotion.current

// region Compose helpers
//
// Each helper encapsulates one common animation pattern and bakes the reduced-motion check in,
// so call sites read like a normal API call — no per-component if/else.

/**
 * Fade-pulse alpha used for skeleton loading placeholders. Returns a static mid value when
 * reduced motion is enabled (still differentiates the placeholder from the background but
 * doesn't animate).
 */
@Composable
fun rememberShimmerAlpha(
    min: Float = 0.15f,
    max: Float = 0.4f,
    durationMs: Int = 800,
    label: String = "shimmer",
): State<Float> {
    if (LocalReducedMotion.current) {
        return remember(min, max) { mutableFloatStateOf((min + max) / 2f) }
    }
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        initialValue = min,
        targetValue = max,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${label}Alpha",
    )
}

/**
 * Crossfade duration to feed into Coil's per-request `.crossfade(...)` or
 * `Modifier.animateContentSize(animationSpec = tween(...))` style call sites. Returns 0 when
 * reduced motion is on (instant swap).
 */
@Composable
@ReadOnlyComposable
fun motionAwareCrossfadeMillis(default: Int = 200): Int =
    if (LocalReducedMotion.current) 0 else default

// endregion
