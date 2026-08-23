package dev.ahmedmohamed.hayai.novel.plugin

import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.novel.error.novelFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.URI

/**
 * Version-tolerant representation of an external LNReader repository document.
 *
 * LNReader currently publishes the descriptor list as the root array. Older and third-party
 * repositories use either a `plugins` or `sources` envelope, so all supported wire shapes are
 * normalized at this boundary before the manager sees them.
 */
internal data class NovelPluginRepositoryDocument(
    val plugins: List<NovelPluginDescriptor>,
) {
    companion object {
        fun decode(
            json: Json,
            value: String,
        ): NovelPluginRepositoryDocument {
            val root = json.parseToJsonElement(value)
            val entries =
                when (root) {
                    is JsonArray -> root
                    is JsonObject ->
                        root["plugins"] as? JsonArray
                            ?: root["sources"] as? JsonArray
                            ?: novelFailure(NovelFailure.Code.PluginRepositoryArray)
                    else -> novelFailure(NovelFailure.Code.PluginRepositoryDocument)
                }
            val plugins =
                json.decodeFromJsonElement<List<NovelPluginDescriptor>>(entries)
                    .map(NovelPluginDescriptor::withCompatibleRepositorySite)
            return NovelPluginRepositoryDocument(plugins)
        }
    }
}

/** Optional catalogue metadata must not make every otherwise valid plugin unavailable. */
private fun NovelPluginDescriptor.withCompatibleRepositorySite(): NovelPluginDescriptor {
    if (site.isBlank()) return this
    val uri = runCatching { URI(site) }.getOrNull()
    val valid =
        site.length <= 8_192 &&
            uri?.isAbsolute == true &&
            uri.host != null &&
            uri.userInfo == null &&
            (uri.scheme.equals("https", true) || uri.scheme.equals("http", true))
    return if (valid) this else copy(site = "")
}
