package hayai.novel.reader.quote

import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.launch
import yokai.i18n.MR
import yokai.presentation.theme.Size
import yokai.presentation.theme.YokaiTheme

/**
 * Bottom sheet that lists, edits, deletes, and adds quotes for the active novel.
 *
 * Adapted from Tsundoku's `QuotesSheet` (`tsundoku/.../presentation/reader/appbars/QuotesSheet.kt`)
 * with the drag-reorder and expand-collapse interactions trimmed to keep this initial port small.
 * Reorder is acceptable as a follow-up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotesSheet(
    quotes: List<Quote>,
    onDismiss: () -> Unit,
    onQuoteAdd: (String) -> Unit,
    onQuoteUpdate: (Quote) -> Unit,
    onQuoteDelete: (Quote) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var quoteToDelete by remember { mutableStateOf<Quote?>(null) }
    var selectedQuote by remember { mutableStateOf<Quote?>(null) }
    var editingQuote by remember { mutableStateOf<Quote?>(null) }
    var editedContent by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var newQuoteContent by remember { mutableStateOf("") }

    val clipboardManager = LocalClipboardManager.current

    if (quoteToDelete != null) {
        val q = quoteToDelete!!
        AlertDialog(
            onDismissRequest = { quoteToDelete = null },
            title = { Text(stringResource(MR.strings.novel_quote_delete_title)) },
            text = { Text(stringResource(MR.strings.novel_quote_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onQuoteDelete(q)
                    quoteToDelete = null
                }) { Text(stringResource(MR.strings.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { quoteToDelete = null }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    if (selectedQuote != null) {
        val q = selectedQuote!!
        val originalCopyText = q.originalContent?.takeIf { it != q.content }
            ?.let { stringResource(MR.strings.novel_quote_original_format, it) }
            .orEmpty()
        val originalCopySuffix = originalCopyText.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
        val quoteCopyText = stringResource(
            MR.strings.novel_quote_copy_format,
            q.content,
            originalCopySuffix,
            q.novelName,
            q.chapterName,
        )
        AlertDialog(
            onDismissRequest = { selectedQuote = null },
            title = { Text(q.chapterName) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(MR.strings.novel_quote_displayed), style = MaterialTheme.typography.labelMedium)
                    Text(q.content, style = MaterialTheme.typography.bodyLarge)
                    q.originalContent?.takeIf { it != q.content }?.let { original ->
                        Text(
                            text = stringResource(MR.strings.novel_quote_original),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = Size.medium),
                        )
                        Text(original, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingQuote = q
                    editedContent = q.content
                    selectedQuote = null
                }) { Text(stringResource(MR.strings.edit)) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(Size.small)) {
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(quoteCopyText))
                        selectedQuote = null
                    }) { Text(stringResource(MR.strings.action_copy)) }
                    TextButton(onClick = { selectedQuote = null }) {
                        Text(stringResource(MR.strings.action_close))
                    }
                }
            },
        )
    }

    if (editingQuote != null) {
        val q = editingQuote!!
        AlertDialog(
            onDismissRequest = { editingQuote = null },
            title = { Text(stringResource(MR.strings.novel_quote_edit)) },
            text = {
                OutlinedTextField(
                    value = editedContent,
                    onValueChange = { editedContent = it },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editedContent.isNotBlank()) {
                        onQuoteUpdate(q.copy(content = editedContent.trim()))
                    }
                    editingQuote = null
                }) { Text(stringResource(MR.strings.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingQuote = null }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(MR.strings.novel_quote_add)) },
            text = {
                OutlinedTextField(
                    value = newQuoteContent,
                    onValueChange = { newQuoteContent = it },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newQuoteContent.isNotBlank()) {
                        onQuoteAdd(newQuoteContent.trim())
                        newQuoteContent = ""
                    }
                    showAddDialog = false
                }) { Text(stringResource(MR.strings.save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    newQuoteContent = ""
                    showAddDialog = false
                }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
        // Drop the default drag handle and trim the top padding so the sheet starts flush
        // with the screen edge instead of leaving a chunk of empty surface.
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Size.large, vertical = Size.small),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.FormatQuote,
                        contentDescription = null,
                        modifier = Modifier.padding(end = Size.small),
                    )
                    Text(
                        text = stringResource(MR.strings.novel_quotes),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(MR.strings.novel_quote_add))
                }
            }

            if (quotes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Size.extraLarge),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(MR.strings.novel_quotes_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .padding(horizontal = Size.medium),
                ) {
                    itemsIndexed(quotes) { _, quote ->
                        val originalCopyText = quote.originalContent?.takeIf { it != quote.content }
                            ?.let { stringResource(MR.strings.novel_quote_original_format, it) }
                            .orEmpty()
                        val originalCopySuffix = originalCopyText.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
                        val quoteCopyText = stringResource(
                            MR.strings.novel_quote_copy_format,
                            quote.content,
                            originalCopySuffix,
                            quote.novelName,
                            quote.chapterName,
                        )
                        QuoteCard(
                            quote = quote,
                            onClick = { selectedQuote = quote },
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(quoteCopyText))
                            },
                            onEdit = {
                                editingQuote = quote
                                editedContent = quote.content
                            },
                            onDelete = { quoteToDelete = quote },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteCard(
    quote: Quote,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Size.tiny),
    ) {
        Column(modifier = Modifier.padding(Size.smedium)) {
            Text(
                text = quote.content,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = quote.chapterName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Size.tiny),
            )
            if (quote.originalContent != null && quote.originalContent != quote.content) {
                Text(
                    text = stringResource(MR.strings.novel_quote_original_saved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Size.tiny),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(MR.strings.action_copy))
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(MR.strings.edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.delete))
                }
            }
            // Tap-to-view-detail target on the row (excludes the action buttons above).
            Text(
                text = stringResource(MR.strings.novel_quote_view),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = Size.tiny)
                    .clickable(onClick = onClick),
            )
        }
    }
}

/**
 * Mounts a [QuotesSheet] backed by a [QuoteManager] for the active novel and removes itself
 * from the view hierarchy on dismissal.
 */
fun showQuotesSheet(
    activity: ReaderActivity,
    novelId: Long,
    novelName: String,
    chapterName: String,
) {
    val manager = QuoteManager(activity)
    val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
    val composeView = ComposeView(activity)
    composeView.setContent {
        YokaiTheme {
            val scope = rememberCoroutineScope()
            var quotes by remember { mutableStateOf(emptyList<Quote>()) }

            LaunchedEffect(novelId) {
                quotes = manager.getQuotes(novelId)
            }

            QuotesSheet(
                quotes = quotes,
                onDismiss = {
                    (composeView.parent as? ViewGroup)?.removeView(composeView)
                },
                onQuoteAdd = { content ->
                    scope.launch {
                        val q = Quote.create(novelName, chapterName, content)
                        manager.addQuote(novelId, q)
                        quotes = manager.getQuotes(novelId)
                    }
                },
                onQuoteUpdate = { updated ->
                    scope.launch {
                        manager.updateQuote(novelId, updated)
                        quotes = manager.getQuotes(novelId)
                    }
                },
                onQuoteDelete = { q ->
                    scope.launch {
                        manager.removeQuote(novelId, q.id)
                        quotes = manager.getQuotes(novelId)
                    }
                },
            )
        }
    }
    rootView.addView(composeView)
}
