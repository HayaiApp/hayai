package dev.ahmedmohamed.hayai.adult.eh.update

import kotlinx.coroutines.CancellationException

data class EhGalleryUpdateRun(
    val stats: EhGalleryUpdaterStats,
    val results: List<EhGalleryUpdateResult>,
) {
    val shouldRetry: Boolean get() = stats.transientFailures > 0
}

class EhGalleryUpdateCoordinator(
    private val operations: EhGalleryUpdateOperations,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(onProgress: (EhGalleryUpdateCandidate, Int, Int) -> Unit = { _, _, _ -> }): EhGalleryUpdateRun {
        val startedAt = clock()
        val candidates = operations.candidates()
        val results = mutableListOf<EhGalleryUpdateResult>()
        var transientFailures = 0
        for ((index, candidate) in candidates.withIndex()) {
            if (transientFailures >= EhGalleryUpdateRuntime.FAILURE_CUTOFF) break
            onProgress(candidate, index, candidates.size)
            val result = try {
                operations.update(candidate)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                EhGalleryUpdateResult(EhGalleryUpdateDisposition.TransientFailure, candidate.title, failure = failure.message)
            }
            results += result
            if (result.disposition == EhGalleryUpdateDisposition.TransientFailure) transientFailures++
        }
        val stats = EhGalleryUpdaterStats(
            startedAt = startedAt,
            finishedAt = clock(),
            eligible = candidates.size,
            attempted = results.size,
            updated = results.count { it.disposition == EhGalleryUpdateDisposition.Updated },
            newRevisions = results.sumOf(EhGalleryUpdateResult::newRevisionCount),
            aged = results.count { it.disposition == EhGalleryUpdateDisposition.Aged },
            notFound = results.count { it.disposition == EhGalleryUpdateDisposition.NotFound },
            authenticationSkipped = results.count { it.disposition == EhGalleryUpdateDisposition.AuthenticationSkipped },
            transientFailures = transientFailures,
            permanentFailures = results.count { it.disposition == EhGalleryUpdateDisposition.PermanentFailure },
            stoppedAtFailureCutoff = transientFailures >= EhGalleryUpdateRuntime.FAILURE_CUTOFF && results.size < candidates.size,
        )
        return EhGalleryUpdateRun(stats, results)
    }
}
