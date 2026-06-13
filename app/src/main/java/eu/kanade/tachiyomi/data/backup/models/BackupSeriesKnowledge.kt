package eu.kanade.tachiyomi.data.backup.models

import hayai.novel.reader.quote.Quote
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
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

@Serializable
data class BackupSeriesKnowledge(
    @ProtoNumber(1) val canon: BackupTranslationCanon? = null,
    @ProtoNumber(2) val entities: List<BackupTranslationEntity> = emptyList(),
    @ProtoNumber(3) val events: List<BackupTranslationEvent> = emptyList(),
    @ProtoNumber(4) val nudges: List<BackupTranslationNudge> = emptyList(),
    @ProtoNumber(7) val metadataValues: List<BackupMetadataValue> = emptyList(),
    @ProtoNumber(8) val metadataChoices: List<BackupMetadataChoice> = emptyList(),
    @ProtoNumber(9) val displayOptions: List<BackupDisplayOption> = emptyList(),
    @ProtoNumber(901) val relationships: List<BackupTranslationRelationship> = emptyList(),
) {
    fun isEmpty(): Boolean =
        canon == null &&
            entities.isEmpty() &&
            relationships.isEmpty() &&
            events.isEmpty() &&
            nudges.isEmpty() &&
            metadataValues.isEmpty() &&
            metadataChoices.isEmpty() &&
            displayOptions.isEmpty()

    fun toBundle(mangaId: Long): SeriesKnowledgeBundle =
        SeriesKnowledgeBundle(
            canon = canon?.toModel(mangaId),
            entities = entities.map { it.toModel(mangaId) },
            relationships = relationships.map { it.toModel(mangaId) },
            events = events.map { it.toModel(mangaId) },
            nudges = nudges.map { it.toModel(mangaId) },
            metadataValues = metadataValues.map { it.toModel(mangaId) },
            metadataChoices = metadataChoices.map { it.toModel(mangaId) },
            displayOptions = displayOptions.map { it.toModel(mangaId) },
        )

    companion object {
        fun copyFrom(bundle: SeriesKnowledgeBundle): BackupSeriesKnowledge =
            BackupSeriesKnowledge(
                canon = bundle.canon?.let(BackupTranslationCanon::copyFrom),
                entities = bundle.entities.map(BackupTranslationEntity::copyFrom),
                relationships = bundle.relationships.map(BackupTranslationRelationship::copyFrom),
                events = bundle.events.map(BackupTranslationEvent::copyFrom),
                nudges = bundle.nudges.map(BackupTranslationNudge::copyFrom),
                metadataValues = bundle.metadataValues.map(BackupMetadataValue::copyFrom),
                metadataChoices = bundle.metadataChoices.map(BackupMetadataChoice::copyFrom),
                displayOptions = bundle.displayOptions.map(BackupDisplayOption::copyFrom),
            )
    }
}

@Serializable
data class BackupTranslationCanon(
    @ProtoNumber(1) val mode: String,
    @ProtoNumber(2) val sourceLanguage: String? = null,
    @ProtoNumber(3) val targetLanguage: String? = null,
    @ProtoNumber(4) val summary: String? = null,
    @ProtoNumber(5) val styleGuide: String? = null,
    @ProtoNumber(6) val createdAt: Long,
    @ProtoNumber(7) val updatedAt: Long,
) {
    fun toModel(mangaId: Long) = SeriesTranslationCanon(
        mangaId = mangaId,
        mode = TranslationMode.fromDbKey(mode),
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        summary = summary,
        styleGuide = styleGuide,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun copyFrom(model: SeriesTranslationCanon) = BackupTranslationCanon(
            mode = model.mode.dbKey,
            sourceLanguage = model.sourceLanguage,
            targetLanguage = model.targetLanguage,
            summary = model.summary,
            styleGuide = model.styleGuide,
            createdAt = model.createdAt,
            updatedAt = model.updatedAt,
        )
    }
}

