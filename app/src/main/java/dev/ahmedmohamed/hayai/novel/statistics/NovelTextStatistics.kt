package dev.ahmedmohamed.hayai.novel.statistics

import dev.ahmedmohamed.hayai.novel.source.NovelContentType
import dev.ahmedmohamed.hayai.novel.source.NovelDocument
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import kotlin.math.ceil

internal object NovelTextStatistics {
    const val DEFAULT_WORDS_PER_MINUTE = 200

    fun analyze(document: NovelDocument): NovelChapterStatistics {
        val readableText =
            when (document.contentType) {
                NovelContentType.Html -> htmlText(document.content)
                NovelContentType.Markdown -> markdownText(document.content)
                NovelContentType.PlainText -> document.content
            }
        return NovelChapterStatistics(wordCount(readableText))
    }

    internal fun wordCount(text: String): Long {
        if (text.isBlank()) return 0
        val codePoints = text.codePoints().toArray()
        var count = 0L
        var inSpacedScriptWord = false
        codePoints.forEachIndexed { index, codePoint ->
            when {
                codePoint.isUnspacedScriptUnit() -> {
                    count++
                    inSpacedScriptWord = false
                }
                Character.isLetterOrDigit(codePoint) -> {
                    if (!inSpacedScriptWord) count++
                    inSpacedScriptWord = true
                }
                Character.getType(codePoint) in MARK_TYPES -> Unit
                codePoint in WORD_JOINERS &&
                    inSpacedScriptWord &&
                    codePoints.getOrNull(index + 1)?.let { Character.isLetterOrDigit(it) && !it.isUnspacedScriptUnit() } == true -> Unit
                else -> inSpacedScriptWord = false
            }
        }
        return count
    }

    private fun Int.isUnspacedScriptUnit(): Boolean =
        Character.isLetterOrDigit(this) &&
            Character.UnicodeScript.of(this) in UNSPACED_SCRIPTS

    private fun htmlText(html: String): String =
        Jsoup
            .parse(html)
            .apply { select("script, style, template, noscript, svg").remove() }
            .text()

    private fun markdownText(markdown: String): String {
        val node = Parser.builder().build().parse(markdown)
        val html = HtmlRenderer.builder().escapeHtml(true).build().render(node)
        return htmlText(html)
    }

    private val UNSPACED_SCRIPTS =
        setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.THAI,
        )
    private val MARK_TYPES =
        setOf(
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
        )
    private val WORD_JOINERS = setOf('\''.code, '’'.code, '-'.code, '_'.code)
}

internal object NovelStatisticsResolver {
    fun resolve(
        document: NovelDocument,
        persisted: () -> NovelChapterStatistics?,
        analyze: (NovelDocument) -> NovelChapterStatistics = NovelTextStatistics::analyze,
    ): NovelChapterStatistics =
        runCatching { analyze(document) }
            .getOrElse { runCatching(persisted).getOrNull() ?: NovelChapterStatistics(0) }
}

internal data class NovelChapterStatistics(
    val wordCount: Long,
) {
    init {
        require(wordCount >= 0)
    }

    fun estimatedMinutes(wordsPerMinute: Int = NovelTextStatistics.DEFAULT_WORDS_PER_MINUTE): Int {
        require(wordsPerMinute > 0)
        if (wordCount == 0L) return 0
        return ceil(wordCount.toDouble() / wordsPerMinute).toInt().coerceAtLeast(1)
    }

    fun wordsRead(progress: Int): Long = wordCount * progress.coerceIn(0, 100) / 100

    fun remainingMinutes(
        progress: Int,
        wordsPerMinute: Int = NovelTextStatistics.DEFAULT_WORDS_PER_MINUTE,
    ): Int = NovelChapterStatistics(wordCount - wordsRead(progress)).estimatedMinutes(wordsPerMinute)
}
