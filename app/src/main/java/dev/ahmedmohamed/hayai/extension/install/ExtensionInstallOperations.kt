package dev.ahmedmohamed.hayai.extension.install

import kotlinx.coroutines.Job

/** Transient ownership of installer work; package state remains in J2K. */
class ExtensionInstallOperations {
    private val jobs = mutableMapOf<String, Job>()
    private val latest = mutableMapOf<String, Job>()

    @Synchronized
    fun replace(packageName: String, job: Job, cleanUp: () -> Unit = {}) {
        jobs.remove(packageName)?.takeUnless { it === job }?.cancel()
        cleanUp()
        jobs[packageName] = job
        latest[packageName] = job
    }

    @Synchronized
    fun isCurrent(packageName: String, job: Job): Boolean = jobs[packageName] === job

    @Synchronized
    fun current(packageName: String): Job? = jobs[packageName]

    @Synchronized
    fun latest(packageName: String): Job? = latest[packageName]

    @Synchronized
    fun withCurrent(packageName: String, action: (Job) -> Unit): Boolean {
        val job = jobs[packageName]?.takeIf { it.isActive } ?: return false
        action(job)
        return true
    }

    @Synchronized
    fun finish(packageName: String, job: Job, cleanUp: () -> Unit = {}): Boolean {
        if (jobs[packageName] !== job) return false
        cleanUp()
        jobs.remove(packageName)
        return true
    }

    @Synchronized
    fun cancel(packageName: String, cleanUp: () -> Unit = {}): Boolean {
        val job = jobs[packageName]
        cleanUp()
        jobs.remove(packageName)
        job?.cancel()
        return job != null
    }
}
