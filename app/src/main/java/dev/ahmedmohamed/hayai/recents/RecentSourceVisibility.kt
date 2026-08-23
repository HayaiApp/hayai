package dev.ahmedmohamed.hayai.recents

import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.data.preference.Preference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

enum class RecentSurface {
    History,
    Updates,
    Mixed,
}

/**
 * Owns source-visibility semantics for every recents presentation.
 *
 * Hiding is presentation-only. It never changes library membership, chapters, history, or update
 * data. Mixed views use the union of the explicit lists so changing layouts cannot expose entries
 * hidden in either tab.
 */
class RecentSourceVisibility(
    private val hiddenInHistory: Preference<Set<String>>,
    private val hiddenInUpdates: Preference<Set<String>>,
) {
    constructor(preferences: HayaiPreferences) : this(
        preferences.hiddenSourcesInHistory,
        preferences.hiddenSourcesInUpdates,
    )

    fun includes(
        sourceId: Long,
        surface: RecentSurface,
    ): Boolean = sourceId !in hiddenSourceIds(surface)

    fun hiddenSourceIds(surface: RecentSurface): Set<Long> =
        when (surface) {
            RecentSurface.History -> hiddenInHistory.get().validSourceIds()
            RecentSurface.Updates -> hiddenInUpdates.get().validSourceIds()
            RecentSurface.Mixed -> hiddenInHistory.get().validSourceIds() + hiddenInUpdates.get().validSourceIds()
        }

    fun hiddenCount(surface: RecentSurface): Int = hiddenSourceIds(surface).size

    fun replaceHiddenSources(
        surface: RecentSurface,
        sourceIds: Set<Long>,
    ) {
        val values = sourceIds.mapTo(sortedSetOf()) { it.toString() }
        when (surface) {
            RecentSurface.History -> hiddenInHistory.set(values)
            RecentSurface.Updates -> hiddenInUpdates.set(values)
            RecentSurface.Mixed -> error("Mixed visibility is derived from History and Updates")
        }
    }

    fun hideSource(
        surface: RecentSurface,
        sourceId: Long,
    ) {
        require(surface != RecentSurface.Mixed)
        replaceHiddenSources(surface, hiddenSourceIds(surface) + sourceId)
    }

    fun changes(): Flow<Unit> =
        combine(hiddenInHistory.changes(), hiddenInUpdates.changes()) { _, _ -> Unit }

    private fun Set<String>.validSourceIds(): Set<Long> = mapNotNullTo(linkedSetOf(), String::toLongOrNull)
}
