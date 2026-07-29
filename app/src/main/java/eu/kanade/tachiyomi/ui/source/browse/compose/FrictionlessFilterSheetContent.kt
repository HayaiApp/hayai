package eu.kanade.tachiyomi.ui.source.browse.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import yokai.domain.source.browse.filter.ActiveConstraint
import yokai.domain.source.browse.filter.ConstraintSemantic
import yokai.domain.source.browse.filter.FilterKind
import yokai.domain.source.browse.filter.FilterPath
import yokai.domain.source.browse.filter.FilterValueSnapshot
import yokai.domain.source.browse.filter.displayValue
import yokai.i18n.MR
import yokai.util.search.FuzzyMatcher

@Composable
internal fun FrictionlessFilterSheetContent(
    holder: FilterSheetStateHolder,
    initialPath: FilterPath? = null,
    onDone: () -> Unit,
    onSave: () -> Unit,
    onLoadPreset: (Long) -> Unit,
    onDeletePreset: (Long) -> Unit,
    onListScrollChange: ((Boolean) -> Unit)? = null,
) {
    val uiState by holder.state.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var drillPath by rememberSaveable { mutableStateOf(initialPath?.indices) }
    val drillNode = drillPath?.let { findNode(uiState.nodes, FilterPath(it)) }
    val configuration = LocalConfiguration.current
    val twoPane = configuration.screenWidthDp >= 840 && configuration.screenHeightDp >= 480

    BackHandler(enabled = drillNode != null && !twoPane) { drillPath = null }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 960.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FilterSheetHeader(
                activeCount = uiState.activeConstraints.size,
                savedSearches = uiState.savedSearches,
                onLoadPreset = onLoadPreset,
                onDeletePreset = onDeletePreset,
            )
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.strings.clear))
                        }
                    }
                } else {
                    null
                },
                placeholder = { Text(stringResource(MR.strings.filter_find)) },
            )
            if (uiState.savedSearches.isNotEmpty()) {
                QuickPresets(uiState.savedSearches, onLoadPreset)
            }
            if (uiState.activeConstraints.isNotEmpty()) {
                ActiveSummary(uiState.activeConstraints, holder::removeConstraint)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Box(modifier = Modifier.weight(1f)) {
                if (searchQuery.isNotBlank()) {
                    FilterSearchResults(
                        nodes = uiState.nodes,
                        query = searchQuery,
                        holder = holder,
                        onOpen = { drillPath = it.path.indices },
                        onListScrollChange = onListScrollChange,
                    )
                } else if (twoPane) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        FilterNodeList(
                            nodes = uiState.nodes,
                            holder = holder,
                            onOpen = { drillPath = it.path.indices },
                            modifier = Modifier.weight(0.44f),
                            onListScrollChange = onListScrollChange,
                        )
                        VerticalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        if (drillNode != null) {
                            FilterDetail(
                                node = drillNode,
                                holder = holder,
                                onOpen = { drillPath = it.path.indices },
                                modifier = Modifier.weight(0.56f),
                                onListScrollChange = onListScrollChange,
                            )
                        } else {
                            FilterSheetEmptyState(
                                icon = Icons.Outlined.Search,
                                message = stringResource(MR.strings.filter_find),
                            )
                        }
                    }
                } else if (drillNode != null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        DrillHeader(drillNode.name) { drillPath = null }
                        FilterDetail(
                            node = drillNode,
                            holder = holder,
                            onOpen = { drillPath = it.path.indices },
                            modifier = Modifier.weight(1f),
                            onListScrollChange = onListScrollChange,
                        )
                    }
                } else {
                    FilterNodeList(
                        nodes = uiState.nodes,
                        holder = holder,
                        onOpen = { drillPath = it.path.indices },
                        modifier = Modifier.fillMaxSize(),
                        onListScrollChange = onListScrollChange,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = holder::reset) {
                    Text(stringResource(MR.strings.reset))
                }
                FilledTonalButton(onClick = onSave) {
                    Text(stringResource(MR.strings.save))
                }
                Button(onClick = onDone) {
                    Text(stringResource(MR.strings.filter_done))
                }
            }
        }
    }
}

