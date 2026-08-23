package dev.ahmedmohamed.hayai.extension

data class ApkLoadFailure(
    val packageName: String,
    val displayName: String = packageName,
    val versionName: String? = null,
    val reason: Reason = Reason.Unknown,
) {
    enum class Reason {
        PackageMissing,
        VersionMissing,
        UnsupportedLibrary,
        UnsupportedManifest,
        Unsigned,
        AdultSourcesDisabled,
        ClassLoader,
        ClassMetadata,
        SourceConstruction,
        EmptySources,
        NovelContract,
        Unknown,
    }
}
