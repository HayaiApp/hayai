package eu.kanade.tachiyomi.ui.source.browse.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.North
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.South
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.source.model.Filter
import yokai.i18n.MR

/**
 * Per-type filter row composables. Each row mutates the supplied [Filter] instance IN PLACE
 * through [FilterMutations] — the legacy contract that
 * `BrowseSourceController.showFilters()` snapshots and compares. Do not work on copies here.
 *
 * Shared scaffolding (row layout, value chip, header / separator / empty state) lives in
 * [FilterSheetCommon] so this file stays focused on per-type rendering.
 */

// region CheckBox — Switch on the right, matches Hayai's SwitchPreferenceWidget.

@Composable
internal fun FilterCheckBoxRow(filter: Filter.CheckBox, stateVersion: Int) {
    var checked by remember(filter, stateVersion) { mutableStateOf(filter.state) }
    FilterPreferenceRow(
        title = filter.name,
        onClick = {
            FilterMutations.toggleCheckbox(filter)
            checked = filter.state
        },
        trailing = {
            // Plain Switch (no colour override) so it matches Hayai's SwitchPreferenceWidget and
            // every other toggle in the app — the Expressive theme supplies the colours.
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        },
    )
}

// endregion

// region TriState — connected 3-segment selector (Off / + / −). All three states always visible.

@Composable
internal fun FilterTriStateRow(filter: Filter.TriState, stateVersion: Int) {
    var state by remember(filter, stateVersion) { mutableIntStateOf(filter.state) }
    FilterPreferenceRow(
        title = filter.name,
        onClick = null,
        trailing = {
            TriStateSegments(
                state = state,
                onChange = { target ->
                    FilterMutations.setTriStateExact(filter, target)
                    state = filter.state
                },
            )
        },
    )
}

@Composable
private fun TriStateSegments(state: Int, onChange: (Int) -> Unit) {
    // Connected button-group shape pattern: outer corners fully rounded, inner edges square so
    // the three segments read as one piece. Colour + icon communicate state — neutral (—),
    // include (+), exclude (⊘) — so the three states are unmistakable.
    Row(verticalAlignment = Alignment.CenterVertically) {
        TriStateSegment(
            selected = state == Filter.TriState.STATE_IGNORE,
            icon = Icons.Outlined.Remove,
            activeContainer = MaterialTheme.colorScheme.secondaryContainer,
            activeContent = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = SegmentShapeStart,
            contentDescription = stringResource(MR.strings.ignore),
            onClick = { onChange(Filter.TriState.STATE_IGNORE) },
        )
        TriStateSegment(
            selected = state == Filter.TriState.STATE_INCLUDE,
            icon = Icons.Outlined.Add,
            activeContainer = MaterialTheme.colorScheme.primary,
            activeContent = MaterialTheme.colorScheme.onPrimary,
            shape = SegmentShapeMiddle,
            contentDescription = stringResource(MR.strings.include),
            onClick = { onChange(Filter.TriState.STATE_INCLUDE) },
        )
        TriStateSegment(
            selected = state == Filter.TriState.STATE_EXCLUDE,
            icon = Icons.Outlined.Block,
            activeContainer = MaterialTheme.colorScheme.error,
            activeContent = MaterialTheme.colorScheme.onError,
            shape = SegmentShapeEnd,
            contentDescription = stringResource(MR.strings.exclude),
            onClick = { onChange(Filter.TriState.STATE_EXCLUDE) },
        )
    }
}

