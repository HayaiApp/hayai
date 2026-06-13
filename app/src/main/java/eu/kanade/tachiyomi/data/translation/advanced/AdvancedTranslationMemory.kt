package eu.kanade.tachiyomi.data.translation.advanced

import eu.kanade.tachiyomi.data.translation.TranslationHtmlUtils
import yokai.domain.series.SeriesKnowledgeRepository
import yokai.domain.series.model.MetadataProviderType
import yokai.domain.series.model.SeriesMetadataField
import yokai.domain.series.model.SeriesMetadataValue
import yokai.domain.series.model.SeriesTranslationCanon
import yokai.domain.series.model.SeriesTranslationEntity
import yokai.domain.series.model.TranslationMode

class AdvancedTranslationMemory(
    private val repository: SeriesKnowledgeRepository,
) {
    suspend fun buildPromptPrefix(
        mangaId: Long?,
        mangaTitle: String?,
        sourceLanguage: String,
        targetLanguage: String,
    ): String {
        mangaId ?: return ""
        val bundle = repository.get(mangaId)
        if (
            bundle.canon == null &&
            bundle.entities.isEmpty() &&
            bundle.relationships.isEmpty() &&
            bundle.nudges.isEmpty()
        ) {
            return ""
        }

        val title = mangaTitle?.takeIf { it.isNotBlank() }
            ?: bundle.metadataValues.firstOrNull { it.field == SeriesMetadataField.TITLE.key }?.value
            ?: "this series"
        val entities = bundle.entities.take(80).joinToString("\n") { entity ->
            val names = listOfNotNull(
                entity.displayName,
                entity.originalName?.takeIf { it != entity.displayName },
                entity.translatedName?.takeIf { it != entity.displayName },
            ).distinct().joinToString(" / ")
            val gender = entity.gender?.let { "; gender: $it" }.orEmpty()
            val note = entity.description?.take(240)?.let { "; note: $it" }.orEmpty()
            "- ${entity.entityType}: $names$gender$note"
        }
        val events = bundle.events.takeLast(24).joinToString("\n") { event ->
            "- ${event.title}: ${event.description.take(240)}"
        }
        val relationships = bundle.relationships.take(80).joinToString("\n") { relationship ->
            val note = relationship.description?.take(240)?.let { "; note: $it" }.orEmpty()
            "- ${relationship.fromEntityName} -> ${relationship.toEntityName}: ${relationship.relationshipType}$note"
        }
        val nudges = bundle.nudges.filter { it.active }.take(24).joinToString("\n") { nudge ->
            "- ${nudge.scope}: ${nudge.instruction}"
        }
        return buildString {
            appendLine("=== SERIES TRANSLATION MEMORY ===")
            appendLine("Series: $title")
            appendLine("Translate from $sourceLanguage to $targetLanguage.")
            appendLine("Keep names, genders, titles, recurring terms, relationship labels, and prior events consistent.")
            appendLine("Do not repeat this memory in the answer; only translate the text after TEXT TO TRANSLATE.")
            bundle.canon?.summary?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Series summary:")
                appendLine(it.take(1200))
            }
            bundle.canon?.styleGuide?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Style guide:")
                appendLine(it.take(1200))
            }
            if (entities.isNotBlank()) {
                appendLine()
                appendLine("Known glossary and cast:")
                appendLine(entities)
            }
            if (relationships.isNotBlank()) {
                appendLine()
                appendLine("Known relationships:")
                appendLine(relationships)
            }
            if (events.isNotBlank()) {
                appendLine()
                appendLine("Recent events:")
                appendLine(events)
            }
            if (nudges.isNotBlank()) {
                appendLine()
                appendLine("User nudges:")
                appendLine(nudges)
            }
            appendLine("=== TEXT TO TRANSLATE ===")
        }.trim()
    }

    suspend fun ingestChapter(
        mangaId: Long?,
        sourceLanguage: String,
        targetLanguage: String,
        originalHtml: String,
        translatedHtml: String,
    ) {
        mangaId ?: return
        val now = System.currentTimeMillis()
        val existing = repository.get(mangaId)
        if (existing.canon == null) {
            repository.upsertTranslationCanon(
                SeriesTranslationCanon(
                    mangaId = mangaId,
                    mode = TranslationMode.ADVANCED,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    summary = null,
                    styleGuide = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        val existingKeys = existing.entities.map { it.entityKey }.toSet()
        val plain = TranslationHtmlUtils.extractTextFromHtml(originalHtml)
        val names = extractCandidateNames(plain)
            .filterNot { it.lowercase() in commonWords }
            .filterNot { normalizeKey(it) in existingKeys }
            .take(24)
        if (names.isNotEmpty()) {
            repository.upsertTranslationEntities(
                names.map { name ->
                    SeriesTranslationEntity(
                        mangaId = mangaId,
                        entityKey = normalizeKey(name),
                        displayName = name,
                        originalName = name,
                        translatedName = null,
                        entityType = "character",
                        gender = null,
                        description = null,
                        aliases = null,
                        source = MetadataProviderType.ADVANCED_TRANSLATION,
                        confidence = 0.45,
                        userLocked = false,
                        createdAt = now,
                        updatedAt = now,
                    )
                },
            )
        }

        val translatedPlain = TranslationHtmlUtils.extractTextFromHtml(translatedHtml)
        val summary = translatedPlain
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.length > 80 }
            ?.take(600)
        if (summary != null && existing.canon?.summary.isNullOrBlank()) {
            repository.upsertTranslationCanon(
                SeriesTranslationCanon(
                    mangaId = mangaId,
                    mode = TranslationMode.ADVANCED,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    summary = summary,
                    styleGuide = existing.canon?.styleGuide,
                    createdAt = existing.canon?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun seedMetadata(values: List<SeriesMetadataValue>) {
        repository.upsertMetadataValues(values)
    }

    private fun extractCandidateNames(text: String): List<String> {
        val latinNames = Regex("""\b[A-Z][a-zA-Z'’-]{2,}(?:\s+[A-Z][a-zA-Z'’-]{2,}){0,2}\b""")
            .findAll(text)
            .map { it.value.trim() }
            .filter { it.length in 3..80 }
            .toList()
        return latinNames
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= 2 }
            .keys
            .sortedBy { it.length }
    }

    private fun normalizeKey(value: String): String =
        value.lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "_")
            .trim('_')

    private val commonWords = setOf(
        "the", "this", "that", "after", "before", "chapter", "english", "japanese",
        "however", "because", "while", "when", "where", "there", "their", "they",
    )
}
