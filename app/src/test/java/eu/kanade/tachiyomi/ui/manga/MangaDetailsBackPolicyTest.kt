package eu.kanade.tachiyomi.ui.manga

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaDetailsBackPolicyTest {

    @Test
    fun `active chapter selection consumes back without navigation preview`() {
        assertFalse(shouldAnimateMangaDetailsPredictiveBack(selectionMode = true, selectedChapterCount = 3))
    }

    @Test
    fun `normal manga details back previews navigation`() {
        assertTrue(shouldAnimateMangaDetailsPredictiveBack(selectionMode = false, selectedChapterCount = 0))
    }

    @Test
    fun `empty selection mode does not trap navigation`() {
        assertTrue(shouldAnimateMangaDetailsPredictiveBack(selectionMode = true, selectedChapterCount = 0))
    }
}