@Serializable
data class BackupTranslationEntity(
    @ProtoNumber(1) val entityKey: String,
    @ProtoNumber(2) val displayName: String,
    @ProtoNumber(3) val originalName: String? = null,
    @ProtoNumber(4) val translatedName: String? = null,
    @ProtoNumber(5) val entityType: String,
    @ProtoNumber(6) val gender: String? = null,
    @ProtoNumber(7) val description: String? = null,
    @ProtoNumber(8) val aliases: String? = null,
    @ProtoNumber(9) val source: String,
    @ProtoNumber(10) val confidence: Double? = null,
    @ProtoNumber(11) val userLocked: Boolean,
    @ProtoNumber(12) val createdAt: Long,
    @ProtoNumber(13) val updatedAt: Long,
) {
    fun toModel(mangaId: Long) = SeriesTranslationEntity(
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

    companion object {
        fun copyFrom(model: SeriesTranslationEntity) = BackupTranslationEntity(
            entityKey = model.entityKey,
            displayName = model.displayName,
            originalName = model.originalName,
            translatedName = model.translatedName,
            entityType = model.entityType,
            gender = model.gender,
            description = model.description,
            aliases = model.aliases,
            source = model.source,
            confidence = model.confidence,
            userLocked = model.userLocked,
            createdAt = model.createdAt,
            updatedAt = model.updatedAt,
        )
    }
}

@Serializable
data class BackupTranslationRelationship(
    @ProtoNumber(1) val fromEntityKey: String,
    @ProtoNumber(2) val fromEntityName: String,
    @ProtoNumber(3) val toEntityKey: String,
    @ProtoNumber(4) val toEntityName: String,
    @ProtoNumber(5) val relationshipType: String,
    @ProtoNumber(6) val description: String? = null,
    @ProtoNumber(7) val source: String,
    @ProtoNumber(8) val userLocked: Boolean,
    @ProtoNumber(9) val createdAt: Long,
    @ProtoNumber(10) val updatedAt: Long,
) {
    fun toModel(mangaId: Long) = SeriesTranslationRelationship(
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

    companion object {
        fun copyFrom(model: SeriesTranslationRelationship) = BackupTranslationRelationship(
            fromEntityKey = model.fromEntityKey,
            fromEntityName = model.fromEntityName,
            toEntityKey = model.toEntityKey,
            toEntityName = model.toEntityName,
            relationshipType = model.relationshipType,
            description = model.description,
            source = model.source,
            userLocked = model.userLocked,
            createdAt = model.createdAt,
            updatedAt = model.updatedAt,
        )
    }
}

@Serializable
data class BackupTranslationEvent(
    @ProtoNumber(1) val chapterId: Long? = null,
    @ProtoNumber(2) val title: String,
    @ProtoNumber(3) val description: String,
    @ProtoNumber(4) val sequenceIndex: Long? = null,
    @ProtoNumber(5) val source: String,
    @ProtoNumber(6) val userLocked: Boolean,
    @ProtoNumber(7) val createdAt: Long,
    @ProtoNumber(8) val updatedAt: Long,
) {
    fun toModel(mangaId: Long) = SeriesTranslationEvent(
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

    companion object {
        fun copyFrom(model: SeriesTranslationEvent) = BackupTranslationEvent(
            chapterId = model.chapterId,
            title = model.title,
            description = model.description,
            sequenceIndex = model.sequenceIndex,
            source = model.source,
            userLocked = model.userLocked,
            createdAt = model.createdAt,
            updatedAt = model.updatedAt,
        )
    }
}

@Serializable
data class BackupTranslationNudge(
    @ProtoNumber(1) val chapterId: Long? = null,
    @ProtoNumber(2) val scope: String,
    @ProtoNumber(3) val instruction: String,
    @ProtoNumber(4) val active: Boolean,
    @ProtoNumber(5) val createdAt: Long,
    @ProtoNumber(6) val updatedAt: Long,
) {
    fun toModel(mangaId: Long) = SeriesTranslationNudge(
        mangaId = mangaId,
        chapterId = chapterId,
        scope = scope,
        instruction = instruction,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun copyFrom(model: SeriesTranslationNudge) = BackupTranslationNudge(
            chapterId = model.chapterId,
            scope = model.scope,
            instruction = model.instruction,
            active = model.active,
            createdAt = model.createdAt,
            updatedAt = model.updatedAt,
        )
    }
}

@Serializable
data class BackupMetadataValue(
    @ProtoNumber(1) val field: String,
    @ProtoNumber(2) val providerType: String,
    @ProtoNumber(3) val providerId: String,
    @ProtoNumber(4) val providerName: String,
    @ProtoNumber(5) val value: String,
    @ProtoNumber(6) val extraJson: String? = null,
    @ProtoNumber(7) val confidence: Double? = null,
    @ProtoNumber(8) val userLocked: Boolean,
    @ProtoNumber(9) val updatedAt: Long,
) {
    fun toModel(mangaId: Long) = SeriesMetadataValue(
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

    companion object {
        fun copyFrom(model: SeriesMetadataValue) = BackupMetadataValue(
            field = model.field,
            providerType = model.providerType,
            providerId = model.providerId,
            providerName = model.providerName,
            value = model.value,
            extraJson = model.extraJson,
            confidence = model.confidence,
            userLocked = model.userLocked,
            updatedAt = model.updatedAt,
        )
    }
}

@Serializable
data class BackupMetadataChoice(
    @ProtoNumber(1) val field: String,
    @ProtoNumber(2) val providerType: String,
    @ProtoNumber(3) val providerId: String,
    @ProtoNumber(4) val updatedAt: Long,
) {
    fun toModel(mangaId: Long) = SeriesMetadataChoice(
        mangaId = mangaId,
        field = field,
        providerType = providerType,
        providerId = providerId,
        updatedAt = updatedAt,
    )

    companion object {
        fun copyFrom(model: SeriesMetadataChoice) = BackupMetadataChoice(
            field = model.field,
            providerType = model.providerType,
            providerId = model.providerId,
            updatedAt = model.updatedAt,
        )
    }
}

@Serializable
data class BackupDisplayOption(
    @ProtoNumber(1) val optionKey: String,
    @ProtoNumber(2) val visible: Boolean,
    @ProtoNumber(3) val updatedAt: Long,
) {
    fun toModel(mangaId: Long) = SeriesDisplayOption(
        mangaId = mangaId,
        optionKey = optionKey,
        visible = visible,
        updatedAt = updatedAt,
    )

    companion object {
        fun copyFrom(model: SeriesDisplayOption) = BackupDisplayOption(
            optionKey = model.optionKey,
            visible = model.visible,
            updatedAt = model.updatedAt,
        )
    }
}

@Serializable
data class BackupNovelQuote(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val novelName: String,
    @ProtoNumber(3) val chapterName: String,
    @ProtoNumber(4) val content: String,
    @ProtoNumber(5) val originalContent: String? = null,
    @ProtoNumber(6) val translatedContent: String? = null,
    @ProtoNumber(7) val language: String? = null,
    @ProtoNumber(8) val timestamp: Long,
) {
    fun toQuote(): Quote = Quote(
        id = id,
        novelName = novelName,
        chapterName = chapterName,
        content = content,
        originalContent = originalContent,
        translatedContent = translatedContent,
        language = language,
        timestamp = timestamp,
    )

    companion object {
        fun copyFrom(quote: Quote) = BackupNovelQuote(
            id = quote.id,
            novelName = quote.novelName,
            chapterName = quote.chapterName,
            content = quote.content,
            originalContent = quote.originalContent,
            translatedContent = quote.translatedContent,
            language = quote.language,
            timestamp = quote.timestamp,
        )
    }
}
