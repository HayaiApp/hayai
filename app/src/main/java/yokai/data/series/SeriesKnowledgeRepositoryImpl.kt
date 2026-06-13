package yokai.data.series

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import yokai.data.DatabaseHandler
import yokai.domain.series.SeriesKnowledgeRepository
import yokai.domain.series.model.SeriesDisplayOption
import yokai.domain.series.model.SeriesKnowledgeBundle
import yokai.domain.series.model.SeriesMetadataChoice
import yokai.domain.series.model.SeriesMetadataValue
import yokai.domain.series.model.SeriesTranslationCanon
import yokai.domain.series.model.SeriesTranslationEntity
import yokai.domain.series.model.SeriesTranslationEvent
import yokai.domain.series.model.SeriesTranslationNudge
import yokai.domain.series.model.SeriesTranslationRelationship
import yokai.domain.series.model.TranslationMode

class SeriesKnowledgeRepositoryImpl(
    private val handler: DatabaseHandler,
) : SeriesKnowledgeRepository {

    override fun subscribe(mangaId: Long): Flow<SeriesKnowledgeBundle> {
        val canon = handler.subscribeToOneOrNull {
            series_knowledgeQueries.findTranslationCanon(mangaId, ::mapTranslationCanon)
        }
        val entities = handler.subscribeToList {
            series_knowledgeQueries.findTranslationEntities(mangaId, ::mapTranslationEntity)
        }
        val relationships = handler.subscribeToList {
            series_knowledgeQueries.findTranslationRelationships(mangaId, ::mapTranslationRelationship)
        }
        val events = handler.subscribeToList {
            series_knowledgeQueries.findTranslationEvents(mangaId, ::mapTranslationEvent)
        }
        val nudges = handler.subscribeToList {
            series_knowledgeQueries.findTranslationNudges(mangaId, ::mapTranslationNudge)
        }
        val values = handler.subscribeToList {
            series_knowledgeQueries.findMetadataValues(mangaId, ::mapMetadataValue)
        }
        val choices = handler.subscribeToList {
            series_knowledgeQueries.findMetadataChoices(mangaId, ::mapMetadataChoice)
        }
        val display = handler.subscribeToList {
            series_knowledgeQueries.findDisplayOptions(mangaId, ::mapDisplayOption)
        }

        return combine(
            canon,
            entities,
            relationships,
            events,
            nudges,
            values,
            choices,
            display,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            SeriesKnowledgeBundle(
                canon = values[0] as SeriesTranslationCanon?,
                entities = values[1] as List<SeriesTranslationEntity>,
                relationships = values[2] as List<SeriesTranslationRelationship>,
                events = values[3] as List<SeriesTranslationEvent>,
                nudges = values[4] as List<SeriesTranslationNudge>,
                metadataValues = values[5] as List<SeriesMetadataValue>,
                metadataChoices = values[6] as List<SeriesMetadataChoice>,
                displayOptions = values[7] as List<SeriesDisplayOption>,
            )
        }
    }

    override suspend fun get(mangaId: Long): SeriesKnowledgeBundle =
        SeriesKnowledgeBundle(
            canon = handler.awaitOneOrNull {
                series_knowledgeQueries.findTranslationCanon(mangaId, ::mapTranslationCanon)
            },
            entities = handler.awaitList {
                series_knowledgeQueries.findTranslationEntities(mangaId, ::mapTranslationEntity)
            },
            relationships = handler.awaitList {
                series_knowledgeQueries.findTranslationRelationships(mangaId, ::mapTranslationRelationship)
            },
            events = handler.awaitList {
                series_knowledgeQueries.findTranslationEvents(mangaId, ::mapTranslationEvent)
            },
            nudges = handler.awaitList {
                series_knowledgeQueries.findTranslationNudges(mangaId, ::mapTranslationNudge)
            },
            metadataValues = handler.awaitList {
                series_knowledgeQueries.findMetadataValues(mangaId, ::mapMetadataValue)
            },
            metadataChoices = handler.awaitList {
                series_knowledgeQueries.findMetadataChoices(mangaId, ::mapMetadataChoice)
            },
            displayOptions = handler.awaitList {
                series_knowledgeQueries.findDisplayOptions(mangaId, ::mapDisplayOption)
            },
        )

    override suspend fun upsertTranslationCanon(canon: SeriesTranslationCanon) {
        handler.await {
            series_knowledgeQueries.upsertTranslationCanon(
                canon.mangaId,
                canon.mode.dbKey,
                canon.sourceLanguage,
                canon.targetLanguage,
                canon.summary,
                canon.styleGuide,
                canon.createdAt,
                canon.updatedAt,
            )
        }
    }

    override suspend fun upsertTranslationEntities(entities: List<SeriesTranslationEntity>) {
        handler.await(true) {
            entities.forEach { entity ->
                series_knowledgeQueries.upsertTranslationEntity(
                    entity.mangaId,
                    entity.entityKey,
                    entity.displayName,
                    entity.originalName,
                    entity.translatedName,
                    entity.entityType,
                    entity.gender,
                    entity.description,
                    entity.aliases,
                    entity.source,
                    entity.confidence,
                    entity.userLocked,
                    entity.createdAt,
                    entity.updatedAt,
                )
            }
        }
    }

    override suspend fun deleteTranslationEntity(mangaId: Long, entityKey: String) {
        handler.await { series_knowledgeQueries.deleteTranslationEntity(mangaId, entityKey) }
    }

    override suspend fun upsertTranslationRelationships(relationships: List<SeriesTranslationRelationship>) {
        handler.await(true) {
            relationships.forEach { relationship ->
                val id = relationship.id
                if (id == null) {
                    series_knowledgeQueries.insertTranslationRelationship(
                        relationship.mangaId,
                        relationship.fromEntityKey,
                        relationship.fromEntityName,
                        relationship.toEntityKey,
                        relationship.toEntityName,
                        relationship.relationshipType,
                        relationship.description,
                        relationship.source,
                        relationship.userLocked,
                        relationship.createdAt,
                        relationship.updatedAt,
                    )
                } else {
                    series_knowledgeQueries.updateTranslationRelationship(
                        relationship.fromEntityKey,
                        relationship.fromEntityName,
                        relationship.toEntityKey,
                        relationship.toEntityName,
                        relationship.relationshipType,
                        relationship.description,
                        relationship.source,
                        relationship.userLocked,
                        relationship.updatedAt,
                        id,
                    )
                }
            }
        }
    }

    override suspend fun deleteTranslationRelationship(id: Long) {
        handler.await { series_knowledgeQueries.deleteTranslationRelationship(id) }
    }

    override suspend fun insertTranslationEvents(events: List<SeriesTranslationEvent>) {
        handler.await(true) {
            events.forEach { event ->
                series_knowledgeQueries.insertTranslationEvent(
                    event.mangaId,
                    event.chapterId,
                    event.title,
                    event.description,
                    event.sequenceIndex,
                    event.source,
                    event.userLocked,
                    event.createdAt,
                    event.updatedAt,
                )
            }
        }
    }

    override suspend fun updateTranslationEvent(event: SeriesTranslationEvent) {
        val id = event.id ?: return
        handler.await {
            series_knowledgeQueries.updateTranslationEvent(
                event.chapterId,
                event.title,
                event.description,
                event.sequenceIndex,
                event.source,
                event.userLocked,
                event.updatedAt,
                id,
            )
        }
    }

    override suspend fun deleteTranslationEvent(id: Long) {
        handler.await { series_knowledgeQueries.deleteTranslationEvent(id) }
    }

    override suspend fun insertTranslationNudges(nudges: List<SeriesTranslationNudge>) {
        handler.await(true) {
            nudges.forEach { nudge ->
                series_knowledgeQueries.insertTranslationNudge(
                    nudge.mangaId,
                    nudge.chapterId,
                    nudge.scope,
                    nudge.instruction,
                    nudge.active,
                    nudge.createdAt,
                    nudge.updatedAt,
                )
            }
        }
    }

    override suspend fun updateTranslationNudge(nudge: SeriesTranslationNudge) {
        val id = nudge.id ?: return
        handler.await {
            series_knowledgeQueries.updateTranslationNudge(
                nudge.chapterId,
                nudge.scope,
                nudge.instruction,
                nudge.active,
                nudge.updatedAt,
                id,
            )
        }
    }

    override suspend fun deleteTranslationNudge(id: Long) {
        handler.await { series_knowledgeQueries.deleteTranslationNudge(id) }
    }

    override suspend fun upsertMetadataValues(values: List<SeriesMetadataValue>) {
        handler.await(true) {
            values.forEach { value ->
                series_knowledgeQueries.upsertMetadataValue(
                    value.mangaId,
                    value.field,
                    value.providerType,
                    value.providerId,
                    value.providerName,
                    value.value,
                    value.extraJson,
                    value.confidence,
                    value.userLocked,
                    value.updatedAt,
                )
            }
        }
    }

    override suspend fun upsertMetadataChoice(choice: SeriesMetadataChoice) {
        handler.await {
            series_knowledgeQueries.upsertMetadataChoice(
                choice.mangaId,
                choice.field,
                choice.providerType,
                choice.providerId,
                choice.updatedAt,
            )
        }
    }

    override suspend fun deleteMetadataChoice(mangaId: Long, field: String) {
        handler.await { series_knowledgeQueries.deleteMetadataChoice(mangaId, field) }
    }

    override suspend fun upsertDisplayOption(option: SeriesDisplayOption) {
        handler.await {
            series_knowledgeQueries.upsertDisplayOption(
                option.mangaId,
                option.optionKey,
                option.visible,
                option.updatedAt,
            )
        }
    }

    override suspend fun deleteDisplayOption(mangaId: Long, optionKey: String) {
        handler.await { series_knowledgeQueries.deleteDisplayOption(mangaId, optionKey) }
    }

    override suspend fun restore(mangaId: Long, bundle: SeriesKnowledgeBundle) {
        handler.await(true) {
            series_knowledgeQueries.deleteTranslationEntitiesForManga(mangaId)
            series_knowledgeQueries.deleteTranslationRelationshipsForManga(mangaId)
            series_knowledgeQueries.deleteTranslationEventsForManga(mangaId)
            series_knowledgeQueries.deleteTranslationNudgesForManga(mangaId)
            series_knowledgeQueries.deleteMetadataValuesForManga(mangaId)
            series_knowledgeQueries.deleteMetadataChoicesForManga(mangaId)
            series_knowledgeQueries.deleteDisplayOptionsForManga(mangaId)
            series_knowledgeQueries.deleteKnowledgeForManga(mangaId)
        }

        copyBundleTo(bundle, mangaId)
    }

    override suspend fun copy(oldId: Long, newId: Long) {
        if (oldId == newId) return

        copyBundleTo(get(oldId), newId)
        handler.await {
            series_knowledgeQueries.copyQuotes(
                new_quote_suffix = newId.toString(),
                new_manga_id = newId,
                old_manga_id = oldId,
            )
        }
    }

    override suspend fun relink(oldId: Long, newId: Long) {
        if (oldId == newId) return

        copyBundleTo(get(oldId), newId)

        handler.await(true) {
            series_knowledgeQueries.relinkQuotes(newId, oldId)
            series_knowledgeQueries.deleteTranslationEntitiesForManga(oldId)
            series_knowledgeQueries.deleteTranslationRelationshipsForManga(oldId)
            series_knowledgeQueries.deleteTranslationEventsForManga(oldId)
            series_knowledgeQueries.deleteTranslationNudgesForManga(oldId)
            series_knowledgeQueries.deleteMetadataValuesForManga(oldId)
            series_knowledgeQueries.deleteMetadataChoicesForManga(oldId)
            series_knowledgeQueries.deleteDisplayOptionsForManga(oldId)
            series_knowledgeQueries.deleteKnowledgeForManga(oldId)
        }
    }

    private suspend fun copyBundleTo(bundle: SeriesKnowledgeBundle, mangaId: Long) {
        bundle.canon?.copy(mangaId = mangaId)?.let { upsertTranslationCanon(it) }
        upsertTranslationEntities(bundle.entities.map { it.copy(id = null, mangaId = mangaId) })
        upsertTranslationRelationships(bundle.relationships.map { it.copy(id = null, mangaId = mangaId) })
        insertTranslationEvents(bundle.events.map { it.copy(id = null, mangaId = mangaId) })
        insertTranslationNudges(bundle.nudges.map { it.copy(id = null, mangaId = mangaId) })

        upsertMetadataValues(bundle.metadataValues.map { it.copy(id = null, mangaId = mangaId) })
        bundle.metadataChoices.forEach { upsertMetadataChoice(it.copy(mangaId = mangaId)) }
        bundle.displayOptions.forEach { upsertDisplayOption(it.copy(mangaId = mangaId)) }
    }

    private fun mapTranslationCanon(
        mangaId: Long,
        mode: String,
        sourceLanguage: String?,
        targetLanguage: String?,
        summary: String?,
        styleGuide: String?,
        createdAt: Long,
        updatedAt: Long,
    ): SeriesTranslationCanon = SeriesTranslationCanon(
        mangaId = mangaId,
        mode = TranslationMode.fromDbKey(mode),
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        summary = summary,
        styleGuide = styleGuide,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun mapTranslationEntity(
        id: Long,
        mangaId: Long,
        entityKey: String,
        displayName: String,
        originalName: String?,
        translatedName: String?,
        entityType: String,
        gender: String?,
        description: String?,
        aliases: String?,
        source: String,
        confidence: Double?,
        userLocked: Boolean,
        createdAt: Long,
        updatedAt: Long,
    ): SeriesTranslationEntity = SeriesTranslationEntity(
        id = id,
        mangaId = mangaId,
        entityKey = entityKey,
        displayName = displayName,
        originalName = originalName,
        translatedName = translatedName,
        entityType = entityType,
        gender = gender,
        description = description,
        aliases = aliases,
        source = source,
        confidence = confidence,
        userLocked = userLocked,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun mapTranslationRelationship(
        id: Long,
        mangaId: Long,
        fromEntityKey: String,
        fromEntityName: String,
        toEntityKey: String,
        toEntityName: String,
        relationshipType: String,
        description: String?,
        source: String,
        userLocked: Boolean,
        createdAt: Long,
        updatedAt: Long,
    ): SeriesTranslationRelationship = SeriesTranslationRelationship(
        id = id,
        mangaId = mangaId,
        fromEntityKey = fromEntityKey,
        fromEntityName = fromEntityName,
        toEntityKey = toEntityKey,
        toEntityName = toEntityName,
        relationshipType = relationshipType,
        description = description,
        source = source,
        userLocked = userLocked,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun mapTranslationEvent(
        id: Long,
        mangaId: Long,
        chapterId: Long?,
        title: String,
        description: String,
        sequenceIndex: Long?,
        source: String,
        userLocked: Boolean,
        createdAt: Long,
        updatedAt: Long,
    ): SeriesTranslationEvent = SeriesTranslationEvent(
        id = id,
        mangaId = mangaId,
        chapterId = chapterId,
        title = title,
        description = description,
        sequenceIndex = sequenceIndex,
        source = source,
        userLocked = userLocked,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun mapTranslationNudge(
        id: Long,
        mangaId: Long,
        chapterId: Long?,
        scope: String,
        instruction: String,
        active: Boolean,
        createdAt: Long,
        updatedAt: Long,
    ): SeriesTranslationNudge = SeriesTranslationNudge(
        id = id,
        mangaId = mangaId,
        chapterId = chapterId,
        scope = scope,
        instruction = instruction,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun mapMetadataValue(
        id: Long,
        mangaId: Long,
        field: String,
        providerType: String,
        providerId: String,
        providerName: String,
        value: String,
        extraJson: String?,
        confidence: Double?,
        userLocked: Boolean,
        updatedAt: Long,
    ): SeriesMetadataValue = SeriesMetadataValue(
        id = id,
        mangaId = mangaId,
        field = field,
        providerType = providerType,
        providerId = providerId,
        providerName = providerName,
        value = value,
        extraJson = extraJson,
        confidence = confidence,
        userLocked = userLocked,
        updatedAt = updatedAt,
    )

    private fun mapMetadataChoice(
        mangaId: Long,
        field: String,
        providerType: String,
        providerId: String,
        updatedAt: Long,
    ): SeriesMetadataChoice = SeriesMetadataChoice(
        mangaId = mangaId,
        field = field,
        providerType = providerType,
        providerId = providerId,
        updatedAt = updatedAt,
    )

    private fun mapDisplayOption(
        mangaId: Long,
        optionKey: String,
        visible: Boolean,
        updatedAt: Long,
    ): SeriesDisplayOption = SeriesDisplayOption(
        mangaId = mangaId,
        optionKey = optionKey,
        visible = visible,
        updatedAt = updatedAt,
    )
}
