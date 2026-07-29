package eu.kanade.tachiyomi.ui.source.browse

import yokai.util.koin.get
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import yokai.domain.source.browse.filter.FilterSerializer
import yokai.domain.source.browse.filter.models.RawSavedSearch
import yokai.domain.source.browse.filter.models.SavedSearch

fun RawSavedSearch.applySave(
    freshFilters: () -> FilterList,
    json: Json = get(),
    filterSerializer: FilterSerializer = get(),
): SavedSearch {
    val rt = SavedSearch(
        id = this.id,
        name = this.name,
        query = this.query.orEmpty(),
        filters = null,
    )
    if (filtersJson == null) {
        return rt
    }

    val encoded = try {
        json.decodeFromString<JsonElement>(filtersJson!!)
    } catch (e: Exception) {
        null
    } ?: return rt

    val filters = freshFilters()
    try {
        when (encoded) {
            is JsonObject -> filterSerializer.deserializeV2(filters, encoded)
            is JsonArray -> filterSerializer.deserialize(filters, encoded)
            else -> return rt
        }
        return rt.copy(filters = filters)
    } catch (e: Exception) {
        return rt
    }
}

fun RawSavedSearch.applySave(
    originalFilters: FilterList,
    json: Json = get(),
    filterSerializer: FilterSerializer = get(),
): SavedSearch = applySave({ originalFilters }, json, filterSerializer)

fun List<RawSavedSearch>.applyAllSave(
    freshFilters: () -> FilterList,
    json: Json = get(),
    filterSerializer: FilterSerializer = get(),
) = this.map { it.applySave(freshFilters, json, filterSerializer) }

fun List<RawSavedSearch>.applyAllSave(
    originalFilters: FilterList,
    json: Json = get(),
    filterSerializer: FilterSerializer = get(),
) = this.map { it.applySave({ originalFilters }, json, filterSerializer) }