@Composable
private fun FilterSheetHeader(
    activeCount: Int,
    savedSearches: List<yokai.domain.source.browse.filter.models.SavedSearch>,
    onLoadPreset: (Long) -> Unit,
    onDeletePreset: (Long) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(MR.strings.filter_refine),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (activeCount > 0) {
            Badge(modifier = Modifier.padding(end = 4.dp)) { Text(activeCount.toString()) }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(MR.strings.filter_manage_presets))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (savedSearches.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.saved_searches)) },
                        onClick = { menuExpanded = false },
                        enabled = false,
                    )
                }
                savedSearches.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { onDeletePreset(preset.id) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(MR.strings.remove))
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onLoadPreset(preset.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickPresets(
    searches: List<yokai.domain.source.browse.filter.models.SavedSearch>,
    onLoadPreset: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(MR.strings.filter_quick_presets),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(searches, key = { it.id }) { preset ->
                AssistChip(
                    onClick = { onLoadPreset(preset.id) },
                    label = { Text(preset.name, maxLines = 1) },
                    leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun ActiveSummary(
    constraints: List<ActiveConstraint>,
    onRemove: (yokai.domain.source.browse.filter.ConstraintRemoval) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(MR.strings.filter_active),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(constraints, key = { "${it.path}-${it.value}-${it.semantic}" }) { constraint ->
                InputChip(
                    selected = true,
                    onClick = { onRemove(constraint.removal) },
                    label = { Text(constraint.chipText(), maxLines = 1) },
                    trailingIcon = { Icon(Icons.Outlined.Close, stringResource(MR.strings.remove), Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun DrillHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FilterNodeList(
    nodes: List<FilterUiNode>,
    holder: FilterSheetStateHolder,
    onOpen: (FilterUiNode) -> Unit,
    modifier: Modifier,
    onListScrollChange: ((Boolean) -> Unit)?,
) {
    val listState = rememberLazyListState()
    BridgeScrollState(listState, onListScrollChange)
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(nodes, key = { it.path.indices.joinToString(".") }) { node ->
            FilterNodeRow(node, holder, onOpen)
        }
    }
}

@Composable
private fun FilterNodeRow(
    node: FilterUiNode,
    holder: FilterSheetStateHolder,
    onOpen: (FilterUiNode) -> Unit,
) {
    when (node.kind) {
        FilterKind.HEADER -> Text(
            text = node.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        )
        FilterKind.SEPARATOR -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        FilterKind.CHECKBOX -> FilterPreferenceRow(
            title = node.name,
            onClick = { holder.toggleCheckbox(node.path) },
        ) {
            val checked = (node.value as FilterValueSnapshot.Checked).value
            Switch(checked = checked, onCheckedChange = { holder.toggleCheckbox(node.path) })
        }
        FilterKind.TRI_STATE -> TriStateRow(node, holder)
        FilterKind.SELECT -> ChoiceRow(node, holder)
        FilterKind.SORT -> SortRow(node, holder)
        FilterKind.TEXT -> TextFilterRow(node, holder)
        FilterKind.GROUP, FilterKind.AUTO_COMPLETE -> FilterPreferenceRow(
            title = node.name,
            onClick = { onOpen(node) },
        ) {
            val value = when (val state = node.value) {
                is FilterValueSnapshot.AutoComplete -> state.values.size.takeIf { it > 0 }?.toString()
                is FilterValueSnapshot.Group -> activeChildCount(node).takeIf { it > 0 }?.toString()
                else -> null
            }
            ValueChip(value, Icons.Outlined.KeyboardArrowRight, node.isActive)
        }
    }
}

@Composable
private fun TriStateRow(node: FilterUiNode, holder: FilterSheetStateHolder) {
    val state = (node.value as FilterValueSnapshot.TriState).value
    val labels = listOf(
        stringResource(MR.strings.filter_neutral),
        stringResource(MR.strings.include),
        stringResource(MR.strings.exclude),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(node.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 6.dp))
        MultiChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    checked = state == index,
                    onCheckedChange = { holder.setTriState(node.path, index) },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            role = Role.RadioButton
                            stateDescription = label
                        },
                    icon = {
                        if (state == index) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp))
                    },
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun ChoiceRow(node: FilterUiNode, holder: FilterSheetStateHolder) {
    var expanded by remember { mutableStateOf(false) }
    val selected = node.value as FilterValueSnapshot.Index
    Box {
        FilterPreferenceRow(node.name, { expanded = true }) {
            ValueChip(selected.label, Icons.Outlined.KeyboardArrowRight, node.isActive)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            node.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = if (option.index == selected.index) {
                        { Icon(Icons.Outlined.Check, null) }
                    } else {
                        null
                    },
                    onClick = {
                        holder.setSelect(node.path, option.index)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SortRow(node: FilterUiNode, holder: FilterSheetStateHolder) {
    var expanded by remember { mutableStateOf(false) }
    val selected = node.value as FilterValueSnapshot.Sort
    Box {
        FilterPreferenceRow(node.name, { expanded = true }) {
            ValueChip(selected.displayValue(), Icons.Outlined.KeyboardArrowRight, node.isActive)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            node.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = if (option.index == selected.index) {
                        { Icon(Icons.Outlined.Check, null) }
                    } else {
                        null
                    },
                    onClick = {
                        holder.toggleSort(node.path, option.index)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TextFilterRow(node: FilterUiNode, holder: FilterSheetStateHolder) {
    val value = (node.value as FilterValueSnapshot.Text).value
    OutlinedTextField(
        value = value,
        onValueChange = { holder.setText(node.path, it) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        label = { Text(node.name) },
        singleLine = true,
    )
}

@Composable
private fun FilterDetail(
    node: FilterUiNode,
    holder: FilterSheetStateHolder,
    onOpen: (FilterUiNode) -> Unit,
    modifier: Modifier,
    onListScrollChange: ((Boolean) -> Unit)?,
) {
    when (node.kind) {
        FilterKind.GROUP -> FilterNodeList(node.children, holder, onOpen, modifier, onListScrollChange)
        FilterKind.AUTO_COMPLETE -> AutoCompleteDetail(node, holder, modifier, onListScrollChange)
        else -> Box(modifier) { FilterNodeRow(node, holder, onOpen) }
    }
}

@Composable
private fun AutoCompleteDetail(
    node: FilterUiNode,
    holder: FilterSheetStateHolder,
    modifier: Modifier,
    onListScrollChange: ((Boolean) -> Unit)?,
) {
    var query by rememberSaveable(node.path.indices) { mutableStateOf("") }
    val state = (node.value as FilterValueSnapshot.AutoComplete).values
    val visibleOptions by produceState(initialValue = node.options.take(80), node.options, query) {
        delay(150)
        value = withContext(Dispatchers.Default) {
            if (query.isBlank()) {
                node.options.take(80)
            } else {
                node.options.asSequence()
                    .map { it to searchScore(query, it.label) }
                    .filter { it.second >= 60 }
                    .sortedByDescending { it.second }
                    .take(120)
                    .map { it.first }
                    .toList()
            }
        }
    }
    val listState = rememberLazyListState()
    BridgeScrollState(listState, onListScrollChange)

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            singleLine = true,
            label = { Text(node.hint.ifBlank { node.name }) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (query.isNotBlank()) {
                {
                    IconButton(
                        onClick = {
                            if (holder.addAutoComplete(node.path, query)) query = ""
                        },
                    ) { Icon(Icons.Outlined.Check, contentDescription = stringResource(MR.strings.include)) }
                }
            } else {
                null
            },
        )
        if (state.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.forEach { selected ->
                    InputChip(
                        selected = true,
                        onClick = { holder.removeAutoComplete(node.path, selected) },
                        label = { Text(selected) },
                        trailingIcon = { Icon(Icons.Outlined.Close, stringResource(MR.strings.remove), Modifier.size(18.dp)) },
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(visibleOptions, key = { it.label }) { option ->
                AutoCompleteOption(node, option.label, state, holder)
            }
        }
    }
}

@Composable
private fun AutoCompleteOption(
    node: FilterUiNode,
    value: String,
    selected: List<String>,
    holder: FilterSheetStateHolder,
) {
    val supportsExclude = "-" in node.validPrefixes
    val tagState = when {
        value in selected -> AutoCompleteTagState.Included
        "-$value" in selected -> AutoCompleteTagState.Excluded
        else -> AutoCompleteTagState.Off
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        AutoCompleteStateButtons(node.path, value, tagState, supportsExclude, holder)
    }
}

@Composable
private fun AutoCompleteStateButtons(
    path: FilterPath,
    value: String,
    state: AutoCompleteTagState,
    supportsExclude: Boolean,
    holder: FilterSheetStateHolder,
) {
    MultiChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = 230.dp)) {
        val choices = buildList {
            add(AutoCompleteTagState.Off to stringResource(MR.strings.filter_neutral))
            add(AutoCompleteTagState.Included to stringResource(MR.strings.include))
            if (supportsExclude) add(AutoCompleteTagState.Excluded to stringResource(MR.strings.exclude))
        }
        choices.forEachIndexed { index, (choice, label) ->
            SegmentedButton(
                checked = state == choice,
                onCheckedChange = { holder.setAutoComplete(path, value, choice) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                modifier = Modifier.semantics {
                    role = Role.RadioButton
                    contentDescription = "$value, $label"
                    stateDescription = label
                },
                icon = {},
            ) { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        }
    }
}

private data class SearchHit(
    val node: FilterUiNode,
    val breadcrumb: List<String>,
    val option: FilterUiOption?,
    val score: Int,
)

@Composable
private fun FilterSearchResults(
    nodes: List<FilterUiNode>,
    query: String,
    holder: FilterSheetStateHolder,
    onOpen: (FilterUiNode) -> Unit,
    onListScrollChange: ((Boolean) -> Unit)?,
) {
    val hits by produceState(initialValue = emptyList<SearchHit>(), nodes, query) {
        delay(120)
        value = withContext(Dispatchers.Default) { searchNodes(nodes, query) }
    }
    val listState = rememberLazyListState()
    BridgeScrollState(listState, onListScrollChange)
    if (hits.isEmpty()) {
        FilterSheetEmptyState(Icons.Outlined.SearchOff, stringResource(MR.strings.filter_no_matches))
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(hits, key = { "${it.node.path.indices}-${it.option?.index}" }) { hit ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = hit.breadcrumb.joinToString(" › "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hit.option != null) {
                    SearchOptionRow(hit, holder)
                } else {
                    FilterNodeRow(hit.node, holder, onOpen)
                }
            }
        }
    }
}

@Composable
private fun SearchOptionRow(hit: SearchHit, holder: FilterSheetStateHolder) {
    val option = hit.option ?: return
    when (hit.node.kind) {
        FilterKind.AUTO_COMPLETE -> AutoCompleteOption(
            node = hit.node,
            value = option.label,
            selected = (hit.node.value as FilterValueSnapshot.AutoComplete).values,
            holder = holder,
        )
        else -> FilterPreferenceRow(option.label, {
            when (hit.node.kind) {
                FilterKind.SELECT -> holder.setSelect(hit.node.path, option.index)
                FilterKind.SORT -> holder.toggleSort(hit.node.path, option.index)
                else -> Unit
            }
        }) {
            val selected = when (val value = hit.node.value) {
                is FilterValueSnapshot.Index -> value.index == option.index
                is FilterValueSnapshot.Sort -> value.index == option.index
                else -> false
            }
            Checkbox(
                checked = selected,
                onCheckedChange = {
                    when (hit.node.kind) {
                        FilterKind.SELECT -> holder.setSelect(hit.node.path, option.index)
                        FilterKind.SORT -> holder.toggleSort(hit.node.path, option.index)
                        else -> Unit
                    }
                },
            )
        }
    }
}

private fun searchNodes(nodes: List<FilterUiNode>, query: String): List<SearchHit> = buildList {
    fun visit(children: List<FilterUiNode>, parents: List<String>) {
        children.forEach { node ->
            val breadcrumb = parents + node.name
            val nodeScore = searchScore(query, node.name)
            if (node.kind !in setOf(FilterKind.HEADER, FilterKind.SEPARATOR) && nodeScore >= 60) {
                add(SearchHit(node, breadcrumb, null, nodeScore))
            }
            node.options.forEach { option ->
                val score = searchScore(query, option.label)
                if (score >= 60) add(SearchHit(node, breadcrumb + option.label, option, score + 2))
            }
            visit(node.children, breadcrumb)
        }
    }
    visit(nodes, emptyList())
}.sortedByDescending(SearchHit::score).take(160)

private fun searchScore(query: String, candidate: String): Int {
    if (candidate.contains(query, ignoreCase = true)) return 100
    return FuzzyMatcher.score(query, candidate)
}

private fun findNode(nodes: List<FilterUiNode>, path: FilterPath): FilterUiNode? {
    var children = nodes
    var current: FilterUiNode? = null
    path.indices.forEach { index ->
        current = children.firstOrNull { it.path.indices.lastOrNull() == index } ?: return null
        children = current.children
    }
    return current
}

private fun activeChildCount(node: FilterUiNode): Int = node.children.sumOf { child ->
    if (child.children.isNotEmpty()) activeChildCount(child) else if (child.isActive) 1 else 0
}

private fun ActiveConstraint.chipText(): String = when (semantic) {
    ConstraintSemantic.QUERY -> value
    ConstraintSemantic.INCLUDE -> "+$value"
    ConstraintSemantic.EXCLUDE -> if (value.startsWith("-")) value else "−$value"
    else -> "$label: $value"
}
