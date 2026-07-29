package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.domain.manga.models.Manga

internal class LibrarySelection {

    private val mangasById = LinkedHashMap<Long, Manga>()

    val ids: Set<Long>
        get() = mangasById.keys

    val mangas: Collection<Manga>
        get() = mangasById.values

    val isEmpty: Boolean
        get() = mangasById.isEmpty()

    val isNotEmpty: Boolean
        get() = mangasById.isNotEmpty()

    fun contains(manga: Manga): Boolean = manga.id?.let(mangasById::containsKey) == true

    fun update(mangas: Iterable<Manga>, selected: Boolean): Map<Long, Boolean> {
        val changes = LinkedHashMap<Long, Boolean>()
        mangas.forEach { manga ->
            val id = manga.id ?: return@forEach
            val wasSelected = id in mangasById
            if (selected) {
                mangasById[id] = manga
            } else {
                mangasById.remove(id)
            }
            if (wasSelected != selected) {
                changes[id] = selected
            }
        }
        return changes
    }

    fun clear() {
        mangasById.clear()
    }
}
