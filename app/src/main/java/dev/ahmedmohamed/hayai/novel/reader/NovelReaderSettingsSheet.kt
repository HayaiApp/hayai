package dev.ahmedmohamed.hayai.novel.reader

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.widget.MaterialSpinnerView
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

    init {
        val tabs =
            listOf(
                R.drawable.ic_text_fields_24dp to R.string.hayai_novel_tab_reader,
                R.drawable.ic_palette_24dp to R.string.hayai_novel_tab_text,
                R.drawable.ic_swipe_24dp to R.string.hayai_novel_tab_layout,
                R.drawable.ic_record_voice_over_24dp to R.string.hayai_novel_tab_tts,
                R.drawable.ic_code_24dp to R.string.hayai_novel_tab_more,
            )
        tabs.forEachIndexed { index, (icon, label) ->
            binding.tabs.getTabAt(index)?.apply {
                setIcon(icon)
                text = null
                contentDescription = context.getString(label)
            }
        }
        binding.menu.isVisible = true
        binding.menu.tooltipText = context.getString(R.string.reader_settings)
        binding.menu.contentDescription = context.getString(R.string.reader_settings)
        binding.menu.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_outline_settings_24dp))
        binding.menu.setOnClickListener {
            onAction(NovelReaderAction.OpenFullSettings)
            dismiss()
        }
    }

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
        page.addView(TextView(context).apply { text = "Progress mode"; setPadding(0, 12.dp, 0, 4.dp) })
        val chips = ChipGroup(context).apply { isSingleSelection = true; isSelectionRequired = true; isSingleLine = true }
        values.forEachIndexed { selection, label ->
            chips.addView(
                Chip(context).apply {
                    text = label
                    isCheckable = true
                    isChecked = selection == current
                    setOnClickListener {
                        val enabled = selection != 0
                        val vertical = selection >= 2
                        if (preferences.novelShowProgressSlider.get() != enabled) preferences.novelShowProgressSlider.set(enabled)
                        if (preferences.novelVerticalScrollbar.get() != vertical) preferences.novelVerticalScrollbar.set(vertical)
                        if (vertical) preferences.novelVerticalScrollbarPosition.set(if (selection == 2) "left" else "right")
                        onChromeChanged()
                    }
                },
            )
        }
        page.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
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
                    setPadding(24.dp, 4.dp, 24.dp, 24.dp)
                    content()
                },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

    private fun LinearLayout.heading(text: String) {
        addView(
            TextView(context).apply {
                this.text = text
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.getResourceColor(R.attr.colorPrimary))
                setPadding(0, 16.dp, 0, 4.dp)
            },
        )
    }

    private fun LinearLayout.toggle(label: String, preference: Preference<Boolean>, changed: () -> Unit = {}) {
        addView(
            MaterialSwitch(context).apply {
                text = label
                textSize = 15f
                isChecked = preference.get()
                minHeight = 48.dp
                setTextColor(context.getResourceColor(R.attr.colorOnBackground))
                setOnCheckedChangeListener { _, checked ->
                    preference.set(checked)
                    changed()
                }
            },
            matchWrap(),
        )
    }

    private fun <T> LinearLayout.choice(label: String, preference: Preference<T>, values: List<Pair<String, T>>, changed: () -> Unit = {}) {
        val selected = values.indexOfFirst { it.second == preference.get() }.coerceAtLeast(0)
        addView(
            MaterialSpinnerView(context, null).apply {
                title = label
                setEntries(values.map { it.first })
                setSelection(selected)
                onItemSelectedListener = { position ->
                    val value = values[position].second
                    if (value != preference.get()) {
                        preference.set(value)
                        changed()
                    }
                }
            },
            matchWrap(),
        )
    }

    private fun LinearLayout.slider(label: String, preference: Preference<Int>, min: Int, max: Int, suffix: String, changed: () -> Unit = {}) {
        val valueText = sliderHeader(label)
        fun update(value: Int) { valueText.text = "$value $suffix" }
        update(preference.get().coerceIn(min, max))
        addView(Slider(context).apply {
            valueFrom = min.toFloat()
            valueTo = max.toFloat()
            stepSize = 1f
            value = preference.get().coerceIn(min, max).toFloat()
            addOnChangeListener { _, newValue, fromUser ->
                if (fromUser) {
                    val resolved = newValue.toInt()
                    preference.set(resolved)
                    update(resolved)
                    changed()
                }
            }
        })
    }

    private fun LinearLayout.sliderFloat(label: String, preference: Preference<Float>, min: Int, max: Int, scale: Float, changed: () -> Unit = {}) {
        val valueText = sliderHeader(label)
        fun update(value: Float) { valueText.text = "${"%.1f".format(value)}×" }
        val initial = preference.get().coerceIn(min / scale, max / scale)
        update(initial)
        addView(Slider(context).apply {
            valueFrom = min / scale
            valueTo = max / scale
            stepSize = 1f / scale
            value = initial
            addOnChangeListener { _, newValue, fromUser ->
                if (fromUser) {
                    preference.set(newValue)
                    update(newValue)
                    changed()
                }
            }
        })
    }

    private fun LinearLayout.button(label: String, action: () -> Unit) {
        addView(
            MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = 48.dp
                isAllCaps = false
                setPadding(0, 0, 0, 0)
                setOnClickListener { action() }
            },
            matchWrap(),
        )
    }

    private fun LinearLayout.sliderHeader(label: String): TextView {
        val value = TextView(context).apply {
            textSize = 14f
            gravity = Gravity.END
            setTextColor(context.getResourceColor(android.R.attr.textColorSecondary))
        }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12.dp, 0, 0)
                addView(TextView(context).apply { text = label; textSize = 15f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
            matchWrap(),
        )
        return value
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
                group.addView(
                    orderedRow(
                        title = state.action.displayName(),
                        enabled = state.enabled,
                        canMoveUp = index > 0,
                        canMoveDown = index < items.lastIndex,
                        onEnabledChanged = { checked ->
                        items[index] = state.copy(enabled = checked)
                        preferences.novelBottomBarItems.set(NovelBottomActions.serialize(items))
                        onChromeChanged()
                        },
                        onMoveUp = {
                            items.add(index - 1, items.removeAt(index))
                            preferences.novelBottomBarItems.set(NovelBottomActions.serialize(items))
                            onChromeChanged()
                            render()
                        },
                        onMoveDown = {
                            items.add(index + 1, items.removeAt(index))
                            preferences.novelBottomBarItems.set(NovelBottomActions.serialize(items))
                            onChromeChanged()
                            render()
                        },
                    ),
                )
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
                group.addView(
                    orderedRow(
                        title = item.name,
                        enabled = null,
                        canMoveUp = index > 0,
                        canMoveDown = index < items.lastIndex,
                        onEnabledChanged = {},
                        onMoveUp = {
                            items.add(index - 1, items.removeAt(index))
                            preferences.novelStatusBarOrder.set(NovelStatusItems.serialize(items))
                            onChromeChanged()
                            render()
                        },
                        onMoveDown = {
                            items.add(index + 1, items.removeAt(index))
                            preferences.novelStatusBarOrder.set(NovelStatusItems.serialize(items))
                            onChromeChanged()
                            render()
                        },
                    ),
                )
            }
            container.addView(group)
        }
        render()
    }

    private fun orderedRow(
        title: String,
        enabled: Boolean?,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        onEnabledChanged: (Boolean) -> Unit,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
    ): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 48.dp
            if (enabled == null) {
                addView(
                    TextView(context).apply {
                        text = title
                        textSize = 15f
                        setTextColor(context.getResourceColor(R.attr.colorOnBackground))
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            } else {
                addView(
                    MaterialSwitch(context).apply {
                        text = title
                        textSize = 15f
                        isChecked = enabled
                        setTextColor(context.getResourceColor(R.attr.colorOnBackground))
                        setOnCheckedChangeListener { _, checked -> onEnabledChanged(checked) }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            addView(orderButton(R.drawable.ic_arrow_upward_24dp, "Move $title up", canMoveUp, onMoveUp))
            addView(orderButton(R.drawable.ic_arrow_downward_24dp, "Move $title down", canMoveDown, onMoveDown))
        }

    private fun orderButton(icon: Int, label: String, enabled: Boolean, action: () -> Unit): ImageButton =
        ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp)
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(context.getResourceColor(R.attr.colorOnBackground))
            setBackgroundResource(R.drawable.square_ripple)
            contentDescription = label
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.38f
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            setOnClickListener { action() }
        }

    private fun NovelBottomAction.displayName(): String =
        when (this) {
            NovelBottomAction.PreviousChapter -> "Previous chapter"
            NovelBottomAction.NextChapter -> "Next chapter"
            NovelBottomAction.ScrollToTop -> "Scroll to top"
            NovelBottomAction.Translate -> "Translate"
            NovelBottomAction.AutoScroll -> "Auto-scroll"
            NovelBottomAction.Tts -> "Read aloud"
            NovelBottomAction.TtsViewport -> "Read from viewport"
            NovelBottomAction.TtsPreviousParagraph -> "Previous paragraph"
            NovelBottomAction.TtsNextParagraph -> "Next paragraph"
            NovelBottomAction.Orientation -> "Orientation"
            NovelBottomAction.Settings -> "Settings"
            NovelBottomAction.Edit -> "Edit"
            NovelBottomAction.Quotes -> "Quotes"
        }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private val Int.dp: Int get() = (this * context.resources.displayMetrics.density).toInt()
}
