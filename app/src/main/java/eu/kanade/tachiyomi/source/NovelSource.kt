package eu.kanade.tachiyomi.source

/**
 * Binary/source compatibility marker retained for existing Tsundoku novel extensions.
 * New extensions should override [Source.isNovelSource] and [Source.fetchPageText].
 */
@Deprecated("Detection is via Source.isNovelSource; fetchPageText is on Source")
interface NovelSource : Source

/**
 * Recognizes both the current virtual property and legacy marker implementations.
 * The name fallback covers extensions whose compile-only API was loaded in another class loader.
 */
@Suppress("DEPRECATION")
fun Source.isNovelSource(): Boolean =
    isNovelSource ||
        this is NovelSource ||
        javaClass.hasInterfaceNamed(LEGACY_NOVEL_SOURCE_NAME)

private fun Class<*>.hasInterfaceNamed(expectedName: String): Boolean {
    val visited = mutableSetOf<Class<*>>()
    fun visit(type: Class<*>?): Boolean {
        if (type == null || !visited.add(type)) return false
        return type.interfaces.any { it.name == expectedName || visit(it) } || visit(type.superclass)
    }
    return visit(this)
}

private const val LEGACY_NOVEL_SOURCE_NAME = "eu.kanade.tachiyomi.source.NovelSource"
