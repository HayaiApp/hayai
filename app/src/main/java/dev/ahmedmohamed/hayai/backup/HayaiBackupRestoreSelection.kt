package dev.ahmedmohamed.hayai.backup

import eu.kanade.tachiyomi.data.backup.BackupConst

fun HayaiBackupData.selectForRestore(flags: Int): HayaiBackupData {
    val library = flags and BackupConst.RESTORE_LIBRARY != 0
    val categories = flags and BackupConst.RESTORE_CATEGORY != 0
    val repositories = flags and BackupConst.RESTORE_EXTENSION_REPOS != 0
    val sources = flags and BackupConst.RESTORE_SOURCE_PREFS != 0
    return copy(
        quotes = if (library) quotes else emptyList(),
        chapterStats = if (library) chapterStats else emptyList(),
        ehFavorites = if (library) ehFavorites else emptyList(),
        ehGalleryAliases = if (library) ehGalleryAliases else emptyList(),
        sourceMetadata = if (library) sourceMetadata else emptyList(),
        novelHighlights = if (library) novelHighlights else emptyList(),
        novelTranslations = if (library) novelTranslations else emptyList(),
        ehCategoryMappings = if (categories) ehCategoryMappings else emptyList(),
        novelRepositories = if (repositories) novelRepositories else emptyList(),
        novelApkRepositories = if (repositories) novelApkRepositories else emptyList(),
        novelPlugins = if (sources) novelPlugins else emptyList(),
        novelCustomSources = if (sources) novelCustomSources else emptyList(),
    )
}
