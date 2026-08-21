package dev.ahmedmohamed.hayai.novel.settings

import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import dev.ahmedmohamed.hayai.novel.extension.NovelApkExtensionManagerActivity
import dev.ahmedmohamed.hayai.novel.integration.NovelDataToolsActivity
import dev.ahmedmohamed.hayai.novel.source.builder.NovelCustomSourceBuilderActivity
import dev.ahmedmohamed.hayai.novel.translation.NovelLanguageToolsSettingsActivity
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.ui.setting.SettingsController
import eu.kanade.tachiyomi.ui.setting.bindTo
import eu.kanade.tachiyomi.ui.setting.editTextPreference
import eu.kanade.tachiyomi.ui.setting.intListPreference
import eu.kanade.tachiyomi.ui.setting.listPreference
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.sliderPreference
import eu.kanade.tachiyomi.ui.setting.switchPreference
import eu.kanade.tachiyomi.ui.setting.titleRes
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.widget.preference.IntListMatPreference
import eu.kanade.tachiyomi.widget.preference.ListMatPreference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

class NovelSettingsController : SettingsController() {
    private val novel = HayaiPreferences(Injekt.get())
    private val customization = NovelCustomizationStore(novel)

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.hayai_novel_reader_settings

        preferenceCategory {
            title = "Typography"
            sliderPreference {
                bindTo(novel.novelFontSize)
                title = "Font size"
                entryValues = (8..72).toList()
                valueFormatter = { "$it sp" }
            }
            listPreference(activity) {
                bindTo(novel.novelFontFamily)
                title = "Font family"
                entries = listOf("System sans", "System serif", "Monospace")
                entryValues = listOf("sans-serif", "serif", "monospace")
            }
            floatChoice("Line height", novel.novelLineHeight, listOf(1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f)) { "${it}×" }
            listPreference(activity) {
                bindTo(novel.novelTextAlign)
                title = "Text alignment"
                entries = listOf("Left", "Justified", "Center", "Right")
                entryValues = listOf("left", "justify", "center", "right")
            }
            switchPreference { bindTo(novel.novelTextSelectable); title = "Selectable text" }
            switchPreference { bindTo(novel.novelForceTextLowercase); title = "Force lowercase" }
            switchPreference { bindTo(novel.novelUseOriginalFonts); title = "Use source fonts" }
            colorPreference("Custom text color", novel.novelFontColor)
            colorPreference("Custom background color", novel.novelBackgroundColor)
        }

