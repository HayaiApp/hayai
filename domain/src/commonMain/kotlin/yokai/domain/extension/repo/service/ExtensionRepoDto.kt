package yokai.domain.extension.repo.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import yokai.domain.extension.repo.model.ExtensionRepo

@Serializable
data class ExtensionRepoMetaDto(
    val meta: ExtensionRepoDto,
    @SerialName("index_v2") val indexV2: String? = null,
)

@Serializable
data class ExtensionRepoDto(
    val name: String,
    val shortName: String?,
    val website: String,
    val signingKeyFingerprint: String,
)

fun ExtensionRepoMetaDto.toExtensionRepo(baseUrl: String): ExtensionRepo {
    return ExtensionRepo(
        baseUrl = baseUrl,
        name = meta.name,
        shortName = meta.shortName,
        website = meta.website,
        signingKeyFingerprint = meta.signingKeyFingerprint,
    )
}
