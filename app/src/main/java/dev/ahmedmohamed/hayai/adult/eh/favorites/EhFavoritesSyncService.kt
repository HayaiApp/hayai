package dev.ahmedmohamed.hayai.adult.eh.favorites

import dev.ahmedmohamed.hayai.adult.eh.persistence.EhFavoriteSnapshot
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhSyncMode
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhSyncOperationStatus
import dev.ahmedmohamed.hayai.adult.eh.persistence.HayaiEhPersistenceStore
import dev.ahmedmohamed.hayai.adult.eh.presentation.EhTextResolver
import dev.ahmedmohamed.hayai.adult.eh.presentation.localizedMessage
import dev.ahmedmohamed.hayai.adult.eh.settings.EhPreferences
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class EhFavoritesSyncService(
    private val text: EhTextResolver,
    private val remote: EhFavoritesRemote,
    private val local: J2kEhFavoritesLocal,
    private val persistence: HayaiEhPersistenceStore,
    private val preferences: EhPreferences,
    private val planner: EhFavoritesPlanner = EhFavoritesPlanner(),
) {
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow<EhFavoritesStatus>(
        persistence.activeSyncRunId()?.let { EhFavoritesStatus.Paused(it, text.get(R.string.hayai_eh_favorites_interrupted)) }
            ?: EhFavoritesStatus.Idle,
    )
    val status: StateFlow<EhFavoritesStatus> = mutableStatus.asStateFlow()

    fun categoryMappings(): List<EhFavoriteCategoryMapping> = persistence.categoryMappings()
    fun availableCategories(): List<Pair<Int, String>> = local.availableCategories()
    fun remapCategory(slot: EhFavoriteSlot, categoryId: Int) = local.remapCategory(slot, categoryId)

    suspend fun preview(request: EhSyncRequest = preferences.favoritesSyncRequest()): EhFavoritesPlan = mutex.withLock {
        check(persistence.activeSyncRunId() == null) { text.get(R.string.hayai_eh_favorites_resume_first) }
        mutableStatus.value = EhFavoritesStatus.Planning(text.get(R.string.hayai_eh_favorites_building_preview))
        val remoteSnapshot = remote.snapshot()
        val mappings = local.ensureCategoryMappings(remoteSnapshot.categories)
        val aliases = EhGalleryAliasIndex(persistence.aliases())
        planner.plan(persistence.favorites(), local.snapshot(aliases, mappings), remoteSnapshot, aliases, request, "preview")
            .also { mutableStatus.value = EhFavoritesStatus.Idle }
    }

    suspend fun start(request: EhSyncRequest = preferences.favoritesSyncRequest()): EhFavoritesStatus = mutex.withLock {
        persistence.activeSyncRunId()?.let { return@withLock resumeLocked(it, request.lenient) }
        mutableStatus.value = EhFavoritesStatus.Planning(text.get(R.string.hayai_eh_favorites_downloading))
        val remoteSnapshot = remote.snapshot()
        val mappings = local.ensureCategoryMappings(remoteSnapshot.categories)
        val aliases = EhGalleryAliasIndex(persistence.aliases())
        mutableStatus.value = EhFavoritesStatus.Planning(text.get(R.string.hayai_eh_favorites_comparing))
        val localSnapshot = local.snapshot(aliases, mappings)
        val runId = UUID.randomUUID().toString()
        val plan = planner.plan(persistence.favorites(), localSnapshot, remoteSnapshot, aliases, request, runId)
        persistence.createSyncPlan(
            runId = runId,
            mode = if (request.mode == EhFavoritesSyncMode.RemoteOnly) EhSyncMode.RemoteOnly else EhSyncMode.Bidirectional,
            plan = plan,
        )
        if (plan.conflicts.isNotEmpty()) {
            persistence.failSync(runId, text.get(R.string.hayai_eh_sync_needs_review))
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
                    val message = (failure as? EhFavoritesFailure)?.localizedMessage(text)
                        ?: text.get(R.string.hayai_eh_favorite_operation_failed)
                    persistence.markOperation(operation.operationId, EhSyncOperationStatus.Failed, message)
                    failures += message
                    if (!lenient) {
                        return EhFavoritesStatus.Paused(runId, message).also { mutableStatus.value = it }
                    }
                }
            }

            if (failures.isNotEmpty()) {
                return EhFavoritesStatus.Paused(
                    runId,
                    text.quantity(R.plurals.hayai_eh_favorite_operations_failed, failures.size, failures.size),
                )
                    .also { mutableStatus.value = it }
            }

            val finalRemote = remote.snapshot()
            val expected = persistence.runExpectedFingerprint(runId)
            if (finalRemote.fingerprint != expected) {
                persistence.requireFullReconcile()
                persistence.failSync(runId, text.get(R.string.hayai_eh_remote_changed_persisted))
                return EhFavoritesStatus.Paused(runId, text.get(R.string.hayai_eh_remote_changed))
                    .also { mutableStatus.value = it }
            }
            val snapshot = finalRemote.favorites.values.map { EhFavoriteSnapshot(it.gallery, it.title, it.category.value) }
            persistence.completeSyncWithSnapshot(runId, snapshot, finalRemote.fingerprint)
            return EhFavoritesStatus.Complete(runId, failures).also { mutableStatus.value = it }
        } catch (cancelled: CancellationException) {
            mutableStatus.value = EhFavoritesStatus.Paused(runId, text.get(R.string.hayai_eh_sync_interrupted))
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
            is EhRemoteRecoveryDecision.Conflict -> error(text.get(R.string.hayai_eh_remote_changed_after_planning))
            EhRemoteRecoveryDecision.Execute -> Unit
        }
        remote.setFavorite(operation.desired)
        check(EhFavoriteRecovery.sameRemoteState(remote.state(operation.gallery), operation.desired)) {
            text.get(R.string.hayai_eh_remote_update_unverified)
        }
        persistence.markOperation(operation.operationId, EhSyncOperationStatus.Applied)
    }

    private suspend fun applyRemoteRemove(operation: EhFavoriteOperation.RemoveRemote) {
        val actual = remote.state(operation.gallery)
        when (EhFavoriteRecovery.decide(actual, operation.expected, null)) {
            EhRemoteRecoveryDecision.AlreadyApplied -> {
                persistence.markOperation(operation.operationId, EhSyncOperationStatus.Applied)
                return
            }
            is EhRemoteRecoveryDecision.Conflict -> error(text.get(R.string.hayai_eh_remote_changed_after_planning))
            EhRemoteRecoveryDecision.Execute -> Unit
        }
        remote.removeFavorite(operation.expected)
        check(remote.state(operation.gallery) == null) { text.get(R.string.hayai_eh_remote_removal_unverified) }
        persistence.markOperation(operation.operationId, EhSyncOperationStatus.Applied)
    }

    private fun EhFavoriteOperation.title(): String? = when (this) {
        is EhFavoriteOperation.SetRemote -> desired.title
        is EhFavoriteOperation.RemoveRemote -> expected.title
        is EhFavoriteOperation.SetLocal -> desired.title
        is EhFavoriteOperation.RemoveLocal -> null
    }
}
