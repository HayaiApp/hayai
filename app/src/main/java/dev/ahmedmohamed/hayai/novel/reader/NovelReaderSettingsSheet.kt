package dev.ahmedmohamed.hayai.novel.reader

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.children
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
                R.drawable.ic_text_fields_24dp to R.string.reading,
                R.drawable.ic_palette_24dp to R.string.appearance,
                R.drawable.ic_swipe_24dp to R.string.hayai_novel_tab_layout,
                R.drawable.ic_record_voice_over_24dp to R.string.hayai_novel_tab_tts,
                R.drawable.ic_code_24dp to R.string.advanced,
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
            R.string.reading,
            R.string.appearance,
            R.string.hayai_novel_tab_layout,
            R.string.hayai_novel_tab_tts,
            R.string.advanced,
        )

    private fun readerPage(page: LinearLayout) {
        page.heading(R.string.hayai_novel_reader_renderer)
        page.choice(R.string.hayai_novel_reader_reading_engine, preferences.novelRenderingMode, listOf(R.string.hayai_novel_reader_native_text to "default", R.string.hayai_novel_reader_webview to "webview"), onStyleChanged)
        page.choice(
            R.string.tap_zones,
            preferences.novelNavigationMode,
            listOf(R.string.default_value to 0, R.string.hayai_novel_reader_tap_zone_l to 1, R.string.hayai_novel_reader_tap_zone_kindlish to 2, R.string.edge_nav to 3, R.string.hayai_novel_reader_left_right to 4, R.string.disabled to 5, R.string.hayai_novel_reader_center_only to 6),
            onChromeChanged,
        )
        page.choice(
            R.string.hayai_novel_reader_tap_inversion,
            preferences.novelNavigationInverted,
            listOf(R.string.none to "NONE", R.string.hayai_novel_reader_horizontal to "HORIZONTAL", R.string.vertical_viewer to "VERTICAL", R.string.hayai_novel_reader_both to "BOTH"),
            onChromeChanged,
        )
        page.toggle(R.string.hayai_novel_reader_tap_to_scroll, preferences.novelTapToScroll)
        page.toggle(R.string.hayai_novel_reader_swipe_between_chapters, preferences.novelSwipeNavigation)
        page.toggle(R.string.hayai_novel_reader_volume_keys_scroll, preferences.novelVolumeKeysScroll)
        page.toggle(R.string.keep_screen_on, preferences.novelKeepScreenOn, onChromeChanged)
        page.toggle(R.string.fullscreen, preferences.novelFullscreen, onChromeChanged)
        page.toggle(R.string.hayai_novel_reader_infinite_scroll, preferences.novelInfiniteScroll, onStyleChanged)
        page.slider(R.string.hayai_novel_reader_load_next_at, preferences.novelAutoLoadNextChapterAt, 50, 100, R.string.hayai_novel_reader_percent_value, onStyleChanged)
        page.slider(R.string.hayai_novel_reader_mark_read_at, preferences.novelMarkAsReadThreshold, 50, 100, R.string.hayai_novel_reader_percent_value)
        page.toggle(R.string.hayai_novel_reader_mark_short_read, preferences.novelMarkShortChapterAsRead)
        page.toggle(R.string.hayai_novel_reader_split_blocks, preferences.novelAutoSplitText, onStyleChanged)
        page.slider(R.string.hayai_novel_reader_split_after_words, preferences.novelAutoSplitWordCount, 20, 200, R.string.hayai_novel_reader_word_value, onStyleChanged)
    }

    private fun textPage(page: LinearLayout) {
        page.heading(R.string.hayai_novel_reader_theme)
        val themeControl = page.choice(
            R.string.hayai_novel_reader_theme,
            preferences.novelTheme,
            listOf(R.string.hayai_novel_reader_app to "app", R.string.hayai_novel_reader_light to "light", R.string.dark to "dark", R.string.hayai_novel_reader_sepia to "sepia", R.string.black to "black", R.string.hayai_novel_reader_grey to "grey", R.string.hayai_novel_reader_custom to "custom"),
            onStyleChanged,
        )
        val activateCustomTheme = {
            preferences.novelTheme.set("custom")
            themeControl.setSelection(6)
        }
        page.colorChoice(
            R.string.hayai_novel_reader_custom_text_color,
            preferences.novelFontColor,
            NovelThemeColors.LIGHT_TEXT,
            FONT_COLORS,
            activateCustomTheme,
        )
        page.colorChoice(
            R.string.hayai_novel_reader_custom_background_color,
            preferences.novelBackgroundColor,
            NovelThemeColors.LIGHT_BACKGROUND,
            BACKGROUND_COLORS,
            activateCustomTheme,
        )
        page.heading(R.string.hayai_novel_reader_typography)
        page.slider(R.string.hayai_novel_reader_font_size, preferences.novelFontSize, 8, 72, R.string.hayai_novel_reader_sp_value, onStyleChanged)
        page.choice(R.string.hayai_novel_reader_font, preferences.novelFontFamily, listOf(R.string.hayai_novel_reader_sans_serif to "sans-serif", R.string.hayai_novel_reader_serif to "serif", R.string.hayai_novel_reader_monospace to "monospace"), onStyleChanged)
        page.button(R.string.hayai_novel_reader_import_font_file) { onAction(NovelReaderAction.ImportFont) }
        page.button(R.string.hayai_novel_reader_manage_imported_fonts) { onAction(NovelReaderAction.ManageFonts) }
        page.choice(R.string.hayai_novel_reader_alignment, preferences.novelTextAlign, listOf(R.string.left to "left", R.string.hayai_novel_reader_justified to "justify", R.string.center to "center", R.string.right to "right"), onStyleChanged)
        page.toggle(R.string.hayai_novel_reader_selectable_text, preferences.novelTextSelectable, onStyleChanged)
        page.toggle(R.string.hayai_novel_reader_force_lowercase, preferences.novelForceTextLowercase, onStyleChanged)
        page.toggle(R.string.hayai_novel_reader_use_source_fonts, preferences.novelUseOriginalFonts, onStyleChanged)
        page.sliderFloat(R.string.hayai_novel_reader_line_height, preferences.novelLineHeight, 8, 30, 10f, onStyleChanged)
        page.sliderFloat(R.string.hayai_novel_reader_paragraph_indent, preferences.novelParagraphIndent, 0, 100, 10f, onStyleChanged)
        page.sliderFloat(R.string.hayai_novel_reader_paragraph_spacing, preferences.novelParagraphSpacing, 0, 50, 10f, onStyleChanged)
        page.toggle(R.string.hayai_novel_reader_custom_brightness, preferences.novelCustomBrightness, onChromeChanged)
        page.slider(R.string.hayai_novel_reader_brightness_adjustment, preferences.novelCustomBrightnessValue, -75, 100, R.string.hayai_novel_reader_percent_value, onChromeChanged)
    }

    private fun layoutPage(page: LinearLayout) {
        page.heading(R.string.hayai_novel_reader_page)
        page.slider(R.string.hayai_novel_reader_left_margin, preferences.novelMarginLeft, 0, 100, R.string.hayai_novel_reader_dp_value, onStyleChanged)
        page.slider(R.string.hayai_novel_reader_right_margin, preferences.novelMarginRight, 0, 100, R.string.hayai_novel_reader_dp_value, onStyleChanged)
        page.slider(R.string.hayai_novel_reader_top_margin, preferences.novelMarginTop, 0, 150, R.string.hayai_novel_reader_dp_value, onStyleChanged)
        page.slider(R.string.hayai_novel_reader_bottom_margin, preferences.novelMarginBottom, 0, 150, R.string.hayai_novel_reader_dp_value, onStyleChanged)
        progressMode(page)
        page.choice(R.string.hayai_novel_reader_preload_chapters, preferences.novelKeepChaptersLoaded, listOf(R.string.hayai_novel_reader_current_only to 0, R.string.previous to 1, R.string.next to 2, R.string.hayai_novel_reader_both to 3), onStyleChanged)
        page.choice(R.string.hayai_novel_reader_vertical_progress_size, preferences.novelVerticalProgressSliderSize, listOf(R.string.hayai_novel_reader_half_screen to "half", R.string.hayai_novel_reader_full_screen to "full"), onChromeChanged)
        page.toggle(R.string.hayai_novel_reader_novel_status_bar, preferences.novelStatusBarEnabled, onChromeChanged)
        page.choice(R.string.hayai_novel_reader_status_position, preferences.novelStatusBarPosition, listOf(R.string.top to "top", R.string.bottom to "bottom"), onChromeChanged)
        page.choice(R.string.hayai_novel_reader_status_size, preferences.novelStatusBarSize, listOf(R.string.hayai_novel_reader_small to "small", R.string.hayai_novel_reader_medium to "medium"), onChromeChanged)
    }

    private fun ttsPage(page: LinearLayout) {
        page.heading(R.string.hayai_novel_reader_read_aloud)
        page.sliderFloat(R.string.hayai_novel_reader_speed, preferences.novelTtsSpeed, 5, 60, 10f)
        page.sliderFloat(R.string.hayai_novel_reader_pitch, preferences.novelTtsPitch, 5, 60, 10f)
        page.button(R.string.hayai_novel_reader_choose_voice) { chooseVoice() }
        page.toggle(R.string.hayai_novel_reader_continue_next, preferences.novelTtsAutoNextChapter)
        page.toggle(R.string.hayai_novel_reader_highlight_spoken, preferences.novelTtsEnableHighlight)
        page.toggle(R.string.hayai_novel_reader_keep_highlight_view, preferences.novelTtsKeepHighlightInView)
        page.choice(R.string.hayai_novel_reader_highlight_style, preferences.novelTtsHighlightStyle, listOf(R.string.hayai_novel_reader_background to "background", R.string.hayai_novel_reader_underline to "underline", R.string.hayai_novel_reader_outline to "outline"), onStyleChanged)
        page.toggle(R.string.hayai_novel_reader_background_playback, preferences.novelTtsBackgroundPlayback)
        page.toggle(R.string.hayai_novel_reader_show_paragraph_controls, preferences.novelTtsControlsVisible, onChromeChanged)
        page.toggle(R.string.hayai_novel_reader_start_tts_panel, preferences.novelTtsAutoStartOnPanelOpen)
        page.button(R.string.hayai_novel_reader_read_visible) { onAction(NovelReaderAction.StartTtsAtViewport) }
    }

    private fun progressMode(page: LinearLayout) {
        val values = listOf(R.string.none, R.string.hayai_novel_reader_horizontal, R.string.hayai_novel_reader_vertical_left, R.string.hayai_novel_reader_vertical_right)
        val current =
            when {
                !preferences.novelShowProgressSlider.get() -> 0
                !preferences.novelVerticalScrollbar.get() -> 1
                preferences.novelVerticalScrollbarPosition.get() == "left" -> 2
                else -> 3
            }
        page.addView(TextView(context).apply { setText(R.string.hayai_novel_reader_progress_mode); setPadding(0, 12.dp, 0, 4.dp) })
        val chips = ChipGroup(context).apply { isSingleSelection = true; isSelectionRequired = true; isSingleLine = true }
        values.forEachIndexed { selection, label ->
            chips.addView(
                Chip(context).apply {
                    setText(label)
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
        page.heading(R.string.hayai_novel_reader_content)
        page.toggle(R.string.hayai_novel_reader_use_epub_styles, preferences.novelEnableEpubStyles, onStyleChanged)
        page.toggle(R.string.hayai_novel_reader_allow_trusted_epub_js, preferences.novelEnableEpubJs, onStyleChanged)
        page.toggle(R.string.hayai_novel_reader_prefer_source_css, preferences.novelSourceCssPriority, onStyleChanged)
        page.toggle(R.string.hayai_novel_reader_block_media, preferences.novelBlockMedia, onStyleChanged)
        page.toggle(R.string.hayai_novel_reader_show_raw_html, preferences.novelShowRawHtml, onStyleChanged)
        page.toggle(R.string.hayai_novel_reader_webview_tools, preferences.novelWebViewDevTools)
        page.toggle(R.string.hayai_novel_reader_webview_errors, preferences.novelConsoleErrorToast)
        page.heading(R.string.hayai_novel_reader_bottom_actions)
        bottomActionEditor(page)
        page.heading(R.string.hayai_novel_reader_status_order)
        statusEditor(page)
        page.heading(R.string.hayai_novel_reader_chapter_tools)
        page.button(R.string.hayai_novel_reader_save_selected_quote) { onAction(NovelReaderAction.SaveQuote) }
        page.button(R.string.hayai_novel_reader_highlights) { onAction(NovelReaderAction.ShowHighlights) }
        page.button(R.string.hayai_novel_reader_dictionary_lookup) { onAction(NovelReaderAction.DictionaryLookup) }
        page.button(R.string.hayai_novel_reader_translate_chapter) { onAction(NovelReaderAction.TranslateChapter) }
        page.button(R.string.hayai_novel_reader_chapter_statistics) { onAction(NovelReaderAction.ShowStatistics) }
        page.button(R.string.hayai_novel_reader_toggle_offline) { onAction(NovelReaderAction.ToggleOffline) }
        page.button(R.string.hayai_novel_reader_toggle_bookmark) { onAction(NovelReaderAction.ToggleBookmark) }
        page.button(R.string.hayai_novel_reader_edit_chapter_text) { onAction(NovelReaderAction.ToggleEditMode) }
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

    private fun LinearLayout.heading(@StringRes textRes: Int) {
        addView(
            TextView(context).apply {
                setText(textRes)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.getResourceColor(R.attr.colorPrimary))
                setPadding(0, 16.dp, 0, 4.dp)
            },
        )
    }

    private fun LinearLayout.toggle(@StringRes labelRes: Int, preference: Preference<Boolean>, changed: () -> Unit = {}) {
        addView(
            MaterialSwitch(context).apply {
                setText(labelRes)
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

    private fun <T> LinearLayout.choice(@StringRes labelRes: Int, preference: Preference<T>, values: List<Pair<Int, T>>, changed: () -> Unit = {}): MaterialSpinnerView {
        val selected = values.indexOfFirst { it.second == preference.get() }.coerceAtLeast(0)
        val spinner =
            MaterialSpinnerView(context, null).apply {
                title = context.getString(labelRes)
                setEntries(values.map { context.getString(it.first) })
                setSelection(selected)
                onItemSelectedListener = { position ->
                    val value = values[position].second
                    if (value != preference.get()) {
                        preference.set(value)
                        changed()
                    }
                }
            }
        addView(spinner, matchWrap())
        return spinner
    }

    private fun LinearLayout.slider(@StringRes labelRes: Int, preference: Preference<Int>, min: Int, max: Int, @StringRes valueFormatRes: Int, changed: () -> Unit = {}) {
        val valueText = sliderHeader(labelRes)
        fun update(value: Int) { valueText.text = context.getString(valueFormatRes, value) }
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

    private fun LinearLayout.sliderFloat(@StringRes labelRes: Int, preference: Preference<Float>, min: Int, max: Int, scale: Float, changed: () -> Unit = {}) {
        val valueText = sliderHeader(labelRes)
        fun update(value: Float) { valueText.text = context.getString(R.string.hayai_novel_reader_multiplier_value, "%.1f".format(value)) }
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

    private fun LinearLayout.button(@StringRes labelRes: Int, action: () -> Unit) {
        addView(
            MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                setText(labelRes)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = 48.dp
                isAllCaps = false
                setPadding(0, 0, 0, 0)
                setOnClickListener { action() }
            },
            matchWrap(),
        )
    }

    private fun LinearLayout.colorChoice(
        @StringRes labelRes: Int,
        preference: Preference<Int>,
        defaultColor: Int,
        values: List<Pair<Int, Int>>,
        activateCustomTheme: () -> Unit,
    ) {
        addView(TextView(context).apply { setText(labelRes); textSize = 15f; setPadding(0, 12.dp, 0, 4.dp) }, matchWrap())
        val group = ChipGroup(context).apply { isSingleSelection = true; isSelectionRequired = true; isSingleLine = true }
        fun renderSelection() {
            val current = preference.get()
            val presetValues = values.mapTo(hashSetOf()) { it.second }
            group.children.forEachIndexed { index, view ->
                val option = values[index]
                val custom = option.second == CUSTOM_COLOR
                (view as Chip).isChecked = if (custom) current != 0 && current !in presetValues else current == option.second
            }
        }
        values.forEach { (optionLabel, color) ->
            val custom = color == CUSTOM_COLOR
            group.addView(
                Chip(context).apply {
                    setText(optionLabel)
                    isCheckable = true
                    if (custom) {
                        chipIcon = ContextCompat.getDrawable(context, R.drawable.ic_palette_24dp)
                        isChipIconVisible = true
                    } else if (color != 0) {
                        chipIcon = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize(16.dp, 16.dp) }
                        chipIconTint = null
                        isChipIconVisible = true
                    }
                    setOnClickListener {
                        if (custom) {
                            NovelColorPickerDialog.show(
                                context = context,
                                titleRes = labelRes,
                                initialColor = preference.get(),
                                defaultColor = defaultColor,
                                onConfirm = { selected ->
                                    preference.set(selected)
                                    activateCustomTheme()
                                    renderSelection()
                                    onStyleChanged()
                                },
                                onDefault = {
                                    preference.set(0)
                                    activateCustomTheme()
                                    renderSelection()
                                    onStyleChanged()
                                },
                            )
                        } else {
                            preference.set(color)
                            activateCustomTheme()
                            renderSelection()
                            onStyleChanged()
                        }
                    }
                },
            )
        }
        renderSelection()
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(group) }, matchWrap())
    }

    private fun LinearLayout.sliderHeader(@StringRes labelRes: Int): TextView {
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
                addView(TextView(context).apply { setText(labelRes); textSize = 15f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

    private fun chooseVoice() {
        var engine: android.speech.tts.TextToSpeech? = null
        engine = android.speech.tts.TextToSpeech(context) { result ->
            if (result == android.speech.tts.TextToSpeech.SUCCESS) {
                val voices = engine?.voices.orEmpty().filterNot { it.isNetworkConnectionRequired }.sortedWith(compareBy({ it.locale.displayName }, { it.name }))
                val labels = listOf(context.getString(R.string.system_default)) + voices.map { voice ->
                    context.getString(R.string.hayai_novel_reader_voice_label, voice.locale.displayName, voice.name)
                }
                val values = listOf("") + voices.map { it.name }
                AlertDialog.Builder(context).setTitle(R.string.hayai_novel_reader_installed_voice).setSingleChoiceItems(labels.toTypedArray(), values.indexOf(preferences.novelTtsVoice.get()).coerceAtLeast(0)) { dialog, index ->
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
                        title = item.displayName(),
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
            addView(orderButton(R.drawable.ic_arrow_upward_24dp, context.getString(R.string.hayai_novel_reader_move_up, title), canMoveUp, onMoveUp))
            addView(orderButton(R.drawable.ic_arrow_downward_24dp, context.getString(R.string.hayai_novel_reader_move_down, title), canMoveDown, onMoveDown))
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
            NovelBottomAction.PreviousChapter -> context.getString(R.string.previous_chapter)
            NovelBottomAction.NextChapter -> context.getString(R.string.next_chapter)
            NovelBottomAction.ScrollToTop -> context.getString(R.string.hayai_novel_reader_scroll_top)
            NovelBottomAction.Translate -> context.getString(R.string.hayai_novel_reader_translate)
            NovelBottomAction.AutoScroll -> context.getString(R.string.hayai_novel_reader_auto_scroll)
            NovelBottomAction.Tts -> context.getString(R.string.hayai_novel_reader_read_aloud)
            NovelBottomAction.TtsViewport -> context.getString(R.string.hayai_novel_reader_read_viewport)
            NovelBottomAction.TtsPreviousParagraph -> context.getString(R.string.hayai_novel_reader_previous_paragraph)
            NovelBottomAction.TtsNextParagraph -> context.getString(R.string.hayai_novel_reader_next_paragraph)
            NovelBottomAction.Orientation -> context.getString(R.string.orientation)
            NovelBottomAction.Settings -> context.getString(R.string.settings)
            NovelBottomAction.Edit -> context.getString(R.string.edit)
            NovelBottomAction.Quotes -> context.getString(R.string.hayai_novel_reader_quotes)
        }

    private fun NovelStatusItem.displayName(): String =
        context.getString(
            when (this) {
                NovelStatusItem.Time -> R.string.hayai_novel_reader_time
                NovelStatusItem.Chapter -> R.string.hayai_novel_reader_chapter
                NovelStatusItem.Progress -> R.string.progress
                NovelStatusItem.Battery -> R.string.hayai_novel_reader_battery
            },
        )

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private val Int.dp: Int get() = (this * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val CUSTOM_COLOR = Int.MIN_VALUE
        val FONT_COLORS =
            listOf(
                R.string.default_value to 0,
                R.string.black to 0xFF000000.toInt(),
                R.string.white to 0xFFFFFFFF.toInt(),
                R.string.gray_background to 0xFF808080.toInt(),
                R.string.hayai_novel_reader_dark_gray to 0xFF404040.toInt(),
                R.string.hayai_novel_reader_light_gray to 0xFFC0C0C0.toInt(),
                R.string.hayai_novel_reader_off_white to 0xFFCCCCCC.toInt(),
                R.string.hayai_novel_reader_sepia_color to 0xFF5C4033.toInt(),
                R.string.hayai_novel_reader_custom to CUSTOM_COLOR,
            )
        val BACKGROUND_COLORS =
            listOf(
                R.string.default_value to 0,
                R.string.white to 0xFFFFFFFF.toInt(),
                R.string.black to 0xFF000000.toInt(),
                R.string.hayai_novel_reader_light_gray to 0xFFF5F5F5.toInt(),
                R.string.hayai_novel_reader_dark_gray to 0xFF1A1A1A.toInt(),
                R.string.hayai_novel_reader_sepia to 0xFFF4ECD8.toInt(),
                R.string.hayai_novel_reader_cream to 0xFFFFFDD0.toInt(),
                R.string.hayai_novel_reader_charcoal to 0xFF292832.toInt(),
                R.string.hayai_novel_reader_custom to CUSTOM_COLOR,
            )
    }
}
