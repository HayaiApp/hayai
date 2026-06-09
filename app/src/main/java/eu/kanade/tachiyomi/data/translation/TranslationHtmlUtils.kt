package eu.kanade.tachiyomi.data.translation

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

object TranslationHtmlUtils {
    private const val MEDIA_SELECTOR = "img, figure, picture, video, source, svg"

    fun extractImages(html: String): Pair<String, Map<String, String>> {
        val doc = Jsoup.parse(html)
        doc.outputSettings().prettyPrint(false)
        val media = mutableMapOf<String, String>()
        var index = 0
        doc.select(MEDIA_SELECTOR).forEach { element: Element ->
            val placeholder = "[IMG_PLACEHOLDER_$index]"
            media[placeholder] = element.outerHtml()
            element.replaceWith(org.jsoup.nodes.TextNode("\n$placeholder\n"))
            index++
        }
        return doc.body().html() to media
    }

    fun reinsertImages(translatedHtml: String, images: Map<String, String>): String {
        var result = translatedHtml
        for ((placeholder, originalTag) in images) {
            result = result.replace(placeholder, originalTag)
        }
        return result
    }

    fun extractTextFromHtml(html: String): String {
        val cleaned = html.replace(
            Regex("data:[a-zA-Z0-9/+.-]+;base64,[A-Za-z0-9+/=\\s]+"),
            "",
        )
        val doc = Jsoup.parse(cleaned)
        doc.outputSettings().prettyPrint(false)
        doc.select("p, div, br, h1, h2, h3, h4, h5, h6, li, blockquote").forEach { el ->
            if (el.tagName() == "br") {
                el.before(org.jsoup.nodes.TextNode("\n"))
            } else {
                el.before(org.jsoup.nodes.TextNode("\n\n"))
            }
        }
        return normalizeLineBreaks(doc.body().wholeText())
            .replace(Regex("[ \\t\\u000B\\f]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun wrapTextInHtml(text: String): String {
        return splitParagraphsPreserving(text)
            .joinToString("") { paragraph ->
                "<p>${escapeHtml(paragraph.trim()).replace("\n", "<br/>")}</p>"
            }
    }

    fun buildChunks(paragraphs: List<String>, chunkSize: Int): List<String> {
        if (paragraphs.isEmpty()) return emptyList()
        return paragraphs.chunked(chunkSize.coerceAtLeast(1)).map { it.joinToString("\n\n") }
    }

    fun splitParagraphsPreserving(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = normalizeLineBreaks(text)
        val byDouble = normalized.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotBlank() }
        if (byDouble.size > 1) return byDouble
        return normalized.split('\n').map { it.trim() }.filter { it.isNotBlank() }
    }

    fun stripContextLeakage(translated: String): String {
        val marker = "=== TEXT TO TRANSLATE"
        val markerIndex = translated.indexOf(marker)
        if (markerIndex < 0) return translated
        val afterMarker = translated.indexOf("\n", markerIndex)
        if (afterMarker < 0) return translated
        return translated.substring(afterMarker + 1).trim()
    }

    fun normalizeLanguageCode(code: String): String =
        code.lowercase().substringBefore('-').substringBefore('_')

    fun languageCodesMatch(a: String, b: String): Boolean =
        normalizeLanguageCode(a) == normalizeLanguageCode(b)

    fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    fun normalizeLineBreaks(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u2028', '\n')
            .replace('\u2029', '\n')
    }
}
