package dev.ahmedmohamed.hayai.novel.extension

/**
 * Manifest compatibility for Tsundoku novel APKs.
 *
 * Rebuilt from the behavior audited at Tsundoku 547ddea3; the Android loader remains
 * J2K-owned and consumes only this small, pure contract.
 */
internal object NovelExtensionManifest {
    const val STANDARD_FEATURE = "tachiyomi.extension"
    const val NOVEL_FEATURE = "tachiyomi.novelextension"
    const val DISPLAY_NAME_KEY = "tachiyomix.name"
    const val EXTENSION_LIB_KEY = "tachiyomix.extensionLib"
    const val CONTENT_WARNING_KEY = "tachiyomix.contentWarning"

    private val supportedFeatures = setOf(STANDARD_FEATURE, NOVEL_FEATURE)

    fun isSupported(requiredFeatures: Iterable<String?>): Boolean = requiredFeatures.any { it in supportedFeatures }

    fun resolve(
        requiredFeatures: Set<String>,
        metadataKeys: Set<String>,
    ): ExtensionManifestNamespace? {
        val hasNovel = NOVEL_FEATURE in requiredFeatures
        val hasStandard = STANDARD_FEATURE in requiredFeatures
        if (!hasNovel && !hasStandard) return null

        return when {
            hasNovel && "$NOVEL_FEATURE.class" in metadataKeys -> ExtensionManifestNamespace(NOVEL_FEATURE, isNovel = true)
            hasStandard && "$STANDARD_FEATURE.class" in metadataKeys -> ExtensionManifestNamespace(STANDARD_FEATURE, isNovel = false)
            hasNovel -> ExtensionManifestNamespace(NOVEL_FEATURE, isNovel = true)
            else -> ExtensionManifestNamespace(STANDARD_FEATURE, isNovel = false)
        }
    }

    fun displayName(
        applicationLabel: String,
        metadataName: String? = null,
    ): String =
        metadataName
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: applicationLabel
            .removePrefix("Tachiyomi: ")
            .removePrefix("Tsundoku: ")
            .trim()

    fun libraryVersion(
        versionName: String,
        metadataVersion: Any?,
    ): Double? =
        when (metadataVersion) {
            // Android manifest decimal values are exposed as Float. Convert through their
            // canonical decimal form so 1.6 does not become 1.600000023841858 and fail the
            // loader's upper-bound check.
            is Number -> metadataVersion.toString().toDoubleOrNull()?.takeUnless { it == 0.0 }
            is String -> metadataVersion.trim().toDoubleOrNull()?.takeUnless { it == 0.0 }
            else -> null
        } ?: versionName.substringBeforeLast('.').toDoubleOrNull()

    fun classCandidates(
        declaredName: String,
        packageName: String,
    ): List<String> {
        val absolute = if (declaredName.startsWith('.')) packageName + declaredName else declaredName
        return buildList {
            add(absolute)
            if (absolute.startsWith(TSUNDOKU_EXTENSION_PREFIX)) {
                add(absolute.replaceFirst(TSUNDOKU_EXTENSION_PREFIX, TACHIYOMI_EXTENSION_PREFIX))
            }
        }.distinct()
    }

    private const val TSUNDOKU_EXTENSION_PREFIX = "app.tsundoku.extension."
    private const val TACHIYOMI_EXTENSION_PREFIX = "eu.kanade.tachiyomi.extension."
}

internal data class ExtensionManifestNamespace(
    val prefix: String,
    val isNovel: Boolean,
) {
    val classKey: String = "$prefix.class"
    val factoryKey: String = "$prefix.factory"
    val nsfwKey: String = "$prefix.nsfw"
}
