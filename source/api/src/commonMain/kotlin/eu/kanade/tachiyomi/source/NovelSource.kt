package eu.kanade.tachiyomi.source

/**
 * Marker interface for novel (text-based) sources.
 *
 * Detection is via [Source.isNovelSource] and text loading is via [Source.fetchPageText].
 * The marker remains only for source compatibility with extensions that still declare it.
 */
@Deprecated("Detection is via Source.isNovelSource; fetchPageText is on Source")
interface NovelSource : Source

fun Source.isNovelSource(): Boolean = isNovelSource
