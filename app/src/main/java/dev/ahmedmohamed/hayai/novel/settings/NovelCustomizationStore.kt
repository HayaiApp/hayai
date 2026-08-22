package dev.ahmedmohamed.hayai.novel.settings

import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class NovelCustomizationStore(
    private val preferences: HayaiPreferences,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun cssSnippets(): List<NovelCodeSnippet> = decode(preferences.novelCustomCssSnippets.get())

    fun jsSnippets(): List<NovelCodeSnippet> = decode(preferences.novelCustomJsSnippets.get())

    fun replacements(): List<NovelRegexReplacement> = decode(preferences.novelRegexReplacements.get())

    fun presets(): List<NovelReaderPreset> = decode(preferences.novelGlobalPresets.get())

    fun saveCssSnippets(items: List<NovelCodeSnippet>) = preferences.novelCustomCssSnippets.set(encode(sanitizeSnippets(items)))

    fun saveJsSnippets(items: List<NovelCodeSnippet>) = preferences.novelCustomJsSnippets.set(encode(sanitizeSnippets(items)))

    fun saveReplacements(items: List<NovelRegexReplacement>) =
        preferences.novelRegexReplacements.set(encode(sanitizeReplacements(items)))

    fun savePresets(items: List<NovelReaderPreset>) = preferences.novelGlobalPresets.set(encode(sanitizePresets(items)))

    fun enabledCss(): String = cssSnippets().filter(NovelCodeSnippet::enabled).joinToString("\n", transform = NovelCodeSnippet::code)

    fun enabledJs(runOnAppend: Boolean = false): String =
        jsSnippets()
            .filter { it.enabled && (!runOnAppend || it.runOnAppend) }
            .map { NovelSnippetExecution(it.title, it.code) }
            .takeIf { it.isNotEmpty() }
            ?.let { snippets ->
                "(function(){const snippets=${json.encodeToString(snippets)};for(const snippet of snippets){" +
                    "try{(new Function(snippet.code))();}catch(error){console.error('Hayai snippet \\\"'+snippet.title+'\\\" failed',error);}}})();"
            }.orEmpty()

    fun createPreset(name: String): NovelReaderPreset =
        NovelReaderPreset(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(MAX_TITLE_LENGTH),
            fontSize = preferences.novelFontSize.get(),
            fontFamily = preferences.novelFontFamily.get(),
            lineHeight = preferences.novelLineHeight.get(),
            textAlign = preferences.novelTextAlign.get(),
            textColor = preferences.novelFontColor.get(),
            backgroundColor = preferences.novelBackgroundColor.get(),
            paragraphIndent = preferences.novelParagraphIndent.get(),
            paragraphSpacing = preferences.novelParagraphSpacing.get(),
            marginLeft = preferences.novelMarginLeft.get(),
            marginRight = preferences.novelMarginRight.get(),
            marginTop = preferences.novelMarginTop.get(),
            marginBottom = preferences.novelMarginBottom.get(),
        )

    fun apply(preset: NovelReaderPreset) {
        preferences.novelFontSize.set(preset.fontSize.coerceIn(8, 72))
        preferences.novelFontFamily.set(preset.fontFamily.take(MAX_FONT_LENGTH))
        preferences.novelLineHeight.set(preset.lineHeight.coerceIn(0.8f, 3f))
        preferences.novelTextAlign.set(preset.textAlign.takeIf { it in ALLOWED_ALIGNMENTS } ?: "left")
        preferences.novelFontColor.set(preset.textColor)
        preferences.novelBackgroundColor.set(preset.backgroundColor)
        preferences.novelParagraphIndent.set(preset.paragraphIndent.coerceIn(0f, 10f))
        preferences.novelParagraphSpacing.set(preset.paragraphSpacing.coerceIn(0f, 5f))
        preferences.novelMarginLeft.set(preset.marginLeft.coerceIn(0, 200))
        preferences.novelMarginRight.set(preset.marginRight.coerceIn(0, 200))
        preferences.novelMarginTop.set(preset.marginTop.coerceIn(0, 200))
        preferences.novelMarginBottom.set(preset.marginBottom.coerceIn(0, 200))
    }

    private inline fun <reified T> decode(value: String): List<T> =
        if (value.isBlank() || value == "[]") {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<T>>(value) }.getOrDefault(emptyList()).take(MAX_ITEMS)
        }

    private inline fun <reified T> encode(value: List<T>): String = json.encodeToString(value.take(MAX_ITEMS))

    private fun sanitizeSnippets(items: List<NovelCodeSnippet>): List<NovelCodeSnippet> =
        items
            .take(MAX_ITEMS)
            .mapNotNull { item ->
                val title = item.title.trim().take(MAX_TITLE_LENGTH)
                val code = item.code.take(MAX_CODE_LENGTH)
                if (title.isBlank() || code.isBlank()) null else item.copy(id = stableId(item.id), title = title, code = code)
            }.distinctBy(NovelCodeSnippet::id)

    private fun sanitizeReplacements(items: List<NovelRegexReplacement>): List<NovelRegexReplacement> =
        items
            .take(MAX_ITEMS)
            .mapNotNull { item ->
                val title = item.title.trim().take(MAX_TITLE_LENGTH)
                val pattern = item.pattern.take(MAX_PATTERN_LENGTH)
                if (title.isBlank() || pattern.isBlank()) {
                    null
                } else {
                    item.copy(
                        id = stableId(item.id),
                        title = title,
                        pattern = pattern,
                        replacement = item.replacement.take(MAX_REPLACEMENT_LENGTH),
                    )
                }
            }.distinctBy(NovelRegexReplacement::id)

    private fun sanitizePresets(items: List<NovelReaderPreset>): List<NovelReaderPreset> =
        items
            .take(MAX_ITEMS)
            .filter { it.name.isNotBlank() }
            .map { it.copy(id = stableId(it.id), name = it.name.trim().take(MAX_TITLE_LENGTH)) }
            .distinctBy(NovelReaderPreset::id)

    private fun stableId(value: String): String = value.takeIf { it.isNotBlank() }?.take(MAX_ID_LENGTH) ?: UUID.randomUUID().toString()

    private companion object {
        const val MAX_ITEMS = 100
        const val MAX_ID_LENGTH = 80
        const val MAX_TITLE_LENGTH = 100
        const val MAX_FONT_LENGTH = 100
        const val MAX_CODE_LENGTH = 100_000
        const val MAX_PATTERN_LENGTH = 512
        const val MAX_REPLACEMENT_LENGTH = 20_000
        val ALLOWED_ALIGNMENTS = setOf("left", "right", "center", "justify", "start", "end")
    }
}

