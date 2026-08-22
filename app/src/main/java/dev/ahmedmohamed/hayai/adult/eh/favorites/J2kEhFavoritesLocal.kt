package dev.ahmedmohamed.hayai.adult.eh.favorites

import dev.ahmedmohamed.hayai.adult.eh.domain.EhGalleryMetadata
import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import dev.ahmedmohamed.hayai.adult.eh.network.EhHttpGateway
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryIdentity
import dev.ahmedmohamed.hayai.adult.eh.persistence.HayaiEhPersistenceStore
import dev.ahmedmohamed.hayai.adult.eh.presentation.EhTextResolver
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.source.model.SManga

class J2kEhFavoritesLocal(
    private val text: EhTextResolver,
    private val database: DatabaseHelper,
    private val persistence: HayaiEhPersistenceStore,
    private val gateway: EhHttpGateway,
) {
    fun availableCategories(): List<Pair<Int, String>> =
        database.getCategories().executeAsBlocking().mapNotNull { category -> category.id?.let { it to category.name } }

    fun remapCategory(slot: EhFavoriteSlot, categoryId: Int) {
        val category = database.getCategories().executeAsBlocking().singleOrNull { it.id == categoryId }
            ?: error(text.get(R.string.hayai_eh_selected_category_missing))
        val current = persistence.categoryMappings().singleOrNull { it.slot == slot }
            ?: error(text.get(R.string.hayai_eh_preview_before_mapping))
        check(persistence.categoryMappings().none { it.slot != slot && it.categoryId == categoryId }) {
            text.get(R.string.hayai_eh_category_already_mapped)
        }
        persistence.upsertCategoryMapping(current.copy(categoryId = requireNotNull(category.id)))
    }

    fun ensureCategoryMappings(remote: List<EhRemoteCategory>): List<EhFavoriteCategoryMapping> {
        require(remote.size == 10)
        val existingMappings = persistence.categoryMappings().associateBy { it.slot }
        val categories = database.getCategories().executeAsBlocking().toMutableList()
        remote.forEach { remoteCategory ->
            val mapping = existingMappings[remoteCategory.slot]
            if (mapping != null) {
                val category = categories.find { it.id == mapping.categoryId }
                    ?: error(text.get(R.string.hayai_eh_mapped_category_deleted, remoteCategory.slot.value))
                if (category.name != remoteCategory.name) {
                    category.name = remoteCategory.name
                    database.insertCategory(category).executeAsBlocking()
                    persistence.upsertCategoryMapping(mapping.copy(remoteName = remoteCategory.name))
                }
            } else {
                val usedNames = categories.mapTo(hashSetOf()) { it.name.lowercase() }
                val safeName = uniqueCategoryName(remoteCategory.name, usedNames)
                val category = Category.create(safeName).apply { order = (categories.maxOfOrNull { it.order } ?: -1) + 1 }
                category.id = requireNotNull(database.insertCategory(category).executeAsBlocking().insertedId()).toInt()
                categories += category
                persistence.upsertCategoryMapping(EhFavoriteCategoryMapping(remoteCategory.slot, requireNotNull(category.id), remoteCategory.name))
            }
        }
        return persistence.categoryMappings()
    }

    fun snapshot(
        aliases: EhGalleryAliasIndex,
        mappings: List<EhFavoriteCategoryMapping>,
    ): EhLocalFavoritesSnapshot {
        val byCategory = mappings.associateBy { it.categoryId }
        val grouped = linkedMapOf<EhGalleryIdentity, MutableList<Pair<Manga, List<Category>>>>()
        database.getFavoriteMangas().executeAsBlocking()
            .filter { it.source == EhSite.EHentai.sourceId || it.source == EhSite.ExHentai.sourceId }
            .forEach { manga ->
                val key = runCatching { GalleryKey.parse(manga.url) }.getOrNull() ?: return@forEach
                val identity = aliases.canonical(EhGalleryIdentity(key.id.value, key.token.value))
                grouped.getOrPut(identity, ::mutableListOf) += manga to database.getCategoriesForManga(manga).executeAsBlocking()
            }

        val conflicts = mutableListOf<EhFavoriteConflict>()
        val favorites = linkedMapOf<EhGalleryIdentity, EhLocalFavoriteState>()
        grouped.forEach { (identity, rows) ->
            if (rows.size > 1) {
                conflicts += EhFavoriteConflict.DuplicateAliases("duplicate-${identity.gid}-${identity.token}", identity, rows.mapNotNullTo(linkedSetOf()) { it.first.id })
                return@forEach
            }
            val (manga, categories) = rows.single()
            val slots = categories.mapNotNullTo(linkedSetOf()) { byCategory[it.id]?.slot }
            if (slots.size > 1) {
                conflicts += EhFavoriteConflict.MultipleMappedCategories("categories-${identity.gid}-${identity.token}", identity, slots)
                return@forEach
            }
            val slot = slots.singleOrNull() ?: return@forEach
            favorites[identity] = EhLocalFavoriteState(
                mangaIds = setOf(requireNotNull(manga.id)),
                state = EhFavoriteState(identity, manga.originalTitle, slot),
                unrelatedCategoryIds = categories.mapNotNullTo(linkedSetOf()) { it.id }.filterTo(linkedSetOf()) { it !in byCategory },
            )
        }
        return EhLocalFavoritesSnapshot(favorites, conflicts)
    }

    suspend fun apply(operation: EhFavoriteOperation, aliases: EhGalleryAliasIndex) {
        when (operation) {
            is EhFavoriteOperation.SetLocal -> setLocal(operation, aliases)
            is EhFavoriteOperation.RemoveLocal -> removeLocal(operation, aliases)
            else -> error(text.get(R.string.hayai_eh_invalid_local_operation))
        }
    }

    private suspend fun setLocal(operation: EhFavoriteOperation.SetLocal, aliases: EhGalleryAliasIndex) {
        val mappings = persistence.categoryMappings()
        val target = mappings.singleOrNull { it.slot == operation.desired.category }
            ?: error(text.get(R.string.hayai_eh_slot_not_mapped, operation.desired.category.value))
        val existing = findEquivalent(aliases.equivalents(operation.gallery))
        val prepared = if (existing == null) prepareManga(operation.desired) else null
        persistence.applyLocalOperation(operation.operationId, mutation = {
            val manga = existing ?: requireNotNull(prepared).also { created ->
                created.id = requireNotNull(database.insertManga(created).executeAsBlocking().insertedId())
            }
            manga.favorite = true
            if (manga.date_added == 0L) manga.date_added = System.currentTimeMillis()
            database.updateMangaFavorite(manga).executeAsBlocking()
            database.updateMangaAdded(manga).executeAsBlocking()
            val mappedIds = mappings.mapTo(hashSetOf()) { it.categoryId }
            val retained = database.getCategoriesForManga(manga).executeAsBlocking().filter { it.id !in mappedIds }
            val targetCategory = database.getCategories().executeAsBlocking().single { it.id == target.categoryId }
            val categories = (retained + targetCategory).distinctBy { it.id }
            database.setMangaCategories(categories.map { MangaCategory.create(manga, it) }, listOf(manga))
        })
    }

    private fun removeLocal(operation: EhFavoriteOperation.RemoveLocal, aliases: EhGalleryAliasIndex) {
        val manga = findEquivalent(aliases.equivalents(operation.gallery))
        persistence.applyLocalOperation(operation.operationId, mutation = {
            if (manga != null) {
                manga.favorite = false
                database.updateMangaFavorite(manga).executeAsBlocking()
                val mappedIds = persistence.categoryMappings().mapTo(hashSetOf()) { it.categoryId }
                val retained = database.getCategoriesForManga(manga).executeAsBlocking().filter { it.id !in mappedIds }
                database.setMangaCategories(retained.map { MangaCategory.create(manga, it) }, listOf(manga))
            }
        })
    }

    private fun findEquivalent(identities: Set<EhGalleryIdentity>): Manga? =
        identities.asSequence().flatMap { identity ->
            val path = "/g/${identity.gid}/${identity.token}/"
            sequenceOf(EhSite.ExHentai, EhSite.EHentai).mapNotNull { database.getManga(path, it.sourceId).executeAsBlocking() }
        }.firstOrNull()

    private suspend fun prepareManga(state: EhFavoriteState): Manga {
        val key = GalleryKey.parse("/g/${state.gallery.gid}/${state.gallery.token}/")
        val metadata = gateway.details(EhSite.ExHentai, key)
        return metadata.metadata.toManga(EhSite.ExHentai.sourceId)
    }

    private fun EhGalleryMetadata.toManga(sourceId: Long): Manga = Manga.create(key.normalizedPath, title, sourceId).apply {
        artist = tags.filter { it.namespace == "artist" }.joinToString { it.name }.takeIf(String::isNotBlank)
        author = tags.filter { it.namespace == "group" }.joinToString { it.name }.takeIf(String::isNotBlank)
        description = uploader?.let { text.get(R.string.hayai_eh_favorite_uploader, it) }
        genre = tags.joinToString { "${it.namespace}: ${it.name}" }.takeIf(String::isNotBlank)
        thumbnail_url = this@toManga.thumbnailUrl
        status = SManga.UNKNOWN
        initialized = true
        favorite = true
        date_added = System.currentTimeMillis()
    }

    private fun uniqueCategoryName(remoteName: String, used: Set<String>): String {
        if (remoteName.lowercase() !in used) return remoteName
        val base = text.get(R.string.hayai_eh_category_collision, remoteName)
        if (base.lowercase() !in used) return base
        return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it.lowercase() !in used }
    }
}
