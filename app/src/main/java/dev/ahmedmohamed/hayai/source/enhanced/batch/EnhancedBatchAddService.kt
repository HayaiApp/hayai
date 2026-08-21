package dev.ahmedmohamed.hayai.source.enhanced.batch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

interface EnhancedBatchLibraryGateway {
    suspend fun resolve(entry: EnhancedBatchEntry): EnhancedResolveResult
    suspend fun addToLibrary(target: EnhancedImportTarget): EnhancedBatchItemResult
}

sealed interface EnhancedResolveResult {
    data class Match(val target: EnhancedImportTarget) : EnhancedResolveResult
    data class Failure(val reason: EnhancedBatchFailure) : EnhancedResolveResult
}

class EnhancedBatchAddService(
    private val library: EnhancedBatchLibraryGateway,
    private val maxNetworkAttempts: Int = 3,
    private val retryDelayMillis: Long = 300,
) {
    private val mutableProgress = MutableStateFlow<EnhancedBatchProgress?>(null)
    private val runMutex = Mutex()
    val progress: StateFlow<EnhancedBatchProgress?> = mutableProgress.asStateFlow()

    init {
        require(maxNetworkAttempts in 1..5)
        require(retryDelayMillis >= 0)
    }

    suspend fun run(plan: EnhancedBatchPlan): EnhancedBatchReport = runMutex.withLock {
        val results = ArrayList<EnhancedBatchItemResult>(plan.entries.size)
        val canonicalTargets = hashSetOf<Pair<Long, String>>()
        mutableProgress.value = EnhancedBatchProgress(0, plan.entries.size, null, emptyList())
        plan.entries.forEach { entry ->
            currentCoroutineContext().ensureActive()
            mutableProgress.value = EnhancedBatchProgress(results.size, plan.entries.size, entry.url, results.toList())
            val resolved = library.resolve(entry)
            val result = when (resolved) {
                is EnhancedResolveResult.Failure -> EnhancedBatchItemResult.Failed(entry.url, resolved.reason)
                is EnhancedResolveResult.Match -> {
                    val key = resolved.target.sourceId to resolved.target.mangaUrl
                    if (!canonicalTargets.add(key)) {
                        EnhancedBatchItemResult.Duplicate(entry.url, resolved.target)
                    } else {
                        addWithRetry(resolved.target)
                    }
                }
            }
            results += result
            mutableProgress.value = EnhancedBatchProgress(results.size, plan.entries.size, null, results.toList())
        }
        EnhancedBatchReport(results)
    }

    private suspend fun addWithRetry(target: EnhancedImportTarget): EnhancedBatchItemResult {
        var lastFailure: EnhancedBatchItemResult.Failed? = null
        repeat(maxNetworkAttempts) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                val result = library.addToLibrary(target)
                if (result !is EnhancedBatchItemResult.Failed || result.reason !is EnhancedBatchFailure.Network) return result
                lastFailure = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                lastFailure = EnhancedBatchItemResult.Failed(
                    target.submittedUrl,
                    EnhancedBatchFailure.Network(error.message ?: "Network request failed"),
                )
            }
            if (attempt + 1 < maxNetworkAttempts && retryDelayMillis > 0) delay(retryDelayMillis)
        }
        return requireNotNull(lastFailure)
    }
}
