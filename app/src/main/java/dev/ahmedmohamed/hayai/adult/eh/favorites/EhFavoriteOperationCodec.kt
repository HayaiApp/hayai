package dev.ahmedmohamed.hayai.adult.eh.favorites

import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

object EhFavoriteOperationCodec {
    fun kind(operation: EhFavoriteOperation): String = when (operation) {
        is EhFavoriteOperation.SetRemote -> "set_remote"
        is EhFavoriteOperation.RemoveRemote -> "remove_remote"
        is EhFavoriteOperation.SetLocal -> "set_local"
        is EhFavoriteOperation.RemoveLocal -> "remove_local"
    }

    fun encode(operation: EhFavoriteOperation): String = buildJsonObject {
        put("operationId", operation.operationId)
        put("sequence", operation.sequence)
        put("gid", operation.gallery.gid)
        put("token", operation.gallery.token)
        when (operation) {
            is EhFavoriteOperation.SetRemote -> {
                put("expected", operation.expected?.toJson() ?: JsonNull)
                put("desired", operation.desired.toJson())
            }
            is EhFavoriteOperation.RemoveRemote -> put("expected", operation.expected.toJson())
            is EhFavoriteOperation.SetLocal -> put("desired", operation.desired.toJson())
            is EhFavoriteOperation.RemoveLocal -> Unit
        }
    }.toString()

    fun decode(kind: String, payload: String): EhFavoriteOperation {
        require(payload.length <= 1_048_576)
        val json = Json.parseToJsonElement(payload).jsonObject
        val identity = EhGalleryIdentity(json.string("gid"), json.string("token"))
        val id = json.string("operationId")
        val sequence = json.getValue("sequence").jsonPrimitive.long
        return when (kind) {
            "set_remote" -> EhFavoriteOperation.SetRemote(id, sequence, identity, (json["expected"] as? JsonObject)?.toState(), json.objectValue("desired").toState())
            "remove_remote" -> EhFavoriteOperation.RemoveRemote(id, sequence, identity, json.objectValue("expected").toState())
            "set_local" -> EhFavoriteOperation.SetLocal(id, sequence, identity, json.objectValue("desired").toState())
            "remove_local" -> EhFavoriteOperation.RemoveLocal(id, sequence, identity)
            else -> error("Unknown E-Hentai favorite operation $kind")
        }
    }

    private fun EhFavoriteState.toJson() = buildJsonObject {
        put("gid", gallery.gid)
        put("token", gallery.token)
        put("title", title)
        put("category", category.value)
    }

    private fun JsonObject.toState() = EhFavoriteState(
        gallery = EhGalleryIdentity(string("gid"), string("token")),
        title = string("title"),
        category = EhFavoriteSlot(getValue("category").jsonPrimitive.int),
    )

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.objectValue(key: String): JsonObject = getValue(key).jsonObject
}
