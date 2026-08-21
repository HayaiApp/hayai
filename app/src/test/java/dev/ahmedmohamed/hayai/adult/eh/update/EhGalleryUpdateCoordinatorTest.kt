package dev.ahmedmohamed.hayai.adult.eh.update

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EhGalleryUpdateCoordinatorTest {
    @Test
    fun `coordinator stops after five transient failures and records deterministic stats`() = kotlinx.coroutines.runBlocking {
        val candidates = (1L..8L).map(::candidate)
        val operations = FakeOperations(candidates) {
            EhGalleryUpdateResult(EhGalleryUpdateDisposition.TransientFailure, it.title, failure = "offline")
        }
        var now = 100L
        val run = EhGalleryUpdateCoordinator(operations) { now++ }.run()

        assertEquals(5, run.stats.attempted)
        assertEquals(5, run.stats.transientFailures)
        assertTrue(run.stats.stoppedAtFailureCutoff)
        assertTrue(run.shouldRetry)
    }

    @Test
    fun `authentication is a no-op and successful revisions aggregate`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(candidate(1), candidate(2))
        val operations = FakeOperations(candidates) {
            if (it.mangaId == 1L) {
                EhGalleryUpdateResult(EhGalleryUpdateDisposition.AuthenticationSkipped, it.title)
            } else {
                EhGalleryUpdateResult(EhGalleryUpdateDisposition.Updated, it.title, newRevisionCount = 3)
            }
        }
        val run = EhGalleryUpdateCoordinator(operations) { 100 }.run()

        assertEquals(1, run.stats.authenticationSkipped)
        assertEquals(1, run.stats.updated)
        assertEquals(3, run.stats.newRevisions)
        assertFalse(run.shouldRetry)
    }

    private fun candidate(id: Long) = EhGalleryUpdateCandidate(
        mangaId = id,
        sourceId = EhSite.EHentai.sourceId,
        title = "Gallery $id",
        mangaUrl = "/g/$id/token$id/",
        state = EhGalleryUpdateState(),
    )

    private class FakeOperations(
        private val candidates: List<EhGalleryUpdateCandidate>,
        private val updateResult: (EhGalleryUpdateCandidate) -> EhGalleryUpdateResult,
    ) : EhGalleryUpdateOperations {
        override suspend fun candidates() = candidates
        override suspend fun update(candidate: EhGalleryUpdateCandidate) = updateResult(candidate)
    }
}

