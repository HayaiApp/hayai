package yokai.presentation.settings.screen.data

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.hippo.unifile.UniFile
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.backup.BackupFileValidator.Results
import eu.kanade.tachiyomi.data.backup.create.BackupCreatorJob
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.util.system.toast
import yokai.domain.DialogHostState
import yokai.i18n.MR
import yokai.presentation.component.LabeledCheckbox
import android.R as AR

suspend fun DialogHostState.awaitRestoreBackup(
    context: Context,
    uri: Uri,
    pair: Pair<Results?, Exception?>,
): Unit = dialog { cont ->
    val (results, e) = pair
    if (results != null) {
        var message = stringResource(MR.strings.restore_content_full)
        if (results.missingSources.isNotEmpty()) {
            message += "\n\n${stringResource(MR.strings.restore_missing_sources)}\n${
                results.missingSources.joinToString(
                    "\n",
                ) { "- $it" }
            }"
        }
        if (results.missingTrackers.isNotEmpty()) {
            message += "\n\n${stringResource(MR.strings.restore_missing_trackers)}\n${
                results.missingTrackers.joinToString(
                    "\n",
                ) { "- $it" }
            }"
        }

        var options by mutableStateOf(RestoreOptions())

        AlertDialog(
            onDismissRequest = { cont.cancel() },
            confirmButton = {
                TextButton(
                    enabled = options.canRestore(),
                    onClick = {
                        context.toast(MR.strings.restoring_backup)
                        BackupRestoreJob.start(context, uri, options)
                        cont.cancel()
                    },
                ) {
                    Text(text = stringResource(MR.strings.restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(text = stringResource(AR.string.cancel))
                }
            },
            title = { Text(text = stringResource(MR.strings.restore_backup)) },
            text = {
                Box {
                    val state = rememberLazyListState()
                    LazyColumn(state = state) {
                        item {
                            Text(text = message)
                        }
                        RestoreOptions.getEntries().forEach { option ->
                            item {
                                LabeledCheckbox(
                                    label = stringResource(option.label),
                                    checked = option.getter(options),
                                    onCheckedChange = { options = option.setter(options, it) },
                                    enabled = option.enabled(options),
                                )
                            }
                        }
                    }
                }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = { cont.cancel() },
            confirmButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(text = stringResource(AR.string.cancel))
                }
            },
            title = { Text(text = stringResource(MR.strings.invalid_backup_file)) },
            text = { e?.message?.let { Text(text = it) } }
        )
    }
}

suspend fun DialogHostState.awaitCreateBackup(
    context: Context,
    uri: Uri,
): Unit = dialog { cont ->
    var options by remember { mutableStateOf(BackupOptions()) }

    AlertDialog(
        onDismissRequest = { cont.cancel() },
        confirmButton = {
            TextButton(onClick = {
                val actualUri =
                    UniFile.fromUri(context, uri)?.createFile(Backup.getBackupFilename())?.uri ?: return@TextButton
                context.toast(MR.strings.creating_backup)
                BackupCreatorJob.startNow(context, actualUri, options)
                cont.cancel()
            }) {
                Text(stringResource(MR.strings.create))
            }
        },
        dismissButton = {
            TextButton(onClick = { cont.cancel() }) {
                Text(stringResource(MR.strings.cancel))
            }
        },
        title = { Text(text = stringResource(MR.strings.create_backup)) },
        text = {
            Box {
                val state = rememberLazyListState()
                LazyColumn(state = state) {
                    BackupOptions.getEntries().forEach { option ->
                        item {
                            LabeledCheckbox(
                                label = stringResource(option.label),
                                checked = option.getter(options),
                                onCheckedChange = { options = option.setter(options, it) },
                                enabled = option.enabled(options),
                            )
                        }
                    }
                }
            }
        },
    )
}
