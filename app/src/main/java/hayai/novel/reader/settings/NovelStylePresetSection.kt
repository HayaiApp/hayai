package hayai.novel.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.icerock.moko.resources.compose.stringResource
import yokai.domain.ui.settings.ReaderPreferences
import yokai.i18n.MR
import yokai.presentation.theme.Size

/**
 * Saved global STYLE presets editor for the novel reader Appearance tab.
 *
 * Lists presets (tap a card to apply live), saves the current style prefs as a named preset, and
 * renames/deletes per row. Storage + apply logic lives in [NovelStylePresetManager]; this section
 * only renders and drives it. Mirrors [RegexReplacementSection]'s plain-Column layout so the caller
 * owns the scroll.
 */
@Composable
internal fun NovelStylePresetSection(prefs: ReaderPreferences) {
    val manager = remember(prefs) { NovelStylePresetManager(prefs) }
    val presetsJson by prefs.novelStylePresets.collectAsState()

    // Re-decode through the manager so the list reflects external writes (e.g. apply/save elsewhere).
    val presets = remember(presetsJson) { manager.list() }

    var showSaveDialog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<NovelStylePreset?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Size.medium, vertical = Size.small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Style,
                    contentDescription = null,
                    modifier = Modifier.padding(end = Size.small),
                )
                Text(
                    text = stringResource(MR.strings.novel_style_presets),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            IconButton(onClick = { showSaveDialog = true }) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(MR.strings.novel_save_style_preset),
                )
            }
        }

        presets.forEach { preset ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Size.tiny)
                    .clickable { manager.apply(preset) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Size.smedium),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(MR.strings.novel_style_preset_tap_to_apply),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        IconButton(onClick = { renaming = preset }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(MR.strings.edit))
                        }
                        IconButton(onClick = { manager.delete(preset.name) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.delete))
                        }
                    }
                }
            }
        }

        if (presets.isEmpty()) {
            Text(
                text = stringResource(MR.strings.novel_no_style_presets),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Size.small),
            )
        }
    }

    if (showSaveDialog) {
        PresetNameDialog(
            titleRes = MR.strings.novel_save_style_preset,
            initialName = "",
            existingNames = presets.map { it.name },
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                manager.saveCurrent(name)
                showSaveDialog = false
            },
        )
    }

    renaming?.let { preset ->
        PresetNameDialog(
            titleRes = MR.strings.novel_rename_style_preset,
            initialName = preset.name,
            existingNames = presets.map { it.name }.filterNot { it == preset.name },
            onDismiss = { renaming = null },
            onConfirm = { name ->
                manager.rename(preset.name, name)
                renaming = null
            },
        )
    }
}

@Composable
private fun PresetNameDialog(
    titleRes: dev.icerock.moko.resources.StringResource,
    initialName: String,
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val isDuplicate = existingNames.any { it.equals(trimmed, ignoreCase = true) }
    val canSave = trimmed.isNotEmpty() && !isDuplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(MR.strings.novel_style_preset_name)) },
                    singleLine = true,
                    isError = isDuplicate,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isDuplicate) {
                    Text(
                        text = stringResource(MR.strings.novel_style_preset_name_taken),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Size.tiny),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { if (canSave) onConfirm(trimmed) },
            ) {
                Text(stringResource(MR.strings.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun <T> eu.kanade.tachiyomi.core.preference.Preference<T>.collectAsState(): androidx.compose.runtime.State<T> {
    val state = remember(this) { androidx.compose.runtime.mutableStateOf(get()) }
    LaunchedEffect(this) {
        changes().collect { state.value = it }
    }
    return state
}
