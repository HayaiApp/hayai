package dev.ahmedmohamed.hayai.adult.eh.favorites

import dev.ahmedmohamed.hayai.adult.eh.persistence.EhFavoriteSnapshot
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryIdentity
import java.security.MessageDigest

class EhFavoritesPlanner {
    fun plan(
        base: Collection<EhFavoriteSnapshot>,
        local: EhLocalFavoritesSnapshot,
        remote: EhRemoteFavoritesSnapshot,
        aliases: EhGalleryAliasIndex,
        request: EhSyncRequest,
        runId: String,
    ): EhFavoritesPlan {
        val baseStates = base.associate { aliases.canonical(it.gallery) to EhFavoriteState(aliases.canonical(it.gallery), it.title, EhFavoriteSlot(it.categorySlot)) }
        val localStates = local.favorites.mapKeys { aliases.canonical(it.key) }.mapValues { it.value.state.copy(gallery = aliases.canonical(it.key)) }
        val conflicts = local.conflicts.toMutableList()
        val remoteGroups = remote.favorites.entries.groupBy { aliases.canonical(it.key) }
        remoteGroups.filterValues { it.size > 1 }.forEach { (canonical, entries) ->
            conflicts += EhFavoriteConflict.DuplicateAliases(
                id = conflictId(runId, canonical),
                gallery = canonical,
                mangaIds = entries.mapTo(linkedSetOf()) { it.key.gid.toLong() },
            )
        }
        val remoteStates = remoteGroups.filterValues { it.size == 1 }.mapValues { (canonical, entries) -> entries.single().value.copy(gallery = canonical) }
        val operations = mutableListOf<EhFavoriteOperation>()
        var sequence = 0L

        (baseStates.keys + localStates.keys + remoteStates.keys).sortedWith(compareBy({ it.gid.toLong() }, { it.token })).forEach { gallery ->
            val baseState = baseStates[gallery]
            val localState = localStates[gallery]
            val remoteState = remoteStates[gallery]
            if (localState == remoteState) return@forEach

            val desiredDirection = when {
                request.mode == EhFavoritesSyncMode.RemoteOnly -> Direction.RemoteToLocal
                localState == baseState -> Direction.RemoteToLocal
                remoteState == baseState -> Direction.LocalToRemote
                request.conflictPolicy == EhConflictPolicy.PreferRemote -> Direction.RemoteToLocal
                request.conflictPolicy == EhConflictPolicy.PreferLocal -> Direction.LocalToRemote
                else -> null
            }
            if (desiredDirection == null) {
                conflicts += EhFavoriteConflict.BothChanged(conflictId(runId, gallery), gallery, baseState, localState, remoteState)
                return@forEach
            }

            val operationId = operationId(runId, gallery, desiredDirection.name)
            when (desiredDirection) {
                Direction.RemoteToLocal ->
                    operations += if (remoteState == null) {
                        EhFavoriteOperation.RemoveLocal(operationId, sequence++, gallery)
                    } else {
                        EhFavoriteOperation.SetLocal(operationId, sequence++, gallery, remoteState)
                    }
                Direction.LocalToRemote ->
                    operations += if (localState == null) {
                        remoteState?.let { EhFavoriteOperation.RemoveRemote(operationId, sequence++, gallery, it) } ?: return@forEach
                    } else {
                        EhFavoriteOperation.SetRemote(operationId, sequence++, gallery, remoteState, localState.copy(title = remoteState?.title ?: localState.title))
                    }
            }
        }
        val ordered = operations
            .sortedWith(compareBy<EhFavoriteOperation> { it !is EhFavoriteOperation.SetRemote && it !is EhFavoriteOperation.RemoveRemote }.thenBy { it.sequence })
            .mapIndexed { index, operation -> operation.withSequence(index.toLong()) }
        val expectedRemote = remoteStates.toMutableMap()
        ordered.forEach { operation ->
            when (operation) {
                is EhFavoriteOperation.SetRemote -> expectedRemote[operation.gallery] = operation.desired
                is EhFavoriteOperation.RemoveRemote -> expectedRemote.remove(operation.gallery)
                else -> Unit
            }
        }
        return EhFavoritesPlan(
            operations = ordered,
            conflicts = conflicts,
            expectedRemoteFingerprint = EhFavoritesFingerprint.create(remote.categories, expectedRemote.values),
        )
    }

    private fun operationId(runId: String, gallery: EhGalleryIdentity, kind: String) = digest("op|$runId|${gallery.gid}|${gallery.token}|$kind")
    private fun conflictId(runId: String, gallery: EhGalleryIdentity) = digest("conflict|$runId|${gallery.gid}|${gallery.token}")
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private enum class Direction { RemoteToLocal, LocalToRemote }

    private fun EhFavoriteOperation.withSequence(value: Long): EhFavoriteOperation = when (this) {
        is EhFavoriteOperation.SetRemote -> copy(sequence = value)
        is EhFavoriteOperation.RemoveRemote -> copy(sequence = value)
        is EhFavoriteOperation.SetLocal -> copy(sequence = value)
        is EhFavoriteOperation.RemoveLocal -> copy(sequence = value)
    }
}
