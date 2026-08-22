package dev.ahmedmohamed.hayai.source.filter

interface SourceTagCompletionFilter {
    val hint: String
    val maximumSelections: Int
    val validPrefixes: Set<Char>

    fun selections(): List<String>

    fun setSelections(values: List<String>)

    fun suggestions(input: String, limit: Int = 100): List<String>
}
