package dev.ahmedmohamed.hayai.adult

import dev.ahmedmohamed.hayai.preferences.LewdLibraryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LewdLibraryFilterTest {
    @Test
    fun `persisted values follow the SY tri-state contract`() {
        assertEquals(0, LewdLibraryFilter.Disabled.persistedValue)
        assertEquals(1, LewdLibraryFilter.Include.persistedValue)
        assertEquals(2, LewdLibraryFilter.Exclude.persistedValue)
        assertEquals(LewdLibraryFilter.Disabled, LewdLibraryFilter.fromPersistedValue(0))
        assertEquals(LewdLibraryFilter.Include, LewdLibraryFilter.fromPersistedValue(1))
        assertEquals(LewdLibraryFilter.Exclude, LewdLibraryFilter.fromPersistedValue(2))
    }

    @Test
    fun `malformed persisted values disable filtering`() {
        listOf(Int.MIN_VALUE, -1, 3, Int.MAX_VALUE).forEach { value ->
            assertEquals(LewdLibraryFilter.Disabled, LewdLibraryFilter.fromPersistedValue(value))
        }
    }

    @Test
    fun `disabled filter includes lewd and non-lewd manga`() {
        assertTrue(LewdLibraryFilter.Disabled.includes(isLewd = true))
        assertTrue(LewdLibraryFilter.Disabled.includes(isLewd = false))
    }

    @Test
    fun `include filter includes only lewd manga`() {
        assertTrue(LewdLibraryFilter.Include.includes(isLewd = true))
        assertFalse(LewdLibraryFilter.Include.includes(isLewd = false))
    }

    @Test
    fun `exclude filter includes only non-lewd manga`() {
        assertFalse(LewdLibraryFilter.Exclude.includes(isLewd = true))
        assertTrue(LewdLibraryFilter.Exclude.includes(isLewd = false))
    }
}
