package eu.kanade.tachiyomi.ui.manga.chapter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.delay
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
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                var confirmingIndex by remember { mutableIntStateOf(-1) }
                LaunchedEffect(confirmingIndex) {
                    if (confirmingIndex >= 0) {
                        delay(1_000)
                        confirmingIndex = -1
                    }
                }
                val onLongClickItem: (Int) -> Unit = { index -> confirmingIndex = index }
                if (state.showBookmark) {
                    ActionButton(
                        MR.strings.action_bookmark,
                        Icons.Outlined.BookmarkAdd,
                        confirmingIndex == 0,
                        { onLongClickItem(0) },
                        handlers.onBookmark,
                    )
                }
                if (state.showRemoveBookmark) {
                    ActionButton(
                        MR.strings.action_remove_bookmark,
                        Icons.Outlined.BookmarkRemove,
                        confirmingIndex == 1,
                        { onLongClickItem(1) },
                        handlers.onRemoveBookmark,
                    )
                }
                if (state.showMarkAsRead) {
                    ActionButton(
                        MR.strings.mark_as_read,
                        Icons.Outlined.DoneAll,
                        confirmingIndex == 2,
                        { onLongClickItem(2) },
                        handlers.onMarkRead,
                    )
                }
                if (state.showMarkAsUnread) {
                    ActionButton(
                        MR.strings.mark_as_unread,
                        Icons.Outlined.RemoveDone,
                        confirmingIndex == 3,
                        { onLongClickItem(3) },
                        handlers.onMarkUnread,
                    )
                }
                if (state.showMarkPreviousAsRead) {
                    ActionButton(
                        MR.strings.mark_previous_as_read,
                        ImageVector.vectorResource(R.drawable.ic_page_previous_outline_24dp),
                        confirmingIndex == 4,
                        { onLongClickItem(4) },
                        handlers.onMarkPreviousRead,
                    )
                }
                if (state.showDownload) {
                    ActionButton(
                        MR.strings.download,
                        Icons.Outlined.Download,
                        confirmingIndex == 5,
                        { onLongClickItem(5) },
                        handlers.onDownload,
                    )
                }
                if (state.showDelete) {
                    ActionButton(
                        MR.strings.remove_downloads,
                        Icons.Outlined.Delete,
                        confirmingIndex == 6,
                        { onLongClickItem(6) },
                        handlers.onDelete,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ActionButton(
    title: StringResource,
    icon: ImageVector,
    toConfirm: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val label = stringResource(title)
    val haptic = LocalHapticFeedback.current
    val animatedWeight by animateFloatAsState(
        targetValue = if (toConfirm) 2f else 1f,
        label = "weight",
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .weight(animatedWeight)
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = ripple(bounded = false),
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(imageVector = icon, contentDescription = label)
            AnimatedVisibility(
                visible = toConfirm,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Text(
                    text = label,
                    overflow = TextOverflow.Visible,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
