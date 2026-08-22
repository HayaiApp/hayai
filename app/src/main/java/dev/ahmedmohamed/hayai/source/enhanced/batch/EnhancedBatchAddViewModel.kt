package dev.ahmedmohamed.hayai.source.enhanced.batch

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.source.SourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class EnhancedBatchAddUiState(
    val input: String = "",
    val running: Boolean = false,
    val progress: EnhancedBatchProgress? = null,
    val report: EnhancedBatchReport? = null,
    val error: EnhancedBatchUiError? = null,
)

enum class EnhancedBatchUiError { InvalidInput, Cancelled, Failed }

class EnhancedBatchAddCoordinator(
    private val scope: CoroutineScope,
    sourceManager: SourceManager = Injekt.get(),
    database: DatabaseHelper = Injekt.get(),
) {
    private val service = EnhancedBatchAddService(J2kEnhancedBatchLibraryGateway(sourceManager, database))
    private val mutableInput = MutableStateFlow("")
    private val mutableResult = MutableStateFlow<ResultState>(ResultState.Idle)
    @Volatile private var runJob: Job? = null

    val state: Flow<EnhancedBatchAddUiState> = combine(mutableInput, mutableResult, service.progress) { input, result, progress ->
        EnhancedBatchAddUiState(
            input = input,
            running = result is ResultState.Running,
            progress = progress.takeIf { result is ResultState.Running || result is ResultState.Finished },
            report = (result as? ResultState.Finished)?.report,
            error = (result as? ResultState.Error)?.reason,
        )
    }

    fun updateInput(value: String) {
        if (runJob == null) mutableInput.value = value
    }

    fun start() {
        if (runJob != null) return
        val plan = runCatching { EnhancedBatchInputParser.parse(mutableInput.value) }.getOrElse {
            mutableResult.value = ResultState.Error(EnhancedBatchUiError.InvalidInput)
            return
        }
        mutableResult.value = ResultState.Running
        runJob = scope.launch(Dispatchers.IO) {
            try {
                val report = service.run(plan)
                mutableResult.value = ResultState.Finished(report)
            } catch (cancelled: CancellationException) {
                mutableResult.value = ResultState.Error(EnhancedBatchUiError.Cancelled)
                throw cancelled
            } catch (error: Exception) {
                mutableResult.value = ResultState.Error(EnhancedBatchUiError.Failed)
            } finally {
                runJob = null
            }
        }
    }

    fun cancel() {
        runJob?.cancel()
    }

    fun clearReport() {
        if (runJob == null) mutableResult.value = ResultState.Idle
    }

    private sealed interface ResultState {
        data object Idle : ResultState
        data object Running : ResultState
        data class Finished(val report: EnhancedBatchReport) : ResultState
        data class Error(val reason: EnhancedBatchUiError) : ResultState
    }
}
