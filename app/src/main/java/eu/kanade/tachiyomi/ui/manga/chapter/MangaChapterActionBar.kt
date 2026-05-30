package eu.kanade.tachiyomi.ui.manga.chapter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
            // surfaceContainer == the theme's colorSurfaceContainer (?colorPrimaryVariant), the
            // identical flat color the BottomNavigationView uses across all themes/AMOLED.
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            // Nav-bar bottom inset is applied once as static padding on the host ComposeView
            // (MangaDetailsController.setInsets); keeping it out of this animated subtree avoids
            // the CoordinatorLayout<->Compose inset re-dispatch + AnimatedVisibility relayout loop.
            Row(
                modifier = Modifier
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
