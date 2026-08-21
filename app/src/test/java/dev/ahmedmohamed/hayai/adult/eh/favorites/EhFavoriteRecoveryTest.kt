package dev.ahmedmohamed.hayai.adult.eh.favorites

import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EhFavoriteRecoveryTest {
    private val gallery = EhGalleryIdentity("1", "token")
    private val before = EhFavoriteState(gallery, "Title", EhFavoriteSlot(1))
    private val after = before.copy(category = EhFavoriteSlot(4))

    @Test
    fun `process death after remote mutation observes postcondition without repeating post`() {
        assertEquals(EhRemoteRecoveryDecision.AlreadyApplied, EhFavoriteRecovery.decide(after, before, after))
        assertEquals(EhRemoteRecoveryDecision.AlreadyApplied, EhFavoriteRecovery.decide(null, before, null))
    }

    @Test
    fun `unchanged precondition executes and remote drift stops`() {
        assertEquals(EhRemoteRecoveryDecision.Execute, EhFavoriteRecovery.decide(before, before, after))
        val drift = before.copy(category = EhFavoriteSlot(7))
        assertTrue(EhFavoriteRecovery.decide(drift, before, after) is EhRemoteRecoveryDecision.Conflict)
    }
}
