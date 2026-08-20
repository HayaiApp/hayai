package dev.ahmedmohamed.hayai.adult.eh.network

import dev.ahmedmohamed.hayai.adult.eh.domain.EhFailure
import dev.ahmedmohamed.hayai.adult.eh.domain.EhTagMode
import dev.ahmedmohamed.hayai.adult.eh.domain.EhTagTerm

object EhTagQueryCodec {
    private const val MAX_TERMS = 8
    private const val MAX_TERM_LENGTH = 100
    private val NAMESPACE = Regex("[a-z][a-z0-9_-]{0,31}")

    fun parse(text: String): List<EhTagTerm> {
        val tokens = tokenize(text)
        if (tokens.size > MAX_TERMS) {
            throw EhFailure.BoundsExceeded("E-Hentai supports at most $MAX_TERMS tag terms")
        }
        return tokens.map(::parseToken)
    }

    fun encode(terms: List<EhTagTerm>): String {
        if (terms.size > MAX_TERMS) {
            throw EhFailure.BoundsExceeded("E-Hentai supports at most $MAX_TERMS tag terms")
        }
        return terms.joinToString(" ") { term ->
            validateValue(term.value)
            val prefix = when (term.mode) {
                EhTagMode.Include -> ""
                EhTagMode.Exclude -> "-"
                EhTagMode.Any -> "~"
            }
            val namespace = term.namespace?.also(::validateNamespace)?.let { "$it:" }.orEmpty()
            val value = if (term.value.any(Char::isWhitespace)) "\"${term.value}\"" else term.value
            "$prefix$namespace$value$"
        }
    }

    private fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        text.forEach { character ->
            when {
                character == '"' -> {
                    quoted = !quoted
                    current.append(character)
                }
                character.isWhitespace() && !quoted -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                character.isISOControl() -> throw EhFailure.MalformedDocument("Tag query contains a control character")
                else -> current.append(character)
            }
        }
        if (quoted) throw EhFailure.MalformedDocument("Tag query has an unterminated quote")
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private fun parseToken(raw: String): EhTagTerm {
        var token = raw
        val mode = when (token.firstOrNull()) {
            '-' -> EhTagMode.Exclude.also { token = token.drop(1) }
            '~' -> EhTagMode.Any.also { token = token.drop(1) }
            else -> EhTagMode.Include
        }
        if (token.endsWith('$')) token = token.dropLast(1)
        val separator = token.indexOf(':')
        val namespace = if (separator >= 0) token.substring(0, separator).lowercase().also(::validateNamespace) else null
        val encodedValue = if (separator >= 0) token.substring(separator + 1) else token
        val value = when {
            encodedValue.startsWith('"') && encodedValue.endsWith('"') && encodedValue.length >= 2 ->
                encodedValue.substring(1, encodedValue.lastIndex)
            '"' in encodedValue -> throw EhFailure.MalformedDocument("Tag query contains an invalid quote")
            else -> encodedValue
        }
        validateValue(value)
        return EhTagTerm(namespace, value, mode)
    }

    private fun validateNamespace(namespace: String) {
        if (!NAMESPACE.matches(namespace)) {
            throw EhFailure.MalformedDocument("Invalid E-Hentai tag namespace")
        }
    }

    private fun validateValue(value: String) {
        if (value.isBlank() || value.length > MAX_TERM_LENGTH || '$' in value || '"' in value || '\\' in value) {
            throw EhFailure.MalformedDocument("Invalid E-Hentai tag value")
        }
    }
}
