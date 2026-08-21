package dev.ahmedmohamed.hayai.novel.source.builder

import android.content.Context
import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NovelCustomSourceStore(context: Context, private val manager: NovelPluginManager, private val json: Json = Json { ignoreUnknownKeys = true }) {
    private val preferences = context.getSharedPreferences("hayai_novel_custom_sources", Context.MODE_PRIVATE)

    fun definitions(): List<NovelCustomSourceDefinition> = preferences.all.values.mapNotNull { value ->
        (value as? String)?.let { runCatching { json.decodeFromString<NovelCustomSourceDefinition>(it).requireValid() }.getOrNull() }
    }.sortedBy { it.name.lowercase() }

    suspend fun save(definition: NovelCustomSourceDefinition): CompiledNovelCustomSource {
        val compiled = NovelCustomSourceCompiler.compile(definition)
        manager.restorePlugin(compiled.descriptor, definition.baseUrl.trimEnd('/') + "/", compiled.code, emptyMap())
        check(preferences.edit().putString(definition.id, json.encodeToString(definition)).commit())
        return compiled
    }

    suspend fun remove(id: String) {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        manager.uninstall(id)
        check(preferences.edit().remove(id).commit())
    }

    fun export(id: String): String = definitions().firstOrNull { it.id == id }?.let(json::encodeToString) ?: error("Custom source not found")
    fun parse(value: String): NovelCustomSourceDefinition {
        require(value.length in 2..256_000)
        return json.decodeFromString<NovelCustomSourceDefinition>(value).requireValid()
    }
}
