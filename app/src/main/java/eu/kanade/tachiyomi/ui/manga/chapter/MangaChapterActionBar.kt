package eu.kanade.tachiyomi.ui.manga.chapter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR

/**
 * State snapshot driving the chapter multi-select bottom action bar. Each flag toggles which
 * contextual action is offered, mirroring mihon's MangaBottomActionMenu visibility rules.
 */
data class ChapterActionBarState(
    val visible: Boolean = false,
    val showBookmark: Boolean = false,
    val showRemoveBookmark: Boolean = false,
    val showMarkAsRead: Boolean = false,
    val showMarkAsUnread: Boolean = false,
    val showMarkPreviousAsRead: Boolean = false,
    val showDownload: Boolean = false,
    val showDelete: Boolean = false,
)

/** Callbacks for the bottom action bar; each maps to an existing controller/presenter op. */
class ChapterActionBarHandlers(
    val onBookmark: () -> Unit,
    val onRemoveBookmark: () -> Unit,
    val onMarkRead: () -> Unit,
    val onMarkUnread: () -> Unit,
    val onMarkPreviousRead: () -> Unit,
    val onDownload: () -> Unit,
    val onDelete: () -> Unit,
    val onSelectAll: () -> Unit,
    val onInvertSelection: () -> Unit,
)

@Composable
fun MangaChapterActionBar(
    state: ChapterActionBarState,
    handlers: ChapterActionBarHandlers,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.visible,
        enter = expandVertically(expandFrom = Alignment.Bottom),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (state.showBookmark) {
                    ActionButton(MR.strings.action_bookmark, Icons.Outlined.Bookmark, handlers.onBookmark)
                }
                if (state.showRemoveBookmark) {
                    ActionButton(MR.strings.action_remove_bookmark, Icons.Outlined.BookmarkRemove, handlers.onRemoveBookmark)
                }
                if (state.showMarkAsRead) {
                    ActionButton(MR.strings.mark_as_read, Icons.Outlined.DoneAll, handlers.onMarkRead)
                }
                if (state.showMarkAsUnread) {
                    ActionButton(MR.strings.mark_as_unread, Icons.Outlined.RemoveDone, handlers.onMarkUnread)
                }
                if (state.showMarkPreviousAsRead) {
                    ActionButton(MR.strings.mark_previous_as_read, Icons.Outlined.Done, handlers.onMarkPreviousRead)
                }
                if (state.showDownload) {
                    ActionButton(MR.strings.download, Icons.Outlined.Download, handlers.onDownload)
                }
                if (state.showDelete) {
                    ActionButton(MR.strings.remove_downloads, Icons.Outlined.Delete, handlers.onDelete)
                }
                OverflowButton(handlers.onSelectAll, handlers.onInvertSelection)
            }
        }
    }
}

@Composable
private fun RowScope.ActionButton(
    title: StringResource,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val label = stringResource(title)
    Box(
        modifier = Modifier
            .size(48.dp)
            .weight(1f)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = ripple(bounded = false),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = label)
    }
}

@Composable
private fun RowScope.OverflowButton(
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(MR.strings.action_menu_overflow_description)
    Box(
        modifier = Modifier
            .size(48.dp)
            .weight(1f)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = ripple(bounded = false),
                onClick = { expanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = label)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.select_all)) },
                leadingIcon = { Icon(Icons.Outlined.SelectAll, null) },
                onClick = {
                    expanded = false
                    onSelectAll()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.select_inverse)) },
                leadingIcon = { Icon(Icons.Outlined.RemoveDone, null) },
                onClick = {
                    expanded = false
                    onInvertSelection()
                },
            )
        }
    }
}
