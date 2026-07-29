package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import yokai.domain.extension.repo.model.ExtensionRepo

@Serializable
data class BackupExtensionRepo(
    @ProtoNumber(1) var baseUrl: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var shortName: String? = null,
    @ProtoNumber(4) var website: String,
    @ProtoNumber(5) var signingKeyFingerprint: String,
) {
    fun getExtensionRepo(): ExtensionRepo {
        return ExtensionRepo(
            baseUrl = baseUrl,
            name = name,
            shortName = shortName,
            website = website,
            signingKeyFingerprint = signingKeyFingerprint,
        )
    }

    companion object {
        fun copyFrom(repo: ExtensionRepo): BackupExtensionRepo {
            return BackupExtensionRepo(
                baseUrl = repo.baseUrl,
                name = repo.name,
                shortName = repo.shortName,
                website = repo.website,
                signingKeyFingerprint = repo.signingKeyFingerprint,
            )
        }
    }
}
