package hayai.novel.reader.settings

import eu.kanade.tachiyomi.core.preference.Preference
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import yokai.domain.ui.settings.ReaderPreferences

/**
 * Named snapshot of the novel reader's purely-visual STYLE prefs. Values are kept as strings keyed
 * by the real pref key so the manager stays type-agnostic; [NovelStylePresetManager] reads/writes
 * each through the typed [Preference] it owns.
 */
@Serializable
data class NovelStylePreset(
    val name: String,
    val values: Map<String, String> = emptyMap(),
)

/**
 * Captures, lists, applies, renames, and deletes saved global novel STYLE presets, persisted as a
 * JSON list in [ReaderPreferences.novelStylePresets] (same storage shape as novelRegexReplacements).
 *
 * Only purely-visual style prefs are captured (font, size, line height, alignment, theme, colors,
 * paragraph indent/spacing, margins, original-fonts). Behavioral prefs — TTS, auto-scroll, brightness,
 * keep-screen-on, content transforms, EPUB toggles — are intentionally excluded. Applying a preset
 * writes each captured value back to its real pref, so the live NovelWebViewPreferenceObserver style
 * bucket re-applies CSS in place without a chapter reload.
 */
class NovelStylePresetManager(private val prefs: ReaderPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Real pref keys whose values a preset captures, mapped to the typed pref they round-trip through. */
    private val styleKeyToPref: Map<String, Preference<*>> = mapOf(
        prefs.novelFontFamily.key() to prefs.novelFontFamily,
        prefs.novelFontSize.key() to prefs.novelFontSize,
        prefs.novelLineHeight.key() to prefs.novelLineHeight,
        prefs.novelTextAlign.key() to prefs.novelTextAlign,
        prefs.novelTheme.key() to prefs.novelTheme,
        prefs.novelFontColor.key() to prefs.novelFontColor,
        prefs.novelBackgroundColor.key() to prefs.novelBackgroundColor,
        prefs.novelParagraphIndent.key() to prefs.novelParagraphIndent,
        prefs.novelParagraphSpacing.key() to prefs.novelParagraphSpacing,
        prefs.novelMarginLeft.key() to prefs.novelMarginLeft,
        prefs.novelMarginRight.key() to prefs.novelMarginRight,
        prefs.novelMarginTop.key() to prefs.novelMarginTop,
        prefs.novelMarginBottom.key() to prefs.novelMarginBottom,
        prefs.novelUseOriginalFonts.key() to prefs.novelUseOriginalFonts,
    )

    fun list(): List<NovelStylePreset> = decode(prefs.novelStylePresets.get())

    /** Snapshots the current style prefs under [name]. A duplicate name overwrites in place. */
    fun saveCurrent(name: String): List<NovelStylePreset> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return list()
        val preset = NovelStylePreset(name = trimmed, values = captureCurrent())
        val updated = list().filterNot { it.name.equals(trimmed, ignoreCase = true) } + preset
        return persist(updated)
    }

    /** Writes every captured value back to its real pref so the style observer fires. */
    fun apply(preset: NovelStylePreset) {
        preset.values.forEach { (key, raw) ->
            val pref = styleKeyToPref[key] ?: return@forEach
            writeRaw(pref, raw)
        }
    }

    fun rename(oldName: String, newName: String): List<NovelStylePreset> {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return list()
        val updated = list().map {
            if (it.name == oldName) it.copy(name = trimmed) else it
        }
        return persist(updated)
    }

    fun delete(name: String): List<NovelStylePreset> = persist(list().filterNot { it.name == name })

    private fun captureCurrent(): Map<String, String> =
        styleKeyToPref.mapValues { (_, pref) -> pref.get().toString() }

    @Suppress("UNCHECKED_CAST")
    private fun writeRaw(pref: Preference<*>, raw: String) {
        // Dispatch on the live default's type — typed prefs are Int/Float/String/Boolean only.
        when (pref.defaultValue()) {
            is Int -> raw.toIntOrNull()?.let { (pref as Preference<Int>).set(it) }
            is Float -> raw.toFloatOrNull()?.let { (pref as Preference<Float>).set(it) }
            is Boolean -> (pref as Preference<Boolean>).set(raw.toBoolean())
            is String -> (pref as Preference<String>).set(raw)
            else -> Unit
        }
    }

    private fun decode(stored: String): List<NovelStylePreset> = try {
        json.decodeFromString<List<NovelStylePreset>>(stored)
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist(presets: List<NovelStylePreset>): List<NovelStylePreset> {
        prefs.novelStylePresets.set(json.encodeToString(presets))
        return presets
    }
}