@Serializable
data class NovelCodeSnippet(
    val id: String,
    val title: String,
    val code: String,
    val enabled: Boolean = true,
    val runOnAppend: Boolean = false,
)

@Serializable
private data class NovelSnippetExecution(val title: String, val code: String)

@Serializable
data class NovelRegexReplacement(
    val title: String,
    val pattern: String,
    val replacement: String,
    val enabled: Boolean = true,
    val isRegex: Boolean = true,
    val matchWholeWord: Boolean = false,
    val caseSensitive: Boolean = false,
    val id: String = "",
)

@Serializable
data class NovelReaderPreset(
    val id: String,
    val name: String,
    val fontSize: Int,
    val fontFamily: String,
    val lineHeight: Float,
    val textAlign: String,
    val textColor: Int,
    val backgroundColor: Int,
    val paragraphIndent: Float,
    val paragraphSpacing: Float,
    val marginLeft: Int,
    val marginRight: Int,
    val marginTop: Int,
    val marginBottom: Int,
)

object NovelRegexSafety {
    private val nestedQuantifier = Regex("\\([^)]*[+*}]\\s*\\)[+*{]")
    private val numericBackReference = Regex("\\\\[1-9]")

    fun rejectionReason(pattern: String): NovelFailure.Code? =
        when {
            pattern.length > 512 -> NovelFailure.Code.ReplacementPatternTooLong
            numericBackReference.containsMatchIn(pattern) -> NovelFailure.Code.ReplacementBackreference
            nestedQuantifier.containsMatchIn(pattern) -> NovelFailure.Code.ReplacementNestedRepetition
            runCatching { Regex(pattern) }.isFailure -> NovelFailure.Code.ReplacementInvalidRegex
            else -> null
        }
}

object NovelReplacementEngine {
    fun apply(text: String, rule: NovelRegexReplacement): Result<String> = runCatching {
        if (!rule.enabled || rule.pattern.isBlank()) return@runCatching text
        if (rule.isRegex) NovelRegexSafety.rejectionReason(rule.pattern)?.let { throw NovelFailure(it) }
        val source =
            if (rule.isRegex) {
                rule.pattern
            } else {
                Regex.escape(rule.pattern).let { escaped ->
                    if (rule.matchWholeWord) "(?<![\\p{L}\\p{N}_])(?:$escaped)(?![\\p{L}\\p{N}_])" else escaped
                }
            }
        Regex(source, if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)).replace(text, rule.replacement)
    }
}
