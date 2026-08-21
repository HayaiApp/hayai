package dev.ahmedmohamed.hayai.novel.export

import dev.ahmedmohamed.hayai.novel.archive.HtmlAssetRewriter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class NovelEpubMetadata(
    val title: String,
    val authors: List<String> = emptyList(),
    val language: String = "en",
    val identifier: String,
    val description: String? = null,
    val cover: NovelEpubAsset? = null,
)
data class NovelEpubChapter(val title: String, val html: String, val sourceUrl: String? = null)
data class NovelEpubAsset(
    val fileName: String,
    val mediaType: String,
    val bytes: ByteArray,
    val sourceUrl: String? = null,
)
data class NovelEpubBook(
    val metadata: NovelEpubMetadata,
    val chapters: List<NovelEpubChapter>,
    val assets: List<NovelEpubAsset> = emptyList(),
)

object NovelEpubNaming {
    fun safeFileName(title: String, extension: String = "epub"): String {
        val clean = title
            .replace(Regex("[\\x00-\\x1f<>:\"/\\\\|?*]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', ' ')
            .take(180)
            .ifBlank { "novel" }
        val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
        val safe = if (clean.uppercase(Locale.ROOT) in reserved) "_$clean" else clean
        return "$safe.${extension.trimStart('.').lowercase()}"
    }
}

class NovelEpubExporter {
    fun write(book: NovelEpubBook, output: OutputStream) {
        validate(book)
        ZipOutputStream(output).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            val crc = CRC32().apply { update(mime) }
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = size
                this.crc = crc.value
            })
            zip.write(mime)
            zip.closeEntry()
            zip.text("META-INF/container.xml", CONTAINER)
            val allAssets = (book.assets + listOfNotNull(book.metadata.cover)).distinctBy { it.fileName }
            zip.text("OEBPS/content.opf", packageDocument(book, allAssets))
            zip.text("OEBPS/nav.xhtml", navigation(book))
            zip.text("OEBPS/toc.ncx", ncx(book))
            book.chapters.forEachIndexed { index, chapter ->
                zip.text(
                    "OEBPS/text/chapter-${index + 1}.xhtml",
                    chapterDocument(chapter, book.metadata.language, allAssets),
                )
            }
            allAssets.forEachIndexed { index, asset ->
                zip.bytes("OEBPS/assets/${assetName(index, asset.fileName)}", asset.bytes)
            }
        }
    }

    private fun validate(book: NovelEpubBook) {
        require(book.metadata.title.isNotBlank() && book.metadata.title.length <= 1_024)
        require(book.metadata.identifier.isNotBlank() && book.metadata.identifier.length <= 1_024)
        require(book.metadata.language.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")))
        require(book.metadata.authors.size <= 100 && book.metadata.authors.all { it.length in 1..1_024 })
        require((book.metadata.description?.length ?: 0) <= MAX_DESCRIPTION_CHARS)
        require(book.metadata.cover == null || book.metadata.cover.mediaType.startsWith("image/"))
        require(book.chapters.isNotEmpty() && book.chapters.size <= MAX_CHAPTERS)
        require(book.chapters.all { it.title.isNotBlank() && it.html.length <= MAX_CHAPTER_CHARS })
        require(book.chapters.sumOf { it.html.length.toLong() } <= MAX_TOTAL_CHAPTER_CHARS)
        val assets = book.assets + listOfNotNull(book.metadata.cover)
        require(assets.size <= MAX_ASSETS && assets.sumOf { it.bytes.size.toLong() } <= MAX_ASSET_BYTES)
        require(assets.all { it.mediaType.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+", RegexOption.IGNORE_CASE)) })
        assets.groupBy { it.fileName.lowercase(Locale.ROOT) }.values.forEach { sameName ->
            require(sameName.size == 1) { "Duplicate EPUB asset filename ${sameName.first().fileName}" }
        }
        assets.mapNotNull(NovelEpubAsset::sourceUrl)
            .map { canonicalUrl(it, null).lowercase(Locale.ROOT) }
            .groupingBy { it }
            .eachCount()
            .forEach { (url, count) ->
                require(count == 1) { "Duplicate EPUB asset source URL $url" }
            }
    }

    private fun packageDocument(book: NovelEpubBook, assets: List<NovelEpubAsset>): String {
        val coverIndex = book.metadata.cover
            ?.let { cover -> assets.indexOfFirst { it === cover || it.fileName == cover.fileName } }
            ?.takeIf { it >= 0 }
        val manifestChapters = book.chapters.indices.joinToString("\n") {
            "<item id=\"chapter-${it + 1}\" href=\"text/chapter-${it + 1}.xhtml\" media-type=\"application/xhtml+xml\"/>"
        }
        val manifestAssets = assets.mapIndexed { index, asset ->
            val coverProperty = if (index == coverIndex) " properties=\"cover-image\"" else ""
            "<item id=\"asset-${index + 1}\" href=\"assets/${xml(assetName(index, asset.fileName))}\" media-type=\"${xml(asset.mediaType)}\"$coverProperty/>"
        }.joinToString("\n")
        val spine = book.chapters.indices.joinToString("\n") { "<itemref idref=\"chapter-${it + 1}\"/>" }
        val authors = book.metadata.authors.joinToString("\n") { "<dc:creator>${xml(it)}</dc:creator>" }
        val sources = book.chapters.mapNotNull(NovelEpubChapter::sourceUrl)
            .distinct()
            .joinToString("") { "<dc:source>${xml(it)}</dc:source>" }
        return """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0" unique-identifier="book-id"><metadata><dc:identifier id="book-id">${xml(book.metadata.identifier)}</dc:identifier><dc:title>${xml(book.metadata.title)}</dc:title><dc:language>${xml(book.metadata.language)}</dc:language>$authors$sources${book.metadata.description?.let { "<dc:description>${xml(it)}</dc:description>" }.orEmpty()}<meta property="dcterms:modified">1970-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>$manifestChapters$manifestAssets</manifest><spine toc="ncx">$spine</spine></package>"""
    }

    private fun navigation(book: NovelEpubBook) = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${xml(book.metadata.language)}"><head><title>${xml(book.metadata.title)}</title></head><body><nav epub:type="toc"><h1>Contents</h1><ol>${book.chapters.mapIndexed { index, chapter -> "<li><a href=\"text/chapter-${index + 1}.xhtml\">${xml(chapter.title)}</a></li>" }.joinToString("")}</ol></nav></body></html>"""
    private fun ncx(book: NovelEpubBook) = """<?xml version="1.0" encoding="UTF-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="${xml(book.metadata.identifier)}"/></head><docTitle><text>${xml(book.metadata.title)}</text></docTitle><navMap>${book.chapters.mapIndexed { index, chapter -> "<navPoint id=\"nav-${index + 1}\" playOrder=\"${index + 1}\"><navLabel><text>${xml(chapter.title)}</text></navLabel><content src=\"text/chapter-${index + 1}.xhtml\"/></navPoint>" }.joinToString("")}</navMap></ncx>"""
    private fun chapterDocument(chapter: NovelEpubChapter, language: String, assets: List<NovelEpubAsset>): String {
        val document = Jsoup.parseBodyFragment(chapter.html, chapter.sourceUrl.orEmpty()).apply {
            outputSettings(
                Document.OutputSettings()
                    .syntax(Document.OutputSettings.Syntax.xml)
                    .escapeMode(Entities.EscapeMode.xhtml)
                    .prettyPrint(false),
            )
        }
        val bodyNode = document.body().apply {
            select("script,iframe,object,embed").remove()
            select("*").forEach { node ->
                node.attributes().asList()
                    .filter { it.key.startsWith("on", true) }
                    .forEach { node.removeAttr(it.key) }
            }
        }
        val targetByUrl = assets.mapIndexedNotNull { index, asset ->
            asset.sourceUrl?.let {
                canonicalUrl(it, chapter.sourceUrl) to "../assets/${assetName(index, asset.fileName)}"
            }
        }.toMap()
        val rewrittenResources = HtmlAssetRewriter.rewriteHtml(bodyNode.html()) { reference ->
            targetByUrl[canonicalUrl(reference, chapter.sourceUrl)]
        }
        val rewrittenDocument = Jsoup.parseBodyFragment(rewrittenResources, chapter.sourceUrl.orEmpty())
        rewrittenDocument.outputSettings(
            Document.OutputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml)
                .prettyPrint(false),
        )
        rewrittenDocument.select("a[href]").forEach { link ->
            val raw = link.attr("href")
            if (raw.isNotBlank() && !raw.startsWith('#')) {
                link.attr("href", canonicalUrl(raw, chapter.sourceUrl))
            }
        }
        val body = rewrittenDocument.body().html()
        return """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" lang="${xml(language)}"><head><title>${xml(chapter.title)}</title></head><body><h1>${xml(chapter.title)}</h1>$body</body></html>"""
    }
    private fun canonicalUrl(value: String, base: String?): String = runCatching {
        java.net.URI(base.orEmpty()).resolve(value).normalize().toASCIIString()
    }.getOrDefault(value)

    private fun assetName(index: Int, original: String): String {
        val extension = original.substringAfterLast('.', "bin")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
            ?: "bin"
        return "asset-${index + 1}-${hash(original).take(8)}.$extension"
    }
    private fun hash(value: String) = hash(value.toByteArray())
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun ZipOutputStream.text(path: String, value: String) = bytes(path, value.toByteArray())
    private fun ZipOutputStream.bytes(path: String, value: ByteArray) {
        putNextEntry(ZipEntry(path).apply { time = 0L })
        write(value)
        closeEntry()
    }

    private companion object {
        const val MAX_CHAPTERS = 20_000
        const val MAX_CHAPTER_CHARS = 8_000_000
        const val MAX_TOTAL_CHAPTER_CHARS = 32L * 1024 * 1024
        const val MAX_DESCRIPTION_CHARS = 1_000_000
        const val MAX_ASSETS = 20_000
        const val MAX_ASSET_BYTES = 64L * 1024 * 1024
        const val CONTAINER = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
    }
}
