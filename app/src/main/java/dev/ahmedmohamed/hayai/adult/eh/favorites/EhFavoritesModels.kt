package dev.ahmedmohamed.hayai.adult.eh.favorites

import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryIdentity
import java.security.MessageDigest

@JvmInline
value class EhFavoriteSlot(val value: Int) {
    init {
        require(value in 0..9) { "E-Hentai favorite slots are between 0 and 9." }
    }
}

data class EhFavoriteState(
    val gallery: EhGalleryIdentity,
    val title: String,
    val category: EhFavoriteSlot,
) {
    init {
        require(title.isNotBlank() && title.length <= 8_192)
    }
}

data class EhRemoteCategory(val slot: EhFavoriteSlot, val name: String) {
    init {
        require(name.isNotBlank() && name.length <= 255)
    }
}

data class EhRemoteFavoritesSnapshot(
    val categories: List<EhRemoteCategory>,
    val favorites: Map<EhGalleryIdentity, EhFavoriteState>,
    val fingerprint: String,
) {
    init {
        require(categories.size == 10 && categories.map { it.slot }.distinct().size == 10)
        require(favorites.size <= 100_000)
        require(fingerprint.isNotBlank() && fingerprint.length <= 512)
    }
}

data class EhLocalFavoriteState(
    val mangaIds: Set<Long>,
    val state: EhFavoriteState,
    val unrelatedCategoryIds: Set<Int>,
) {
    init {
        require(mangaIds.isNotEmpty())
    }
}

data class EhLocalFavoritesSnapshot(
    val favorites: Map<EhGalleryIdentity, EhLocalFavoriteState>,
    val conflicts: List<EhFavoriteConflict> = emptyList(),
)

data class EhFavoriteCategoryMapping(
    val slot: EhFavoriteSlot,
    val categoryId: Int,
    val remoteName: String,
)

enum class EhFavoritesSyncMode(val storedValue: String) {
    Bidirectional("bidirectional"),
    RemoteOnly("remote_only"),
}

enum class EhConflictPolicy {
    StopForReview,
    PreferRemote,
    PreferLocal,
}

data class EhSyncRequest(
    val mode: EhFavoritesSyncMode,
    val conflictPolicy: EhConflictPolicy = EhConflictPolicy.StopForReview,
    val lenient: Boolean = false,
)

sealed interface EhFavoriteConflict {
    val id: String
    val gallery: EhGalleryIdentity?
    val kind: EhFavoriteConflictKind

    data class BothChanged(
        override val id: String,
        override val gallery: EhGalleryIdentity,
        val base: EhFavoriteState?,
        val local: EhFavoriteState?,
        val remote: EhFavoriteState?,
    ) : EhFavoriteConflict {
        override val kind = EhFavoriteConflictKind.BothChanged
    }

    data class MultipleMappedCategories(
        override val id: String,
        override val gallery: EhGalleryIdentity,
        val slots: Set<EhFavoriteSlot>,
    ) : EhFavoriteConflict {
        override val kind = EhFavoriteConflictKind.MultipleMappedCategories
    }

    data class DuplicateAliases(
        override val id: String,
        override val gallery: EhGalleryIdentity,
        val mangaIds: Set<Long>,
    ) : EhFavoriteConflict {
        override val kind = EhFavoriteConflictKind.DuplicateAliases
    }

    data class RemoteChanged(
        override val id: String,
        override val gallery: EhGalleryIdentity,
        val expected: EhFavoriteState?,
        val actual: EhFavoriteState?,
    ) : EhFavoriteConflict {
        override val kind = EhFavoriteConflictKind.RemoteChanged
    }
}

enum class EhFavoriteConflictKind {
    BothChanged,
    MultipleMappedCategories,
    DuplicateAliases,
    RemoteChanged,
}

sealed interface EhFavoriteOperation {
    val operationId: String
    val sequence: Long
    val gallery: EhGalleryIdentity

    data class SetRemote(
        override val operationId: String,
        override val sequence: Long,
        override val gallery: EhGalleryIdentity,
        val expected: EhFavoriteState?,
        val desired: EhFavoriteState,
    ) : EhFavoriteOperation

    data class RemoveRemote(
        override val operationId: String,
        override val sequence: Long,
        override val gallery: EhGalleryIdentity,
        val expected: EhFavoriteState,
    ) : EhFavoriteOperation

    data class SetLocal(
        override val operationId: String,
        override val sequence: Long,
        override val gallery: EhGalleryIdentity,
        val desired: EhFavoriteState,
    ) : EhFavoriteOperation

    data class RemoveLocal(
        override val operationId: String,
        override val sequence: Long,
        override val gallery: EhGalleryIdentity,
    ) : EhFavoriteOperation
}

data class EhFavoritesPlan(
    val operations: List<EhFavoriteOperation>,
    val conflicts: List<EhFavoriteConflict>,
    val expectedRemoteFingerprint: String,
)

sealed interface EhFavoritesStatus {
    data object Idle : EhFavoritesStatus
    data class Planning(val message: String) : EhFavoritesStatus
    data class NeedsReview(val runId: String, val conflicts: List<EhFavoriteConflict>) : EhFavoritesStatus
    data class Running(val runId: String, val completed: Int, val total: Int, val title: String?) : EhFavoritesStatus
    data class Paused(val runId: String, val reason: String) : EhFavoritesStatus
    data class Complete(val runId: String, val failures: List<String>) : EhFavoritesStatus
}

object EhFavoritesFingerprint {
    fun create(categories: List<EhRemoteCategory>, favorites: Collection<EhFavoriteState>): String {
        val canonical = buildString {
            categories.sortedBy { it.slot.value }.forEach { append("c|").append(it.slot.value).append('|').append(it.name).append('\n') }
            favorites.sortedWith(compareBy({ it.gallery.gid.toLong() }, { it.gallery.token })).forEach {
                append("f|").append(it.gallery.gid).append('|').append(it.gallery.token).append('|').append(it.category.value).append('|').append(it.title).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
