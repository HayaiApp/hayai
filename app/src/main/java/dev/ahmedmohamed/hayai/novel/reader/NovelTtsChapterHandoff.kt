package dev.ahmedmohamed.hayai.novel.reader

internal class NovelTtsChapterHandoff {
    private var targetChapterId: Long? = null

    fun schedule(chapterId: Long) {
        targetChapterId = chapterId
    }

    fun cancelUnlessTarget(chapterId: Long) {
        if (targetChapterId != chapterId) clear()
    }

    fun consume(chapterId: Long): Boolean {
        if (targetChapterId != chapterId) return false
        clear()
        return true
    }

    fun clear() {
        targetChapterId = null
    }
}
