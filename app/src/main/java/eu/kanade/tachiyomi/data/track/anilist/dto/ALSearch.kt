package eu.kanade.tachiyomi.data.track.anilist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ALSearchResult(
    val data: ALSearchPage,
)

@Serializable
data class ALMediaResult(
    val data: ALMediaData,
)

@Serializable
data class ALMediaData(
    @SerialName("Media")
    val media: ALSearchItem,
)

@Serializable
data class ALSearchPage(
    @SerialName("Page")
    val page: ALSearchMedia,
)

@Serializable
data class ALSearchMedia(
    val media: List<ALSearchItem>,
)
