package dev.ahmedmohamed.hayai.adult.eh.source

import android.content.Context

// Matching and namespace drill-down follow SY 14648c7cf0aa84e5a35d48de9dbf1386df6cca42.
class EhTagCatalog private constructor(private val loadTags: () -> List<String>) {
    constructor(context: Context) : this(sharedLoader(context.applicationContext))

    internal constructor(tags: List<String>) : this({ tags })

    private val tags: List<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED, loadTags)

    fun suggest(input: String, limit: Int): List<String> {
        require(limit in 1..MAXIMUM_RESULTS)
        val trimmed = input.trim()
        val prefix = trimmed.firstOrNull()?.takeIf { it == '-' || it == '~' }
        val query = prefix?.let { trimmed.drop(1) } ?: trimmed
        if (trimmed.length < MINIMUM_QUERY_LENGTH || query.isBlank()) return emptyList()
        return (NAMESPACES.asSequence().map { "$it:" } + tags.asSequence())
            .filter { it.contains(query, ignoreCase = true) }
            .map { tag -> prefix?.let { "$it$tag" } ?: tag }
            .take(limit)
            .toList()
    }

    companion object {
        const val ASSET_PATH = "hayai/eh_tags.txt"
        private const val MINIMUM_QUERY_LENGTH = 3
        private const val MAXIMUM_RESULTS = 100
        private val NAMESPACES = listOf(
            "reclass", "language", "parody", "character", "group", "artist", "cosplayer",
            "male", "female", "mixed", "location", "other",
        )
        private val catalogLock = Any()
        @Volatile private var cachedTags: List<String>? = null

        private fun sharedLoader(context: Context): () -> List<String> = {
            cachedTags ?: synchronized(catalogLock) {
                cachedTags ?: context.assets.open(ASSET_PATH).bufferedReader().useLines { lines ->
                    lines.map(String::trim).filter(String::isNotBlank).distinct().toList()
                }.also { cachedTags = it }
            }
        }
    }
}
