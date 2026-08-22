package dev.ahmedmohamed.hayai.novel.tracker.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NovelTrackerContractTest {
    @Test
    fun `stable remote ids are deterministic and positive`() {
        assertEquals(stableRemoteId("same-key"), stableRemoteId("same-key"))
        assertNotEquals(stableRemoteId("same-key"), stableRemoteId("other-key"))
        assert(stableRemoteId("same-key") > 0)
    }

    @Test
    fun `credentials reject header injection and oversized secrets`() {
        assertThrows(NovelTrackerFailure.InvalidCredentials::class.java) {
            requireSafeCredential("value\r\nInjected: yes", NovelTrackerCredential.SessionToken)
        }
        assertThrows(NovelTrackerFailure.InvalidCredentials::class.java) {
            requireSafeCredential("x".repeat(8_193), NovelTrackerCredential.SessionToken)
        }
    }
}
