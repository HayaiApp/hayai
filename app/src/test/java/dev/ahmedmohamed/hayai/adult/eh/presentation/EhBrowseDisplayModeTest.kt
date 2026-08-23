package dev.ahmedmohamed.hayai.adult.eh.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EhBrowseDisplayModeTest {
    @Test
    fun `display action leaves the enhanced list for a real grid`() {
        val grid = EhBrowseDisplayMode(browseAsList = false, enhancedList = true).toggled()

        assertFalse(grid.isList)
        assertFalse(grid.browseAsList)
        assertFalse(grid.enhancedList)
    }

    @Test
    fun `display action restores the SY detailed list from grid`() {
        val detailed = EhBrowseDisplayMode(browseAsList = false, enhancedList = false).toggled()

        assertTrue(detailed.isList)
        assertEquals(EhBrowseDisplayMode(browseAsList = true, enhancedList = true), detailed)
    }
}
