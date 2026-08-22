package dev.ahmedmohamed.hayai.novel.reader

import android.app.Activity
import android.graphics.Typeface
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import eu.kanade.tachiyomi.R
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.widget.TabbedBottomSheetDialog

internal class NovelReaderSettingsSheet(
    activity: Activity,
    private val preferences: HayaiPreferences,
    private val onStyleChanged: () -> Unit,
    private val onChromeChanged: () -> Unit,
    private val onAction: (NovelReaderAction) -> Unit,
) : TabbedBottomSheetDialog(activity) {
    // Keep these as individual fields, matching J2K's own tabbed sheets. The base class asks
    // for the tab count from its constructor, before a subclass collection field can exist.
    private val readerView = page { readerPage(this) }
    private val textView = page { textPage(this) }
    private val layoutView = page { layoutPage(this) }
    private val ttsView = page { ttsPage(this) }
    private val moreView = page { morePage(this) }

    override fun getTabViews(): List<View> = listOf(readerView, textView, layoutView, ttsView, moreView)

    override fun getTabTitles(): List<Int> =
        listOf(
            R.string.hayai_novel_tab_reader,
            R.string.hayai_novel_tab_text,
            R.string.hayai_novel_tab_layout,
            R.string.hayai_novel_tab_tts,
            R.string.hayai_novel_tab_more,
        )

    private fun readerPage(page: LinearLayout) {
        page.heading("Renderer")
        page.choice("Reading engine", preferences.novelRenderingMode, listOf("Native text" to "default", "WebView" to "webview"), onStyleChanged)
        page.choice(
            "Theme",
            preferences.novelTheme,
            listOf("App" to "app", "Light" to "light", "Dark" to "dark", "Sepia" to "sepia", "Black" to "black", "Grey" to "grey", "Custom" to "custom"),
            onStyleChanged,
        )
        page.choice(
            "Tap zones",
            preferences.novelNavigationMode,
            listOf("Default" to 0, "L" to 1, "Kindlish" to 2, "Edge" to 3, "Left / right" to 4, "Disabled" to 5, "Center only" to 6),
            onChromeChanged,
        )
        page.choice(
            "Tap inversion",
            preferences.novelNavigationInverted,
            listOf("None" to "NONE", "Horizontal" to "HORIZONTAL", "Vertical" to "VERTICAL", "Both" to "BOTH"),
            onChromeChanged,
        )
        page.toggle("Tap to scroll", preferences.novelTapToScroll)
        page.toggle("Swipe between chapters", preferences.novelSwipeNavigation)
        page.toggle("Volume keys scroll", preferences.novelVolumeKeysScroll)
        page.toggle("Keep screen on", preferences.novelKeepScreenOn, onChromeChanged)
        page.toggle("Fullscreen", preferences.novelFullscreen, onChromeChanged)
        page.toggle("Infinite scroll", preferences.novelInfiniteScroll, onStyleChanged)
        page.slider("Load next chapter at", preferences.novelAutoLoadNextChapterAt, 50, 100, "%", onStyleChanged)
        page.slider("Mark chapter read at", preferences.novelMarkAsReadThreshold, 50, 100, "%")
        page.toggle("Mark short chapters read", preferences.novelMarkShortChapterAsRead)
        page.toggle("Split long text blocks", preferences.novelAutoSplitText, onStyleChanged)
        page.slider("Split after words", preferences.novelAutoSplitWordCount, 20, 200, "words", onStyleChanged)
    }

    private fun textPage(page: LinearLayout) {
        page.heading("Typography")
        page.slider("Font size", preferences.novelFontSize, 8, 72, "sp", onStyleChanged)
        page.choice("Font", preferences.novelFontFamily, listOf("Sans serif" to "sans-serif", "Serif" to "serif", "Monospace" to "monospace"), onStyleChanged)
        page.button("Import a font file") { onAction(NovelReaderAction.ImportFont) }
        page.button("Choose or delete imported fonts") { onAction(NovelReaderAction.ManageFonts) }
        page.choice("Alignment", preferences.novelTextAlign, listOf("Left" to "left", "Justified" to "justify", "Center" to "center", "Right" to "right"), onStyleChanged)
        page.toggle("Selectable text", preferences.novelTextSelectable, onStyleChanged)
        page.toggle("Force lowercase", preferences.novelForceTextLowercase, onStyleChanged)
        page.toggle("Use source fonts", preferences.novelUseOriginalFonts, onStyleChanged)
        page.sliderFloat("Line height", preferences.novelLineHeight, 8, 30, 10f, onStyleChanged)
        page.sliderFloat("Paragraph indent", preferences.novelParagraphIndent, 0, 100, 10f, onStyleChanged)
        page.sliderFloat("Paragraph spacing", preferences.novelParagraphSpacing, 0, 50, 10f, onStyleChanged)
        page.button("Text color") { editColor("Text color", preferences.novelFontColor, onStyleChanged) }
        page.button("Background color") { editColor("Background color", preferences.novelBackgroundColor, onStyleChanged) }
        page.toggle("Custom brightness", preferences.novelCustomBrightness, onChromeChanged)
        page.slider("Brightness adjustment", preferences.novelCustomBrightnessValue, -75, 100, "%", onChromeChanged)
    }

    private fun layoutPage(page: LinearLayout) {
        page.heading("Page")
        page.slider("Left margin", preferences.novelMarginLeft, 0, 100, "dp", onStyleChanged)
        page.slider("Right margin", preferences.novelMarginRight, 0, 100, "dp", onStyleChanged)
        page.slider("Top margin", preferences.novelMarginTop, 0, 150, "dp", onStyleChanged)
        page.slider("Bottom margin", preferences.novelMarginBottom, 0, 150, "dp", onStyleChanged)
        progressMode(page)
        page.choice("Preload chapters", preferences.novelKeepChaptersLoaded, listOf("Current only" to 0, "Previous" to 1, "Next" to 2, "Both" to 3), onStyleChanged)
        page.choice("Vertical progress size", preferences.novelVerticalProgressSliderSize, listOf("Half screen" to "half", "Full screen" to "full"), onChromeChanged)
        page.toggle("Novel status bar", preferences.novelStatusBarEnabled, onChromeChanged)
        page.choice("Status position", preferences.novelStatusBarPosition, listOf("Top" to "top", "Bottom" to "bottom"), onChromeChanged)
        page.choice("Status size", preferences.novelStatusBarSize, listOf("Small" to "small", "Medium" to "medium"), onChromeChanged)
    }

    private fun ttsPage(page: LinearLayout) {
        page.heading("Read aloud")
        page.sliderFloat("Speed", preferences.novelTtsSpeed, 5, 60, 10f)
        page.sliderFloat("Pitch", preferences.novelTtsPitch, 5, 60, 10f)
        page.button("Choose installed voice") { chooseVoice() }
        page.toggle("Continue to next chapter", preferences.novelTtsAutoNextChapter)
        page.toggle("Highlight spoken paragraph", preferences.novelTtsEnableHighlight)
        page.toggle("Keep highlight in view", preferences.novelTtsKeepHighlightInView)
        page.choice("Highlight style", preferences.novelTtsHighlightStyle, listOf("Background" to "background", "Underline" to "underline", "Outline" to "outline"), onStyleChanged)
        page.toggle("Background playback", preferences.novelTtsBackgroundPlayback)
        page.toggle("Show paragraph controls", preferences.novelTtsControlsVisible, onChromeChanged)
        page.toggle("Start when TTS panel opens", preferences.novelTtsAutoStartOnPanelOpen)
        page.button("Read from visible paragraph") { onAction(NovelReaderAction.StartTtsAtViewport) }
    }

    private fun progressMode(page: LinearLayout) {
        val values = listOf("None", "Horizontal", "Vertical left", "Vertical right")
        val current =
            when {
                !preferences.novelShowProgressSlider.get() -> 0
                !preferences.novelVerticalScrollbar.get() -> 1
                preferences.novelVerticalScrollbarPosition.get() == "left" -> 2
                else -> 3
            }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8.dp, 4.dp, 8.dp, 4.dp) }
        row.addView(TextView(context).apply { text = "Progress mode" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(current)
            onItemSelectedListener = SimpleItemSelectedListener { selection ->
                val enabled = selection != 0
                val vertical = selection >= 2
                if (preferences.novelShowProgressSlider.get() != enabled) preferences.novelShowProgressSlider.set(enabled)
                if (preferences.novelVerticalScrollbar.get() != vertical) preferences.novelVerticalScrollbar.set(vertical)
                if (vertical) preferences.novelVerticalScrollbarPosition.set(if (selection == 2) "left" else "right")
                onChromeChanged()
            }
        })
        page.addView(row)
    }

    private fun morePage(page: LinearLayout) {
        page.heading("Content")
        page.toggle("Use EPUB styles", preferences.novelEnableEpubStyles, onStyleChanged)
        page.toggle("Allow trusted EPUB JavaScript", preferences.novelEnableEpubJs, onStyleChanged)
        page.toggle("Prefer source CSS", preferences.novelSourceCssPriority, onStyleChanged)
        page.toggle("Block embedded media", preferences.novelBlockMedia, onStyleChanged)
        page.toggle("Show raw HTML", preferences.novelShowRawHtml, onStyleChanged)
        page.toggle("WebView developer tools", preferences.novelWebViewDevTools)
        page.toggle("Show WebView console errors", preferences.novelConsoleErrorToast)
        page.button("Open full novel settings") { onAction(NovelReaderAction.OpenFullSettings) }
        page.heading("Bottom actions")
        bottomActionEditor(page)
        page.heading("Status order")
        statusEditor(page)
        page.heading("Chapter tools")
        page.button("Save selected quote") { onAction(NovelReaderAction.SaveQuote) }
        page.button("Highlights") { onAction(NovelReaderAction.ShowHighlights) }
        page.button("Dictionary lookup") { onAction(NovelReaderAction.DictionaryLookup) }
        page.button("Translate chapter") { onAction(NovelReaderAction.TranslateChapter) }
        page.button("Chapter statistics") { onAction(NovelReaderAction.ShowStatistics) }
        page.button("Save or remove offline copy") { onAction(NovelReaderAction.ToggleOffline) }
        page.button("Toggle chapter bookmark") { onAction(NovelReaderAction.ToggleBookmark) }
        page.button("Edit chapter text") { onAction(NovelReaderAction.ToggleEditMode) }
    }

    private fun page(content: LinearLayout.() -> Unit): NestedScrollView =
        NestedScrollView(context).apply {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16.dp, 8.dp, 16.dp, 24.dp)
                    content()
                },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

    private fun LinearLayout.heading(text: String) {
        addView(TextView(context).apply { this.text = text; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setPadding(8.dp, 16.dp, 8.dp, 4.dp) })
    }

    private fun LinearLayout.toggle(label: String, preference: Preference<Boolean>, changed: () -> Unit = {}) {
        addView(CheckBox(context).apply {
            text = label
            isChecked = preference.get()
            setPadding(8.dp, 4.dp, 8.dp, 4.dp)
            setOnCheckedChangeListener { _, checked -> preference.set(checked); changed() }
        })
    }

    private fun <T> LinearLayout.choice(label: String, preference: Preference<T>, values: List<Pair<String, T>>, changed: () -> Unit = {}) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8.dp, 4.dp, 8.dp, 4.dp) }
        row.addView(TextView(context).apply { text = label }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values.map { it.first })
            setSelection(values.indexOfFirst { it.second == preference.get() }.coerceAtLeast(0))
            onItemSelectedListener = SimpleItemSelectedListener { position ->
                val selected = values[position].second
                if (selected != preference.get()) { preference.set(selected); changed() }
            }
        })
        addView(row)
    }

    private fun LinearLayout.slider(label: String, preference: Preference<Int>, min: Int, max: Int, suffix: String, changed: () -> Unit = {}) {
        val title = TextView(context)
        fun update(value: Int) { title.text = "$label   $value $suffix" }
        update(preference.get())
        addView(title.apply { setPadding(8.dp, 8.dp, 8.dp, 0) })
        addView(SeekBar(context).apply {
            this.max = max - min
            progress = preference.get().coerceIn(min, max) - min
            setOnSeekBarChangeListener(SimpleSeekBarListener { value -> preference.set(value + min); update(value + min); changed() })
        })
    }

    private fun LinearLayout.sliderFloat(label: String, preference: Preference<Float>, min: Int, max: Int, scale: Float, changed: () -> Unit = {}) {
        val title = TextView(context)
        fun update(value: Int) { title.text = "$label   ${"%.1f".format(value / scale)}×" }
        val initial = (preference.get() * scale).toInt().coerceIn(min, max)
        update(initial)
        addView(title.apply { setPadding(8.dp, 8.dp, 8.dp, 0) })
        addView(SeekBar(context).apply {
            this.max = max - min
            progress = initial - min
            setOnSeekBarChangeListener(SimpleSeekBarListener { value -> val actual = value + min; preference.set(actual / scale); update(actual); changed() })
        })
    }

    private fun LinearLayout.button(label: String, action: () -> Unit) {
        addView(Button(context).apply { text = label; setOnClickListener { action() } })
    }

    private fun editString(title: String, preference: Preference<String>, changed: () -> Unit) {
        val input = android.widget.EditText(context).apply { setText(preference.get()); setSingleLine() }
        AlertDialog.Builder(context).setTitle(title).setView(input).setPositiveButton(android.R.string.ok) { _, _ ->
            input.text.toString().trim().takeIf(String::isNotBlank)?.let { preference.set(it); changed() }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun editColor(title: String, preference: Preference<Int>, changed: () -> Unit) {
        val input = android.widget.EditText(context).apply {
            setSingleLine()
            hint = "#AARRGGBB, blank for automatic"
            setText(preference.get().takeUnless { it == 0 }?.let { String.format("#%08X", it) }.orEmpty())
        }
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(input).setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                val parsed = if (value.isBlank()) 0 else runCatching { Color.parseColor(value) }.getOrNull()
                if (parsed == null) input.error = "Use #RRGGBB or #AARRGGBB" else { preference.set(parsed); changed(); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun chooseVoice() {
        var engine: android.speech.tts.TextToSpeech? = null
        engine = android.speech.tts.TextToSpeech(context) { result ->
            if (result == android.speech.tts.TextToSpeech.SUCCESS) {
                val voices = engine?.voices.orEmpty().filterNot { it.isNetworkConnectionRequired }.sortedWith(compareBy({ it.locale.displayName }, { it.name }))
                val labels = listOf("System default") + voices.map { voice ->
                    "${voice.locale.displayName} · ${voice.name}"
                }
                val values = listOf("") + voices.map { it.name }
                AlertDialog.Builder(context).setTitle("Installed voice").setSingleChoiceItems(labels.toTypedArray(), values.indexOf(preferences.novelTtsVoice.get()).coerceAtLeast(0)) { dialog, index ->
                    preferences.novelTtsVoice.set(values[index]); dialog.dismiss()
                }.setOnDismissListener { engine?.shutdown() }.show()
            } else {
                engine?.shutdown()
            }
        }
    }

    private fun bottomActionEditor(container: LinearLayout) {
        fun render() {
            val marker = "hayai-bottom-actions"
            container.findViewWithTag<View>(marker)?.let(container::removeView)
            val group = LinearLayout(context).apply { tag = marker; orientation = LinearLayout.VERTICAL }
            val items = NovelBottomActions.deserialize(preferences.novelBottomBarItems.get()).toMutableList()
            items.forEachIndexed { index, state ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(CheckBox(context).apply {
                    text = state.action.name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                    isChecked = state.enabled
                    setOnCheckedChangeListener { _, checked ->
                        items[index] = state.copy(enabled = checked)
                        preferences.novelBottomBarItems.set(NovelBottomActions.serialize(items))
                        onChromeChanged()
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Button(context).apply { text = "↑"; isEnabled = index > 0; setOnClickListener {
                    val moved = items.removeAt(index); items.add(index - 1, moved)
                    preferences.novelBottomBarItems.set(NovelBottomActions.serialize(items)); onChromeChanged(); render()
                } })
                row.addView(Button(context).apply { text = "↓"; isEnabled = index < items.lastIndex; setOnClickListener {
                    val moved = items.removeAt(index); items.add(index + 1, moved)
                    preferences.novelBottomBarItems.set(NovelBottomActions.serialize(items)); onChromeChanged(); render()
                } })
                group.addView(row)
            }
            container.addView(group)
        }
        render()
    }

    private fun statusEditor(container: LinearLayout) {
        fun render() {
            val marker = "hayai-status-actions"
            container.findViewWithTag<View>(marker)?.let(container::removeView)
            val group = LinearLayout(context).apply { tag = marker; orientation = LinearLayout.VERTICAL }
            val items = NovelStatusItems.deserialize(preferences.novelStatusBarOrder.get()).toMutableList()
            items.forEachIndexed { index, item ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(context).apply { text = item.name }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Button(context).apply { text = "↑"; isEnabled = index > 0; setOnClickListener {
                    val moved = items.removeAt(index); items.add(index - 1, moved)
                    preferences.novelStatusBarOrder.set(NovelStatusItems.serialize(items)); onChromeChanged(); render()
                } })
                row.addView(Button(context).apply { text = "↓"; isEnabled = index < items.lastIndex; setOnClickListener {
                    val moved = items.removeAt(index); items.add(index + 1, moved)
                    preferences.novelStatusBarOrder.set(NovelStatusItems.serialize(items)); onChromeChanged(); render()
                } })
                group.addView(row)
            }
            container.addView(group)
        }
        render()
    }

    private val Int.dp: Int get() = (this * context.resources.displayMetrics.density).toInt()

    private class SimpleSeekBarListener(private val changed: (Int) -> Unit) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) changed(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    }
}

private class SimpleItemSelectedListener(
    private val selected: (Int) -> Unit,
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = selected(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
