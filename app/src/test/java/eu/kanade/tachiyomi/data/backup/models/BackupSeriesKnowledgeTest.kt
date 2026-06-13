package eu.kanade.tachiyomi.data.backup.models

import hayai.novel.reader.quote.Quote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import yokai.domain.series.model.MetadataProviderType
import yokai.domain.series.model.SeriesDisplayOption
import yokai.domain.series.model.SeriesKnowledgeBundle
import yokai.domain.series.model.SeriesMetadataChoice
import yokai.domain.series.model.SeriesMetadataField
import yokai.domain.series.model.SeriesMetadataValue
import yokai.domain.series.model.SeriesTranslationCanon
import yokai.domain.series.model.SeriesTranslationEntity
import yokai.domain.series.model.SeriesTranslationEvent
import yokai.domain.series.model.SeriesTranslationNudge
import yokai.domain.series.model.SeriesTranslationRelationship
import yokai.domain.series.model.TranslationMode

class BackupSeriesKnowledgeTest {

    @Test
    fun `series knowledge backup restores onto target manga id`() {
        val sourceMangaId = 10L
        val targetMangaId = 20L
        val bundle = SeriesKnowledgeBundle(
            canon = SeriesTranslationCanon(
                mangaId = sourceMangaId,
                mode = TranslationMode.ADVANCED,
                sourceLanguage = "ja",
                targetLanguage = "en",
                summary = "A tower-climbing novel.",
                styleGuide = "Keep honorifics.",
                createdAt = 1L,
                updatedAt = 2L,
            ),
            entities = listOf(
                SeriesTranslationEntity(
                    mangaId = sourceMangaId,
                    entityKey = "cassie",
                    displayName = "Cassie",
                    originalName = null,
                    translatedName = "Cassie",
                    entityType = "character",
                    gender = "female",
                    description = "Oracle.",
                    aliases = "Song of the Fallen",
                    source = "user",
                    confidence = 1.0,
                    userLocked = true,
                    createdAt = 3L,
                    updatedAt = 4L,
                ),
            ),
            relationships = listOf(
                SeriesTranslationRelationship(
                    mangaId = sourceMangaId,
                    fromEntityKey = "sunny",
                    fromEntityName = "Sunny",
                    toEntityKey = "cassie",
                    toEntityName = "Cassie",
                    relationshipType = "ally",
                    description = "Keeps a complicated trust.",
                    source = "user",
                    userLocked = true,
                    createdAt = 13L,
                    updatedAt = 14L,
                ),
            ),
            events = listOf(
                SeriesTranslationEvent(
                    mangaId = sourceMangaId,
                    chapterId = 100L,
                    title = "Forgotten shore",
                    description = "The cast reaches a new danger.",
                    sequenceIndex = 1L,
                    source = "user",
                    userLocked = true,
                    createdAt = 15L,
                    updatedAt = 16L,
                ),
            ),
            nudges = listOf(
                SeriesTranslationNudge(
                    mangaId = sourceMangaId,
                    chapterId = 99L,
                    scope = "Cassie",
                    instruction = "Keep the oracle title consistent.",
                    active = true,
                    createdAt = 5L,
                    updatedAt = 6L,
                ),
            ),
            metadataValues = listOf(
                SeriesMetadataValue(
                    mangaId = sourceMangaId,
                    field = SeriesMetadataField.CHARACTERS.key,
                    providerType = MetadataProviderType.USER,
                    providerId = "manual",
                    providerName = "Manual",
                    value = "Cassie",
                    extraJson = "A major character.",
                    confidence = 0.8,
                    userLocked = false,
                    updatedAt = 10L,
                ),
            ),
            metadataChoices = listOf(
                SeriesMetadataChoice(
                    mangaId = sourceMangaId,
                    field = SeriesMetadataField.DESCRIPTION.key,
                    providerType = MetadataProviderType.USER,
                    providerId = "manual",
                    updatedAt = 11L,
                ),
            ),
            displayOptions = listOf(
                SeriesDisplayOption(
                    mangaId = sourceMangaId,
                    optionKey = "characters",
                    visible = true,
                    updatedAt = 12L,
                ),
            ),
        )

        val restored = BackupSeriesKnowledge.copyFrom(bundle).toBundle(targetMangaId)

        assertEquals(targetMangaId, restored.canon?.mangaId)
        assertEquals(targetMangaId, restored.entities.single().mangaId)
        assertEquals(targetMangaId, restored.relationships.single().mangaId)
        assertEquals(targetMangaId, restored.events.single().mangaId)
        assertEquals(targetMangaId, restored.nudges.single().mangaId)
        assertEquals(targetMangaId, restored.metadataValues.single().mangaId)
        assertEquals(targetMangaId, restored.metadataChoices.single().mangaId)
        assertEquals(targetMangaId, restored.displayOptions.single().mangaId)
        assertEquals("ally", restored.relationships.single().relationshipType)
        assertEquals("Forgotten shore", restored.events.single().title)
    }

    @Test
    fun `translated quote backup preserves displayed and original text`() {
        val quote = Quote(
            id = "quote-1",
            novelName = "Novel",
            chapterName = "Chapter 1",
            content = "Displayed translated text",
            originalContent = "Original source text",
            translatedContent = "Displayed translated text",
            language = "en",
            timestamp = 100L,
        )

        val restored = BackupNovelQuote.copyFrom(quote).toQuote()

        assertEquals("Displayed translated text", restored.content)
        assertEquals("Original source text", restored.originalContent)
        assertEquals("Displayed translated text", restored.translatedContent)
        assertEquals("en", restored.language)
    }

    @Test
    fun `backup preserves multiple character metadata values from one provider`() {
        val sourceMangaId = 10L
        val targetMangaId = 20L
        val bundle = SeriesKnowledgeBundle.Empty.copy(
            metadataValues = listOf(
                SeriesMetadataValue(
                    mangaId = sourceMangaId,
                    field = SeriesMetadataField.CHARACTERS.key,
                    providerType = MetadataProviderType.TRACKER,
                    providerId = "tracker#Cassie",
                    providerName = "Tracker",
                    value = "Cassie",
                    extraJson = null,
                    confidence = 0.8,
                    userLocked = false,
                    updatedAt = 1L,
                ),
                SeriesMetadataValue(
                    mangaId = sourceMangaId,
                    field = SeriesMetadataField.CHARACTERS.key,
                    providerType = MetadataProviderType.TRACKER,
                    providerId = "tracker#Sunny",
                    providerName = "Tracker",
                    value = "Sunny",
                    extraJson = null,
                    confidence = 0.8,
                    userLocked = false,
                    updatedAt = 2L,
                ),
            ),
        )

        val restored = BackupSeriesKnowledge.copyFrom(bundle).toBundle(targetMangaId)

        assertEquals(2, restored.metadataValues.size)
        assertEquals(listOf("Cassie", "Sunny"), restored.metadataValues.map { it.value })
        assertEquals(listOf(targetMangaId, targetMangaId), restored.metadataValues.map { it.mangaId })
    }

    @Test
    fun `backup manga model does not contain cached translated chapter bodies`() {
        val forbidden = BackupManga::class.java.declaredFields
            .map { it.name.lowercase() }
            .filter { it.contains("translationcache") || it.contains("cachedtranslation") }

        assertFalse(forbidden.isNotEmpty(), forbidden.joinToString())
    }
}
