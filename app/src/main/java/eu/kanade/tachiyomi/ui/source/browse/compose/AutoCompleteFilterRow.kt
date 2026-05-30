package eu.kanade.tachiyomi.ui.source.browse.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import yokai.util.search.FuzzyMatcher

/**
 * AutoComplete handling for the source filter sheet.
 *
 *  - [FilterAutoCompleteRow] — row in the main filter list. Shows include/exclude pills for the
 *    currently-selected tags directly beneath the row. Tap pill body cycles include⇄exclude
 *    (if the source declares `-` in `validPrefixes`); trailing × removes.
 *
 *  - [AutoCompleteScreen] — full sheet-body picker. The search bar lives in the drill top bar
 *    above this screen, so the query is passed in from the caller. Pinned-selected tags appear
 *    first; the pinning snapshot is taken once per query change so tapping a tag inside the
 *    picker never reorders rows.
 *
 * State mutation is routed through [FilterMutations] so the in-place contract
 * `BrowseSourceController.showFilters()` snapshots and compares stays intact.
 */

// region Main-list row — title row + selected-tag pills below.

@Composable
internal fun FilterAutoCompleteRow(
    filter: Filter.AutoComplete,
    outerSelectionVersion: Int,
    onDrill: (Filter.AutoComplete) -> Unit,
) {
    var localVersion by remember(filter) { mutableIntStateOf(0) }
    // outer version bumps when the user drills out of any picker so this row picks up changes
    // made inside the picker. local version bumps for in-row pill mutations.
    val active = remember(filter, outerSelectionVersion, localVersion) { filter.state.toList() }
    Column(modifier = Modifier.fillMaxWidth()) {
        FilterPreferenceRow(
            title = filter.name,
            onClick = { onDrill(filter) },
            trailing = {
                ValueChip(
                    value = if (active.isNotEmpty()) active.size.toString() else null,
                    trailing = Icons.Outlined.ChevronRight,
                    active = active.isNotEmpty(),
                    constrainValueWidth = false,
                )
            },
        )
        if (active.isNotEmpty()) {
            SelectedTagPills(
                filter = filter,
                selectedState = active,
                onChange = { localVersion++ },
            )
        }
    }
}

// endregion

// region Selected-tag pills — main-list row only. Picker drops these in favour of pinned rows.

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedTagPills(
    filter: Filter.AutoComplete,
    selectedState: List<String>,
    onChange: () -> Unit,
) {
    val supportsExclude = "-" in filter.validPrefixes
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Iterate the raw selection order — NOT partitioned by include/exclude — so toggling a
        // pill's state never repositions it within the row. The mutator replaces in place.
        selectedState.forEach { entry ->
            val isExcluded = entry.startsWith("-")
            val baseTag = if (isExcluded) entry.removePrefix("-") else entry
            val state = if (isExcluded) AutoCompleteTagState.Excluded else AutoCompleteTagState.Included
            TagPill(
                label = baseTag,
                state = state,
                onClick = when {
                    isExcluded -> {
                        {
                            FilterMutations.setAutoCompleteTagState(filter, baseTag, AutoCompleteTagState.Included)
                            onChange()
                        }
                    }
                    supportsExclude -> {
                        {
                            FilterMutations.setAutoCompleteTagState(filter, baseTag, AutoCompleteTagState.Excluded)
                            onChange()
                        }
                    }
                    else -> null
                },
                onRemove = {
                    FilterMutations.setAutoCompleteTagState(filter, baseTag, AutoCompleteTagState.Off)
                    onChange()
                },
            )
        }
    }
}

/**
 * Shared pill component — used both by [SelectedTagPills] (AutoComplete) and by
 * `FilterGroupRow`'s active-children summary (Filter.Group). Same look, same gestures.
 *
 * Background is the full primary / error tone (not the container variants) so the pill reads as
 * an unambiguous "active filter" badge against the sheet's surface.
 */
