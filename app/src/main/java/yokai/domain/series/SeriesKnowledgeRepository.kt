package yokai.domain.series

import kotlinx.coroutines.flow.Flow
import yokai.domain.series.model.SeriesDisplayOption
import yokai.domain.series.model.SeriesKnowledgeBundle
import yokai.domain.series.model.SeriesMetadataChoice
import yokai.domain.series.model.SeriesMetadataValue
import yokai.domain.series.model.SeriesTranslationCanon
import yokai.domain.series.model.SeriesTranslationEntity
import yokai.domain.series.model.SeriesTranslationEvent
import yokai.domain.series.model.SeriesTranslationNudge
import yokai.domain.series.model.SeriesTranslationRelationship

interface SeriesKnowledgeRepository {
    fun subscribe(mangaId: Long): Flow<SeriesKnowledgeBundle>
    suspend fun get(mangaId: Long): SeriesKnowledgeBundle

    suspend fun upsertTranslationCanon(canon: SeriesTranslationCanon)
    suspend fun upsertTranslationEntities(entities: List<SeriesTranslationEntity>)
    suspend fun deleteTranslationEntity(mangaId: Long, entityKey: String)
    suspend fun upsertTranslationRelationships(relationships: List<SeriesTranslationRelationship>)
    suspend fun deleteTranslationRelationship(id: Long)
    suspend fun insertTranslationEvents(events: List<SeriesTranslationEvent>)
    suspend fun updateTranslationEvent(event: SeriesTranslationEvent)
    suspend fun deleteTranslationEvent(id: Long)
    suspend fun insertTranslationNudges(nudges: List<SeriesTranslationNudge>)
    suspend fun updateTranslationNudge(nudge: SeriesTranslationNudge)
    suspend fun deleteTranslationNudge(id: Long)

    suspend fun upsertMetadataValues(values: List<SeriesMetadataValue>)
    suspend fun upsertMetadataChoice(choice: SeriesMetadataChoice)
    suspend fun deleteMetadataChoice(mangaId: Long, field: String)

    suspend fun upsertDisplayOption(option: SeriesDisplayOption)
    suspend fun deleteDisplayOption(mangaId: Long, optionKey: String)

    suspend fun restore(mangaId: Long, bundle: SeriesKnowledgeBundle)
    suspend fun copy(oldId: Long, newId: Long)
    suspend fun relink(oldId: Long, newId: Long)
}
