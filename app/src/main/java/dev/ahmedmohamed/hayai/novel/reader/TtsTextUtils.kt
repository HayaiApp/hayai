package dev.ahmedmohamed.hayai.novel.reader

internal object TtsTextUtils {
    fun splitTextForTts(
        text: String,
        maxLength: Int,
    ): List<String> {
        if (text.isBlank()) return emptyList()
        val limit = maxLength.coerceAtLeast(1)
        val chunks = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            if (remaining.length <= limit) {
                chunks += remaining
                break
            }
            val slice = remaining.substring(0, limit)
            val sentenceEnd = slice.lastIndexOfAny(charArrayOf('.', '!', '?', '。', '！', '？', '\n'))
            val whitespace = slice.indexOfLast(Char::isWhitespace)
            val breakPoint =
                when {
                    sentenceEnd >= limit / 2 -> sentenceEnd + 1
                    whitespace >= limit / 2 -> whitespace + 1
                    else -> limit
                }
            chunks += remaining.substring(0, breakPoint).trim()
            remaining = remaining.substring(breakPoint).trim()
        }
        return chunks.filter(String::isNotBlank)
    }
}