@Composable
private fun TriStateSegment(
    selected: Boolean,
    activeContainer: Color,
    activeContent: Color,
    shape: Shape,
    contentDescription: String,
    onClick: () -> Unit,
    icon: ImageVector,
) {
    val container by animateColorAsState(
        targetValue = if (selected) activeContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "tri-seg-container",
    )
    val content by animateColorAsState(
        targetValue = if (selected) activeContent else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "tri-seg-content",
    )
    Surface(
        onClick = onClick,
        shape = shape,
        color = container,
        contentColor = content,
        // Selected segment is bordered with its own tone so the active state reads even for
        // colour-blind users; idle segments stay borderless.
        border = if (selected) BorderStroke(1.dp, activeContent.copy(alpha = 0.35f)) else null,
        modifier = Modifier.size(width = 42.dp, height = 36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private val SegmentShapeStart = RoundedCornerShape(
    topStartPercent = 50,
    bottomStartPercent = 50,
    topEndPercent = 0,
    bottomEndPercent = 0,
)
private val SegmentShapeMiddle = RoundedCornerShape(0)
private val SegmentShapeEnd = RoundedCornerShape(
    topStartPercent = 0,
    bottomStartPercent = 0,
    topEndPercent = 50,
    bottomEndPercent = 50,
)

// endregion

// region Select — value chip + chevron on the right; anchored DropdownMenu.

@Composable
internal fun FilterSelectRow(filter: Filter.Select<*>, stateVersion: Int) {
    var expanded by remember { mutableStateOf(false) }
    var selectedIndex by remember(filter, stateVersion) { mutableIntStateOf(filter.state) }
    val displayValue = filter.values.getOrNull(selectedIndex)?.toString().orEmpty()
    FilterPreferenceRow(
        title = filter.name,
        onClick = { expanded = true },
        trailing = {
            Box {
                ValueChip(
                    value = displayValue,
                    trailing = Icons.Outlined.ExpandMore,
                    active = selectedIndex != 0,
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    shape = MenuShape,
                    // Match the sheet's surface — the popup shadow + rounded corners do the
                    // "lift" instead of an elevated grayish surfaceContainer tone.
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    filter.values.forEachIndexed { index, value ->
                        DropdownMenuItem(
                            text = { DropdownItemText(value.toString(), selected = index == selectedIndex) },
                            onClick = {
                                FilterMutations.setSelect(filter, index)
                                selectedIndex = filter.state
                                expanded = false
                            },
                        )
                    }
                }
            }
        },
    )
}

// endregion

// region Sort — same row as Select but the trailing arrow shows direction.

@Composable
internal fun FilterSortRow(filter: Filter.Sort, stateVersion: Int) {
    var expanded by remember { mutableStateOf(false) }
    var state by remember(filter, stateVersion) { mutableStateOf(filter.state) }
    val selectedIndex = state?.index
    val ascending = state?.ascending == true
    val displayValue = selectedIndex?.let { filter.values.getOrNull(it) }.orEmpty()
    FilterPreferenceRow(
        title = filter.name,
        onClick = { expanded = true },
        trailing = {
            Box {
                ValueChip(
                    value = displayValue,
                    trailing = when {
                        selectedIndex == null -> Icons.Outlined.ExpandMore
                        ascending -> Icons.Outlined.North
                        else -> Icons.Outlined.South
                    },
                    active = selectedIndex != null,
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    shape = MenuShape,
                    // Match the sheet's surface — the popup shadow + rounded corners do the
                    // "lift" instead of an elevated grayish surfaceContainer tone.
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    filter.values.forEachIndexed { index, name ->
                        val isSelected = selectedIndex == index
                        DropdownMenuItem(
                            text = { DropdownItemText(name, selected = isSelected) },
                            trailingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = if (ascending) Icons.Outlined.North else Icons.Outlined.South,
                                        contentDescription = stringResource(
                                            if (ascending) MR.strings.sort_ascending else MR.strings.sort_descending,
                                        ),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                FilterMutations.toggleSort(filter, index)
                                state = filter.state
                                expanded = false
                            },
                        )
                    }
                }
            }
        },
    )
}

// endregion

// region Shared dropdown helpers — keep Select / Sort menus visually identical.

private val MenuShape = RoundedCornerShape(16.dp)

@Composable
private fun DropdownItemText(text: String, selected: Boolean) {
    Text(
        text = text,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (selected) FontWeight.SemiBold else null,
    )
}

// endregion

// region Group — drill-down row + active-children pills.

@Composable
internal fun FilterGroupRow(
    filter: Filter.Group<*>,
    outerSelectionVersion: Int,
    onDrill: (Filter.Group<*>) -> Unit,
) {
    var localVersion by remember(filter) { mutableIntStateOf(0) }
    val children = remember(filter, outerSelectionVersion, localVersion) { filter.state.toList() }
    val activePills = remember(children) { computeGroupActivePills(children) }

    Column(modifier = Modifier.fillMaxWidth()) {
        FilterPreferenceRow(
            title = filter.name,
            onClick = { onDrill(filter) },
            trailing = {
                ValueChip(
                    value = if (activePills.isNotEmpty()) "${activePills.size}" else null,
                    trailing = Icons.Outlined.ChevronRight,
                    active = activePills.isNotEmpty(),
                )
            },
        )
        if (activePills.isNotEmpty()) {
            GroupActivePillsRow(
                pills = activePills,
                onChange = { localVersion++ },
            )
        }
    }
}

/**
 * One active child of a [Filter.Group]. The label rendered into the pill, the visual state
 * (Included / Excluded), an optional toggle (for TriState only), and the remove action that
 * resets the child to its default state.
 */
private data class GroupActivePill(
    val label: String,
    val state: AutoCompleteTagState,
    val onToggle: (() -> Unit)?,
    val onRemove: () -> Unit,
)

/**
 * Walks a Group's children and produces one [GroupActivePill] per non-default child.
 *
 * For TriState children we get full include / exclude semantics — pill tap flips the two,
 * × resets to ignore. For CheckBox / Select / Text only the "active" (included) state exists,
 * so the pill body tap behaves the same as × (remove).
 */
// Parameter type is `List<*>` because `Filter.Group<V>.state` is `List<V>` and V resolves to
// `out Any?` at this call site — Kotlin can't narrow it to `Filter<*>` without a cast. The
// `when (child)` branches smart-cast each element back to a concrete Filter subtype.
private fun computeGroupActivePills(children: List<*>): List<GroupActivePill> =
    children.mapNotNull { child ->
        when (child) {
            is Filter.CheckBox -> if (child.state) {
                GroupActivePill(
                    label = child.name,
                    state = AutoCompleteTagState.Included,
                    onToggle = null,
                    onRemove = { FilterMutations.toggleCheckbox(child) },
                )
            } else null
            is Filter.TriState -> when (child.state) {
                Filter.TriState.STATE_INCLUDE -> GroupActivePill(
                    label = child.name,
                    state = AutoCompleteTagState.Included,
                    onToggle = { FilterMutations.setTriStateExact(child, Filter.TriState.STATE_EXCLUDE) },
                    onRemove = { FilterMutations.setTriStateExact(child, Filter.TriState.STATE_IGNORE) },
                )
                Filter.TriState.STATE_EXCLUDE -> GroupActivePill(
                    label = child.name,
                    state = AutoCompleteTagState.Excluded,
                    onToggle = { FilterMutations.setTriStateExact(child, Filter.TriState.STATE_INCLUDE) },
                    onRemove = { FilterMutations.setTriStateExact(child, Filter.TriState.STATE_IGNORE) },
                )
                else -> null
            }
            is Filter.Select<*> -> if (child.state != 0) {
                val value = child.values.getOrNull(child.state)?.toString().orEmpty()
                GroupActivePill(
                    label = if (value.isEmpty()) child.name else "${child.name}: $value",
                    state = AutoCompleteTagState.Included,
                    onToggle = null,
                    onRemove = { FilterMutations.setSelect(child, 0) },
                )
            } else null
            is Filter.Text -> if (child.state.isNotEmpty()) {
                GroupActivePill(
                    label = "${child.name}: ${child.state}",
                    state = AutoCompleteTagState.Included,
                    onToggle = null,
                    onRemove = { FilterMutations.setText(child, "") },
                )
            } else null
            else -> null
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupActivePillsRow(
    pills: List<GroupActivePill>,
    onChange: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        pills.forEach { pill ->
            TagPill(
                label = pill.label,
                state = pill.state,
                onClick = pill.onToggle?.let { toggle -> { toggle(); onChange() } },
                onRemove = { pill.onRemove(); onChange() },
            )
        }
    }
}

// endregion

// region Section header + Separator + Text (full-width).

@Composable
internal fun FilterHeaderRow(filter: Filter.Header) {
    // Mirrors yokai.presentation.component.preference.widget.PreferenceGroupHeader so source-
    // provided notices (e-hentai's "WILL IGNORE OTHER PARAMETERS!") read as a subdued group
    // label introducing the next item. labelLarge + SemiBold + secondary makes it clearly a
    // section header rather than another filter title.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = 16.dp,
                bottom = 8.dp,
                start = FilterRowHorizontalPadding,
                end = FilterRowHorizontalPadding,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = filter.name,
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
    }
}

@Composable
internal fun FilterSeparatorRow(@Suppress("UNUSED_PARAMETER") filter: Filter.Separator) {
    // Inset to align with the row content padding so the divider reads as a grouping rule rather
    // than a full-bleed sheet edge.
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = FilterRowHorizontalPadding, vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
internal fun FilterTextRow(filter: Filter.Text, stateVersion: Int) {
    var value by remember(filter, stateVersion) { mutableStateOf(filter.state) }
    // Every keystroke commits to the filter state — matches the legacy contract where the
    // source consumes the latest text without an explicit "submit" gesture.
    LaunchedEffect(value) {
        if (filter.state != value) FilterMutations.setText(filter, value)
    }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        // Label (not placeholder) so the field name stays visible once the user types — the field
        // keeps its identity in a list of several text filters.
        label = {
            Text(
                text = filter.name,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            // Faded primary instead of the default `outline` gray so the border picks up the
            // theme accent even when the field isn't focused.
            unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FilterRowHorizontalPadding, vertical = FilterRowVerticalPadding),
    )
}

// endregion
