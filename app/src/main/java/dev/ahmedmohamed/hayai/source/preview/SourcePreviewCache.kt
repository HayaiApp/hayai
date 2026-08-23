package dev.ahmedmohamed.hayai.source.preview

import android.content.Context
import android.text.format.Formatter
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

class SourcePreviewCache(private val context: Context) {
    private val root = File(context.cacheDir, "hayai_source_previews").apply { mkdirs() }
    private val listings = File(root, "listings").apply { mkdirs() }
    private val images = File(root, "images").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    val readableSize: String
        get() = Formatter.formatFileSize(context, root.walkTopDown().filter(File::isFile).sumOf(File::length))

    fun listingKey(manga: Manga, chapterIds: List<Long>, page: Int): String =
        digest("$LISTING_SCHEMA:${manga.id}:${manga.source}:${manga.url}:${chapterIds.joinToString("-")}:$page")

    @Synchronized
    fun readListing(key: String): SourcePreviewPage? = read(listings, key)?.let {
        runCatching { json.decodeFromString<SourcePreviewPage>(it.toString(Charsets.UTF_8)) }.getOrNull()
    }

    @Synchronized
    fun writeListing(key: String, page: SourcePreviewPage) {
        write(listings, key, json.encodeToString(SourcePreviewPage.serializer(), page).toByteArray())
        trim()
    }

    @Synchronized
    fun readImage(key: String): ByteArray? = read(images, digest(key))

    @Synchronized
    fun writeImage(key: String, bytes: ByteArray) {
        if (bytes.size > MAX_IMAGE_BYTES) return
        write(images, digest(key), bytes)
        trim()
    }

    @Synchronized
    fun clear(): Int {
        val files = root.walkBottomUp().filter(File::isFile).toList()
        files.forEach(File::delete)
        listings.mkdirs()
        images.mkdirs()
        return files.size
    }

    private fun read(directory: File, key: String): ByteArray? {
        val file = File(directory, key)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun write(directory: File, key: String, bytes: ByteArray) {
        check(directory.mkdirs() || directory.isDirectory) { "Unable to create page-preview cache directory" }
        val target = File(directory, key)
        val temporary = File.createTempFile("$key.", ".tmp", directory)
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target)) { "Unable to commit page-preview cache entry" }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun trim() {
        val files = root.walkTopDown().filter(File::isFile).filterNot { it.extension == "tmp" }.toList()
        var size = files.sumOf(File::length)
        files.sortedBy(File::lastModified).forEach { file ->
            if (size <= MAX_CACHE_BYTES) return
            val length = file.length()
            if (file.delete()) size -= length
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val LISTING_SCHEMA = "v2"
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        const val MAX_CACHE_BYTES = 75L * 1024L * 1024L
    }
}
