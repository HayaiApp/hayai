package eu.kanade.tachiyomi.ui.manga.translation

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import yokai.domain.series.SeriesKnowledgeRepository
import yokai.domain.series.SeriesPreferences
import yokai.domain.series.model.MetadataProviderType
import yokai.domain.series.model.SeriesKnowledgeBundle
import yokai.domain.series.model.SeriesTranslationCanon
import yokai.domain.series.model.SeriesTranslationEntity
import yokai.domain.series.model.SeriesTranslationEvent
import yokai.domain.series.model.SeriesTranslationNudge
import yokai.domain.series.model.SeriesTranslationRelationship
import yokai.domain.series.model.TranslationMode
import yokai.i18n.MR
import yokai.presentation.theme.Size
import yokai.presentation.theme.YokaiTheme
import java.util.Locale

fun showAdvancedTranslationSheet(controller: MangaDetailsController) {
    val activity = controller.activity ?: return
    val mangaId = controller.presenter.manga.id ?: return
    val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
    val composeView = ComposeView(activity)
    composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    composeView.setContent {
        YokaiTheme {
            AdvancedTranslationSheet(
                mangaId = mangaId,
                onDismiss = { (composeView.parent as? ViewGroup)?.removeView(composeView) },
            )
        }
    }
    rootView.addView(composeView)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedTranslationSheet(
    mangaId: Long,
    onDismiss: () -> Unit,
) {
    val repository = remember { GlobalContext.get().get<SeriesKnowledgeRepository>() }
    val seriesPreferences = remember { GlobalContext.get().get<SeriesPreferences>() }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var bundle by remember { mutableStateOf(SeriesKnowledgeBundle.Empty) }
    var summary by remember { mutableStateOf("") }
    var styleGuide by remember { mutableStateOf("") }
    var entityToEdit by remember { mutableStateOf<SeriesTranslationEntity?>(null) }
    var showEntityDialog by remember { mutableStateOf(false) }
    var relationshipToEdit by remember { mutableStateOf<SeriesTranslationRelationship?>(null) }
    var showRelationshipDialog by remember { mutableStateOf(false) }
    var eventToEdit by remember { mutableStateOf<SeriesTranslationEvent?>(null) }
    var showEventDialog by remember { mutableStateOf(false) }
    var nudgeToEdit by remember { mutableStateOf<SeriesTranslationNudge?>(null) }
    var showNudgeDialog by remember { mutableStateOf(false) }
    var advancedMode by remember {
        mutableStateOf(TranslationMode.fromDbKey(seriesPreferences.translationMode().get()) == TranslationMode.ADVANCED)
    }

    suspend fun reload() {
        val next = repository.get(mangaId)
        bundle = next
        summary = next.canon?.summary.orEmpty()
        styleGuide = next.canon?.styleGuide.orEmpty()
    }

    LaunchedEffect(mangaId) { reload() }

    if (showEntityDialog) {
        EntityDialog(
            entity = entityToEdit,
            onDismiss = {
                showEntityDialog = false
                entityToEdit = null
            },
            onSave = { entity ->
                scope.launch {
                    repository.upsertTranslationEntities(listOf(entity.copy(mangaId = mangaId)))
                    reload()
                    showEntityDialog = false
                    entityToEdit = null
                }
            },
        )
    }

    if (showRelationshipDialog) {
        RelationshipDialog(
            relationship = relationshipToEdit,
            onDismiss = {
                showRelationshipDialog = false
                relationshipToEdit = null
            },
            onSave = { relationship ->
                scope.launch {
                    repository.upsertTranslationRelationships(listOf(relationship.copy(mangaId = mangaId)))
                    reload()
                    showRelationshipDialog = false
                    relationshipToEdit = null
                }
            },
        )
    }

    if (showEventDialog) {
        EventDialog(
            event = eventToEdit,
            onDismiss = {
                showEventDialog = false
                eventToEdit = null
            },
            onSave = { event ->
                scope.launch {
                    if (event.id == null) {
                        repository.insertTranslationEvents(listOf(event.copy(mangaId = mangaId)))
                    } else {
                        repository.updateTranslationEvent(event.copy(mangaId = mangaId, updatedAt = System.currentTimeMillis()))
                    }
                    reload()
                    showEventDialog = false
                    eventToEdit = null
                }
            },
        )
    }

    if (showNudgeDialog) {
        NudgeDialog(
            nudge = nudgeToEdit,
            onDismiss = {
                showNudgeDialog = false
                nudgeToEdit = null
            },
            onSave = { nudge ->
                scope.launch {
                    if (nudge.id == null) {
                        repository.insertTranslationNudges(listOf(nudge.copy(mangaId = mangaId)))
                    } else {
                        repository.updateTranslationNudge(nudge.copy(mangaId = mangaId, updatedAt = System.currentTimeMillis()))
                    }
                    reload()
                    showNudgeDialog = false
                    nudgeToEdit = null
                }
            },
        )
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .padding(Size.large),
            verticalArrangement = Arrangement.spacedBy(Size.medium),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Psychology, contentDescription = null)
                        Text(
                            modifier = Modifier.padding(start = Size.small),
                            text = stringResource(MR.strings.advanced_translation_memory),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    AssistChip(
                        onClick = {
                            advancedMode = !advancedMode
                            seriesPreferences.translationMode().set(
                                if (advancedMode) TranslationMode.ADVANCED.dbKey else TranslationMode.SIMPLE.dbKey,
                            )
                        },
                        label = {
                            Text(
                                stringResource(
                                    if (advancedMode) {
                                        MR.strings.pref_translation_mode_advanced
                                    } else {
                                        MR.strings.pref_translation_mode_simple
                                    },
                                ),
                            )
                        },
                        leadingIcon = { Icon(Icons.Outlined.Translate, contentDescription = null) },
                    )
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier.padding(Size.medium),
                        verticalArrangement = Arrangement.spacedBy(Size.small),
                    ) {
                        Text(
                            text = stringResource(MR.strings.advanced_translation_canon),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = summary,
                            onValueChange = { summary = it },
                            minLines = 2,
                            label = { Text(stringResource(MR.strings.advanced_translation_summary)) },
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = styleGuide,
                            onValueChange = { styleGuide = it },
                            minLines = 2,
                            label = { Text(stringResource(MR.strings.advanced_translation_style_guide)) },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val now = System.currentTimeMillis()
                                        val existing = bundle.canon
                                        repository.upsertTranslationCanon(
                                            SeriesTranslationCanon(
                                                mangaId = mangaId,
                                                mode = TranslationMode.ADVANCED,
                                                sourceLanguage = existing?.sourceLanguage,
                                                targetLanguage = existing?.targetLanguage,
                                                summary = summary.takeIf { it.isNotBlank() },
                                                styleGuide = styleGuide.takeIf { it.isNotBlank() },
                                                createdAt = existing?.createdAt ?: now,
                                                updatedAt = now,
                                            ),
                                        )
                                        reload()
                                    }
                                },
                            ) {
                                Text(stringResource(MR.strings.save))
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    title = stringResource(MR.strings.advanced_translation_glossary),
                    actionLabel = stringResource(MR.strings.advanced_translation_add_entity),
                    onAction = {
                        entityToEdit = null
                        showEntityDialog = true
                    },
                )
            }

            if (bundle.entities.isEmpty()) {
                item {
                    EmptyText(text = stringResource(MR.strings.advanced_translation_no_entities))
                }
            } else {
                items(bundle.entities, key = { it.entityKey }) { entity ->
                    EntityRow(
                        entity = entity,
                        onEdit = {
                            entityToEdit = entity
                            showEntityDialog = true
                        },
                        onDelete = {
                            scope.launch {
                                repository.deleteTranslationEntity(mangaId, entity.entityKey)
                                reload()
                            }
                        },
                    )
                }
            }

            item {
                SectionHeader(
                    title = stringResource(MR.strings.advanced_translation_relationships),
                    actionLabel = stringResource(MR.strings.advanced_translation_add_relationship),
                    onAction = {
                        relationshipToEdit = null
                        showRelationshipDialog = true
                    },
                )
            }

            if (bundle.relationships.isEmpty()) {
                item {
                    EmptyText(text = stringResource(MR.strings.advanced_translation_no_relationships))
                }
            } else {
                items(bundle.relationships, key = { it.id ?: "${it.fromEntityKey}:${it.toEntityKey}:${it.relationshipType}" }) { relationship ->
                    RelationshipRow(
                        relationship = relationship,
                        onEdit = {
                            relationshipToEdit = relationship
                            showRelationshipDialog = true
                        },
                        onDelete = {
                            scope.launch {
                                relationship.id?.let { repository.deleteTranslationRelationship(it) }
                                reload()
                            }
                        },
                    )
                }
            }

            item {
                SectionHeader(
                    title = stringResource(MR.strings.advanced_translation_nudges),
                    actionLabel = stringResource(MR.strings.advanced_translation_add_nudge),
                    onAction = {
                        nudgeToEdit = null
                        showNudgeDialog = true
                    },
                )
            }

            if (bundle.nudges.isEmpty()) {
                item {
                    EmptyText(text = stringResource(MR.strings.advanced_translation_no_nudges))
                }
            } else {
                items(bundle.nudges, key = { it.id ?: it.instruction }) { nudge ->
                    NudgeRow(
                        nudge = nudge,
                        onEdit = {
                            nudgeToEdit = nudge
                            showNudgeDialog = true
                        },
                        onDelete = {
                            scope.launch {
                                nudge.id?.let { repository.deleteTranslationNudge(it) }
                                reload()
                            }
                        },
                    )
                }
            }

            item {
                SectionHeader(
                    title = stringResource(MR.strings.advanced_translation_events),
                    actionLabel = stringResource(MR.strings.advanced_translation_add_event),
                    onAction = {
                        eventToEdit = null
                        showEventDialog = true
                    },
                )
            }

            if (bundle.events.isEmpty()) {
                item {
                    EmptyText(text = stringResource(MR.strings.advanced_translation_no_events))
                }
            } else {
                item {
                    Divider()
                }
                items(bundle.events, key = { it.id ?: it.title }) { event ->
                    EventRow(
                        event = event,
                        onEdit = {
                            eventToEdit = event
                            showEventDialog = true
                        },
                        onDelete = {
                            scope.launch {
                                event.id?.let { repository.deleteTranslationEvent(it) }
                                reload()
                            }
                        },
                    )
                }
            }

            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Size.small)) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                stringResource(
                                    MR.strings.advanced_translation_relationship_count_format,
                                    bundle.relationships.size,
                                ),
                            )
                        },
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                stringResource(
                                    MR.strings.advanced_translation_event_count_format,
                                    bundle.events.size,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onAction) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(modifier = Modifier.padding(start = Size.small), text = actionLabel)
        }
    }
}

