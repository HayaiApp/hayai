package dev.ahmedmohamed.hayai.novel.download

import android.os.SystemClock
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

internal fun interface NovelDownloadMonotonicClock {
    fun nowMillis(): Long
}

internal fun interface NovelDownloadSleeper {
    suspend fun sleep(delayMillis: Long)
}

internal class NovelDownloadPacer(
    private val clock: NovelDownloadMonotonicClock = NovelDownloadMonotonicClock(SystemClock::elapsedRealtime),
    private val sleeper: NovelDownloadSleeper = NovelDownloadSleeper { delay(it) },
) {
    private val sources = ConcurrentHashMap<Long, SourcePacingState>()

    suspend fun awaitPermit(
        sourceId: Long,
        delayMillis: Long,
        isUnmetered: Boolean,
    ) {
        if (isUnmetered || delayMillis <= 0L) return

        val state = sources.getOrPut(sourceId, ::SourcePacingState)
        state.mutex.withLock {
            state.lastPermitAtMillis?.let { lastPermit ->
                val remaining = (lastPermit + delayMillis - clock.nowMillis()).coerceAtLeast(0L)
                if (remaining > 0L) sleeper.sleep(remaining)
            }
            state.lastPermitAtMillis = clock.nowMillis()
        }
    }

    private class SourcePacingState {
        val mutex = Mutex()
        var lastPermitAtMillis: Long? = null
    }
}

internal object NovelDownloadThrottle {
    private val preferences by lazy { NovelDownloadPreferences(Injekt.get<PreferenceStore>()) }
    private val pacer = NovelDownloadPacer()

    suspend fun awaitPermit(source: Source) {
        pacer.awaitPermit(
            sourceId = source.id,
            delayMillis = preferences.delayMillisFor(source.id),
            isUnmetered = source is UnmeteredSource,
        )
    }
}