        preferenceCategory {
            title = "Page layout"
            sliderPreference { bindTo(novel.novelMarginLeft); title = "Left margin"; entryValues = (0..64 step 2).toList(); valueFormatter = { "$it dp" } }
            sliderPreference { bindTo(novel.novelMarginRight); title = "Right margin"; entryValues = (0..64 step 2).toList(); valueFormatter = { "$it dp" } }
            sliderPreference { bindTo(novel.novelMarginTop); title = "Top margin"; entryValues = (0..100 step 5).toList(); valueFormatter = { "$it dp" } }
            sliderPreference { bindTo(novel.novelMarginBottom); title = "Bottom margin"; entryValues = (0..100 step 5).toList(); valueFormatter = { "$it dp" } }
            floatChoice("Paragraph indent", novel.novelParagraphIndent, listOf(0f, 0.5f, 1f, 1.5f, 2f, 3f)) { "${it} em" }
            floatChoice("Paragraph spacing", novel.novelParagraphSpacing, listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 1.5f)) { "${it} em" }
            listPreference(activity) {
                bindTo(novel.novelTheme)
                title = "Novel theme"
                entries = listOf("Follow app", "Light", "Dark", "Sepia")
                entryValues = listOf("app", "light", "dark", "sepia")
            }
            listPreference(activity) {
                bindTo(novel.novelRenderingMode)
                title = "Rendering mode"
                entries = listOf("Default", "Paged", "Continuous")
                entryValues = listOf("default", "paged", "continuous")
            }
        }

        preferenceCategory {
            title = "Reading behavior"
            switchPreference { bindTo(novel.novelInfiniteScroll); title = "Infinite chapter scroll" }
            intListPreference(activity) {
                bindTo(novel.novelKeepChaptersLoaded)
                title = "Keep adjacent chapters loaded"
                entries = listOf("Off", "1 chapter", "2 chapters", "3 chapters", "5 chapters")
                entryValues = listOf(0, 1, 2, 3, 5)
            }
            sliderPreference { bindTo(novel.novelAutoLoadNextChapterAt); title = "Load next chapter at"; entryValues = (50..100 step 5).toList(); valueFormatter = { "$it%" } }
            sliderPreference { bindTo(novel.novelMarkAsReadThreshold); title = "Mark as read at"; entryValues = (50..100 step 5).toList(); valueFormatter = { "$it%" } }
            switchPreference { bindTo(novel.novelMarkShortChapterAsRead); title = "Mark short chapters as read" }
            switchPreference { bindTo(novel.novelHideChapterTitle); title = "Hide chapter title" }
            intListPreference(activity) {
                bindTo(novel.novelChapterTitleDisplay)
                title = "Chapter title format"
                entries = listOf("Name", "Number", "Number and name")
                entryValues = listOf(0, 1, 2)
            }
            switchPreference { bindTo(novel.novelAutoSplitText); title = "Split long text blocks automatically" }
            sliderPreference { bindTo(novel.novelAutoSplitWordCount); title = "Split after word count"; entryValues = (20..200 step 10).toList() }
            switchPreference { bindTo(novel.novelKeepScreenOn); title = "Keep screen on" }
        }

        preferenceCategory {
            title = "Navigation and progress"
            switchPreference { bindTo(novel.novelShowProgressSlider); title = "Show progress slider" }
            switchPreference { bindTo(novel.novelVerticalScrollbar); title = "Vertical scrollbar" }
            listPreference(activity) {
                bindTo(novel.novelVerticalScrollbarPosition)
                title = "Scrollbar position"
                entries = listOf("Left", "Right")
                entryValues = listOf("left", "right")
            }
            listPreference(activity) {
                bindTo(novel.novelVerticalProgressSliderSize)
                title = "Scrollbar thickness"
                entries = listOf("Thin", "Medium", "Thick")
                entryValues = listOf("quarter", "half", "full")
            }
            switchPreference { bindTo(novel.novelSwipeNavigation); title = "Swipe chapter navigation" }
            switchPreference { bindTo(novel.novelVolumeKeysScroll); title = "Volume keys scroll" }
            switchPreference { bindTo(novel.novelTapToScroll); title = "Tap to scroll" }
            sliderPreference {
                bindTo(novel.novelAutoScrollSpeed)
                title = "Auto-scroll speed"
                entryValues = (2..20).toList()
                valueFormatter = { "${it / 2f}×" }
            }
        }

        preferenceCategory {
            title = "Status bar"
            switchPreference { bindTo(novel.novelStatusBarEnabled); title = "Novel status bar" }
            switchPreference { bindTo(novel.novelStatusBarShowTime); title = "Show time" }
            switchPreference { bindTo(novel.novelStatusBarShowBattery); title = "Show battery" }
            switchPreference { bindTo(novel.novelStatusBarShowCharging); title = "Show charging state" }
            switchPreference { bindTo(novel.novelStatusBarShowChapterNumber); title = "Show chapter number" }
            switchPreference { bindTo(novel.novelStatusBarShowChapterTitle); title = "Show chapter title" }
            switchPreference { bindTo(novel.novelStatusBarShowProgress); title = "Show progress" }
            listPreference(activity) {
                bindTo(novel.novelStatusBarPosition)
                title = "Position"
                entries = listOf("Top", "Bottom")
                entryValues = listOf("top", "bottom")
            }
            listPreference(activity) {
                bindTo(novel.novelStatusBarSize)
                title = "Size"
                entries = listOf("Small", "Medium", "Large")
                entryValues = listOf("small", "medium", "large")
            }
        }

        preferenceCategory {
            title = "Text to speech"
            floatChoice("Speech speed", novel.novelTtsSpeed, listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) { "${it}×" }
            floatChoice("Speech pitch", novel.novelTtsPitch, listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) { "${it}×" }
            editTextPreference(activity) { bindTo(novel.novelTtsVoice); title = "Preferred voice ID"; summary = "Blank uses the system default" }
            switchPreference { bindTo(novel.novelTtsAutoNextChapter); title = "Continue into next chapter" }
            switchPreference { bindTo(novel.novelTtsEnableHighlight); title = "Highlight spoken text" }
            switchPreference { bindTo(novel.novelTtsKeepHighlightInView); title = "Keep highlight in view" }
            listPreference(activity) {
                bindTo(novel.novelTtsHighlightStyle)
                title = "Highlight style"
                entries = listOf("Background", "Underline", "Text color")
                entryValues = listOf("background", "underline", "text")
            }
            colorPreference("Highlight color", novel.novelTtsHighlightColor)
            colorPreference("Highlight text color", novel.novelTtsHighlightTextColor)
            switchPreference { bindTo(novel.novelTtsBackgroundPlayback); title = "Background playback" }
            switchPreference { bindTo(novel.novelTtsControlsVisible); title = "Keep TTS controls visible" }
            switchPreference { bindTo(novel.novelTtsAutoStartOnPanelOpen); title = "Start TTS when a chapter opens" }
        }

        preferenceCategory {
            title = "Source content and customization"
            switchPreference { bindTo(novel.novelEnableEpubStyles); title = "Use EPUB styles" }
            switchPreference { bindTo(novel.novelEnableEpubJs); title = "Allow EPUB JavaScript"; summary = "Only enable for trusted books" }
            switchPreference { bindTo(novel.novelSourceCssPriority); title = "Prefer source CSS" }
            switchPreference { bindTo(novel.novelBlockMedia); title = "Block embedded media" }
            switchPreference { bindTo(novel.novelShowRawHtml); title = "Show raw HTML" }
            editTextPreference(activity) { bindTo(novel.novelCustomCss); title = "Custom CSS"; summary = "Applied to every novel after sanitization" }
            editTextPreference(activity) { bindTo(novel.novelCustomJs); title = "Custom JavaScript"; summary = "Runs only inside the isolated novel reader" }
            preference {
                title = "CSS snippets"
                summary = "Create, edit, enable, disable, and delete reusable styles"
                onClick { showSnippetList(SnippetKind.Css) }
            }
            preference {
                title = "JavaScript snippets"
                summary = "Reusable scripts; only add code you trust"
                onClick { showSnippetList(SnippetKind.JavaScript) }
            }
            preference {
                title = "Text replacements"
                summary = "Clean source text with ordered literal or regular-expression rules"
                onClick { showReplacementList() }
            }
            preference {
                title = "Reader presets"
                summary = "Save the current typography and layout, then restore it later"
                onClick { showPresetList() }
            }
        }

        preferenceCategory {
            title = "Language, sources, and data"
            preference {
                title = "Translation and dictionary"
                summary = "Choose translation providers, languages, credentials, and dictionary fallback."
                onClick { startActivity(Intent(context, NovelLanguageToolsSettingsActivity::class.java)) }
            }
            preference {
                title = "Visual novel source builder"
                summary = "Create, validate, preview, install, edit, export, and remove CSS-selector novel sources."
                onClick { startActivity(Intent(context, NovelCustomSourceBuilderActivity::class.java)) }
            }
            preference {
                title = "Remote novel extensions"
                summary = "Manage trusted repositories and install, update, remove, or trust novel APK extensions through J2K."
                onClick { startActivity(Intent(context, NovelApkExtensionManagerActivity::class.java)) }
            }
            preference {
                title = "Import Tsundoku or LNReader"
                summary = "Inspect an external backup, review warnings and missing sources, then import it transactionally."
                onClick { startActivity(Intent(context, NovelDataToolsActivity::class.java)) }
            }
        }

        preferenceCategory {
            title = "Screen brightness"
            switchPreference { bindTo(novel.novelCustomBrightness); title = "Override brightness for novels" }
            sliderPreference {
                bindTo(novel.novelCustomBrightnessValue)
                title = "Brightness adjustment"
                entryValues = (-100..100 step 5).toList()
                valueFormatter = { if (it > 0) "+$it%" else "$it%" }
            }
        }
    }

    private fun PreferenceGroup.floatChoice(
        titleText: String,
        valuePreference: Preference<Float>,
        values: List<Float>,
        formatter: (Float) -> String,
    ) {
        preference {
            title = titleText
            summary = formatter(valuePreference.get())
            onClick {
                val labels = values.map(formatter).toTypedArray()
                val selected = values.indices.minByOrNull { kotlin.math.abs(values[it] - valuePreference.get()) } ?: 0
                context.materialAlertDialog()
                    .setTitle(titleText)
                    .setSingleChoiceItems(labels, selected) { dialog, which ->
                        valuePreference.set(values[which])
                        summary = formatter(values[which])
                        dialog.dismiss()
                    }.show()
            }
        }
    }

    private fun <T> androidx.preference.Preference.bindTo(preference: Preference<T>) {
        key = preference.key()
        setDefaultValue(preference.defaultValue())
    }

    private fun <T> ListMatPreference.bindTo(preference: Preference<T>) {
        key = preference.key()
        setDefaultValue(preference.defaultValue().toString())
    }

    private fun <T> IntListMatPreference.bindTo(preference: Preference<T>) {
        key = preference.key()
        setDefaultValue(preference.defaultValue())
    }

    private fun PreferenceGroup.colorPreference(
        titleText: String,
        valuePreference: Preference<Int>,
    ) {
        preference {
            title = titleText
            summary = colorSummary(valuePreference.get())
            onClick {
                val input = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine(true)
                    setText(if (valuePreference.get() == 0) "" else String.format("#%08X", valuePreference.get()))
                    hint = "#AARRGGBB, blank for automatic"
                }
                val dialog = context.materialAlertDialog()
                    .setTitle(titleText)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val text = input.text?.toString()?.trim().orEmpty()
                        val parsed = if (text.isBlank()) 0 else runCatching { Color.parseColor(text) }.getOrNull()
                        if (parsed == null) {
                            input.error = "Use #RRGGBB or #AARRGGBB"
                        } else {
                            valuePreference.set(parsed)
                            summary = colorSummary(parsed)
                            dialog.dismiss()
                        }
                    }
                }
                dialog.show()
            }
        }
    }

    private fun colorSummary(color: Int): String = if (color == 0) "Automatic" else String.format("#%08X", color)

    private fun showSnippetList(kind: SnippetKind) {
        val items = if (kind == SnippetKind.Css) customization.cssSnippets() else customization.jsSnippets()
        val labels = items.map { "${if (it.enabled) "✓" else "○"}  ${it.title}" }.toTypedArray()
        val dialog =
            activity!!.materialAlertDialog()
                .setTitle(if (kind == SnippetKind.Css) "CSS snippets" else "JavaScript snippets")
                .setItems(labels) { _, index -> showSnippetActions(kind, items[index]) }
                .setPositiveButton("Add", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { editSnippet(kind, null) }
        }
        dialog.show()
    }

    private fun showSnippetActions(
        kind: SnippetKind,
        snippet: NovelCodeSnippet,
    ) {
        activity!!.materialAlertDialog()
            .setTitle(snippet.title)
            .setItems(arrayOf(if (snippet.enabled) "Disable" else "Enable", "Edit", "Delete")) { _, action ->
                when (action) {
                    0 -> saveSnippet(kind, snippet.copy(enabled = !snippet.enabled))
                    1 -> editSnippet(kind, snippet)
                    2 -> confirmDelete(snippet.title) {
                        val updated = snippetItems(kind).filterNot { it.id == snippet.id }
                        saveSnippetItems(kind, updated)
                    }
                }
            }.show()
    }

    private fun editSnippet(
        kind: SnippetKind,
        existing: NovelCodeSnippet?,
    ) {
        val titleInput = textInput("Name", existing?.title.orEmpty(), singleLine = true)
        val codeInput = textInput(if (kind == SnippetKind.Css) "CSS" else "JavaScript", existing?.code.orEmpty())
        val enabled = CheckBox(activity).apply { text = "Enabled"; isChecked = existing?.enabled ?: true }
        val content = form(titleInput, codeInput, enabled)
        val dialog =
            activity!!.materialAlertDialog()
                .setTitle(if (existing == null) "Add snippet" else "Edit snippet")
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text?.toString()?.trim().orEmpty()
                val code = codeInput.text?.toString().orEmpty()
                when {
                    title.isBlank() -> titleInput.error = "Name is required"
                    code.isBlank() -> codeInput.error = "Code is required"
                    else -> {
                        saveSnippet(
                            kind,
                            NovelCodeSnippet(existing?.id ?: UUID.randomUUID().toString(), title, code, enabled.isChecked),
                        )
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun saveSnippet(
        kind: SnippetKind,
        snippet: NovelCodeSnippet,
    ) {
        val items = snippetItems(kind).toMutableList()
        val index = items.indexOfFirst { it.id == snippet.id }
        if (index >= 0) items[index] = snippet else items += snippet
        saveSnippetItems(kind, items)
    }

    private fun snippetItems(kind: SnippetKind) =
        if (kind == SnippetKind.Css) customization.cssSnippets() else customization.jsSnippets()

    private fun saveSnippetItems(
        kind: SnippetKind,
        items: List<NovelCodeSnippet>,
    ) {
        if (kind == SnippetKind.Css) customization.saveCssSnippets(items) else customization.saveJsSnippets(items)
    }

    private fun showReplacementList() {
        val items = customization.replacements()
        val labels = items.map { "${if (it.enabled) "✓" else "○"}  ${it.title}" }.toTypedArray()
        val dialog =
            activity!!.materialAlertDialog()
                .setTitle("Text replacements")
                .setItems(labels) { _, index -> showReplacementActions(items[index]) }
                .setPositiveButton("Add", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { editReplacement(null) }
        }
        dialog.show()
    }

    private fun showReplacementActions(rule: NovelRegexReplacement) {
        activity!!.materialAlertDialog()
            .setTitle(rule.title)
            .setItems(arrayOf(if (rule.enabled) "Disable" else "Enable", "Edit", "Delete")) { _, action ->
                when (action) {
                    0 -> saveReplacement(rule.copy(enabled = !rule.enabled))
                    1 -> editReplacement(rule)
                    2 -> confirmDelete(rule.title) {
                        customization.saveReplacements(customization.replacements().filterNot { it.id == rule.id })
                    }
                }
            }.show()
    }

    private fun editReplacement(existing: NovelRegexReplacement?) {
        val titleInput = textInput("Name", existing?.title.orEmpty(), singleLine = true)
        val patternInput = textInput("Pattern", existing?.pattern.orEmpty())
        val replacementInput = textInput("Replacement", existing?.replacement.orEmpty())
        val enabled = CheckBox(activity).apply { text = "Enabled"; isChecked = existing?.enabled ?: true }
        val regex = CheckBox(activity).apply { text = "Regular expression"; isChecked = existing?.isRegex ?: true }
        val wholeWord = CheckBox(activity).apply { text = "Match whole words"; isChecked = existing?.matchWholeWord ?: false }
        val caseSensitive = CheckBox(activity).apply { text = "Case sensitive"; isChecked = existing?.caseSensitive ?: false }
        val dialog =
            activity!!.materialAlertDialog()
                .setTitle(if (existing == null) "Add replacement" else "Edit replacement")
                .setView(form(titleInput, patternInput, replacementInput, enabled, regex, wholeWord, caseSensitive))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text?.toString()?.trim().orEmpty()
                val pattern = patternInput.text?.toString().orEmpty()
                when {
                    title.isBlank() -> titleInput.error = "Name is required"
                    pattern.isBlank() -> patternInput.error = "Pattern is required"
                    regex.isChecked && NovelRegexSafety.rejectionReason(pattern) != null ->
                        patternInput.error = NovelRegexSafety.rejectionReason(pattern)
                    else -> {
                        saveReplacement(
                            NovelRegexReplacement(
                                id = existing?.id ?: UUID.randomUUID().toString(),
                                title = title,
                                pattern = pattern,
                                replacement = replacementInput.text?.toString().orEmpty(),
                                enabled = enabled.isChecked,
                                isRegex = regex.isChecked,
                                matchWholeWord = wholeWord.isChecked,
                                caseSensitive = caseSensitive.isChecked,
                            ),
                        )
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun saveReplacement(rule: NovelRegexReplacement) {
        val items = customization.replacements().toMutableList()
        val index = items.indexOfFirst { it.id == rule.id }
        if (index >= 0) items[index] = rule else items += rule
        customization.saveReplacements(items)
    }

    private fun showPresetList() {
        val items = customization.presets()
        val dialog =
            activity!!.materialAlertDialog()
                .setTitle("Reader presets")
                .setItems(items.map(NovelReaderPreset::name).toTypedArray()) { _, index -> showPresetActions(items[index]) }
                .setPositiveButton("Save current", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { nameCurrentPreset() }
        }
        dialog.show()
    }

    private fun nameCurrentPreset() {
        val input = textInput("Preset name", "", singleLine = true)
        val dialog =
            activity!!.materialAlertDialog()
                .setTitle("Save current reader style")
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    input.error = "Name is required"
                } else {
                    customization.savePresets(customization.presets() + customization.createPreset(name))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showPresetActions(preset: NovelReaderPreset) {
        activity!!.materialAlertDialog()
            .setTitle(preset.name)
            .setItems(arrayOf("Apply", "Replace with current", "Delete")) { _, action ->
                when (action) {
                    0 -> customization.apply(preset)
                    1 -> {
                        val replacement = customization.createPreset(preset.name).copy(id = preset.id)
                        customization.savePresets(customization.presets().map { if (it.id == preset.id) replacement else it })
                    }
                    2 -> confirmDelete(preset.name) {
                        customization.savePresets(customization.presets().filterNot { it.id == preset.id })
                    }
                }
            }.show()
    }

    private fun confirmDelete(
        name: String,
        delete: () -> Unit,
    ) {
        activity!!.materialAlertDialog()
            .setTitle("Delete $name?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> delete() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun textInput(
        hintText: String,
        value: String,
        singleLine: Boolean = false,
    ) = EditText(activity).apply {
        hint = hintText
        setText(value)
        setSingleLine(singleLine)
        if (!singleLine) {
            minLines = 3
            maxLines = 12
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
    }

    private fun form(vararg views: android.view.View): ScrollView {
        val density = activity!!.resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
            views.forEach { view ->
                if (view.parent != null) (view.parent as? ViewGroup)?.removeView(view)
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        return ScrollView(activity).apply { addView(container) }
    }

    private enum class SnippetKind { Css, JavaScript }
}
