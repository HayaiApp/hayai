package dev.ahmedmohamed.hayai.source.filter

interface SourceTagCompletionFilter {
    val hint: String
    val maximumSelections: Int
    val validPrefixes: Set<Char>

    fun isNamespaceSuggestion(value: String): Boolean {
        val trimmed = value.trim()
        val tag = if (trimmed.firstOrNull() in validPrefixes) trimmed.drop(1).trim() else trimmed
        return NAMESPACE.matches(tag)
    }

    fun selections(): List<String>

    fun setSelections(values: List<String>)

    fun suggestions(input: String, limit: Int = 100): List<String>

    companion object {
        private val NAMESPACE = Regex("[a-z][a-z0-9_-]{0,31}:", RegexOption.IGNORE_CASE)
    }
}
