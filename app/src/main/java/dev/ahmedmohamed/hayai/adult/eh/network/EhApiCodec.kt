package dev.ahmedmohamed.hayai.adult.eh.network

import dev.ahmedmohamed.hayai.adult.eh.domain.EhFailure
import dev.ahmedmohamed.hayai.adult.eh.domain.EhImagePageRef
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryId
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryKey
import dev.ahmedmohamed.hayai.adult.eh.domain.GalleryToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object EhApiCodec {
    const val endpoint = "https://api.e-hentai.org/api.php"

    fun galleryTokenRequest(page: EhImagePageRef): String =
        buildJsonObject {
            put("method", "gtoken")
            put(
                "pagelist",
                buildJsonArray {
                    add(
                        buildJsonArray {
                            add(JsonPrimitive(page.galleryId.value.toLong()))
                            add(JsonPrimitive(page.pageToken.value))
                            add(JsonPrimitive(page.page))
                        },
                    )
                },
            )
        }.toString()

    fun parseGalleryTokenResponse(json: String): GalleryKey {
        if (json.length > MAX_RESPONSE_CHARS) throw EhFailure.BoundsExceeded("E-Hentai API response is too large")
        try {
            val root = Json.parseToJsonElement(json).jsonObject
            root["error"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                throw EhFailure.RemoteWarning(it)
            }
            val token = root["tokenlist"]?.jsonArray?.singleOrNull()?.jsonObject
                ?: throw EhFailure.MalformedDocument("E-Hentai API returned no gallery token")
            val id = token["gid"]?.jsonPrimitive?.let { primitive ->
                primitive.contentOrNull ?: primitive.intOrNull?.toString()
            } ?: throw EhFailure.MalformedDocument("E-Hentai API omitted the gallery ID")
            val galleryToken = token["token"]?.jsonPrimitive?.contentOrNull
                ?: throw EhFailure.MalformedDocument("E-Hentai API omitted the gallery token")
            return GalleryKey(GalleryId.parse(id), GalleryToken.parse(galleryToken))
        } catch (failure: EhFailure) {
            throw failure
        } catch (failure: Exception) {
            throw EhFailure.MalformedDocument("Malformed E-Hentai API response", failure)
        }
    }

    private const val MAX_RESPONSE_CHARS = 1024 * 1024
}
