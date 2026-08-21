package dev.ahmedmohamed.hayai.adult.eh.favorites

import dev.ahmedmohamed.hayai.adult.eh.persistence.EhFavoriteSnapshot
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhSyncMode
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhSyncOperationStatus
import dev.ahmedmohamed.hayai.adult.eh.persistence.HayaiEhPersistenceStore
import dev.ahmedmohamed.hayai.adult.eh.settings.EhPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class EhFavoritesSyncService(
    private val remote: EhFavoritesRemote,
    private val local: J2kEhFavoritesLocal,
    private val persistence: HayaiEhPersistenceStore,
    private val preferences: EhPreferences,
    private val planner: EhFavoritesPlanner = EhFavoritesPlanner(),
) {
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow<EhFavoritesStatus>(
        persistence.activeSyncRunId()?.let { EhFavoritesStatus.Paused(it, "An interrupted favorites sync can be resumed.") }
            ?: EhFavoritesStatus.Idle,
    )
    val status: StateFlow<EhFavoritesStatus> = mutableStatus.asStateFlow()

    fun categoryMappings(): List<EhFavoriteCategoryMapping> = persistence.categoryMappings()
    fun availableCategories(): List<Pair<Int, String>> = local.availableCategories()
    fun remapCategory(slot: EhFavoriteSlot, categoryId: Int) = local.remapCategory(slot, categoryId)

    suspend fun preview(request: EhSyncRequest = preferences.favoritesSyncRequest()): EhFavoritesPlan = mutex.withLock {
        check(persistence.activeSyncRunId() == null) { "Resume the interrupted sync before creating another preview." }
        mutableStatus.value = EhFavoritesStatus.Planning("Building a read-only favorites preview")
        val remoteSnapshot = remote.snapshot()
        val mappings = local.ensureCategoryMappings(remoteSnapshot.categories)
        val aliases = EhGalleryAliasIndex(persistence.aliases())
        planner.plan(persistence.favorites(), local.snapshot(aliases, mappings), remoteSnapshot, aliases, request, "preview")
            .also { mutableStatus.value = EhFavoritesStatus.Idle }
    }

    suspend fun start(request: EhSyncRequest = preferences.favoritesSyncRequest()): EhFavoritesStatus = mutex.withLock {
        persistence.activeSyncRunId()?.let { return@withLock resumeLocked(it, request.lenient) }
        mutableStatus.value = EhFavoritesStatus.Planning("Downloading remote favorites")
        val remoteSnapshot = remote.snapshot()
        val mappings = local.ensureCategoryMappings(remoteSnapshot.categories)
        val aliases = EhGalleryAliasIndex(persistence.aliases())
        mutableStatus.value = EhFavoritesStatus.Planning("Comparing the J2K library with the last completed snapshot")
        val localSnapshot = local.snapshot(aliases, mappings)
        val runId = UUID.randomUUID().toString()
        val plan = planner.plan(persistence.favorites(), localSnapshot, remoteSnapshot, aliases, request, runId)
        persistence.createSyncPlan(
            runId = runId,
            mode = if (request.mode == EhFavoritesSyncMode.RemoteOnly) EhSyncMode.RemoteOnly else EhSyncMode.Bidirectional,
            plan = plan,
        )
        if (plan.conflicts.isNotEmpty()) {
            persistence.failSync(runId, "Sync needs conflict review")
            return@withLock EhFavoritesStatus.NeedsReview(runId, plan.conflicts).also { mutableStatus.value = it }
        }
        resumeLocked(runId, request.lenient)
    }

    suspend fun resumeActive(): EhFavoritesStatus = mutex.withLock {
        val runId = persistence.activeSyncRunId()
            ?: return@withLock EhFavoritesStatus.Idle.also { mutableStatus.value = it }
        resumeLocked(runId, preferences.favoritesLenient.get())
    }

    private suspend fun resumeLocked(runId: String, lenient: Boolean): EhFavoritesStatus {
        val aliases = EhGalleryAliasIndex(persistence.aliases())
        val operations = persistence.typedPendingOperations(runId)
        val failures = mutableListOf<String>()
        try {
            operations.forEachIndexed { index, operation ->
                mutableStatus.value = EhFavoritesStatus.Running(runId, index, operations.size, operation.title())
                persistence.markAttemptStarted(operation.operationId)
                try {
                    when (operation) {
                        is EhFavoriteOperation.SetRemote -> applyRemoteSet(operation)
                        is EhFavoriteOperation.RemoveRemote -> applyRemoteRemove(operation)
                        is EhFavoriteOperation.SetLocal, is EhFavoriteOperation.RemoveLocal -> local.apply(operation, aliases)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val message = failure.message?.take(8_000) ?: "Favorite operation failed"
                    persistence.markOperation(operation.operationId, EhSyncOperationStatus.Failed, message)
                    failures += message
                    if (!lenient) {
                        return EhFavoritesStatus.Paused(runId, message).also { mutableStatus.value = it }
                    }
                }
            }

            if (failures.isNotEmpty()) {
                return EhFavoritesStatus.Paused(runId, "${failures.size} favorite operations failed and can be retried.")
                    .also { mutableStatus.value = it }
            }

            val finalRemote = remote.snapshot()
            val expected = persistence.runExpectedFingerprint(runId)
            if (finalRemote.fingerprint != expected) {
                persistence.requireFullReconcile()
                persistence.failSync(runId, "Remote favorites changed while the sync was running")
                return EhFavoritesStatus.Paused(runId, "Remote favorites changed while the sync was running. Start a fresh reconciliation.")
                    .also { mutableStatus.value = it }
            }
            val snapshot = finalRemote.favorites.values.map { EhFavoriteSnapshot(it.gallery, it.title, it.category.value) }
            persistence.completeSyncWithSnapshot(runId, snapshot, finalRemote.fingerprint)
            return EhFavoritesStatus.Complete(runId, failures).also { mutableStatus.value = it }
        } catch (cancelled: CancellationException) {
            mutableStatus.value = EhFavoritesStatus.Paused(runId, "Sync was interrupted and can be resumed.")
            throw cancelled
        }
    }

    private suspend fun applyRemoteSet(operation: EhFavoriteOperation.SetRemote) {
        val actual = remote.state(operation.gallery)
        when (EhFavoriteRecovery.decide(actual, operation.expected, operation.desired)) {
            EhRemoteRecoveryDecision.AlreadyApplied -> {
                persistence.markOperation(operation.operationId, EhSyncOperationStatus.Applied)
                return
            }
            is EhRemoteRecoveryDecision.Conflict -> error("Remote favorite changed after planning.")
            EhRemoteRecoveryDecision.Execute -> Unit
        }
        remote.setFavorite(operation.desired)
        check(EhFavoriteRecovery.sameRemoteState(remote.state(operation.gallery), operation.desired)) { "Remote favorite update could not be verified." }
        persistence.markOperation(operation.operationId, EhSyncOperationStatus.Applied)
    }

    private suspend fun applyRemoteRemove(operation: EhFavoriteOperation.RemoveRemote) {
        val actual = remote.state(operation.gallery)
        when (EhFavoriteRecovery.decide(actual, operation.expected, null)) {
            EhRemoteRecoveryDecision.AlreadyApplied -> {
                persistence.markOperation(operation.operationId, EhSyncOperationStatus.Applied)
                return
            }
            is EhRemoteRecoveryDecision.Conflict -> error("Remote favorite changed after planning.")
            EhRemoteRecoveryDecision.Execute -> Unit
        }
        remote.removeFavorite(operation.expected)
        check(remote.state(operation.gallery) == null) { "Remote favorite removal could not be verified." }
        persistence.markOperation(operation.operationId, EhSyncOperationStatus.Applied)
    }

    private fun EhFavoriteOperation.title(): String? = when (this) {
        is EhFavoriteOperation.SetRemote -> desired.title
        is EhFavoriteOperation.RemoveRemote -> expected.title
        is EhFavoriteOperation.SetLocal -> desired.title
        is EhFavoriteOperation.RemoveLocal -> null
    }
}
