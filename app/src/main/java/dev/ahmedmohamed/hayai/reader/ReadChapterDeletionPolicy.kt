package dev.ahmedmohamed.hayai.reader

// J2K 3d06488991 adds duplicate deletion; retain the category policy shared by reader deletions.
internal object ReadChapterDeletionPolicy {
    fun allowsDeletion(
        removeAfterReadSlots: Int,
        excludedCategoryIds: Set<Int>,
        mangaCategoryIds: () -> List<Int>,
    ): Boolean {
        if (removeAfterReadSlots == -1) return false
        if (excludedCategoryIds.isEmpty()) return true
        return mangaCategoryIds().ifEmpty { listOf(0) }.none { it in excludedCategoryIds }
    }
}
