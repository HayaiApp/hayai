package eu.kanade.tachiyomi.source

/** Compile-only extension ABI from Tsundoku b04f9a4d39aa4e99a9ca14e4bb76f38d35566998. */
interface RateLimited {
    val minimumDelayMillis: Long

    val recommendedDelayMillis: Long
        get() = minimumDelayMillis

    val recommendedPermits: Int
        get() = 1
}
