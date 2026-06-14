package hayai.novel.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.presentation.reader.settings.RegexReplacement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import yokai.domain.ui.settings.ReaderPreferences
import yokai.i18n.MR
import yokai.presentation.theme.Size

/**
 * Regex find-and-replace editor for the novel reader Advanced tab.
 *
 * Adapted from Tsundoku's `RegexReplacementSection` + `RegexEditDialog`
 * (`tsundoku/.../presentation/reader/settings/NovelPage.kt:1010-1319`).
 * Storage shape (`pref_novel_regex_replacements`: JSON-encoded `List<RegexReplacement>`)
 * matches Tsundoku verbatim; the apply pipeline lives in
 * `NovelViewerTextUtils.applyRegexReplacements`.
 */
@Composable
internal fun RegexReplacementSection(prefs: ReaderPreferences) {
    val regexJson by prefs.novelRegexReplacements.collectAsState()
    val normalize by prefs.novelTextNormalize.collectAsState()
    val aggressive by prefs.novelTextAggressiveCleanup.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<Pair<Int, RegexReplacement>?>(null) }

    val rules = remember(regexJson) {
        try {
            Json.decodeFromString<List<RegexReplacement>>(regexJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

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
                    imageVector = Icons.Outlined.FindReplace,
                    contentDescription = null,
                    modifier = Modifier.padding(end = Size.small),
                )
                Text(
                    text = stringResource(MR.strings.novel_find_replace),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(MR.strings.novel_add_rule))
            }
        }

        PreprocessSwitchRow(
            title = stringResource(MR.strings.novel_text_normalize_title),
            subtitle = stringResource(MR.strings.novel_text_normalize_summary),
            checked = normalize,
            onChange = { prefs.novelTextNormalize.set(it) },
        )
        PreprocessSwitchRow(
            title = stringResource(MR.strings.novel_text_aggressive_cleanup_title),
            subtitle = stringResource(MR.strings.novel_text_aggressive_cleanup_summary),
            checked = aggressive,
            onChange = { prefs.novelTextAggressiveCleanup.set(it) },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = Size.small))

        rules.forEachIndexed { index, rule ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Size.tiny)
                    .clickable {
                        val updated = rules.toMutableList().apply {
                            this[index] = this[index].copy(enabled = !this[index].enabled)
                        }
                        prefs.novelRegexReplacements.set(Json.encodeToString(updated))
                    },
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
                            text = rule.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (rule.enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        val kindLabel = stringResource(
                            if (rule.isRegex) MR.strings.novel_rule_kind_regex else MR.strings.novel_rule_kind_text,
                        )
                        val statusLabel = stringResource(
                            if (rule.enabled) MR.strings.novel_rule_enabled else MR.strings.novel_rule_disabled,
                        )
                        Text(
                            text = "$kindLabel • $statusLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (rule.enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        val replacementDisplay =
                            rule.replacement.ifEmpty { stringResource(MR.strings.novel_rule_remove) }
                        Text(
                            text = "/${rule.pattern}/ → $replacementDisplay",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        IconButton(onClick = { editingRule = index to rule }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(MR.strings.edit))
                        }
                        IconButton(onClick = {
                            val updated = rules.toMutableList().apply { removeAt(index) }
                            prefs.novelRegexReplacements.set(Json.encodeToString(updated))
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.delete))
                        }
                    }
                }
            }
        }

        if (rules.isEmpty()) {
            Text(
                text = stringResource(MR.strings.novel_no_rules),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Size.small),
            )
        }
    }

    if (showAddDialog || editingRule != null) {
        RegexEditDialog(
            initialRule = editingRule?.second,
            onDismiss = {
                showAddDialog = false
                editingRule = null
            },
            onConfirm = { rule ->
                val updated = rules.toMutableList()
                if (editingRule != null) {
                    updated[editingRule!!.first] = rule
                } else {
                    updated.add(rule)
                }
                prefs.novelRegexReplacements.set(Json.encodeToString(updated))
                showAddDialog = false
                editingRule = null
            },
        )
    }
}

