package eu.kanade.tachiyomi.ui.source.browse.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.TurnedIn
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import yokai.domain.source.browse.filter.models.SavedSearch
import yokai.i18n.MR
import yokai.util.search.FuzzyMatcher

/**
 * Root composable for the redesigned source filter sheet.
 *
 * Design language mirrors the rest of the app:
 *  - Section headers ([FilterHeaderRow]) match `PreferenceGroupHeader` — `secondary` colour,
 *    `labelMedium` text.
 *  - Each filter renders as a flat full-width row (title left, control right) via
 *    [FilterPreferenceRow] — same pattern as `SwitchPreferenceWidget`, no card wrapping.
 *  - Drill-down sub-screens replace the body when the user opens a Group or the AutoComplete
 *    tag picker; the [BackHandler] returns to the main list.
 *
 * Layout invariants that keep the action bar sticky inside the peek window:
 *  - XML container is `wrap_content` so the Column sizes to its content.
 *  - The body Box is bounded by a screen-height-derived max (see [BodyMaxClamp]). With tabs
 *    (~48 dp) and action bar (~80 dp) added, the Column's natural height stays under the
 *    `peekHeight` (also derived from screen height in [ComposeSourceFilterSheet]).
 *  - The body scrolls internally when filters overflow — the action bar never moves.
 *
 * The composition mutates filter state IN PLACE on the [FilterList] passed in — matching the
 * legacy contract that [eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController.showFilters]
 * relies on. Do not substitute a UI-state copy here.
 *
 * The two `*Version` ints are change tokens — incremented by the host class when the underlying
 * data identity changes (`refreshFilters` after a reset, `refreshSavedSearches` after a save) so
 * the composition picks up the new pointer and rebuilds.
 */
