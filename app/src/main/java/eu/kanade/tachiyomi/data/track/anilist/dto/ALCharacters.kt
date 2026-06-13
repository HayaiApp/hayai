package eu.kanade.tachiyomi.data.track.anilist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ALCharactersResult(
    val data: ALCharactersData,
)

@Serializable
data class ALCharactersData(
    @SerialName("Media")
    val media: ALCharactersMedia? = null,
)

@Serializable
data class ALCharactersMedia(
    val characters: ALCharacterConnection? = null,
)

@Serializable
data class ALCharacterConnection(
    val edges: List<ALCharacterEdge> = emptyList(),
)

@Serializable
data class ALCharacterEdge(
    val role: String? = null,
    val node: ALCharacterNode? = null,
)

@Serializable
data class ALCharacterNode(
    val id: Long? = null,
    val name: ALCharacterName? = null,
    val image: ALCharacterImage? = null,
    val gender: String? = null,
    val description: String? = null,
    val siteUrl: String? = null,
)

@Serializable
data class ALCharacterName(
    val full: String? = null,
    val native: String? = null,
    val userPreferred: String? = null,
)

@Serializable
data class ALCharacterImage(
    val large: String? = null,
    val medium: String? = null,
)

data class ALCharacterMetadata(
    val id: Long?,
    val name: String,
    val role: String?,
    val imageUrl: String?,
    val gender: String?,
    val description: String?,
    val siteUrl: String?,
)

fun ALCharactersResult.toCharacterMetadata(): List<ALCharacterMetadata> =
    data.media
        ?.characters
        ?.edges
        .orEmpty()
        .mapNotNull { edge ->
            val node = edge.node ?: return@mapNotNull null
            val name = node.name?.userPreferred
                ?: node.name?.full
                ?: node.name?.native
                ?: return@mapNotNull null
            name.trim().takeIf { it.isNotBlank() }?.let {
                ALCharacterMetadata(
                    id = node.id,
                    name = it,
                    role = edge.role,
                    imageUrl = node.image?.large ?: node.image?.medium,
                    gender = node.gender,
                    description = node.description,
                    siteUrl = node.siteUrl ?: node.id?.let { id -> "https://anilist.co/character/$id" },
                )
            }
        }
        .distinctBy { it.name.lowercase() }
