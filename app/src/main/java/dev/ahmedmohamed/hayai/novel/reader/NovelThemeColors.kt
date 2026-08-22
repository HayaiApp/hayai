package dev.ahmedmohamed.hayai.novel.reader

internal object NovelThemeColors {
    const val LIGHT_BACKGROUND = 0xFFFFFFFF.toInt()
    const val LIGHT_TEXT = 0xFF202124.toInt()
    const val DARK_BACKGROUND = 0xFF121212.toInt()
    const val DARK_TEXT = 0xFFE6E1E5.toInt()

    fun resolve(
        theme: String,
        customBackground: Int,
        customText: Int,
        appBackground: Int,
        appText: Int,
    ): Pair<Int, Int> =
        when (theme) {
            "light" -> LIGHT_BACKGROUND to LIGHT_TEXT
            "dark" -> DARK_BACKGROUND to DARK_TEXT
            "sepia" -> 0xFFF4ECD8.toInt() to 0xFF3B2F2F.toInt()
            "black" -> 0xFF000000.toInt() to 0xFFECECEC.toInt()
            "grey" -> 0xFF303030.toInt() to 0xFFF1F1F1.toInt()
            "custom" ->
                (customBackground.takeUnless { it == 0 } ?: LIGHT_BACKGROUND) to
                    (customText.takeUnless { it == 0 } ?: LIGHT_TEXT)
            else -> appBackground to appText
        }

    fun parseRgb(input: String): Int? {
        val value = input.trim().removePrefix("#")
        val expanded =
            when (value.length) {
                3 -> value.flatMap { listOf(it, it) }.joinToString("")
                6 -> value
                else -> return null
            }
        if (expanded.any { it.digitToIntOrNull(16) == null }) return null
        return (0xFF000000L or expanded.toLong(16)).toInt()
    }

    fun formatRgb(color: Int): String = "#%06X".format(color and 0xFFFFFF)
}
