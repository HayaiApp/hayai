package dev.ahmedmohamed.hayai.extension.managed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Tsundoku cc36869a0b: reserve each JS update once and execute the queue in order. */
class ManagedPluginOperations(private val scope: CoroutineScope) {
    private val jobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()
    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active = _active.asStateFlow()

    @Synchronized
    fun start(pluginId: String, onFailure: suspend (Throwable) -> Unit, action: suspend () -> Unit): Boolean {
        if (pluginId in jobs) return false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                mutex.withLock { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(error)
            }
        }
        jobs[pluginId] = job
        _active.value = jobs.keys.toSet()
        job.invokeOnCompletion {
            synchronized(this) {
                if (jobs[pluginId] === job) {
                    jobs.remove(pluginId)
                    _active.value = jobs.keys.toSet()
                }
            }
        }
        job.start()
        return true
    }

    @Synchronized
    fun cancel(pluginId: String) {
        jobs[pluginId]?.cancel()
    }
}
