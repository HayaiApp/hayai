package dev.ahmedmohamed.hayai.novel.download

import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.novel.error.novelFailure
import dev.ahmedmohamed.hayai.novel.error.novelRequire
import dev.ahmedmohamed.hayai.novel.source.NovelContentType
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

class NovelDownloadStore(
    private val root: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun contains(
        sourceId: Long,
        chapterUrl: String,
    ): Boolean = loadDocument(sourceId, chapterUrl) != null

    fun loadDocument(
        sourceId: Long,
        chapterUrl: String,
    ): NovelDocument? {
        val directory = chapterDirectory(sourceId, chapterUrl)
        val manifest = readManifest(directory) ?: return null
        if (!manifest.matches(sourceId, chapterUrl)) return null
        val document = File(directory, DOCUMENT_FILE)
        if (manifest.documentSize !in 0..MAX_DOCUMENT_BYTES.toLong() ||
            !document.isFile ||
            document.length() != manifest.documentSize ||
            sha256(document) != manifest.documentSha256
        ) {
            return null
        }
        return NovelDocument(
            content = runCatching { document.readText(Charsets.UTF_8) }.getOrNull() ?: return null,
            contentType = runCatching { NovelContentType.valueOf(manifest.contentType) }.getOrNull() ?: return null,
            baseUrl = null,
        )
    }

    fun openAsset(
        sourceId: Long,
        chapterUrl: String,
        assetPath: String,
    ): InputStream? {
        val normalized = normalizeAssetPath(assetPath) ?: return null
        val directory = chapterDirectory(sourceId, chapterUrl)
        val manifest = readManifest(directory)?.takeIf { it.matches(sourceId, chapterUrl) } ?: return null
        var totalSize = 0L
        for (entry in manifest.assets) {
            if (entry.size !in 0L..MAX_ASSET_BYTES || totalSize > MAX_TOTAL_ASSET_BYTES - entry.size) return null
            totalSize += entry.size
        }
        val asset = manifest.assets.firstOrNull { it.path == normalized } ?: return null
        if (!asset.fileName.matches(SAFE_ASSET_FILE)) return null
        val file = File(File(directory, ASSET_DIRECTORY), asset.fileName)
        if (!file.isFile || file.length() != asset.size || sha256(file) != asset.sha256) return null
        return FileInputStream(file)
    }

    suspend fun save(
        sourceId: Long,
        chapterUrl: String,
        document: NovelDocument,
        assetResolver: NovelDownloadAssetResolver?,
    ): NovelDownloadResult {
        novelRequire(chapterUrl.isNotBlank(), NovelFailure.Code.OfflineChapterUrl)
        ensureRoot()
        val references = NovelAssetReferences.extract(document)
        val offlinePaths = references.associateWith(::offlineAssetPath)
        val offlineDocument = NovelAssetReferences.rewrite(document, offlinePaths)
        val documentBytes = offlineDocument.content.toByteArray(Charsets.UTF_8)
        novelRequire(documentBytes.size <= MAX_DOCUMENT_BYTES, NovelFailure.Code.OfflineTextTooLarge)
        val key = chapterKey(sourceId, chapterUrl)
        val staging = File(root, ".$key.tmp-${UUID.randomUUID()}")
        val target = File(root, key)
        val backup = File(root, ".$key.bak-${UUID.randomUUID()}")
        novelRequire(staging.mkdir(), NovelFailure.Code.OfflineStaging)

        try {
            val documentFile = File(staging, DOCUMENT_FILE)
            writeDurably(documentFile, documentBytes)
            val documentSize = documentFile.length()
            val assetsDirectory = File(staging, ASSET_DIRECTORY).apply { check(mkdir()) }
            val savedAssets = mutableListOf<DownloadedAsset>()
            val unavailable = mutableListOf<String>()
            var totalAssetBytes = 0L
            for (reference in references) {
                val normalized = checkNotNull(offlinePaths[reference])
                val input =
                    try {
                        assetResolver?.open(reference)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                if (input == null) {
                    unavailable += reference
                    continue
                }
                val fileName = sha256(normalized.toByteArray(Charsets.UTF_8))
                val output = File(assetsDirectory, fileName)
                val copied = input.use { stream -> copyBounded(stream, output, MAX_ASSET_BYTES, MAX_TOTAL_ASSET_BYTES - totalAssetBytes) }
                totalAssetBytes += copied.size
                savedAssets += DownloadedAsset(normalized, fileName, copied.size, copied.sha256)
            }
            val manifest =
                NovelDownloadManifest(
                    sourceId = sourceId,
                    chapterUrl = chapterUrl,
                    contentType = offlineDocument.contentType.name,
                    documentSize = documentSize,
                    documentSha256 = sha256(documentFile),
                    assets = savedAssets,
                    unavailableAssets = unavailable,
                )
            writeDurably(File(staging, MANIFEST_FILE), json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            replaceAtomically(staging, target, backup)
            return NovelDownloadResult(savedAssets.size, unavailable.size, documentSize + totalAssetBytes)
        } catch (error: Exception) {
            staging.deleteRecursivelyWithin(root)
            throw error
        }
    }

    fun remove(
        sourceId: Long,
        chapterUrl: String,
    ): Boolean {
        val target = chapterDirectory(sourceId, chapterUrl)
        if (!target.exists()) return false
        return target.deleteRecursivelyWithin(root)
    }

    private fun readManifest(directory: File): NovelDownloadManifest? {
        val file = File(directory, MANIFEST_FILE)
        if (!file.isFile || file.length() !in 1..MAX_MANIFEST_BYTES) return null
        return runCatching { json.decodeFromString<NovelDownloadManifest>(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun ensureRoot() {
        check((root.isDirectory || root.mkdirs()) && root.canonicalFile == root.absoluteFile.canonicalFile) {
            "Unable to create the novel download directory."
        }
    }

    private fun replaceAtomically(
        staging: File,
        target: File,
        backup: File,
    ) {
        val hadTarget = target.exists()
        if (hadTarget && !target.renameTo(backup)) novelFailure(NovelFailure.Code.OfflinePreservePrevious)
        if (!staging.renameTo(target)) {
            if (hadTarget && !backup.renameTo(target)) {
                novelFailure(NovelFailure.Code.OfflinePublishPreserved, backup.name)
            }
            novelFailure(NovelFailure.Code.OfflinePublish)
        }
        backup.deleteRecursivelyWithin(root)
    }

    private fun copyBounded(
        input: InputStream,
        target: File,
        perAssetLimit: Long,
        totalRemaining: Long,
    ): CopiedAsset {
        novelRequire(totalRemaining > 0, NovelFailure.Code.OfflineAssetsTooLarge)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        DigestInputStream(input, digest).use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    size += read
                    novelRequire(size <= perAssetLimit && size <= totalRemaining, NovelFailure.Code.OfflineAssetsTooLarge)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        return CopiedAsset(size, digest.digest().toHex())
    }

    private fun writeDurably(
        target: File,
        content: ByteArray,
    ) {
        FileOutputStream(target).use { output ->
            output.write(content)
            output.fd.sync()
        }
    }

    private fun chapterDirectory(
        sourceId: Long,
        chapterUrl: String,
    ) = File(root, chapterKey(sourceId, chapterUrl))

    private fun chapterKey(
        sourceId: Long,
        chapterUrl: String,
    ): String = sha256("$sourceId\n$chapterUrl".toByteArray(Charsets.UTF_8))

    private fun sha256(file: File): String = FileInputStream(file).use { sha256(it) }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        DigestInputStream(input, digest).use { stream -> while (stream.read(buffer) >= 0) Unit }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun offlineAssetPath(reference: String): String {
        val cleanPath = reference.substringBefore('#').substringBefore('?')
        val extension = cleanPath.substringAfterLast('.', "").lowercase().takeIf { it.matches(SAFE_EXTENSION) }
        return "offline/${sha256(reference.toByteArray(Charsets.UTF_8))}${extension?.let { ".$it" }.orEmpty()}"
    }

    private fun normalizeAssetPath(path: String): String? =
        path.replace('\\', '/').trimStart('/').takeIf { normalized ->
            normalized.isNotBlank() && normalized.length <= MAX_ASSET_PATH_LENGTH && normalized.split('/').none { it == ".." }
        }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun NovelDownloadManifest.matches(
        expectedSourceId: Long,
        expectedChapterUrl: String,
    ): Boolean = schemaVersion == SCHEMA_VERSION && sourceId == expectedSourceId && chapterUrl == expectedChapterUrl

    private fun File.deleteRecursivelyWithin(parent: File): Boolean {
        val canonicalParent = parent.canonicalFile
        val canonicalTarget = canonicalFile
        check(canonicalTarget.parentFile == canonicalParent) { "Refusing to delete outside the novel download directory." }
        return deleteRecursively()
    }

    private data class CopiedAsset(
        val size: Long,
        val sha256: String,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MANIFEST_FILE = "manifest.json"
        const val DOCUMENT_FILE = "document.txt"
        const val ASSET_DIRECTORY = "assets"
        const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
        const val MAX_DOCUMENT_BYTES = 64 * 1024 * 1024
        const val MAX_ASSET_BYTES = 32L * 1024 * 1024
        const val MAX_TOTAL_ASSET_BYTES = 256L * 1024 * 1024
        const val MAX_ASSET_PATH_LENGTH = 2048
        val SAFE_ASSET_FILE = Regex("[0-9a-f]{64}")
        val SAFE_EXTENSION = Regex("[a-z0-9]{1,10}")
    }
}

@Serializable
private data class NovelDownloadManifest(
    val schemaVersion: Int = 1,
    val sourceId: Long,
    val chapterUrl: String,
    val contentType: String,
    val documentSize: Long,
    val documentSha256: String,
    val assets: List<DownloadedAsset>,
    val unavailableAssets: List<String>,
)

@Serializable
private data class DownloadedAsset(
    val path: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
)

data class NovelDownloadResult(
    val savedAssetCount: Int,
    val unavailableAssetCount: Int,
    val bytesWritten: Long,
)

fun interface NovelDownloadAssetResolver {
    suspend fun open(reference: String): InputStream?
}
