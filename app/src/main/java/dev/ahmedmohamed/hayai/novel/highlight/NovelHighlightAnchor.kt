package dev.ahmedmohamed.hayai.novel.highlight

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.abs

@Serializable
data class NovelHighlightAnchor(
    val exact: String,
    val prefix: String,
    val suffix: String,
    val occurrence: Int,
    val documentHash: String,
) {
    init {
        require(exact.isNotBlank() && exact.length <= MAX_EXACT)
        require(prefix.length <= CONTEXT_LENGTH && suffix.length <= CONTEXT_LENGTH)
        require(occurrence >= 0)
        require(documentHash.matches(Regex("[a-f0-9]{64}")))
    }

    fun resolve(document: String): TextRange? {
        val normalized = normalize(document)
        val matches = exactMatches(normalized, exact)
        if (matches.isEmpty()) return fuzzyResolve(normalized)
        val ranked = matches.mapIndexed { index, start ->
            val end = start + exact.length
            val contextScore = suffixScore(prefix, normalized.substring(maxOf(0, start - prefix.length), start)) +
                prefixScore(suffix, normalized.substring(end, minOf(normalized.length, end + suffix.length)))
            val ordinalPenalty = abs(index - occurrence).coerceAtMost(16)
            TextRange(start, end) to (contextScore * 4 - ordinalPenalty)
        }
        return ranked.maxByOrNull { it.second }?.takeIf { it.second >= MIN_CONTEXT_SCORE || ranked.size == 1 }?.first
    }

    private fun fuzzyResolve(document: String): TextRange? {
        if (exact.length < 12 || document.length > MAX_DOCUMENT_CHARS) return null
        val seedLength = minOf(16, exact.length / 3)
        val seeds = listOf(0, (exact.length - seedLength) / 2, exact.length - seedLength).distinct()
        val starts = linkedSetOf<Int>()
        for (offset in seeds) {
            val seed = exact.substring(offset, offset + seedLength)
            var from = 0
            while (starts.size < MAX_FUZZY_CANDIDATES) {
                val found = document.indexOf(seed, from)
                if (found < 0) break
                val candidate = found - offset
                if (candidate in document.indices) starts += candidate
                from = found + 1
            }
        }
        var best: Pair<TextRange, Double>? = null
        starts.forEach { start ->
            val suffixSeed = suffix.take(16)
            val suffixStart = suffixSeed.takeIf(String::isNotEmpty)?.let { document.indexOf(it, start + seedLength).takeIf { found -> found >= 0 } }
            val end = suffixStart ?: minOf(document.length, start + (exact.length * 1.25).toInt())
            if (end <= start || end - start < exact.length * 0.7) return@forEach
            val candidate = document.substring(start, end)
            val score = tokenSimilarity(exact, candidate)
            val previous = best
            if (score >= FUZZY_THRESHOLD && (previous == null || score > previous.second)) {
                best = TextRange(start, end) to score
            }
        }
        return best?.first
    }

    companion object {
        private const val CONTEXT_LENGTH = 64
        private const val MAX_EXACT = 8_192
        private const val MIN_CONTEXT_SCORE = 8
        private const val FUZZY_THRESHOLD = 0.84
        private const val MAX_DOCUMENT_CHARS = 4_000_000
        private const val MAX_FUZZY_CANDIDATES = 128

        fun capture(document: String, start: Int, end: Int): NovelHighlightAnchor {
            val normalized = normalize(document)
            require(start in 0 until normalized.length && end in (start + 1)..normalized.length)
            val exact = normalized.substring(start, end).take(MAX_EXACT)
            val prior = exactMatches(normalized.substring(0, start), exact).size
            return NovelHighlightAnchor(
                exact = exact,
                prefix = normalized.substring(maxOf(0, start - CONTEXT_LENGTH), start),
                suffix = normalized.substring(end, minOf(normalized.length, end + CONTEXT_LENGTH)),
                occurrence = prior,
                documentHash = sha256(normalized),
            )
        }

        fun fromSelection(document: String, selected: String): NovelHighlightAnchor? {
            val normalized = normalize(document)
            val exact = normalize(selected).trim()
            if (exact.isEmpty() || exact.length > MAX_EXACT) return null
            val start = normalized.indexOf(exact)
            return if (start >= 0) capture(normalized, start, start + exact.length) else null
        }

        fun fromContext(document: String, selected: String, prefix: String, suffix: String, occurrence: Int): NovelHighlightAnchor? {
            val normalizedDocument = normalize(document)
            val exact = normalize(selected).trim()
            if (exact.isEmpty() || exact.length > MAX_EXACT) return null
            return NovelHighlightAnchor(exact, normalize(prefix).takeLast(CONTEXT_LENGTH), normalize(suffix).take(CONTEXT_LENGTH), occurrence.coerceAtLeast(0), sha256(normalizedDocument))
        }

        fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()

        private fun exactMatches(haystack: String, needle: String): List<Int> = buildList {
            var from = 0
            while (from <= haystack.length - needle.length) {
                val index = haystack.indexOf(needle, from)
                if (index < 0) break
                add(index)
                from = index + 1
            }
        }

        private fun suffixScore(expected: String, actual: String): Int = expected.reversed().zip(actual.reversed()).takeWhile { it.first == it.second }.size
        private fun prefixScore(expected: String, actual: String): Int = expected.zip(actual).takeWhile { it.first == it.second }.size

        private fun tokenSimilarity(left: String, right: String): Double {
            val a = left.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotEmpty).toSet()
            val b = right.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotEmpty).toSet()
            return if (a.isEmpty() || b.isEmpty()) 0.0 else 2.0 * a.intersect(b).size / (a.size + b.size)
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

data class TextRange(val start: Int, val endExclusive: Int)
