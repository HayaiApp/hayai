package dev.ahmedmohamed.hayai.extension.install

/** Monotonic deadlines, including a short grace for PackageInstaller result broadcasts. */
class ExtensionInstallWait(private val startedAt: Long) {
    private var missingSessionAt: Long? = null

    fun expired(now: Long): Boolean = now - startedAt >= TIMEOUT_MILLIS

    fun sessionDisappeared(now: Long, sessionExists: Boolean): Boolean {
        if (sessionExists) {
            missingSessionAt = null
            return false
        }
        val since = missingSessionAt ?: now.also { missingSessionAt = it }
        return now - since >= RESULT_GRACE_MILLIS
    }

    companion object {
        const val TIMEOUT_MILLIS = 5 * 60 * 1000L
        const val RESULT_GRACE_MILLIS = 3_000L
    }
}
