package dev.ahmedmohamed.hayai.recents

import eu.kanade.tachiyomi.data.preference.Preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentSourceVisibilityTest {
    private val history = FakePreference(setOf("1", "-2", "invalid"))
    private val updates = FakePreference(setOf("3", "1"))
    private val visibility = RecentSourceVisibility(history, updates)

    @Test
    fun `each dedicated surface uses only its own hidden sources`() {
        assertFalse(visibility.includes(1, RecentSurface.History))
        assertFalse(visibility.includes(-2, RecentSurface.History))
        assertTrue(visibility.includes(3, RecentSurface.History))

        assertFalse(visibility.includes(3, RecentSurface.Updates))
        assertTrue(visibility.includes(-2, RecentSurface.Updates))
    }

    @Test
    fun `mixed views use the union and ignore malformed legacy values`() {
        assertEquals(setOf(1L, -2L, 3L), visibility.hiddenSourceIds(RecentSurface.Mixed))
        assertFalse(visibility.includes(1, RecentSurface.Mixed))
        assertFalse(visibility.includes(-2, RecentSurface.Mixed))
        assertFalse(visibility.includes(3, RecentSurface.Mixed))
        assertTrue(visibility.includes(4, RecentSurface.Mixed))
    }

    @Test
    fun `replacing a dedicated surface is exact and preserves signed source ids`() {
        visibility.replaceHiddenSources(RecentSurface.History, setOf(Long.MIN_VALUE, 42))

        assertEquals(setOf(Long.MIN_VALUE.toString(), "42"), history.get())
        assertEquals(setOf("3", "1"), updates.get())
    }

    @Test(expected = IllegalStateException::class)
    fun `mixed view cannot be edited independently`() {
        visibility.replaceHiddenSources(RecentSurface.Mixed, setOf(1))
    }
}

private class FakePreference<T>(initial: T) : Preference<T> {
    private val state = MutableStateFlow(initial)

    override fun key(): String = "fake"

    override fun get(): T = state.value

    override fun set(value: T) {
        state.value = value
    }

    override fun isSet(): Boolean = true

    override fun delete() = Unit

    override fun defaultValue(): T = state.value

    override fun changes(): Flow<T> = state

    override fun stateIn(scope: CoroutineScope): StateFlow<T> = state
}
