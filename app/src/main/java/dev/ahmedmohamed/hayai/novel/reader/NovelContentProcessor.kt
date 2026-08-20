package dev.ahmedmohamed.hayai.novel.reader

import dev.ahmedmohamed.hayai.novel.source.NovelContentType
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import dev.ahmedmohamed.hayai.novel.settings.NovelRegexReplacement
import dev.ahmedmohamed.hayai.novel.settings.NovelRegexSafety
import kotlinx.serialization.json.Json
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

internal class NovelContentProcessor(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun process(
        document: NovelDocument,
        chapterName: String,
        options: NovelContentOptions,
    ): ProcessedNovelContent {
        val sourceHtml =
            when (document.contentType) {
                NovelContentType.Html -> document.content
                NovelContentType.Markdown -> markdownToHtml(document.content)
                NovelContentType.PlainText -> plainTextToHtml(document.content)
            }
        var parsed = Jsoup.parseBodyFragment(sourceHtml, document.baseUrl.orEmpty())

        if (options.showRawHtml) {
            parsed = Jsoup.parseBodyFragment("<pre>${escapeHtml(document.content)}</pre>")
        } else {
            applyRegexReplacements(parsed, options.regexReplacements)
            if (options.hideChapterTitle) removeDuplicateTitle(parsed, chapterName)
            sanitize(parsed, options)
            if (options.forceLowercase) lowercaseTextNodes(parsed.body())
            if (options.autoSplitText) splitLongParagraphs(parsed, options.autoSplitWordCount)
        }

        return ProcessedNovelContent(parsed.body().html(), document.baseUrl)
    }

    private fun markdownToHtml(markdown: String): String {
        val parser = Parser.builder().build()
        return HtmlRenderer
            .builder()
            .escapeHtml(true)
            .build()
            .render(parser.parse(markdown))
    }

    private fun plainTextToHtml(text: String): String =
        text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split(Regex("\n{2,}"))
            .joinToString("\n") { paragraph -> "<p>${escapeHtml(paragraph).replace("\n", "<br>")}</p>" }

    private fun applyRegexReplacements(
        document: Document,
        rulesJson: String,
    ) {
        if (rulesJson.isBlank() || rulesJson == "[]") return
        val rules = runCatching { json.decodeFromString<List<NovelRegexReplacement>>(rulesJson) }.getOrDefault(emptyList()).take(100)
        var html = document.body().html()
        rules.filter { it.enabled && it.pattern.isNotBlank() }.forEach { rule ->
            runCatching {
                val options = if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val pattern =
                    if (rule.isRegex) {
                        if (NovelRegexSafety.rejectionReason(rule.pattern) != null) return@runCatching
                        rule.pattern
                    } else {
                        val escaped = Regex.escape(rule.pattern)
                        if (rule.matchWholeWord) "(?<![\\p{L}\\p{N}_])(?:$escaped)(?![\\p{L}\\p{N}_])" else escaped
                    }
                html = Regex(pattern, options).replace(html, rule.replacement)
            }
        }
        document.body().html(html)
    }

    private fun sanitize(
        document: Document,
        options: NovelContentOptions,
    ) {
        document.select("iframe, object, embed, form, input, button, textarea").remove()
        if (!options.keepEmbeddedJs) document.select("script").remove()
        if (!options.keepEmbeddedCss) document.select("style, link[rel=stylesheet]").remove()
        if (options.blockMedia) document.select("img, image, picture, video, audio, source, track").remove()

        document.allElements.forEach { element ->
            element.attributes().asList().filter { it.key.startsWith("on", ignoreCase = true) }.forEach {
                element.removeAttr(it.key)
            }
            listOf("href", "src", "xlink:href", "poster").forEach { attribute ->
                if (element.attr(attribute).trim().startsWith("javascript:", ignoreCase = true)) {
                    element.removeAttr(attribute)
                }
            }
        }
    }

    private fun removeDuplicateTitle(
        document: Document,
        chapterName: String,
    ) {
        val normalizedChapter = normalizeTitle(chapterName)
        if (normalizedChapter.isBlank()) return
        document
            .select("h1, h2, h3, h4, h5, h6")
            .firstOrNull { heading ->
                val normalizedHeading = normalizeTitle(heading.text())
                normalizedHeading.isNotBlank() &&
                    (
                        normalizedHeading == normalizedChapter ||
                            normalizedChapter.contains(normalizedHeading) ||
                            normalizedHeading.contains(normalizedChapter)
                    )
            }?.remove()
    }

    private fun lowercaseTextNodes(root: Element) {
        root.childNodes().forEach { node ->
            when (node) {
                is TextNode -> node.text(node.text().lowercase())
                is Element -> if (node.tagName() !in setOf("style", "script", "code", "pre")) lowercaseTextNodes(node)
            }
        }
    }

    private fun splitLongParagraphs(
        document: Document,
        wordCount: Int,
    ) {
        val threshold = wordCount.coerceIn(10, 500)
        document.select("p").toList().forEach { paragraph ->
            if (paragraph.childrenSize() > 0) return@forEach
            val words = paragraph.text().split(Regex("\\s+")).filter(String::isNotBlank)
            if (words.size <= threshold) return@forEach
            val chunks = words.chunked(threshold)
            chunks.forEach { chunk -> paragraph.before(Element("p").text(chunk.joinToString(" "))) }
            paragraph.remove()
        }
    }

    private fun normalizeTitle(value: String): String = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "").trim()

    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}

internal data class NovelContentOptions(
    val hideChapterTitle: Boolean,
    val forceLowercase: Boolean,
    val blockMedia: Boolean,
    val keepEmbeddedCss: Boolean,
    val keepEmbeddedJs: Boolean,
    val showRawHtml: Boolean,
    val autoSplitText: Boolean,
    val autoSplitWordCount: Int,
    val regexReplacements: String,
)

internal data class ProcessedNovelContent(
    val html: String,
    val baseUrl: String?,
)
