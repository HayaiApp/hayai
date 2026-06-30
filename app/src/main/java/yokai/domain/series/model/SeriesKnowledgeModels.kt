package yokai.domain.series.model

import eu.kanade.tachiyomi.domain.manga.models.Manga

enum class TranslationMode(val dbKey: String) {
    SIMPLE("simple"),
    ADVANCED("advanced");

    companion object {
        fun fromDbKey(value: String?): TranslationMode =
            entries.firstOrNull { it.dbKey == value } ?: SIMPLE
    }
}

enum class SeriesMetadataField(val key: String, val label: String) {
    TITLE("title", "Title"),
    COVER("cover", "Cover"),
    BANNER("banner", "Banner"),
    IMAGES("images", "Images"),
    ALIASES("aliases", "Aliases"),
    DESCRIPTION("description", "Description"),
    GENRES("genres", "Genres"),
    AUTHOR("author", "Author"),
    ARTIST("artist", "Artist"),
    STATUS("status", "Status"),
    CHARACTERS("characters", "Characters"),
    TRACKER_SUMMARY("tracker_summary", "Tracker summary");

    companion object {
        val editable = listOf(TITLE, AUTHOR, ARTIST, DESCRIPTION, GENRES, STATUS)
        val displayable = listOf(
            TITLE,
            COVER,
            BANNER,
            AUTHOR,
            ARTIST,
            DESCRIPTION,
            GENRES,
            ALIASES,
            CHARACTERS,
            IMAGES,
            TRACKER_SUMMARY,
        )

        fun fromKey(key: String): SeriesMetadataField? = entries.firstOrNull { it.key == key }
    }
}

enum class SeriesDisplaySection(val key: String, val label: String, val defaultVisible: Boolean) {
    TITLE("title", "Title", true),
    COVER_BANNER("cover_banner", "Cover and banner", true),
    AUTHORS("authors", "Authors", true),
    DESCRIPTION("description", "Description", true),
    GENRES("genres", "Genres", true),
    ALIASES("aliases", "Aliases", true),
    CHARACTERS("characters", "Characters", true),
    TRACKERS("trackers", "Tracker metadata", true),
    QUOTES_TRANSLATION("quotes_translation", "Quotes and translation", true),
    EXTRA_IMAGES("extra_images", "Extra images", true);

    companion object {
        fun fromKey(key: String): SeriesDisplaySection? = entries.firstOrNull { it.key == key }
    }
}

object MetadataProviderType {
    const val SOURCE = "source"
    const val USER = "user"
    const val TRACKER = "tracker"
    const val ADVANCED_TRANSLATION = "advanced_translation"
}

data class MetadataProviderRef(
    val type: String,
    val id: String,
    val name: String,
) {
    val stableKey: String = "$type:$id"
}

data class SeriesTranslationCanon(
    val mangaId: Long,
    val mode: TranslationMode,
    val sourceLanguage: String?,
    val targetLanguage: String?,
    val summary: String?,
    val styleGuide: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class SeriesTranslationEntity(
    val id: Long? = null,
    val mangaId: Long,
    val entityKey: String,
    val displayName: String,
    val originalName: String?,
    val translatedName: String?,
    val entityType: String,
    val gender: String?,
    val description: String?,
    val aliases: String?,
    val source: String,
    val confidence: Double?,
    val userLocked: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class SeriesTranslationRelationship(
    val id: Long? = null,
    val mangaId: Long,
    val fromEntityKey: String,
    val fromEntityName: String,
    val toEntityKey: String,
    val toEntityName: String,
    val relationshipType: String,
    val description: String?,
    val source: String,
    val userLocked: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class SeriesTranslationEvent(
    val id: Long? = null,
    val mangaId: Long,
    val chapterId: Long?,
    val title: String,
    val description: String,
    val sequenceIndex: Long?,
    val source: String,
    val userLocked: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class SeriesTranslationNudge(
    val id: Long? = null,
    val mangaId: Long,
    val chapterId: Long?,
    val scope: String,
    val instruction: String,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class SeriesMetadataValue(
    val id: Long? = null,
    val mangaId: Long,
    val field: String,
    val providerType: String,
    val providerId: String,
    val providerName: String,
    val value: String,
    val extraJson: String?,
    val confidence: Double?,
    val userLocked: Boolean,
    val updatedAt: Long,
) {
    val providerRef: MetadataProviderRef
        get() = MetadataProviderRef(providerType, providerId, providerName)
}

data class SeriesMetadataChoice(
    val mangaId: Long,
    val field: String,
    val providerType: String,
    val providerId: String,
    val updatedAt: Long,
)

data class SeriesDisplayOption(
    val mangaId: Long,
    val optionKey: String,
    val visible: Boolean,
    val updatedAt: Long,
)

data class SeriesKnowledgeBundle(
    val canon: SeriesTranslationCanon?,
    val entities: List<SeriesTranslationEntity>,
    val relationships: List<SeriesTranslationRelationship> = emptyList(),
    val events: List<SeriesTranslationEvent>,
    val nudges: List<SeriesTranslationNudge>,
    val metadataValues: List<SeriesMetadataValue>,
    val metadataChoices: List<SeriesMetadataChoice>,
    val displayOptions: List<SeriesDisplayOption>,
) {
    companion object {
        val Empty = SeriesKnowledgeBundle(
            canon = null,
            entities = emptyList(),
            relationships = emptyList(),
            events = emptyList(),
            nudges = emptyList(),
            metadataValues = emptyList(),
            metadataChoices = emptyList(),
            displayOptions = emptyList(),
        )
    }
}

fun Manga.sourceMetadataValues(now: Long = System.currentTimeMillis()): List<SeriesMetadataValue> {
    val mangaId = id ?: return emptyList()
    return buildList {
        add(
            SeriesMetadataValue(
                mangaId = mangaId,
                field = SeriesMetadataField.TITLE.key,
                providerType = MetadataProviderType.SOURCE,
                providerId = source.toString(),
                providerName = MetadataProviderType.SOURCE,
                value = originalTitle,
                extraJson = null,
                confidence = 1.0,
                userLocked = false,
                updatedAt = now,
            ),
        )
        originalAuthor?.takeIf { it.isNotBlank() }?.let {
            add(sourceValue(mangaId, SeriesMetadataField.AUTHOR, it, now))
        }
        originalArtist?.takeIf { it.isNotBlank() }?.let {
            add(sourceValue(mangaId, SeriesMetadataField.ARTIST, it, now))
        }
        originalDescription?.takeIf { it.isNotBlank() }?.let {
            add(sourceValue(mangaId, SeriesMetadataField.DESCRIPTION, it, now))
        }
        getOriginalGenres()?.takeIf { it.isNotEmpty() }?.let {
            add(sourceValue(mangaId, SeriesMetadataField.GENRES, it.joinToString(", "), now))
        }
        thumbnail_url?.takeIf { it.isNotBlank() }?.let {
            add(sourceValue(mangaId, SeriesMetadataField.COVER, it, now))
        }
        add(sourceValue(mangaId, SeriesMetadataField.STATUS, originalStatus.toString(), now))
    }
}

private fun Manga.sourceValue(
    mangaId: Long,
    field: SeriesMetadataField,
    value: String,
    now: Long,
): SeriesMetadataValue =
    SeriesMetadataValue(
        mangaId = mangaId,
        field = field.key,
        providerType = MetadataProviderType.SOURCE,
        providerId = source.toString(),
        providerName = MetadataProviderType.SOURCE,
        value = value,
        extraJson = null,
        confidence = 1.0,
        userLocked = false,
        updatedAt = now,
    )
