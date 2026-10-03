package dev.ahmedmohamed.hayai.novel.search

import java.util.regex.Pattern
import dev.ahmedmohamed.hayai.novel.settings.NovelRegexSafety
import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import kotlinx.coroutines.CancellationException

internal data class NovelTextSearchOptions(val regex: Boolean = false, val caseSensitive: Boolean = false, val wholeWord: Boolean = false)
internal data class NovelTextSnippet(val text: String, val start: Int, val end: Int)
internal data class NovelTextMatches(val count: Int, val snippets: List<NovelTextSnippet>)

/** Text matching rebuilt from Tsundoku 455cb456c162a223304286e28a7a80345ecdffe3. */
internal object NovelChapterTextSearch {
    fun pattern(query: String, options: NovelTextSearchOptions): Pattern {
        require(query.isNotBlank())
        if (options.regex) NovelRegexSafety.rejectionReason(query)?.let { throw NovelFailure(it) }
        val expression = if (options.regex) query else Pattern.quote(query)
        val bounded = if (options.wholeWord) "(?<![\\p{L}\\p{N}_])(?:$expression)(?![\\p{L}\\p{N}_])" else expression
        val flags = if (options.caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return Pattern.compile(bounded, flags)
    }

    fun find(text: String, pattern: Pattern, isActive: () -> Boolean = { true }): NovelTextMatches {
        if (!isActive()) throw CancellationException("Chapter text search cancelled")
        val matcher = pattern.matcher(CancellableText(text, isActive))
        var count = 0
        val snippets = mutableListOf<NovelTextSnippet>()
        while (matcher.find()) {
            if (!isActive()) throw CancellationException("Chapter text search cancelled")
            if (matcher.start() == matcher.end()) continue
            count++
            if (snippets.size >= 3) continue
            val start = matcher.start()
            val end = minOf(matcher.end(), start + 200)
            val from = (start - 60).coerceAtLeast(0)
            val to = (end + 60).coerceAtMost(text.length)
            val prefix = if (from > 0) "…" else ""
            val suffix = if (to < text.length) "…" else ""
            snippets += NovelTextSnippet(prefix + text.substring(from, to) + suffix, prefix.length + start - from, prefix.length + end - from)
        }
        return NovelTextMatches(count, snippets)
    }

    private class CancellableText(private val text: CharSequence, private val isActive: () -> Boolean) : CharSequence {
        private var reads = 0
        override val length: Int get() = text.length
        override fun get(index: Int): Char {
            if ((++reads and 0xFFF) == 0 && !isActive()) throw CancellationException("Chapter text search cancelled")
            return text[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = CancellableText(text.subSequence(startIndex, endIndex), isActive)
        override fun toString(): String = text.toString()
    }
}
