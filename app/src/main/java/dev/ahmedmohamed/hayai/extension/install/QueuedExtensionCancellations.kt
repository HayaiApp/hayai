package dev.ahmedmohamed.hayai.extension.install

/** Covers the interval between enqueueing existing WorkManager work and starting its worker. */
object QueuedExtensionCancellations {
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun start(packageNames: List<String>) { cancelled.removeAll(packageNames.toSet()) }

    fun cancel(packageName: String) { cancelled += packageName }

    fun contains(packageName: String): Boolean = packageName in cancelled
}
