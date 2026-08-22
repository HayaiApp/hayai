package dev.ahmedmohamed.hayai.novel.source.builder

import android.content.Context
import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.novel.error.novelFailure
import dev.ahmedmohamed.hayai.novel.error.novelRequire
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
        novelRequire(preferences.edit().putString(definition.id, json.encodeToString(definition)).commit(), NovelFailure.Code.CustomSourcePersist)
        return compiled
    }

    suspend fun remove(id: String) {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        manager.uninstall(id)
        novelRequire(preferences.edit().remove(id).commit(), NovelFailure.Code.CustomSourceRemove)
    }

    fun export(id: String): String = definitions().firstOrNull { it.id == id }?.let(json::encodeToString)
        ?: novelFailure(NovelFailure.Code.CustomSourceNotFound)

    fun parse(value: String): NovelCustomSourceDefinition {
        novelRequire(value.length in 2..256_000, NovelFailure.Code.CustomSourceInvalidDocument)
        return try {
            json.decodeFromString<NovelCustomSourceDefinition>(value).requireValid()
        } catch (failure: NovelFailure) {
            throw failure
        } catch (failure: Exception) {
            throw NovelFailure(NovelFailure.Code.CustomSourceInvalidDocument, cause = failure)
        }
    }
}
