package dev.ahmedmohamed.hayai.adult.eh.persistence

data class SourceMangaIdentity(
    val sourceId: Long,
    val mangaUrl: String,
) {
    init {
        require(mangaUrl.isNotBlank() && mangaUrl.length <= 8_192) { "Invalid manga URL" }
    }
}

data class EhGalleryIdentity(
    val gid: String,
    val token: String,
) {
    init {
        require(gid.toLongOrNull()?.let { it > 0 } == true) { "Invalid gallery ID" }
        require(TOKEN.matches(token)) { "Invalid gallery token" }
    }

    companion object {
        private val TOKEN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

data class EhFavoriteSnapshot(
    val gallery: EhGalleryIdentity,
    val title: String,
    val categorySlot: Int,
) {
    init {
        require(title.isNotBlank() && title.length <= 8_192) { "Invalid favorite title" }
        require(categorySlot in 0..9) { "Invalid favorite category" }
    }
}

data class EhGalleryAlias(
    val canonical: EhGalleryIdentity,
    val alternate: EhGalleryIdentity,
) {
    init {
        require(canonical != alternate) { "A gallery cannot alias itself" }
    }
}

data class SourceMetadataTag(
    val namespace: String?,
    val name: String,
    val type: Int,
) {
    init {
        require(namespace == null || namespace.length <= 256) { "Invalid metadata tag namespace" }
        require(name.isNotBlank() && name.length <= 2_048) { "Invalid metadata tag" }
    }
}

data class SourceMetadataTitle(
    val title: String,
    val type: Int,
) {
    init {
        require(title.isNotBlank() && title.length <= 8_192) { "Invalid metadata title" }
    }
}

data class SourceMetadata(
    val identity: SourceMangaIdentity,
    val uploader: String?,
    val extra: String,
    val indexedExtra: String?,
    val extraVersion: Int,
    val tags: List<SourceMetadataTag> = emptyList(),
    val titles: List<SourceMetadataTitle> = emptyList(),
) {
    init {
        require(uploader == null || uploader.length <= 2_048) { "Invalid metadata uploader" }
        require(extra.isNotBlank() && extra.length <= 1_048_576) { "Invalid metadata payload" }
        require(indexedExtra == null || indexedExtra.length <= 2_048) { "Invalid indexed metadata" }
        require(extraVersion >= 0) { "Invalid metadata version" }
        require(tags.size <= 20_000) { "Too many metadata tags" }
        require(titles.size <= 1_000) { "Too many metadata titles" }
        require(tags.distinct().size == tags.size) { "Duplicate metadata tags" }
        require(titles.distinct().size == titles.size) { "Duplicate metadata titles" }
    }
}

enum class EhSyncMode(
    val storedValue: String,
) {
    Bidirectional("bidirectional"),
    RemoteOnly("remote_only"),
}

enum class EhSyncRunStatus(
    val storedValue: String,
) {
    Running("running"),
    Complete("complete"),
    Failed("failed"),
}

enum class EhSyncOperationStatus(
    val storedValue: String,
) {
    Pending("pending"),
    Applied("applied"),
    Failed("failed"),
}

data class EhSyncCheckpoint(
    val generation: Long,
    val completedAt: Long?,
    val remoteFingerprint: String?,
    val requiresFullReconcile: Boolean,
)

data class EhSyncJournalOperation(
    val operationId: String,
    val runId: String,
    val sequence: Long,
    val kind: String,
    val gallery: EhGalleryIdentity?,
    val payloadJson: String,
    val status: EhSyncOperationStatus,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(operationId.isNotBlank() && operationId.length <= 128) { "Invalid operation ID" }
        require(runId.isNotBlank() && runId.length <= 128) { "Invalid sync run ID" }
        require(sequence >= 0) { "Invalid sync sequence" }
        require(kind.isNotBlank() && kind.length <= 128) { "Invalid sync operation kind" }
        require(payloadJson.isNotBlank() && payloadJson.length <= 1_048_576) { "Invalid sync operation payload" }
        require(attempts >= 0) { "Invalid sync attempt count" }
    }
}