@Composable
private fun PreprocessSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = Size.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RegexEditDialog(
    initialRule: RegexReplacement?,
    onDismiss: () -> Unit,
    onConfirm: (RegexReplacement) -> Unit,
) {
    var title by remember(initialRule) { mutableStateOf(initialRule?.title ?: "") }
    var pattern by remember(initialRule) { mutableStateOf(initialRule?.pattern ?: "") }
    var replacement by remember(initialRule) { mutableStateOf(initialRule?.replacement ?: "") }
    var isRegex by remember(initialRule) { mutableStateOf(initialRule?.isRegex ?: true) }
    var testInput by remember(initialRule) { mutableStateOf("") }
    var testOutput by remember(initialRule) { mutableStateOf<String?>(null) }
    var testError by remember(initialRule) { mutableStateOf<String?>(null) }

    val patternEmptyText = stringResource(MR.strings.novel_rule_pattern_empty)
    val invalidRegexText = stringResource(MR.strings.novel_rule_invalid_regex)
    val invalidRegexFormatTemplate = stringResource(MR.strings.novel_rule_invalid_regex_format, "%s")
    val outputFormatTemplate = stringResource(MR.strings.novel_rule_output_format, "%s")
    val errorFormatTemplate = stringResource(MR.strings.novel_rule_error_format, "%s")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initialRule != null) MR.strings.novel_edit_rule else MR.strings.novel_add_rule))
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(MR.strings.novel_rule_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = {
                        pattern = it
                        testOutput = null
                        testError = null
                    },
                    label = {
                        Text(
                            stringResource(
                                if (isRegex) MR.strings.novel_rule_regex_pattern
                                else MR.strings.novel_rule_find_text,
                            ),
                        )
                    },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = Size.small),
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = {
                        replacement = it
                        testOutput = null
                    },
                    label = { Text(stringResource(MR.strings.novel_rule_replace_with)) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = Size.small),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Size.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = isRegex,
                        onCheckedChange = {
                            isRegex = it
                            testOutput = null
                            testError = null
                        },
                    )
                    Text(
                        text = stringResource(MR.strings.novel_rule_use_regex),
                        modifier = Modifier.padding(start = Size.tiny),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Size.small))
                Text(
                    text = stringResource(MR.strings.novel_rule_test),
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = testInput,
                    onValueChange = {
                        testInput = it
                        testOutput = null
                        testError = null
                    },
                    label = { Text(stringResource(MR.strings.novel_rule_sample_input)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(top = Size.tiny),
                )
                TextButton(
                    onClick = {
                        if (pattern.isBlank()) {
                            testError = patternEmptyText
                            return@TextButton
                        }
                        try {
                            testOutput = if (isRegex) {
                                val regex = Regex(pattern)
                                regex.replace(testInput, replacement)
                            } else {
                                testInput.replace(pattern, replacement)
                            }
                            testError = null
                        } catch (e: Exception) {
                            testError = e.message ?: invalidRegexText
                            testOutput = null
                        }
                    },
                    modifier = Modifier.padding(top = Size.tiny),
                ) {
                    Text(stringResource(MR.strings.novel_rule_run_test))
                }
                testOutput?.let {
                    Text(
                        text = outputFormatTemplate.replace("%s", it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Size.tiny),
                    )
                }
                testError?.let {
                    Text(
                        text = errorFormatTemplate.replace("%s", it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Size.tiny),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank() && pattern.isNotBlank()) {
                        if (isRegex) {
                            try {
                                Regex(pattern)
                            } catch (e: Exception) {
                                testError = invalidRegexFormatTemplate.replace("%s", e.message ?: "")
                                return@TextButton
                            }
                        }
                        onConfirm(
                            RegexReplacement(
                                title = title.trim(),
                                pattern = pattern,
                                replacement = replacement,
                                enabled = initialRule?.enabled ?: true,
                                isRegex = isRegex,
                            ),
                        )
                    }
                },
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
