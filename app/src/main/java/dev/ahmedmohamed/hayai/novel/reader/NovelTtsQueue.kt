package dev.ahmedmohamed.hayai.novel.reader

internal class NovelTtsQueue {
    data class Chunk(val paragraphIndex: Int, val text: String)

    var chunks: List<Chunk> = emptyList()
        private set
    var position: Int = 0
        private set
    private var chapterId: Long? = null

    val current: Chunk? get() = chunks.getOrNull(position)
    val paragraphIndex: Int get() = current?.paragraphIndex ?: chunks.lastOrNull()?.paragraphIndex ?: 0

    fun update(chapterId: Long, paragraphs: List<String>, maxLength: Int, startParagraph: Int? = null): Boolean {
        val replacement = paragraphs.flatMapIndexed { index, text ->
            TtsTextUtils.splitTextForTts(text, maxLength).map { Chunk(index, it) }
        }
        if (this.chapterId == chapterId && replacement == chunks && startParagraph == null) return false
        this.chapterId = chapterId
        chunks = replacement
        position = chunks.indexOfFirst { it.paragraphIndex >= (startParagraph ?: 0).coerceAtLeast(0) }.takeIf { it >= 0 } ?: 0
        return true
    }

    fun advance() { position = (position + 1).coerceAtMost(chunks.size) }

    fun restartIfCompleted() { if (position >= chunks.size) position = 0 }

    fun seekParagraph(delta: Int): Int? {
        if (chunks.isEmpty()) return null
        val target = (paragraphIndex + delta).coerceIn(0, chunks.last().paragraphIndex)
        position = chunks.indexOfFirst { it.paragraphIndex >= target }.takeIf { it >= 0 } ?: 0
        return paragraphIndex
    }
}
