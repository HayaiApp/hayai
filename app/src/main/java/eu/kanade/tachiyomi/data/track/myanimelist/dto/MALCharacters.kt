package eu.kanade.tachiyomi.data.track.myanimelist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MALCharactersResult(
    val data: List<MALCharacterEntry> = emptyList(),
)

@Serializable
data class MALCharacterEntry(
    val character: MALCharacterNode? = null,
    val role: String? = null,
)

@Serializable
data class MALCharacterNode(
    @SerialName("mal_id")
    val id: Long? = null,
    val url: String? = null,
    val name: String? = null,
    val images: MALCharacterImages? = null,
)

@Serializable
data class MALCharacterImages(
    val jpg: MALCharacterImage? = null,
    val webp: MALCharacterImage? = null,
)

@Serializable
data class MALCharacterImage(
    @SerialName("image_url")
    val imageUrl: String? = null,
)

data class MALCharacterMetadata(
    val id: Long?,
    val name: String,
    val role: String?,
    val imageUrl: String?,
    val url: String?,
)

fun MALCharactersResult.toCharacterMetadata(): List<MALCharacterMetadata> =
    data
        .mapNotNull { entry ->
            val character = entry.character ?: return@mapNotNull null
            val name = character.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MALCharacterMetadata(
                id = character.id,
                name = name,
                role = entry.role,
                imageUrl = character.images?.jpg?.imageUrl ?: character.images?.webp?.imageUrl,
                url = character.url,
            )
        }
        .distinctBy { it.name.lowercase() }
