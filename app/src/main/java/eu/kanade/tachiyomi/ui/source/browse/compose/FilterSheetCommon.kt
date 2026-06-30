package eu.kanade.tachiyomi.ui.source.browse.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Shared scaffolding for the source filter sheet — style tokens, the title-left/widget-right row
 * scaffold every per-type composable wraps, the trailing value chip used by Select / Sort / Group
 * / AutoComplete rows, and small utility composables (empty state, scroll bridge).
 *
 * Kept in one file so the rest of the sheet code (`FilterRowComposables.kt`,
 * `AutoCompleteFilterRow.kt`, `SourceFilterSheetContent.kt`) only contains feature-specific code.
 *
 * Everything here is `internal` so files in this package can reuse it without re-exporting.
 */

// region Style tokens — mirror PreferenceCommon so the sheet reads as part of the same family.
// One 8/12/16 scale shared by every row and screen so vertical rhythm stays consistent.

internal val FilterRowHorizontalPadding = 16.dp
internal val FilterRowVerticalPadding = 8.dp
// 48 dp is the M3 minimum touch target — every interactive row honours it.
internal val FilterRowMinHeight = 48.dp
internal val ValueChipMaxWidth = 160.dp

// endregion

// region Row scaffold — title on the left, widget on the right.

/**
 * The canonical filter row layout used by every per-type composable in the sheet. Modelled on
 * `yokai.presentation.component.preference.widget.SwitchPreferenceWidget`: full-width tap target,
 * title `bodyLarge` on the left, control on the right. No card background; sections separate via
 * the [FilterHeaderRow] label instead.
 */
@Composable
internal fun FilterPreferenceRow(
    title: String,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit,
) {
    val rowMod = Modifier
        .fillMaxWidth()
        .heightIn(min = FilterRowMinHeight)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = FilterRowHorizontalPadding, vertical = FilterRowVerticalPadding)
    Row(modifier = rowMod, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(modifier = Modifier.padding(start = 12.dp)) { trailing() }
    }
}

// endregion

// region Trailing value chip — shared by Select / Sort / Group / AutoComplete rows.

/**
 * Borderless trailing chip — `Label · Value ▾` style — used by every drill-down or pop-menu row in
 * the sheet. Colour alone communicates active/idle; active picks up the primary tint that the
 * legacy filter sheet's "Clear filters" text button used (`?attr/colorSecondary`).
 */
@Composable
internal fun ValueChip(
    value: String?,
    trailing: ImageVector,
    active: Boolean,
    constrainValueWidth: Boolean = true,
) {
    val contentColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!value.isNullOrEmpty()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (constrainValueWidth) {
                    Modifier.widthIn(max = ValueChipMaxWidth)
                } else {
                    Modifier
                },
            )
        }
        Icon(
            imageVector = trailing,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

// endregion

// region Empty state — used by every screen body that may have no items.

@Composable
internal fun FilterSheetEmptyState(icon: ImageVector, message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// endregion

// region Scroll bridge — drives sheetBehavior.isDraggable from list scroll state.

/**
 * Replicates `E2EBottomSheetDialog`'s "only drag when scrolled to top" gesture, originally wired
 * for RecyclerView. Each LazyColumn inside the sheet body opts in by calling this with its list
 * state and the host class's callback.
 */
@Composable
internal fun BridgeScrollState(
    state: LazyListState,
    onChange: ((canScrollUp: Boolean) -> Unit)?,
) {
    if (onChange == null) return
    LaunchedEffect(state, onChange) {
        snapshotFlow { state.canScrollBackward }
            .distinctUntilChanged()
            .collect { onChange(it) }
    }
}

// endregion

// region Drill top bar — back arrow + inline search field, replaces the legacy "back + title" bar.

/**
 * The top bar shown when the sheet is inside a drill-down (AutoComplete picker or Group page).
 *
 * The search input IS the title: an inline [BasicTextField] sits where the heading used to be,
 * with the filter name shown as a placeholder when no query is entered. This collapses the old
 * "back + title strip" plus the separate "search bar strip below" into one row of chrome — so
 * the search bar reads as part of the top bar instead of floating beneath it.
 *
 * Used for both the Tag picker (AutoComplete drill) and the Group page (Filter.Group drill),
 * so they look and behave identically. Reuse, not per-screen styling.
 */
@Composable
internal fun DrillSearchTopBar(
    placeholder: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSubmit: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        // Leading magnifier — makes the placeholder explicitly read as "this is a search field"
        // even before the user taps in.
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardActions = if (onSubmit != null) {
                KeyboardActions(onAny = { onSubmit() })
            } else {
                KeyboardActions.Default
            },
            keyboardOptions = KeyboardOptions(
                imeAction = if (onSubmit != null) ImeAction.Send else ImeAction.Search,
            ),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable {
                        onQueryChange("")
                        focusManager.clearFocus()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// endregion

// region Filter.Group inspection — how many of its children are non-default.

/**
 * Counts non-default children inside a [Filter.Group]. Used by [FilterGroupRow]'s trailing count
 * chip so the drill-down preview shows how many filters are active without opening it.
 */
internal fun groupActiveCount(group: Filter.Group<*>): Int = group.state.count { child ->
    when (child) {
        is Filter.CheckBox -> child.state
        is Filter.TriState -> child.state != Filter.TriState.STATE_IGNORE
        is Filter.Text -> child.state.isNotEmpty()
        is Filter.Select<*> -> child.state != 0
        else -> false
    }
}

// endregion
