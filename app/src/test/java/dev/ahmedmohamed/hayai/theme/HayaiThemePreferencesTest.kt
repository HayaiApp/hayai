package dev.ahmedmohamed.hayai.theme

import dev.ahmedmohamed.hayai.testing.MemoryPreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HayaiThemePreferencesTest {
    @Test
    fun `custom seed is opt in with a stable default`() {
        val preferences = HayaiThemePreferences(MemoryPreferenceStore())

        assertFalse(preferences.customSeedEnabled.get())
        assertEquals(HayaiThemePreferences.DEFAULT_SEED_COLOR, preferences.customSeedColor.get())
    }

    @Test
    fun `custom seed preferences persist independently`() {
        val store = MemoryPreferenceStore()
        val preferences = HayaiThemePreferences(store)
        preferences.customSeedEnabled.set(true)
        preferences.customSeedColor.set(0xFFAA5500.toInt())

        val restored = HayaiThemePreferences(store)
        assertTrue(restored.customSeedEnabled.get())
        assertEquals(0xFFAA5500.toInt(), restored.customSeedColor.get())
    }

    @Test
    fun `policy applies only when enabled and runtime colors are available`() {
        assertEquals(HayaiThemeSeedDecision.Disabled, HayaiThemeSeedPolicy.decide(false, true))
        assertEquals(HayaiThemeSeedDecision.Unsupported, HayaiThemeSeedPolicy.decide(true, false))
        assertEquals(HayaiThemeSeedDecision.Apply, HayaiThemeSeedPolicy.decide(true, true))
    }
}
