package dev.ahmedmohamed.hayai.extension.install

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Per-package cancellation inside J2K's existing WorkManager batch. */
class ExtensionInstallBatch(private val parallelism: Int = 3) {
    private val jobs = mutableMapOf<String, Job>()
    private val cancelled = mutableSetOf<String>()

    fun wasCancelled(packageName: String): Boolean = synchronized(this) { packageName in cancelled }

    suspend fun run(packageNames: List<String>, install: suspend CoroutineScope.(String) -> Unit) = coroutineScope {
        val semaphore = Semaphore(parallelism)
        val children = packageNames.distinct().map { packageName ->
            launch(start = CoroutineStart.LAZY) {
                semaphore.withPermit { install(packageName) }
            }.also { job ->
                synchronized(this@ExtensionInstallBatch) {
                    jobs[packageName] = job
                    if (packageName in cancelled) job.cancel()
                }
            }
        }
        try {
            children.forEach { it.start() }
            children.joinAll()
        } finally {
            synchronized(this@ExtensionInstallBatch) { jobs.clear() }
        }
    }

    @Synchronized
    fun cancel(packageName: String) {
        cancelled += packageName
        jobs[packageName]?.cancel()
    }
}
