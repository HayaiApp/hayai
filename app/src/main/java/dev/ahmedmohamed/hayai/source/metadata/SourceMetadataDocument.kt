package dev.ahmedmohamed.hayai.source.metadata

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMetadata
import dev.ahmedmohamed.hayai.source.SourceFamily
import dev.ahmedmohamed.hayai.source.enhanced.EnhancedDescriptionLabel
import dev.ahmedmohamed.hayai.source.enhanced.EnhancedDetails
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMangaIdentity
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMetadataTag
import dev.ahmedmohamed.hayai.adult.eh.persistence.SourceMetadataTitle

enum class SourceMetadataKey {
    AlternativeTitle,
    AlternativeTitles,
    ArchiveType,
    Artist,
    BaseUrl,
    Category,
    Description,
    EnglishTitle,
    Favorites,
    File,
    FileSize,
    GalleryId,
    JapaneseTitle,
    IsExHentai,
    Language,
    Length,
    MediaId,
    Pages,
    Posted,
    Path,
    Parent,
    Rating,
    RatingCount,
    Scanlator,
    ShortTitle,
    Summary,
    Translated,
    ThumbnailUrl,
    Token,
    Uploader,
    Url,
    Visibility,
}

data class SourceMetadataField(
    val key: SourceMetadataKey,
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

data class SourceMetadataTagValue(
    val namespace: String?,
    val name: String,
) {
    init {
        require(namespace == null || namespace.isNotBlank())
        require(name.isNotBlank())
    }

    val searchableValue: String
        get() = namespace?.let { "$it:$name" } ?: name
}

data class SourceMetadataDocument(
    val family: SourceFamily,
    val titles: List<String>,
    val fields: List<SourceMetadataField>,
    val tags: List<SourceMetadataTagValue>,
) {
    init {
        require(titles.none(String::isBlank))
        require(titles.distinct().size == titles.size)
        require(fields.distinctBy(SourceMetadataField::key).size == fields.size)
        require(tags.distinct().size == tags.size)
    }

    fun value(key: SourceMetadataKey): String? = fields.firstOrNull { it.key == key }?.value
}

object SourceMetadataDocuments {
    private val json = Json { ignoreUnknownKeys = true }

    fun fromEh(metadata: SourceMetadata): SourceMetadataDocument {
        val extra = runCatching { json.parseToJsonElement(metadata.extra) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val family = EhSite.entries.firstOrNull { it.sourceId == metadata.identity.sourceId }?.let {
            if (it == EhSite.ExHentai) SourceFamily.ExHentai else SourceFamily.EHentai
        } ?: SourceFamily.EHentai
        return SourceMetadataDocument(
            family = family,
            titles = metadata.titles.sortedBy { it.type }.map { it.title }.distinct(),
            fields = fields(
                SourceMetadataKey.GalleryId to metadata.indexedExtra,
                SourceMetadataKey.Token to extra.string("token"),
                SourceMetadataKey.ThumbnailUrl to extra.string("thumbnail"),
                SourceMetadataKey.IsExHentai to (family == SourceFamily.ExHentai).toString(),
                SourceMetadataKey.Uploader to metadata.uploader,
                SourceMetadataKey.Category to extra.string("category"),
                SourceMetadataKey.Language to extra.string("language"),
                SourceMetadataKey.Translated to extra.boolean("translated")?.toString(),
                SourceMetadataKey.Pages to extra.string("pageCount"),
                SourceMetadataKey.FileSize to extra.string("sizeBytes"),
                SourceMetadataKey.Favorites to extra.string("favoriteCount"),
                SourceMetadataKey.RatingCount to extra.string("ratingCount"),
                SourceMetadataKey.Rating to extra.string("averageRating"),
                SourceMetadataKey.Visibility to extra.string("visible"),
                SourceMetadataKey.Posted to extra.string("postedAt"),
                SourceMetadataKey.Parent to extra.string("parent"),
            ),
            tags = metadata.tags.map { SourceMetadataTagValue(it.namespace, it.name) }.distinct(),
        )
    }

    fun fromEnhanced(family: SourceFamily, details: EnhancedDetails): SourceMetadataDocument =
        SourceMetadataDocument(
            family = family,
            titles = listOfNotNull(details.title, details.alternateTitle).map(String::trim).filter(String::isNotBlank).distinct(),
            fields = buildList {
                details.descriptionRows.filterNot { it.label == EnhancedDescriptionLabel.Tags }.mapNotNullTo(this) { row ->
                    row.value.trim().takeIf(String::isNotBlank)?.let { SourceMetadataField(row.label.toMetadataKey(), it) }
                }
                details.thumbnailUrl?.trim()?.takeIf(String::isNotBlank)?.let {
                    add(SourceMetadataField(SourceMetadataKey.ThumbnailUrl, it))
                }
            }.distinctBy(SourceMetadataField::key),
            tags = details.genres.mapNotNull(::parseTag).distinct(),
        )

    fun fromStoredEnhanced(metadata: SourceMetadata): SourceMetadataDocument? {
        val extra = runCatching { json.parseToJsonElement(metadata.extra) as? JsonObject }.getOrNull() ?: return null
        val family = extra.string(STORED_FAMILY)?.let { runCatching { SourceFamily.valueOf(it) }.getOrNull() } ?: return null
        val fieldsObject = extra[STORED_FIELDS] as? JsonObject ?: JsonObject(emptyMap())
        return SourceMetadataDocument(
            family = family,
            titles = metadata.titles.sortedBy { it.type }.map { it.title }.distinct(),
            fields = fieldsObject.mapNotNull { (key, value) ->
                val metadataKey = runCatching { SourceMetadataKey.valueOf(key) }.getOrNull() ?: return@mapNotNull null
                (value as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let { SourceMetadataField(metadataKey, it) }
            },
            tags = metadata.tags.map { SourceMetadataTagValue(it.namespace, it.name) }.distinct(),
        )
    }

    fun toStoredEnhanced(
        identity: SourceMangaIdentity,
        family: SourceFamily,
        details: EnhancedDetails,
    ): SourceMetadata {
        val document = fromEnhanced(family, details)
        val extra = buildJsonObject {
            put(STORED_FAMILY, family.name)
            put(
                STORED_FIELDS,
                buildJsonObject {
                    document.fields.forEach { field -> put(field.key.name, field.value) }
                },
            )
        }.toString()
        return SourceMetadata(
            identity = identity,
            uploader = document.value(SourceMetadataKey.Uploader),
            extra = extra,
            indexedExtra = document.value(SourceMetadataKey.GalleryId) ?: document.value(SourceMetadataKey.MediaId),
            extraVersion = STORED_VERSION,
            tags = document.tags.map { SourceMetadataTag(it.namespace, it.name, 0) },
            titles = document.titles.mapIndexed { index, title -> SourceMetadataTitle(title, index) },
        )
    }

    private fun fields(vararg values: Pair<SourceMetadataKey, String?>): List<SourceMetadataField> =
        values.mapNotNull { (key, value) -> value?.trim()?.takeIf(String::isNotBlank)?.let { SourceMetadataField(key, it) } }

    private fun parseTag(value: String): SourceMetadataTagValue? {
        val normalized = value.trim().takeIf(String::isNotBlank) ?: return null
        val separator = normalized.indexOf(':')
        return if (separator > 0) {
            SourceMetadataTagValue(normalized.substring(0, separator).trim(), normalized.substring(separator + 1).trim())
        } else {
            SourceMetadataTagValue(null, normalized)
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    private const val STORED_FAMILY = "hayaiFamily"
    private const val STORED_FIELDS = "fields"
    private const val STORED_VERSION = 1
}

private fun EnhancedDescriptionLabel.toMetadataKey(): SourceMetadataKey = when (this) {
    EnhancedDescriptionLabel.AlternativeTitle -> SourceMetadataKey.AlternativeTitle
    EnhancedDescriptionLabel.AlternativeTitles -> SourceMetadataKey.AlternativeTitles
    EnhancedDescriptionLabel.ArchiveType -> SourceMetadataKey.ArchiveType
    EnhancedDescriptionLabel.Artist -> SourceMetadataKey.Artist
    EnhancedDescriptionLabel.BaseUrl -> SourceMetadataKey.BaseUrl
    EnhancedDescriptionLabel.Description -> SourceMetadataKey.Description
    EnhancedDescriptionLabel.EnglishTitle -> SourceMetadataKey.EnglishTitle
    EnhancedDescriptionLabel.Favorites -> SourceMetadataKey.Favorites
    EnhancedDescriptionLabel.File -> SourceMetadataKey.File
    EnhancedDescriptionLabel.FileSize -> SourceMetadataKey.FileSize
    EnhancedDescriptionLabel.Id -> SourceMetadataKey.GalleryId
    EnhancedDescriptionLabel.JapaneseTitle -> SourceMetadataKey.JapaneseTitle
    EnhancedDescriptionLabel.Length -> SourceMetadataKey.Length
    EnhancedDescriptionLabel.MediaId -> SourceMetadataKey.MediaId
    EnhancedDescriptionLabel.Pages -> SourceMetadataKey.Pages
    EnhancedDescriptionLabel.Path -> SourceMetadataKey.Path
    EnhancedDescriptionLabel.Posted -> SourceMetadataKey.Posted
    EnhancedDescriptionLabel.Rating -> SourceMetadataKey.Rating
    EnhancedDescriptionLabel.RatingCount -> SourceMetadataKey.RatingCount
    EnhancedDescriptionLabel.Scanlator -> SourceMetadataKey.Scanlator
    EnhancedDescriptionLabel.ShortTitle -> SourceMetadataKey.ShortTitle
    EnhancedDescriptionLabel.Summary -> SourceMetadataKey.Summary
    EnhancedDescriptionLabel.Tags -> error("Tags are represented by SourceMetadataDocument.tags")
    EnhancedDescriptionLabel.ThumbnailUrl -> SourceMetadataKey.ThumbnailUrl
    EnhancedDescriptionLabel.Token -> SourceMetadataKey.Token
    EnhancedDescriptionLabel.Uploader -> SourceMetadataKey.Uploader
    EnhancedDescriptionLabel.Url -> SourceMetadataKey.Url
}
