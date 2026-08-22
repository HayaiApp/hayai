package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.UUID

internal class NovelFontStore(
    private val context: Context,
    private val preferences: HayaiPreferences,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val directory get() = File(context.filesDir, "hayai/novel-fonts")

    fun fonts(): List<NovelImportedFont> =
        runCatching { json.decodeFromString<List<NovelImportedFont>>(preferences.novelImportedFonts.get()) }
            .getOrDefault(emptyList())
            .filter { item ->
                item.id.matches(ID_PATTERN) &&
                    item.extension in EXTENSIONS &&
                    item.name.isNotBlank() &&
                    item.name.length <= MAX_NAME_LENGTH &&
                    file(item).isFile
            }
            .distinctBy(NovelImportedFont::id)
            .take(MAX_FONTS)

    fun importFont(uri: Uri): Result<NovelImportedFont> = synchronized(LOCK) {
        runCatching {
            val displayName = displayName(uri).take(MAX_NAME_LENGTH).ifBlank { context.getString(R.string.hayai_novel_reader_imported_font_default) }
            val extension = displayName.substringAfterLast('.', "").lowercase()
            require(extension in EXTENSIONS) { context.getString(R.string.hayai_novel_reader_font_choose_type) }
            require(fonts().size < MAX_FONTS) { context.getString(R.string.hayai_novel_reader_font_limit, MAX_FONTS) }
            val id = UUID.randomUUID().toString().replace("-", "")
            directory.mkdirs()
            val item = NovelImportedFont(id, displayName.substringBeforeLast('.').trim().ifBlank { context.getString(R.string.hayai_novel_reader_imported_font_default) }, extension)
            val target = file(item)
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { context.getString(R.string.hayai_novel_reader_font_open_error) }
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_FONT_BYTES) { context.getString(R.string.hayai_novel_reader_font_size_limit) }
                            output.write(buffer, 0, read)
                        }
                        require(total >= MIN_FONT_BYTES) { context.getString(R.string.hayai_novel_reader_font_invalid) }
                    }
                }
                val signature = target.inputStream().use { input -> ByteArray(4).also { require(input.read(it) == it.size) } }
                val recognized =
                    signature.contentEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00)) ||
                        signature.toString(Charsets.US_ASCII) in setOf("OTTO", "ttcf", "true")
                require(recognized) { context.getString(R.string.hayai_novel_reader_font_unsupported) }
                Typeface.createFromFile(target)
                val updated = (fonts() + item).distinctBy(NovelImportedFont::id)
                preferences.novelImportedFonts.set(json.encodeToString(updated))
                item
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
    }

    fun delete(id: String): Boolean = synchronized(LOCK) {
        val item = fonts().firstOrNull { it.id == id } ?: return@synchronized false
        val deleted = file(item).delete() || !file(item).exists()
        if (deleted) preferences.novelImportedFonts.set(json.encodeToString(fonts().filterNot { it.id == id }))
        deleted
    }

    fun typeface(token: String): Typeface? = resolve(token)?.let(::file)?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }

    fun open(id: String): InputStream? = fonts().firstOrNull { it.id == id }?.let { runCatching { file(it).inputStream() }.getOrNull() }

    fun resolve(token: String): NovelImportedFont? = token.removePrefix(TOKEN_PREFIX).takeIf { token.startsWith(TOKEN_PREFIX) }?.let { id -> fonts().firstOrNull { it.id == id } }

    fun token(item: NovelImportedFont): String = TOKEN_PREFIX + item.id

    private fun file(item: NovelImportedFont) = File(directory, "${item.id}.${item.extension}")

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()

    companion object {
        const val TOKEN_PREFIX = "hayai-font:"
        const val WEB_SCHEME = "hayai-novel-font"
        private const val MAX_FONTS = 32
        private const val MAX_NAME_LENGTH = 120
        private const val MAX_FONT_BYTES = 20L * 1024L * 1024L
        private const val MIN_FONT_BYTES = 256L
        private val EXTENSIONS = setOf("ttf", "otf")
        private val ID_PATTERN = Regex("[a-f0-9]{32}")
        private val LOCK = Any()
    }
}

@Serializable
internal data class NovelImportedFont(val id: String, val name: String, val extension: String)
