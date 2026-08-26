package dev.ahmedmohamed.hayai.novel.download

import dev.ahmedmohamed.hayai.testing.MemoryPreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NovelDownloadPreferencesTest {
    @Test
    fun `global delay is the default for every source`() {
        val preferences = NovelDownloadPreferences(MemoryPreferenceStore())

        assertEquals(3_000L, preferences.delayMillisFor(1L))
        assertEquals(3_000L, preferences.delayMillisFor(2L))
    }

    @Test
    fun `source overrides are independent and keyed by stable source ID`() {
        val store = MemoryPreferenceStore()
        val preferences = NovelDownloadPreferences(store)
        preferences.globalDelayMillis.set(2_000)
        preferences.sourceDelayOverrideMillis(11L).set(7_000)
        preferences.sourceDelayOverrideMillis(12L).set(500)

        assertEquals(7_000L, preferences.delayMillisFor(11L))
        assertEquals(500L, preferences.delayMillisFor(12L))
        assertEquals(2_000L, preferences.delayMillisFor(13L))
        assertEquals(
            7_000L,
            NovelDownloadPreferences(store).delayMillisFor(11L),
        )
        assertNotEquals(
            NovelDownloadPreferences.sourceDelayKey(11L),
            NovelDownloadPreferences.sourceDelayKey(12L),
        )
    }

    @Test
    fun `negative stored delays cannot create negative waits`() {
        assertEquals(
            0L,
            NovelDownloadDelayPolicy.resolve(
                globalDelayMillis = -10,
                sourceDelayOverrideMillis = NovelDownloadPreferences.INHERIT_GLOBAL_DELAY,
            ),
        )
    }
}
