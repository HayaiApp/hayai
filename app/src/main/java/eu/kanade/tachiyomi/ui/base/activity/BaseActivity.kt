package eu.kanade.tachiyomi.ui.base.activity

import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.viewbinding.ViewBinding
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.main.SearchActivity
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getThemeWithExtras
import eu.kanade.tachiyomi.util.system.setLocaleByAppCompat
import eu.kanade.tachiyomi.util.system.setThemeByPref
import yokai.util.koin.injectLazy
import yokai.presentation.theme.ReducedMotion
import android.R as AR

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    val preferences: PreferencesHelper by injectLazy()
    lateinit var binding: VB
    val isBindingInitialized get() = this::binding.isInitialized

    private var updatedTheme: Resources.Theme? = null

    /**
     * Per-activity splash gate. Held until [releaseSplash] is called. The keep-on-screen
     * predicate installed by [SplashScreen.configure] watches this flag together with
     * SPLASH_MIN_DURATION and SPLASH_MAX_DURATION (see the constants in the companion).
     */
    @Volatile
    private var splashHeld = true

    override fun onCreate(savedInstanceState: Bundle?) {
        setLocaleByAppCompat()
        updatedTheme = null
        setThemeByPref(preferences)
        super.onCreate(savedInstanceState)
        SecureActivityDelegate.setSecure(this)
        applyReducedMotionTransitions()
    }

    /**
     * Strips this activity's open/close transitions when the user has reduced motion enabled.
     * On Android 14+ a single `overrideActivityTransition` call covers both directions; on older
     * platforms we fall back to `overridePendingTransition` from [startActivity] / [finish].
     */
    private fun applyReducedMotionTransitions() {
        if (!ReducedMotion.isEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    @Suppress("DEPRECATION")
    override fun startActivity(intent: Intent) {
        super.startActivity(intent)
        if (ReducedMotion.isEnabled() && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(0, 0)
        }
    }

    @Suppress("DEPRECATION")
    override fun startActivity(intent: Intent, options: Bundle?) {
        super.startActivity(intent, options)
        if (ReducedMotion.isEnabled() && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(0, 0)
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (ReducedMotion.isEnabled() && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(0, 0)
        }
    }

    /**
     * Install the AndroidX SplashScreen for this activity.
     *
     * **MUST be called BEFORE [super.onCreate]** — two reasons:
     *  1. AndroidX requires it (the library reads the current theme's splash attributes
     *     during install and the activity's theme has to be the splash theme at that point).
     *  2. `installSplashScreen()` swaps the activity's theme to `postSplashScreenTheme`
     *     (the basic [R.style.Theme_Tachiyomi]). [BaseActivity.onCreate] then calls
     *     [setThemeByPref] which re-applies the user's chosen theme on top. If install runs
     *     AFTER [super.onCreate], the library's swap clobbers the user's theme — the app
     *     ends up rendering in the basic theme instead of the user-configured one.
     *
     * After install, also call [SplashScreen.configure] AFTER [super.onCreate] (and after
     * any `window.requestFeature()` calls) to arm the keep-on-screen predicate.
     *
     * Returns null on configuration-change recreate / when the splash has already been
     * shown earlier in this process (Android only shows the splash once per process).
     */
    fun installSplash(savedInstanceState: Bundle?): SplashScreen? {
        if (splashShownThisProcess || savedInstanceState != null) {
            setTheme(R.style.Theme_Tachiyomi)
            splashHeld = false
            return null
        }
        splashShownThisProcess = true
        return installSplashScreen()
    }

    /**
     * Arm the keep-on-screen predicate for a splash returned by [installSplash].
     *
     * **MUST be called AFTER [super.onCreate]** (the predicate registers via
     * `setKeepOnScreenCondition`, which touches the decor view) **AND AFTER any
     * `window.requestFeature(...)` calls** (window features must be requested before any
     * content is added; touching the decor view counts).
     *
     * The predicate keeps the splash on screen until BOTH:
     *  - SPLASH_MIN_DURATION (500 ms) has elapsed (avoids a flicker on instant cold starts), AND
     *  - [releaseSplash] has been called.
     *
     * As an absolute backstop, the predicate also dismisses the splash unconditionally after
     * SPLASH_MAX_DURATION (5 s). That cap is what saves the user from a hung cold start or
     * a root controller that never finishes loading its first content — without it the
     * splash would sit there forever waiting for a [releaseSplash] call that never comes.
     */
    fun SplashScreen.configure() {
        val startTime = System.currentTimeMillis()
        setKeepOnScreenCondition {
            val elapsed = System.currentTimeMillis() - startTime
            elapsed <= SPLASH_MIN_DURATION || (splashHeld && elapsed <= SPLASH_MAX_DURATION)
        }
        setSplashScreenExitAnimation()
    }

    /**
     * Release the splash screen. Safe to call multiple times. The next pre-draw will check the
     * predicate, see the minimum duration has elapsed and the gate is clear, and dismiss the
     * splash so the activity content becomes visible.
     *
     * Root controllers (Library/Recents/Browse) call this from their `onViewCreated` once their
     * RecyclerView has populated, so the splash hides exactly when there's something behind it
     * worth showing.
     */
    fun releaseSplash() {
        splashHeld = false
    }

    private fun SplashScreen.setSplashScreenExitAnimation() {
        val root = findViewById<View>(AR.id.content)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            this.setOnExitAnimationListener { splashProvider ->
                // For some reason the SplashScreen applies (incorrect) Y translation to the iconView
                splashProvider.iconView.translationY = 0F

                if (ReducedMotion.isEnabled()) {
                    root.translationY = 0f
                    splashProvider.remove()
                } else {
                    root.translationY = 16f.dpToPx
                    root.animate()
                        .translationY(0f)
                        .setInterpolator(LinearOutSlowInInterpolator())
                        .setDuration(SPLASH_EXIT_ANIM_DURATION)
                        .withLayer()
                        .start()

                    splashProvider.view.animate()
                        .alpha(0f)
                        .setInterpolator(FastOutSlowInInterpolator())
                        .setDuration(SPLASH_EXIT_ANIM_DURATION)
                        .withLayer()
                        .withEndAction(splashProvider::remove)
                        .start()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (this !is SearchActivity) {
            SecureActivityDelegate.promptLockIfNeeded(this)
        }
    }

    override fun getTheme(): Resources.Theme {
        val newTheme = getThemeWithExtras(super.getTheme(), preferences, updatedTheme)
        updatedTheme = newTheme
        return newTheme
    }

    companion object {
        /**
         * Whether the splash has been installed at least once in this process. Android only shows
         * the splash on the first activity launch per process, so subsequent activities (or this
         * activity after a config-change recreate, when savedInstanceState != null) skip the install.
         */
        @Volatile
        private var splashShownThisProcess = false

        // Splash screen
        private const val SPLASH_MIN_DURATION = 500 // ms
        private const val SPLASH_MAX_DURATION = 5000 // ms
        private const val SPLASH_EXIT_ANIM_DURATION = 400L // ms
    }
}