@Composable
internal fun SourceFilterSheetContent(
    filters: FilterList,
    savedSearches: List<SavedSearch>,
    filterVersion: Int,
    savedSearchesVersion: Int,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onSavedSearchClicked: (Long) -> Unit,
    onDeleteSavedSearchClicked: (Long) -> Unit,
    onListScrollChange: ((canScrollUp: Boolean) -> Unit)? = null,
) {
    var selectedTab by rememberSaveable { mutableStateOf(FilterSheetTab.Filters) }
    // Drill-down navigation. The target is held by reference so back-gesture from inside a sub-
    // screen always returns to the top-level filters even if the underlying FilterList shifts
    // (reset / saved-search apply replaces the entire list).
    var drillTarget by remember(filters) { mutableStateOf<FilterDrillTarget?>(null) }
    // Search query owned by the sheet (not the drill screens). The drill top bar renders the
    // input; the drill bodies consume the query for filtering. Reset on every drill change.
    var drillQuery by rememberSaveable(drillTarget) { mutableStateOf("") }
    // Outer selection token — bumps on every drill change so main-list rows re-read filter state
    // after the user mutates something inside a drill and backs out.
    var outerSelectionVersion by remember(filters) { mutableIntStateOf(0) }
    LaunchedEffect(drillTarget) { outerSelectionVersion++ }

    BackHandler(enabled = drillTarget != null) { drillTarget = null }

    // Derive body bounds from the live screen height so the sheet adapts to device size and
    // landscape; the top bar (~48dp) + action bar (~80dp) + dividers live outside this Box.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val bodyMaxHeight = (screenHeightDp * 0.55f).dp.coerceIn(BodyMinClamp, BodyMaxClamp)
    val bodyMinHeight = (screenHeightDp * 0.25f).dp.coerceIn(160.dp, bodyMaxHeight)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SheetTopBar(
                drillTarget = drillTarget,
                drillQuery = drillQuery,
                onDrillQueryChange = { drillQuery = it },
                onBack = { drillTarget = null },
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                savedSearchCount = savedSearches.size,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = bodyMinHeight, max = bodyMaxHeight),
            ) {
                AnimatedContent(
                    targetState = drillTarget,
                    transitionSpec = {
                        val durationMs = 220
                        if (targetState != null) {
                            // Entering a drill — slide in from the right, main body slides slightly left
                            (slideInHorizontally(tween(durationMs)) { it } + fadeIn(tween(durationMs))) togetherWith
                                (slideOutHorizontally(tween(durationMs)) { -it / 6 } + fadeOut(tween(durationMs)))
                        } else {
                            // Backing out — drill slides off right, main body slides in from the left
                            (slideInHorizontally(tween(durationMs)) { -it / 6 } + fadeIn(tween(durationMs))) togetherWith
                                (slideOutHorizontally(tween(durationMs)) { it } + fadeOut(tween(durationMs)))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "drill",
                ) { target ->
                    SheetBody(
                        drillTarget = target,
                        drillQuery = drillQuery,
                        selectedTab = selectedTab,
                        filters = filters,
                        savedSearches = savedSearches,
                        filterVersion = filterVersion,
                        outerSelectionVersion = outerSelectionVersion,
                        savedSearchesVersion = savedSearchesVersion,
                        onDrillTarget = { drillTarget = it },
                        onSavedSearchClicked = onSavedSearchClicked,
                        onDeleteSavedSearchClicked = onDeleteSavedSearchClicked,
                        onListScrollChange = onListScrollChange,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FilterSheetActionBar(onReset, onSave, onApply)
        }
    }
}

internal enum class FilterSheetTab { Filters, Saved }

// Clamp bounds for the responsive body height — keep the sheet usable on tiny screens and from
// swallowing the whole display on tablets / unfolded foldables.
private val BodyMinClamp = 240.dp
private val BodyMaxClamp = 520.dp

/**
 * Where the user has drilled to inside the sheet body. Sub-screens swap the main filter list out
 * for a focused view of one filter.
 */
private sealed interface FilterDrillTarget {
    val name: String
    data class Group(val filter: Filter.Group<*>) : FilterDrillTarget {
        override val name: String get() = filter.name
    }
    data class TagList(val filter: Filter.AutoComplete) : FilterDrillTarget {
        override val name: String get() = filter.name
    }
}

// region Top bar — tabs at the top level, drill-down header when inside a sub-screen.

@Composable
private fun SheetTopBar(
    drillTarget: FilterDrillTarget?,
    drillQuery: String,
    onDrillQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    selectedTab: FilterSheetTab,
    onSelectTab: (FilterSheetTab) -> Unit,
    savedSearchCount: Int,
) {
    AnimatedContent(
        targetState = drillTarget,
        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
        label = "topbar",
    ) { target ->
        if (target == null) {
            FilterSheetTabRow(
                selected = selectedTab,
                onSelected = onSelectTab,
                savedSearchCount = savedSearchCount,
            )
        } else {
            // Submit only meaningful for AutoComplete (custom tag entry). Group filters out by
            // pattern only — no submit affordance, so the IME shows "Search" instead of "Send".
            val onSubmit: (() -> Unit)? = (target as? FilterDrillTarget.TagList)?.let { tagList ->
                {
                    val text = drillQuery.trim()
                    if (text.isNotEmpty() &&
                        FilterMutations.addAutoCompleteTag(tagList.filter, text)
                    ) {
                        onDrillQueryChange("")
                    }
                }
            }
            DrillSearchTopBar(
                placeholder = target.name,
                query = drillQuery,
                onQueryChange = onDrillQueryChange,
                onBack = onBack,
                onSubmit = onSubmit,
            )
        }
    }
}

@Composable
private fun FilterSheetTabRow(
    selected: FilterSheetTab,
    onSelected: (FilterSheetTab) -> Unit,
    savedSearchCount: Int,
) {
    // Explicit primary content color + indicator pulls the tab row out of the default
    // "everything is onSurfaceVariant" gray look. The selected tab text and the underline
    // now both pick up the theme's primary accent.
    PrimaryTabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selected.ordinal),
                color = MaterialTheme.colorScheme.primary,
            )
        },
        divider = {},
    ) {
        Tab(
            selected = selected == FilterSheetTab.Filters,
            onClick = { onSelected(FilterSheetTab.Filters) },
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            text = {
                TabLabel(
                    text = stringResource(MR.strings.filter),
                    active = selected == FilterSheetTab.Filters,
                )
            },
        )
        Tab(
            selected = selected == FilterSheetTab.Saved,
            onClick = { onSelected(FilterSheetTab.Saved) },
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TabLabel(
                        text = stringResource(MR.strings.saved_searches),
                        active = selected == FilterSheetTab.Saved,
                    )
                    if (savedSearchCount > 0) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Text(
                                text = savedSearchCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun TabLabel(text: String, active: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
    )
}

// endregion

// region Body — dispatch to filters list / drill sub-screen / saved-searches tab.

@Composable
private fun SheetBody(
    drillTarget: FilterDrillTarget?,
    drillQuery: String,
    selectedTab: FilterSheetTab,
    filters: FilterList,
    savedSearches: List<SavedSearch>,
    filterVersion: Int,
    outerSelectionVersion: Int,
    savedSearchesVersion: Int,
    onDrillTarget: (FilterDrillTarget?) -> Unit,
    onSavedSearchClicked: (Long) -> Unit,
    onDeleteSavedSearchClicked: (Long) -> Unit,
    onListScrollChange: ((canScrollUp: Boolean) -> Unit)?,
) {
    when (drillTarget) {
        is FilterDrillTarget.Group -> GroupChildrenScreen(
            group = drillTarget.filter,
            filterVersion = filterVersion,
            outerSelectionVersion = outerSelectionVersion,
            query = drillQuery,
            onDrillGroup = { onDrillTarget(FilterDrillTarget.Group(it)) },
            onListScrollChange = onListScrollChange,
        )
        is FilterDrillTarget.TagList -> AutoCompleteScreen(
            filter = drillTarget.filter,
            query = drillQuery,
            onListScrollChange = onListScrollChange,
        )
        null -> when (selectedTab) {
            FilterSheetTab.Filters -> FiltersBody(
                filters = filters,
                filterVersion = filterVersion,
                outerSelectionVersion = outerSelectionVersion,
                onDrillGroup = { onDrillTarget(FilterDrillTarget.Group(it)) },
                onDrillTags = { onDrillTarget(FilterDrillTarget.TagList(it)) },
                onListScrollChange = onListScrollChange,
            )
            FilterSheetTab.Saved -> SavedSearchesList(
                savedSearches = savedSearches,
                savedSearchesVersion = savedSearchesVersion,
                onApply = onSavedSearchClicked,
                onDelete = onDeleteSavedSearchClicked,
            )
        }
    }
}

@Composable
private fun FiltersBody(
    filters: FilterList,
    filterVersion: Int,
    outerSelectionVersion: Int,
    onDrillGroup: (Filter.Group<*>) -> Unit,
    onDrillTags: (Filter.AutoComplete) -> Unit,
    onListScrollChange: ((canScrollUp: Boolean) -> Unit)?,
) {
    val listState = rememberLazyListState()
    BridgeScrollState(listState, onListScrollChange)

    if (filters.isEmpty()) {
        FilterSheetEmptyState(
            icon = Icons.Outlined.SearchOff,
            message = stringResource(MR.strings.source_has_no_filters),
        )
        return
    }

    // Render filters in the order the source extension provides them — no client-side sorting.
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
    ) {
        itemsIndexed(
            items = filters.list,
            key = { index, _ -> "filter-$filterVersion-$index" },
        ) { _, filter ->
            FilterRow(filter, outerSelectionVersion, onDrillGroup, onDrillTags)
        }
    }
}

@Composable
private fun FilterRow(
    filter: Filter<*>,
    outerSelectionVersion: Int,
    onDrillGroup: (Filter.Group<*>) -> Unit,
    onDrillTags: (Filter.AutoComplete) -> Unit,
) {
    when (filter) {
        is Filter.Header -> FilterHeaderRow(filter)
        is Filter.Separator -> FilterSeparatorRow(filter)
        is Filter.CheckBox -> FilterCheckBoxRow(filter, outerSelectionVersion)
        is Filter.TriState -> FilterTriStateRow(filter, outerSelectionVersion)
        is Filter.Select<*> -> FilterSelectRow(filter, outerSelectionVersion)
        is Filter.Sort -> FilterSortRow(filter, outerSelectionVersion)
        is Filter.Text -> FilterTextRow(filter, outerSelectionVersion)
        is Filter.AutoComplete -> FilterAutoCompleteRow(
            filter = filter,
            outerSelectionVersion = outerSelectionVersion,
            onDrill = onDrillTags,
        )
        is Filter.Group<*> -> FilterGroupRow(
            filter = filter,
            outerSelectionVersion = outerSelectionVersion,
            onDrill = onDrillGroup,
        )
    }
}

@Composable
private fun GroupChildrenScreen(
    group: Filter.Group<*>,
    filterVersion: Int,
    outerSelectionVersion: Int,
    query: String,
    onDrillGroup: (Filter.Group<*>) -> Unit,
    onListScrollChange: ((canScrollUp: Boolean) -> Unit)?,
) {
    val listState = rememberLazyListState()
    BridgeScrollState(listState, onListScrollChange)

    val children = group.state
    if (children.isEmpty()) {
        FilterSheetEmptyState(
            icon = Icons.Outlined.SearchOff,
            message = stringResource(MR.strings.source_has_no_filters),
        )
        return
    }

    val visibleChildren = remember(children, query, filterVersion) {
        if (query.isBlank()) {
            children.toList()
        } else {
            // Group child counts are small (rarely > 50) so a single fuzzy pass is fine here —
            // no debounce / substring fast path needed.
            children.asSequence()
                .map { it to FuzzyMatcher.score(query, (it as Filter<*>).name) }
                .filter { it.second >= GroupChildFuzzyThreshold }
                .sortedByDescending { it.second }
                .map { it.first }
                .toList()
        }
    }

    // Scroll back to the top whenever the query changes so the user isn't left mid-list.
    LaunchedEffect(query) {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
    ) {
        itemsIndexed(
            items = visibleChildren,
            // Stable key (index + child name) keeps rows in place across selection / query changes
            // — flipping a checkbox doesn't tear down the row, and the index disambiguates
            // empty-named structural children (Separator) that would otherwise collide.
            key = { index, child -> "$index-${(child as Filter<*>).name}" },
        ) { _, child ->
            // Group children mirror top-level rendering. Nested Group/Sort children drill or sort
            // in place instead of being dropped; structural Header/Separator render as-is.
            when (child) {
                is Filter.Header -> FilterHeaderRow(child)
                is Filter.Separator -> FilterSeparatorRow(child)
                is Filter.CheckBox -> FilterCheckBoxRow(child, outerSelectionVersion)
                is Filter.TriState -> FilterTriStateRow(child, outerSelectionVersion)
                is Filter.Select<*> -> FilterSelectRow(child, outerSelectionVersion)
                is Filter.Sort -> FilterSortRow(child, outerSelectionVersion)
                is Filter.Text -> FilterTextRow(child, outerSelectionVersion)
                is Filter.Group<*> -> FilterGroupRow(
                    filter = child,
                    outerSelectionVersion = outerSelectionVersion,
                    onDrill = onDrillGroup,
                )
                else -> Unit
            }
        }
    }
}

private const val GroupChildFuzzyThreshold = 60

// endregion

// region Saved searches

@Composable
private fun SavedSearchesList(
    savedSearches: List<SavedSearch>,
    savedSearchesVersion: Int,
    onApply: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (savedSearches.isEmpty()) {
        FilterSheetEmptyState(
            icon = Icons.Outlined.TurnedIn,
            message = stringResource(MR.strings.no_saved_searches),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
    ) {
        items(
            items = savedSearches,
            key = { "saved-$savedSearchesVersion-${it.id}" },
        ) { search ->
            SavedSearchRow(
                search = search,
                onApply = { onApply(search.id) },
                onDelete = { onDelete(search.id) },
            )
        }
    }
}

@Composable
private fun SavedSearchRow(
    search: SavedSearch,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onApply)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.TurnedIn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = search.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(MR.strings.save_search_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// endregion

// region Action bar — M3 Expressive button group on the left, primary CTA on the right.
//
// Reset and Save are visually grouped via shape-joining (outer corners fully rounded, inner
// corners near-square) with only a 2-dp gap so they read as one piece. Each segment keeps its
// own intent colour:
//   Reset → errorContainer  — soft-danger "clear" action, no shouting red
//   Save  → secondaryContainer — neutral constructive action with bookmark icon
//   Filter (apply) → standalone primary Button — the prominent CTA, separated by weight(1f).
// All filled; colour does the hierarchy work (tonal pair reads quieter than the primary CTA).

@Composable
private fun FilterSheetActionBar(
    onReset: () -> Unit,
    onSave: () -> Unit,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupedTonalButton(
            onClick = onReset,
            shape = ButtonGroupStartShape,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            label = stringResource(MR.strings.reset),
        )
        GroupedTonalButton(
            onClick = onSave,
            shape = ButtonGroupEndShape,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            icon = Icons.Outlined.TurnedIn,
            label = stringResource(MR.strings.save),
        )
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onApply,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = stringResource(MR.strings.filter),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun GroupedTonalButton(
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        shape = shape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** First segment of the connected button group — outer (start) corners fully rounded. */
private val ButtonGroupStartShape = RoundedCornerShape(
    topStartPercent = 50,
    bottomStartPercent = 50,
    topEndPercent = 12,
    bottomEndPercent = 12,
)

/** Last segment of the connected button group — outer (end) corners fully rounded. */
private val ButtonGroupEndShape = RoundedCornerShape(
    topStartPercent = 12,
    bottomStartPercent = 12,
    topEndPercent = 50,
    bottomEndPercent = 50,
)

// endregion
