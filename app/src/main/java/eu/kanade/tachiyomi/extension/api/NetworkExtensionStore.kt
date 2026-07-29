package eu.kanade.tachiyomi.extension.api

import android.annotation.SuppressLint
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Mirrors the "ExtensionStore" index format Keiyoushi/Mihon migrated to (served as protobuf at
 * index_v2, or as a JSON object with the same shape). The older `index.min.json` array format
 * (see [ExtensionJsonObject]) is still understood and used as a fallback.
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: Contact,
    @ProtoNumber(101) val extensionList: ExtensionList?,
    @ProtoNumber(102) val extensionListUrl: String?,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String,
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(@ProtoNumber(1) val extensions: List<StoreExtension>)

    @Serializable
    data class StoreExtension(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val packageName: String,
        @ProtoNumber(3) val resources: Resources,
        @ProtoNumber(4) val extensionLib: String,
        @ProtoNumber(5) val versionCode: Long,
        @ProtoNumber(6) val versionName: String,
        @ProtoNumber(7) val contentWarning: ContentWarning,
        @ProtoNumber(8) val sources: List<StoreSource>,
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String,
        @ProtoNumber(2) val iconUrl: String,
    )

    @Serializable
    data class StoreSource(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val language: String,
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(7) val message: String? = null,
    )

    @Suppress("unused")
    enum class ContentWarning {
        @ProtoNumber(0)
        @JsonNames("CONTENT_WARNING_UNSPECIFIED")
        UNSPECIFIED,

        @ProtoNumber(1)
        @JsonNames("CONTENT_WARNING_SAFE")
        SAFE,

        @ProtoNumber(2)
        @JsonNames("CONTENT_WARNING_MIXED")
        MIXED,

        @ProtoNumber(3)
        @JsonNames("CONTENT_WARNING_NSFW")
        NSFW,
    }
}

@Serializable
data class NetworkExtensionStoreList(
    @SerialName("extensions") val extensions: List<NetworkExtensionStore.StoreExtension>,
)

fun NetworkExtensionStore.ExtensionList.toExtensions(repoUrl: String): List<Extension.Available> {
    return extensions.toExtensions(repoUrl)
}

fun List<NetworkExtensionStore.StoreExtension>.toExtensions(repoUrl: String): List<Extension.Available> {
    return map { extension ->
        val lang = extension.sources.map { it.language }.toSet()
        Extension.Available(
            name = extension.name,
            pkgName = extension.packageName,
            versionName = extension.versionName,
            versionCode = extension.versionCode,
            libVersion = extension.extensionLib.toDouble(),
            lang = if (lang.size == 1) lang.first() else "all",
            isNsfw = extension.contentWarning >= NetworkExtensionStore.ContentWarning.MIXED,
            apkName = extension.resources.apkUrl,
            iconUrl = extension.resources.iconUrl,
            sources = extension.sources.map { source ->
                Extension.AvailableSource(
                    id = source.id,
                    name = source.name,
                    lang = source.language,
                    baseUrl = source.homeUrl,
                )
            },
            repoUrl = repoUrl,
        )
    }
}