@Composable
private fun EntityRow(
    entity: SeriesTranslationEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(Size.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entity.displayName, style = MaterialTheme.typography.titleSmall)
                val detail = listOfNotNull(
                    entity.originalName,
                    entity.translatedName,
                    entity.gender,
                    entity.entityType,
                ).filter { it.isNotBlank() }.joinToString(" • ")
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entity.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(MR.strings.edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.delete))
            }
        }
    }
}

@Composable
private fun RelationshipRow(
    relationship: SeriesTranslationRelationship,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(Size.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        MR.strings.advanced_translation_relationship_pair_format,
                        relationship.fromEntityName,
                        relationship.toEntityName,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = relationship.relationshipType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                relationship.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(MR.strings.edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.delete))
            }
        }
    }
}

@Composable
private fun EventRow(
    event: SeriesTranslationEvent,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(Size.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = event.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(MR.strings.edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.delete))
            }
        }
    }
}

@Composable
private fun NudgeRow(
    nudge: SeriesTranslationNudge,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(Size.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nudge.scope,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (nudge.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = nudge.instruction,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(MR.strings.edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.delete))
            }
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun EntityDialog(
    entity: SeriesTranslationEntity?,
    onDismiss: () -> Unit,
    onSave: (SeriesTranslationEntity) -> Unit,
) {
    var displayName by remember(entity) { mutableStateOf(entity?.displayName.orEmpty()) }
    var originalName by remember(entity) { mutableStateOf(entity?.originalName.orEmpty()) }
    var translatedName by remember(entity) { mutableStateOf(entity?.translatedName.orEmpty()) }
    var entityType by remember(entity) { mutableStateOf(entity?.entityType ?: "character") }
    var gender by remember(entity) { mutableStateOf(entity?.gender.orEmpty()) }
    var aliases by remember(entity) { mutableStateOf(entity?.aliases.orEmpty()) }
    var description by remember(entity) { mutableStateOf(entity?.description.orEmpty()) }
    var locked by remember(entity) { mutableStateOf(entity?.userLocked ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.advanced_translation_entity)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Size.small)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = displayName,
                    onValueChange = { displayName = it },
                    singleLine = true,
                    label = { Text(stringResource(MR.strings.advanced_translation_entity_name)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Size.small)) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = originalName,
                        onValueChange = { originalName = it },
                        singleLine = true,
                        label = { Text(stringResource(MR.strings.advanced_translation_original_name)) },
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = translatedName,
                        onValueChange = { translatedName = it },
                        singleLine = true,
                        label = { Text(stringResource(MR.strings.advanced_translation_translated_name)) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Size.small)) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = entityType,
                        onValueChange = { entityType = it },
                        singleLine = true,
                        label = { Text(stringResource(MR.strings.advanced_translation_entity_type)) },
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = gender,
                        onValueChange = { gender = it },
                        singleLine = true,
                        label = { Text(stringResource(MR.strings.advanced_translation_gender)) },
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = aliases,
                    onValueChange = { aliases = it },
                    singleLine = true,
                    label = { Text(stringResource(MR.strings.advanced_translation_aliases)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = description,
                    onValueChange = { description = it },
                    minLines = 2,
                    label = { Text(stringResource(MR.strings.advanced_translation_description)) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = locked, onCheckedChange = { locked = it })
                    Text(stringResource(MR.strings.advanced_translation_user_locked))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = displayName.isNotBlank(),
                onClick = {
                    val now = System.currentTimeMillis()
                    val key = entity?.entityKey ?: displayName.toEntityKey()
                    onSave(
                        SeriesTranslationEntity(
                            id = entity?.id,
                            mangaId = entity?.mangaId ?: 0L,
                            entityKey = key,
                            displayName = displayName.trim(),
                            originalName = originalName.takeIf { it.isNotBlank() },
                            translatedName = translatedName.takeIf { it.isNotBlank() },
                            entityType = entityType.takeIf { it.isNotBlank() } ?: "entity",
                            gender = gender.takeIf { it.isNotBlank() },
                            description = description.takeIf { it.isNotBlank() },
                            aliases = aliases.takeIf { it.isNotBlank() },
                            source = MetadataProviderType.USER,
                            confidence = 1.0,
                            userLocked = locked,
                            createdAt = entity?.createdAt ?: now,
                            updatedAt = now,
                        ),
                    )
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
private fun RelationshipDialog(
    relationship: SeriesTranslationRelationship?,
    onDismiss: () -> Unit,
    onSave: (SeriesTranslationRelationship) -> Unit,
) {
    var fromName by remember(relationship) { mutableStateOf(relationship?.fromEntityName.orEmpty()) }
    var toName by remember(relationship) { mutableStateOf(relationship?.toEntityName.orEmpty()) }
    var relationshipType by remember(relationship) { mutableStateOf(relationship?.relationshipType.orEmpty()) }
    var description by remember(relationship) { mutableStateOf(relationship?.description.orEmpty()) }
    var locked by remember(relationship) { mutableStateOf(relationship?.userLocked ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.advanced_translation_relationship)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Size.small)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Size.small)) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = fromName,
                        onValueChange = { fromName = it },
                        singleLine = true,
                        label = { Text(stringResource(MR.strings.advanced_translation_relationship_from)) },
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = toName,
                        onValueChange = { toName = it },
                        singleLine = true,
                        label = { Text(stringResource(MR.strings.advanced_translation_relationship_to)) },
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = relationshipType,
                    onValueChange = { relationshipType = it },
                    singleLine = true,
                    label = { Text(stringResource(MR.strings.advanced_translation_relationship_type)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = description,
                    onValueChange = { description = it },
                    minLines = 2,
                    label = { Text(stringResource(MR.strings.advanced_translation_relationship_description)) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = locked, onCheckedChange = { locked = it })
                    Text(stringResource(MR.strings.advanced_translation_user_locked))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = fromName.isNotBlank() && toName.isNotBlank() && relationshipType.isNotBlank(),
                onClick = {
                    val now = System.currentTimeMillis()
                    val trimmedFromName = fromName.trim()
                    val trimmedToName = toName.trim()
                    onSave(
                        SeriesTranslationRelationship(
                            id = relationship?.id,
                            mangaId = relationship?.mangaId ?: 0L,
                            fromEntityKey = relationship
                                ?.let { existing ->
                                    existing.fromEntityKey.takeIf { trimmedFromName == existing.fromEntityName }
                                }
                                ?: trimmedFromName.toEntityKey(),
                            fromEntityName = trimmedFromName,
                            toEntityKey = relationship
                                ?.let { existing ->
                                    existing.toEntityKey.takeIf { trimmedToName == existing.toEntityName }
                                }
                                ?: trimmedToName.toEntityKey(),
                            toEntityName = trimmedToName,
                            relationshipType = relationshipType.trim(),
                            description = description.takeIf { it.isNotBlank() }?.trim(),
                            source = MetadataProviderType.USER,
                            userLocked = locked,
                            createdAt = relationship?.createdAt ?: now,
                            updatedAt = now,
                        ),
                    )
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
private fun EventDialog(
    event: SeriesTranslationEvent?,
    onDismiss: () -> Unit,
    onSave: (SeriesTranslationEvent) -> Unit,
) {
    var title by remember(event) { mutableStateOf(event?.title.orEmpty()) }
    var description by remember(event) { mutableStateOf(event?.description.orEmpty()) }
    var sequenceIndex by remember(event) { mutableStateOf(event?.sequenceIndex?.toString().orEmpty()) }
    var locked by remember(event) { mutableStateOf(event?.userLocked ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.advanced_translation_event)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Size.small)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text(stringResource(MR.strings.advanced_translation_event_title)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = description,
                    onValueChange = { description = it },
                    minLines = 3,
                    label = { Text(stringResource(MR.strings.advanced_translation_event_description)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = sequenceIndex,
                    onValueChange = { sequenceIndex = it },
                    singleLine = true,
                    label = { Text(stringResource(MR.strings.advanced_translation_event_sequence)) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = locked, onCheckedChange = { locked = it })
                    Text(stringResource(MR.strings.advanced_translation_user_locked))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && description.isNotBlank(),
                onClick = {
                    val now = System.currentTimeMillis()
                    onSave(
                        SeriesTranslationEvent(
                            id = event?.id,
                            mangaId = event?.mangaId ?: 0L,
                            chapterId = event?.chapterId,
                            title = title.trim(),
                            description = description.trim(),
                            sequenceIndex = sequenceIndex.trim().takeIf { it.isNotBlank() }?.toLongOrNull(),
                            source = MetadataProviderType.USER,
                            userLocked = locked,
                            createdAt = event?.createdAt ?: now,
                            updatedAt = now,
                        ),
                    )
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
private fun NudgeDialog(
    nudge: SeriesTranslationNudge?,
    onDismiss: () -> Unit,
    onSave: (SeriesTranslationNudge) -> Unit,
) {
    var scope by remember(nudge) { mutableStateOf(nudge?.scope.orEmpty()) }
    var instruction by remember(nudge) { mutableStateOf(nudge?.instruction.orEmpty()) }
    var active by remember(nudge) { mutableStateOf(nudge?.active ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.advanced_translation_nudge)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Size.small)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = scope,
                    onValueChange = { scope = it },
                    singleLine = true,
                    label = { Text(stringResource(MR.strings.advanced_translation_nudge_scope)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = instruction,
                    onValueChange = { instruction = it },
                    minLines = 3,
                    label = { Text(stringResource(MR.strings.advanced_translation_nudge_instruction)) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = active, onCheckedChange = { active = it })
                    Text(stringResource(MR.strings.advanced_translation_nudge_active))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = scope.isNotBlank() && instruction.isNotBlank(),
                onClick = {
                    val now = System.currentTimeMillis()
                    onSave(
                        SeriesTranslationNudge(
                            id = nudge?.id,
                            mangaId = nudge?.mangaId ?: 0L,
                            chapterId = nudge?.chapterId,
                            scope = scope.trim(),
                            instruction = instruction.trim(),
                            active = active,
                            createdAt = nudge?.createdAt ?: now,
                            updatedAt = now,
                        ),
                    )
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

private fun String.toEntityKey(): String =
    trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "entity_${System.currentTimeMillis()}" }
