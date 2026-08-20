package dev.ahmedmohamed.hayai.recents

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import eu.kanade.tachiyomi.source.SourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class RecentSourceVisibilityEditor(
    private val visibility: RecentSourceVisibility =
        RecentSourceVisibility(HayaiPreferences(Injekt.get<PreferenceStore>())),
    private val sourceManager: SourceManager = Injekt.get(),
    private val database: DatabaseHelper = Injekt.get(),
) {
    fun label(
        context: Context,
        surface: RecentSurface,
    ): String {
        val count = visibility.hiddenCount(surface)
        return if (count == 0) {
            context.getString(R.string.recents_hidden_sources)
        } else {
            context.getString(R.string.recents_hidden_sources_count, count)
        }
    }

    fun show(
        context: Context,
        scope: CoroutineScope,
        surface: RecentSurface,
        onChanged: () -> Unit,
    ) {
        require(surface != RecentSurface.Mixed) { "Mixed visibility must be edited through History or Updates" }
        scope.launch {
            val choices = loadChoices(surface)
            if (choices.isEmpty()) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.recents_hidden_sources)
                    .setMessage(R.string.recents_no_sources_available)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            val selected = visibility.hiddenSourceIds(surface).toMutableSet()
            val checked = BooleanArray(choices.size) { choices[it].sourceId in selected }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.recents_hidden_sources)
                .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { _, index, isChecked ->
                    val sourceId = choices[index].sourceId
                    if (isChecked) selected += sourceId else selected -= sourceId
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    visibility.replaceHiddenSources(surface, selected)
                    onChanged()
                }
                .setNeutralButton(R.string.clear) { _, _ ->
                    visibility.replaceHiddenSources(surface, emptySet())
                    onChanged()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private suspend fun loadChoices(surface: RecentSurface): List<SourceChoice> =
        withContext(Dispatchers.IO) {
            val installedIds = sourceManager.getCatalogueSources().mapTo(mutableSetOf()) { it.id }
            val storedIds = visibility.hiddenSourceIds(surface)
            val databaseIds = runCatching { sourceIdsInDatabase() }.getOrDefault(emptySet())
            val choices =
                (installedIds + storedIds + databaseIds).map { sourceId ->
                    val source = sourceManager.getOrStub(sourceId)
                    val language = source.lang.takeIf { it.isNotBlank() }
                    val baseLabel = if (language == null) source.name else "${source.name} · $language"
                    SourceChoice(sourceId, baseLabel)
                }
            val duplicateLabels = choices.groupingBy { it.label.lowercase() }.eachCount()
            choices
                .map { choice ->
                    if (duplicateLabels.getValue(choice.label.lowercase()) > 1) {
                        choice.copy(label = "${choice.label} (${choice.sourceId})")
                    } else {
                        choice
                    }
                }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SourceChoice::label).thenBy(SourceChoice::sourceId))
        }

    private fun sourceIdsInDatabase(): Set<Long> =
        database
            .lowLevel()
            .rawQuery(RawQuery.builder().query("SELECT DISTINCT source FROM mangas").build())
            .use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getLong(0))
                }
            }

    private data class SourceChoice(
        val sourceId: Long,
        val label: String,
    )
}
