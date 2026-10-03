package dev.ahmedmohamed.hayai.novel.statistics

/** Aggregate coverage and density rebuilt from Tsundoku a3e32c8b60269e461a5573229beaa57f4d6a2147. */
internal data class NovelWordDensity(val totalWords: Long, val countedChapters: Int, val totalChapters: Int, val unreadableChapters: Int) {
    val notDownloadedChapters: Int get() = (totalChapters - countedChapters - unreadableChapters).coerceAtLeast(0)
    val averageWords: Long get() = if (countedChapters == 0) 0 else totalWords / countedChapters
    val tier: Int get() = if (averageWords <= 400) 1 else (2 + (averageWords - 401) / 100).coerceAtMost(10).toInt()
}
