package dev.ahmedmohamed.hayai.adult.eh.source

import android.content.Context

class EhTagCatalog(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val tags: List<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        applicationContext.assets.open(ASSET_PATH).bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotBlank).distinct().toList()
        }
    }

    fun suggest(input: String, limit: Int): List<String> {
        require(limit in 1..MAXIMUM_RESULTS)
        val trimmed = input.trim()
        val prefix = trimmed.firstOrNull()?.takeIf { it == '-' || it == '~' }
        val query = prefix?.let { trimmed.drop(1) } ?: trimmed
        if (query.length < MINIMUM_QUERY_LENGTH) return emptyList()
        return tags.asSequence()
            .filter { it.contains(query, ignoreCase = true) }
            .map { tag -> prefix?.let { "$it$tag" } ?: tag }
            .take(limit)
            .toList()
    }

    companion object {
        const val ASSET_PATH = "hayai/eh_tags.txt"
        private const val MINIMUM_QUERY_LENGTH = 3
        private const val MAXIMUM_RESULTS = 100
    }
}
