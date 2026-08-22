package dev.ahmedmohamed.hayai.adult.eh.source

import dev.ahmedmohamed.hayai.adult.eh.domain.EhTagMode
import dev.ahmedmohamed.hayai.adult.eh.domain.EhTagTerm
import dev.ahmedmohamed.hayai.adult.eh.network.EhTagQueryCodec
import java.util.Locale

object EhTagSelectionCodec {
    const val MAXIMUM_TAGS = 8

    fun decode(encoded: String): List<String> =
        EhTagQueryCodec.parse(encoded).map(::display)

    fun encode(selections: List<String>): String =
        normalize(selections).map(::parseSelection).joinToString(" ") { term ->
            val prefix = when (term.mode) {
                EhTagMode.Include -> ""
                EhTagMode.Exclude -> "-"
                EhTagMode.Any -> "~"
            }
            val namespace = term.namespace?.let { "$it:" }.orEmpty()
            val value = if (term.value.any(Char::isWhitespace)) "\"${term.value}\"" else term.value
            "$prefix$namespace$value"
        }

    fun normalize(selections: List<String>): List<String> {
        val normalized = selections.map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }
        require(normalized.size <= MAXIMUM_TAGS)
        normalized.forEach(::parseSelection)
        return normalized
    }

    private fun parseSelection(selection: String): EhTagTerm {
        var value = selection.trim()
        val mode = when (value.firstOrNull()) {
            '-' -> EhTagMode.Exclude.also { value = value.drop(1) }
            '~' -> EhTagMode.Any.also { value = value.drop(1) }
            else -> EhTagMode.Include
        }
        val separator = value.indexOf(':')
        val namespace = value.substring(0, separator.takeIf { it >= 0 } ?: 0).takeIf { separator > 0 }
        val tag = if (separator > 0) value.substring(separator + 1) else value
        val term = EhTagTerm(namespace, tag, mode)
        return EhTagQueryCodec.parse(EhTagQueryCodec.encode(listOf(term))).single()
    }

    private fun display(term: EhTagTerm): String {
        val prefix = when (term.mode) {
            EhTagMode.Include -> ""
            EhTagMode.Exclude -> "-"
            EhTagMode.Any -> "~"
        }
        return "$prefix${term.namespace?.let { "$it:" }.orEmpty()}${term.value}"
    }
}