@Composable
internal fun TagPill(
    label: String,
    state: AutoCompleteTagState,
    onClick: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    val container: Color
    val content: Color
    val leading: ImageVector
    when (state) {
        AutoCompleteTagState.Included -> {
            container = MaterialTheme.colorScheme.primary
            content = MaterialTheme.colorScheme.onPrimary
            leading = Icons.Outlined.Add
        }
        AutoCompleteTagState.Excluded -> {
            container = MaterialTheme.colorScheme.error
            content = MaterialTheme.colorScheme.onError
            leading = Icons.Outlined.Block
        }
        AutoCompleteTagState.Off -> return
    }
    Surface(
        onClick = onClick ?: onRemove,
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier.heightIn(min = 32.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(
                imageVector = leading,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// endregion

// region Drill page — pinned-selected tag list. Search bar lives in the drill top bar above.

@Composable
internal fun AutoCompleteScreen(
    filter: Filter.AutoComplete,
    query: String,
    onListScrollChange: ((canScrollUp: Boolean) -> Unit)?,
) {
    // selectionVersion bumps on every cycle. The outer state list snapshot rebuilds via this key
    // so per-row colour updates ride the recomposition. LazyColumn keys are kept stable (tag
    // name only) so rows don't get disposed/recreated — the row stays in place and only repaints.
    var selectionVersion by remember(filter) { mutableIntStateOf(0) }

    val visibleTags by visibleTagsState(filter, query)
    val currentState = remember(filter, selectionVersion) { filter.state.toList() }
    // Pinned ordering is snapshotted from filter.state once per `visibleTags` change (which only
    // shifts on query change) — tapping a tag to include/exclude does NOT recompute this list.
    val orderedTags = remember(visibleTags, filter) {
        val selectedBase = filter.state.map { it.removePrefix("-") }.toSet()
        val (pinned, rest) = visibleTags.partition { it in selectedBase }
        pinned + rest
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AutoCompleteTagList(
            tags = orderedTags,
            state = currentState,
            supportsExclude = "-" in filter.validPrefixes,
            query = query,
            onCycle = { tag ->
                FilterMutations.cycleAutoCompleteTag(filter, tag)
                selectionVersion++
            },
            onListScrollChange = onListScrollChange,
        )
    }
}

/**
 * Filters [Filter.AutoComplete.values] against [query] off the main thread.
 *
 * Perf characteristics for sources with thousands of tags (e-hentai ~6k):
 *  - Coroutine debounce of [SearchDebounceMs] — `produceState` cancels the in-flight coroutine
 *    on every keystroke; the [delay] doesn't fire its result if the user types again first.
 *  - Substring fast path: `String.contains(ignoreCase=true)` is O(n*q) where q is query length,
 *    runs in single-digit ms for 6k tags. Matches are sorted by where the query appears (prefix
 *    hits before mid-string hits) — that's good enough as a relevance signal for tag names.
 *  - Fuzzy fallback only fires if substring returned zero matches. That's where the slow
 *    [FuzzyMatcher.score] (FuzzyWuzzy `partialRatio`) runs — only on typo'd queries.
 *
 * Empty `query` yields the full list (minus skipped tags).
 */
@Composable
private fun visibleTagsState(
    filter: Filter.AutoComplete,
    query: String,
) = produceState(initialValue = filter.values, key1 = filter, key2 = query) {
    val prefix = filter.validPrefixes.find { p -> query.startsWith(p) }
    val stripped = (if (prefix != null) query.removePrefix(prefix) else query).trim()
    if (stripped.isEmpty()) {
        value = filter.values.filter { it !in filter.skipAutoFillTags }
        return@produceState
    }
    // Wait a beat so keystrokes don't each spawn a fuzzy pass. Cancellation propagates if the
    // user types again before this completes.
    delay(SearchDebounceMs)
    withContext(Dispatchers.Default) {
        val baseList = filter.values.filter { it !in filter.skipAutoFillTags }
        val substring = baseList.filter { it.contains(stripped, ignoreCase = true) }
        value = if (substring.isNotEmpty()) {
            // Sort by where the match starts — prefix matches at index 0 surface first.
            substring.sortedBy { it.indexOf(stripped, ignoreCase = true) }
        } else {
            // No substring hit — probably a typo. Slow fuzzy fallback over the full list.
            baseList.asSequence()
                .map { it to FuzzyMatcher.score(stripped, it) }
                .filter { it.second >= FuzzyTagThreshold }
                .sortedByDescending { it.second }
                .map { it.first }
                .toList()
        }
    }
}

private const val SearchDebounceMs = 150L

// Lower than the conventional 70 because tag names are short — a forgiving cutoff catches
// substring queries like "elf" → "long-elven-hair" without surfacing noise.
private const val FuzzyTagThreshold = 60

@Composable
private fun AutoCompleteTagList(
    tags: List<String>,
    state: List<String>,
    supportsExclude: Boolean,
    query: String,
    onCycle: (String) -> Unit,
    onListScrollChange: ((canScrollUp: Boolean) -> Unit)?,
) {
    if (tags.isEmpty()) return
    val listState = rememberLazyListState()
    BridgeScrollState(listState, onListScrollChange)
    // Reset scroll position whenever the query changes — otherwise the user is left mid-list
    // looking at irrelevant entries after filtering.
    LaunchedEffect(query) {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            listState.scrollToItem(0)
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(items = tags, key = { it }) { tag ->
            val tagState = tagStateFor(tag, state, supportsExclude)
            AutoCompleteTagRow(
                tag = tag,
                state = tagState,
                onClick = { onCycle(tag) },
            )
        }
    }
}

private fun tagStateFor(
    tag: String,
    state: List<String>,
    supportsExclude: Boolean,
): AutoCompleteTagState = when {
    tag in state -> AutoCompleteTagState.Included
    supportsExclude && "-$tag" in state -> AutoCompleteTagState.Excluded
    else -> AutoCompleteTagState.Off
}

@Composable
private fun AutoCompleteTagRow(
    tag: String,
    state: AutoCompleteTagState,
    onClick: () -> Unit,
) {
    val visual = tagRowVisual(state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .background(visual.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.bodyMedium,
            color = visual.contentColor,
            fontWeight = if (state == AutoCompleteTagState.Off) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        visual.icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = visual.contentColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private data class TagRowVisual(
    val background: Color,
    val contentColor: Color,
    val icon: ImageVector?,
)

@Composable
private fun tagRowVisual(state: AutoCompleteTagState): TagRowVisual = when (state) {
    AutoCompleteTagState.Included -> TagRowVisual(
        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.primary,
        icon = Icons.Outlined.Add,
    )
    AutoCompleteTagState.Excluded -> TagRowVisual(
        background = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.error,
        icon = Icons.Outlined.Block,
    )
    AutoCompleteTagState.Off -> TagRowVisual(
        background = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        icon = null,
    )
}

// endregion
