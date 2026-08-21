package dev.ahmedmohamed.hayai.backup

import dev.ahmedmohamed.hayai.novel.highlight.NovelHighlightBackup
import dev.ahmedmohamed.hayai.novel.source.builder.NovelCustomSourceDefinition
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class HayaiBackupData(
    @ProtoNumber(1) val version: Int = 1,
    @ProtoNumber(2) val quotes: List<HayaiBackupQuote> = emptyList(),
    @ProtoNumber(3) val novelRepositories: List<HayaiBackupNovelRepository> = emptyList(),
    @ProtoNumber(4) val chapterStats: List<HayaiBackupChapterStat> = emptyList(),
    @ProtoNumber(5) val ehFavorites: List<HayaiBackupEhFavorite> = emptyList(),
    @ProtoNumber(6) val novelPlugins: List<HayaiBackupNovelPlugin> = emptyList(),
    @ProtoNumber(7) val ehGalleryAliases: List<HayaiBackupEhGalleryAlias> = emptyList(),
    @ProtoNumber(8) val sourceMetadata: List<HayaiBackupSourceMetadata> = emptyList(),
    @ProtoNumber(9) val ehCategoryMappings: List<HayaiBackupEhCategoryMapping> = emptyList(),
    @ProtoNumber(10) val novelHighlights: List<NovelHighlightBackup> = emptyList(),
    @ProtoNumber(11) val novelCustomSources: List<NovelCustomSourceDefinition> = emptyList(),
    @ProtoNumber(12) val novelApkRepositories: List<String> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 4
        const val MINIMUM_SUPPORTED_VERSION = 1
    }
}

@Serializable
data class HayaiBackupQuote(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val sourceId: Long,
    @ProtoNumber(3) val mangaUrl: String,
    @ProtoNumber(4) val novelName: String,
    @ProtoNumber(5) val chapterName: String,
    @ProtoNumber(6) val displayedContent: String,
    @ProtoNumber(7) val originalContent: String? = null,
    @ProtoNumber(8) val translatedContent: String? = null,
    @ProtoNumber(9) val language: String? = null,
    @ProtoNumber(10) val timestamp: Long,
)

@Serializable
data class HayaiBackupNovelRepository(
    @ProtoNumber(1) val baseUrl: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val enabled: Boolean = true,
)

@Serializable
data class HayaiBackupChapterStat(
    @ProtoNumber(1) val sourceId: Long,
    @ProtoNumber(2) val mangaUrl: String,
    @ProtoNumber(3) val chapterUrl: String,
    @ProtoNumber(4) val wordCount: Long,
)

@Serializable
data class HayaiBackupEhFavorite(
    @ProtoNumber(1) val gid: String,
    @ProtoNumber(2) val token: String,
    @ProtoNumber(3) val title: String,
    @ProtoNumber(4) val category: Int,
)

@Serializable
data class HayaiBackupEhGalleryAlias(
    @ProtoNumber(1) val canonicalGid: String,
    @ProtoNumber(2) val canonicalToken: String,
    @ProtoNumber(3) val alternateGid: String,
    @ProtoNumber(4) val alternateToken: String,
)

@Serializable
data class HayaiBackupEhCategoryMapping(
    @ProtoNumber(1) val slot: Int,
    @ProtoNumber(2) val remoteName: String,
    @ProtoNumber(3) val localCategoryName: String,
)

@Serializable
data class HayaiBackupSourceMetadata(
    @ProtoNumber(1) val sourceId: Long,
    @ProtoNumber(2) val mangaUrl: String,
    @ProtoNumber(3) val uploader: String? = null,
    @ProtoNumber(4) val extra: String,
    @ProtoNumber(5) val indexedExtra: String? = null,
    @ProtoNumber(6) val extraVersion: Int,
    @ProtoNumber(7) val tags: List<HayaiBackupSourceMetadataTag> = emptyList(),
    @ProtoNumber(8) val titles: List<HayaiBackupSourceMetadataTitle> = emptyList(),
)

@Serializable
data class HayaiBackupSourceMetadataTag(
    @ProtoNumber(1) val namespace: String? = null,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val type: Int,
)

@Serializable
data class HayaiBackupSourceMetadataTitle(
    @ProtoNumber(1) val title: String,
    @ProtoNumber(2) val type: Int,
)

@Serializable
data class HayaiBackupNovelPlugin(
    @ProtoNumber(1) val descriptorJson: String,
    @ProtoNumber(2) val repositoryUrl: String,
    @ProtoNumber(3) val code: ByteArray,
    @ProtoNumber(4) val preferences: List<HayaiBackupPluginPreference> = emptyList(),
)

@Serializable
data class HayaiBackupPluginPreference(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val value: String,
)

data class HayaiRestoreReport(
    val restored: Int,
    val skipped: Int,
    val errors: List<String>,
)
