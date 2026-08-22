package dev.ahmedmohamed.hayai.novel.plugin

import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.novel.error.novelRequire
import android.content.ContentValues
import android.content.Context
import android.system.Os
import com.pushtorefresh.storio.sqlite.queries.InsertQuery
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import com.pushtorefresh.storio.sqlite.queries.UpdateQuery
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

internal class NovelPluginStore(
    context: Context,
    private val database: DatabaseHelper,
    private val json: Json,
) {
    private val root = File(context.filesDir, "hayai/novel-plugins").apply { mkdirs() }

    fun repositories(): List<NovelPluginRepository> =
        database
            .lowLevel()
            .rawQuery(
                RawQuery
                    .builder()
                    .query(
                        "SELECT name, base_url, enabled FROM hayai_novel_repos ORDER BY name COLLATE NOCASE, base_url",
                    ).build(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(NovelPluginRepository(cursor.getString(0), cursor.getString(1), cursor.getInt(2) != 0))
                }
            }

    fun saveRepository(repository: NovelPluginRepository) {
        val values =
            ContentValues(3).apply {
                put("base_url", repository.url)
                put("name", repository.name)
                put("enabled", repository.enabled)
            }
        val exists =
            database
                .lowLevel()
                .rawQuery(
                    RawQuery
                        .builder()
                        .query("SELECT 1 FROM hayai_novel_repos WHERE base_url = ?")
                        .args(repository.url)
                        .build(),
                ).use { it.moveToFirst() }
        if (exists) {
            check(
                database.lowLevel().update(
                    UpdateQuery
                        .builder()
                        .table("hayai_novel_repos")
                        .where("base_url = ?")
                        .whereArgs(repository.url)
                        .build(),
                    values,
                ) > 0,
            )
        } else {
            check(database.lowLevel().insert(InsertQuery.builder().table("hayai_novel_repos").build(), values) >= 0)
        }
    }

    fun removeRepository(url: String) {
        database.lowLevel().executeSQL(
            RawQuery
                .builder()
                .query("DELETE FROM hayai_novel_repos WHERE base_url = ?")
                .args(url)
                .build(),
        )
    }

    fun installed(): List<InstalledNovelPlugin> {
        val metadataFiles = root.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty()
        return metadataFiles
            .mapNotNull { metadata ->
                runCatching {
                    require(metadata.length() in 1..MAX_METADATA_BYTES)
                    val installed = json.decodeFromString<InstalledNovelPlugin>(metadata.readText())
                    installed.descriptor.validate(installed.repositoryUrl)
                    require(isSafeCodeFile(installed.descriptor.id, installed.codeSha256, installed.codeFile))
                    val code = File(root, installed.codeFile)
                    require(code.isFile && code.length() in 1..MAX_PLUGIN_BYTES)
                    require(sha256Hex(code.readBytes()) == installed.codeSha256.lowercase())
                    installed
                }.getOrNull()
            }.distinctBy { it.descriptor.id }
    }

    fun rememberSource(descriptor: NovelPluginDescriptor) {
        val values =
            ContentValues(5).apply {
                put("source_id", descriptor.sourceId())
                put("plugin_id", descriptor.id)
                put("name", descriptor.name)
                put("lang", descriptor.normalizedLanguage())
                put("last_seen", System.currentTimeMillis())
            }
        val exists =
            database
                .lowLevel()
                .rawQuery(
                    RawQuery
                        .builder()
                        .query("SELECT 1 FROM hayai_novel_plugin_sources WHERE plugin_id = ?")
                        .args(descriptor.id)
                        .build(),
                ).use { it.moveToFirst() }
        if (exists) {
            check(
                database.lowLevel().update(
                    UpdateQuery
                        .builder()
                        .table("hayai_novel_plugin_sources")
                        .where("plugin_id = ?")
                        .whereArgs(descriptor.id)
                        .build(),
                    values,
                ) >
                    0,
            )
        } else {
            check(database.lowLevel().insert(InsertQuery.builder().table("hayai_novel_plugin_sources").build(), values) >= 0)
        }
    }

    fun sourceName(sourceId: Long): String? =
        database
            .lowLevel()
            .rawQuery(
                RawQuery
                    .builder()
                    .query("SELECT name FROM hayai_novel_plugin_sources WHERE source_id = ?")
                    .args(sourceId)
                    .build(),
            ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun readCode(plugin: InstalledNovelPlugin): String {
        require(isSafeCodeFile(plugin.descriptor.id, plugin.codeSha256, plugin.codeFile))
        val file = File(root, plugin.codeFile)
        novelRequire(file.isFile && file.length() in 1..MAX_PLUGIN_BYTES, NovelFailure.Code.PluginCodeSize)
        val bytes = file.readBytes()
        novelRequire(sha256Hex(bytes) == plugin.codeSha256.lowercase(), NovelFailure.Code.PluginChecksum)
        return bytes.toString(Charsets.UTF_8)
    }

    fun install(
        descriptor: NovelPluginDescriptor,
        repositoryUrl: String,
        code: ByteArray,
    ): InstalledNovelPlugin {
        novelRequire(code.size in 1..MAX_PLUGIN_BYTES.toInt(), NovelFailure.Code.PluginCodeSize)
        val hash = sha256Hex(code)
        descriptor.sha256?.let { novelRequire(hash.equals(it, true), NovelFailure.Code.PluginChecksum) }
        novelRequire(code.toString(Charsets.UTF_8).isNotBlank(), NovelFailure.Code.PluginCodeSize)
        val installed = InstalledNovelPlugin(descriptor, repositoryUrl, System.currentTimeMillis(), hash)
        val codeFile = File(root, installed.codeFile)
        val codeWasValid =
            codeFile.isFile &&
                codeFile.length() == code.size.toLong() &&
                runCatching { sha256Hex(codeFile.readBytes()) == hash }.getOrDefault(false)
        if (!codeWasValid) atomicWrite(codeFile, code)
        var metadataPublished = false
        try {
            rememberSource(descriptor)
            atomicWrite(File(root, "${descriptor.id}.json"), json.encodeToString(installed).toByteArray())
            metadataPublished = true
            root
                .listFiles { file ->
                    file.isFile && file.extension == "js" && file.name.startsWith("${descriptor.id}-") && file.name != installed.codeFile
                }.orEmpty()
                .forEach(File::delete)
        } catch (error: Exception) {
            if (!metadataPublished && !codeWasValid) codeFile.delete()
            throw error
        }
        return installed
    }

    fun uninstall(pluginId: String) {
        val metadata = File(root, "$pluginId.json")
        root
            .listFiles { file -> file.isFile && file.extension == "js" && file.name.startsWith("$pluginId-") }
            .orEmpty()
            .forEach { novelRequire(it.delete(), NovelFailure.Code.PluginRemoveCode) }
        novelRequire(!metadata.exists() || metadata.delete(), NovelFailure.Code.PluginRemoveMetadata)
    }

    private fun atomicWrite(
        target: File,
        bytes: ByteArray,
    ) {
        root.mkdirs()
        val temporary = File(root, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Os.rename(temporary.absolutePath, target.absolutePath)
        } finally {
            temporary.delete()
        }
    }

    private fun isSafeCodeFile(
        pluginId: String,
        checksum: String,
        codeFile: String,
    ): Boolean {
        novelRequire(Regex("[A-Za-z0-9._-]{1,128}").matches(pluginId), NovelFailure.Code.PluginId)
        return checksum.matches(Regex("[a-f0-9]{64}")) && codeFile == "$pluginId-$checksum.js"
    }

    companion object {
        const val MAX_PLUGIN_BYTES = 8L * 1024 * 1024
        private const val MAX_METADATA_BYTES = 256L * 1024
    }
}
