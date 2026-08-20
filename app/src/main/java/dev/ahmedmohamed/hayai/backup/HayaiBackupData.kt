package dev.ahmedmohamed.hayai.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class HayaiBackupData(
    @ProtoNumber(1) val version: Int = CURRENT_VERSION,
    @ProtoNumber(2) val quotes: List<HayaiBackupQuote> = emptyList(),
    @ProtoNumber(3) val novelRepositories: List<HayaiBackupNovelRepository> = emptyList(),
    @ProtoNumber(4) val chapterStats: List<HayaiBackupChapterStat> = emptyList(),
    @ProtoNumber(5) val ehFavorites: List<HayaiBackupEhFavorite> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
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

data class HayaiRestoreReport(
    val restored: Int,
    val skipped: Int,
    val errors: List<String>,
)
