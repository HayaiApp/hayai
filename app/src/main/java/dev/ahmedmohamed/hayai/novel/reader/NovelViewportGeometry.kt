package dev.ahmedmohamed.hayai.novel.reader

internal data class NovelViewportAnchor(val chapterId: Long, val offset: Int)

internal object NovelViewportGeometry {
    fun contains(top: Int, bottom: Int, scrollY: Int): Boolean = scrollY >= top && scrollY < bottom

    fun offset(top: Int, height: Int, viewportHeight: Int, progress: Int): Int =
        top + ((height - viewportHeight).coerceAtLeast(0).toLong() * progress.coerceIn(0, 100) / 100).toInt()

    fun progress(top: Int, height: Int, viewportHeight: Int, scrollY: Int): Int {
        val range = (height - viewportHeight).coerceAtLeast(0)
        if (range == 0) return 100
        return (((scrollY - top).toLong() * 100 / range).toInt()).coerceIn(0, 100)
    }
}
