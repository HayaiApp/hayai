package eu.kanade.tachiyomi.data.track.anilist.dto

import kotlinx.serialization.Serializable

@Serializable
data class ALSearchItem(
    val id: Long,
    val title: ALItemTitle,
    val coverImage: ItemCover,
    val description: String?,
    val format: String,
    val status: String?,
    val startDate: ALFuzzyDate,
    val chapters: Long?,
    val averageScore: Int?,
    val bannerImage: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<ALMediaTag> = emptyList(),
    val siteUrl: String? = null,
    val externalLinks: List<ALMediaExternalLink> = emptyList(),
) {
    fun toALManga(): ALManga = ALManga(
        remoteId = id,
        title = title.userPreferred,
        imageUrl = coverImage.large,
        description = description,
        format = format.replace("_", "-"),
        publishingStatus = status ?: "",
        startDateFuzzy = startDate.toEpochMilli(),
        totalChapters = chapters ?: 0,
        averageScore = averageScore ?: -1,
        bannerImage = bannerImage,
        genres = genres,
        tags = tags.mapNotNull { tag ->
            tag.name.trim().takeIf { it.isNotBlank() && !tag.isGeneralSpoiler && !tag.isMediaSpoiler }
        },
        siteUrl = siteUrl,
        externalLinks = externalLinks.mapNotNull { link ->
            link.url.trim().takeIf { it.isNotBlank() }
        },
    )
}

@Serializable
data class ALItemTitle(
    val userPreferred: String,
)

@Serializable
data class ItemCover(
    val large: String,
)

@Serializable
data class ALMediaTag(
    val name: String,
    val rank: Int? = null,
    val isGeneralSpoiler: Boolean = false,
    val isMediaSpoiler: Boolean = false,
)

@Serializable
data class ALMediaExternalLink(
    val url: String,
    val site: String? = null,
)
