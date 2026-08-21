package dev.ahmedmohamed.hayai.adult.eh.favorites

import dev.ahmedmohamed.hayai.adult.eh.persistence.EhFavoriteSnapshot
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EhFavoritesPlannerTest {
    private val planner = EhFavoritesPlanner()
    private val aliases = EhGalleryAliasIndex(emptyList())
    private val categories = (0..9).map { EhRemoteCategory(EhFavoriteSlot(it), "Category $it") }

    @Test
    fun `remote-only change becomes an atomic local operation`() {
        val base = state(1, 0)
        val remote = state(1, 2)

        val plan = plan(listOf(base.toSnapshot()), mapOf(base.gallery to local(base)), mapOf(remote.gallery to remote))

        assertEquals(listOf(EhFavoriteOperation.SetLocal::class), plan.operations.map { it::class })
        assertEquals(EhFavoriteSlot(2), (plan.operations.single() as EhFavoriteOperation.SetLocal).desired.category)
    }

    @Test
    fun `local-only change uploads in bidirectional mode`() {
        val base = state(1, 0)
        val local = state(1, 4)

        val plan = plan(listOf(base.toSnapshot()), mapOf(local.gallery to local(local)), mapOf(base.gallery to base))

        assertTrue(plan.operations.single() is EhFavoriteOperation.SetRemote)
    }

    @Test
    fun `different local and remote changes require review`() {
        val base = state(1, 0)
        val local = state(1, 3)
        val remote = state(1, 7)

        val plan = plan(listOf(base.toSnapshot()), mapOf(local.gallery to local(local)), mapOf(remote.gallery to remote))

        assertTrue(plan.operations.isEmpty())
        assertTrue(plan.conflicts.single() is EhFavoriteConflict.BothChanged)
    }

    @Test
    fun `remote operations are sequenced before local operations`() {
        val localOnly = state(1, 1)
        val remoteOnly = state(2, 2)
        val plan = plan(emptyList(), mapOf(localOnly.gallery to local(localOnly)), mapOf(remoteOnly.gallery to remoteOnly))

        assertTrue(plan.operations.first() is EhFavoriteOperation.SetRemote)
        assertTrue(plan.operations.last() is EhFavoriteOperation.SetLocal)
        assertEquals(listOf(0L, 1L), plan.operations.map { it.sequence })
    }

    private fun plan(
        base: List<EhFavoriteSnapshot>,
        local: Map<EhGalleryIdentity, EhLocalFavoriteState>,
        remote: Map<EhGalleryIdentity, EhFavoriteState>,
    ) = planner.plan(
        base,
        EhLocalFavoritesSnapshot(local),
        EhRemoteFavoritesSnapshot(categories, remote, EhFavoritesFingerprint.create(categories, remote.values)),
        aliases,
        EhSyncRequest(EhFavoritesSyncMode.Bidirectional),
        "run",
    )

    private fun state(id: Int, slot: Int) = EhFavoriteState(EhGalleryIdentity(id.toString(), "token$id"), "Gallery $id", EhFavoriteSlot(slot))
    private fun local(state: EhFavoriteState) = EhLocalFavoriteState(setOf(state.gallery.gid.toLong()), state, emptySet())
    private fun EhFavoriteState.toSnapshot() = EhFavoriteSnapshot(gallery, title, category.value)
}

