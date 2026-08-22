package dev.ahmedmohamed.hayai.novel.settings

import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import dev.ahmedmohamed.hayai.preferences.HayaiPreferences
import dev.ahmedmohamed.hayai.novel.error.NovelFailure
import dev.ahmedmohamed.hayai.novel.error.novelFailureMessage
import dev.ahmedmohamed.hayai.novel.extension.NovelApkExtensionManagerController
import dev.ahmedmohamed.hayai.novel.integration.NovelDataToolsController
import dev.ahmedmohamed.hayai.novel.reader.NovelFontStore
import dev.ahmedmohamed.hayai.novel.reader.NovelColorPickerDialog
import dev.ahmedmohamed.hayai.novel.reader.NovelThemeColors
import dev.ahmedmohamed.hayai.novel.source.builder.NovelCustomSourceBuilderController
import dev.ahmedmohamed.hayai.novel.translation.NovelLanguageToolsSettingsController
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
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.widget.preference.IntListMatPreference
import eu.kanade.tachiyomi.widget.preference.ListMatPreference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class NovelSettingsController : SettingsController() {
    private val novel = HayaiPreferences(Injekt.get())
    private val customization = NovelCustomizationStore(novel)

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.hayai_novel_reader_settings

        preferenceCategory {
            title = context.getString(R.string.hayai_novel_reader_theme_and_colors)
            summary = context.getString(R.string.hayai_novel_reader_custom_theme_summary)
            val themePreference = listPreference(activity) {
                bindTo(novel.novelTheme)
                title = context.getString(R.string.hayai_novel_reader_theme)
                entries = listOf(R.string.hayai_novel_reader_follow_app, R.string.hayai_novel_reader_light, R.string.dark, R.string.hayai_novel_reader_sepia, R.string.black, R.string.hayai_novel_reader_grey, R.string.hayai_novel_reader_custom).map(context::getString)
                entryValues = listOf("app", "light", "dark", "sepia", "black", "grey", "custom")
            }
            val showCustomTheme = { themePreference.summary = context.getString(R.string.hayai_novel_reader_custom) }
            colorPreference(R.string.hayai_novel_reader_custom_text_color, novel.novelFontColor, NovelThemeColors.LIGHT_TEXT, activateCustomTheme = true, onApplied = showCustomTheme)
            colorPreference(R.string.hayai_novel_reader_custom_background_color, novel.novelBackgroundColor, NovelThemeColors.LIGHT_BACKGROUND, activateCustomTheme = true, onApplied = showCustomTheme)
        }

        preferenceCategory {
            title = context.getString(R.string.hayai_novel_reader_typography)
            sliderPreference {
                bindTo(novel.novelFontSize)
                title = context.getString(R.string.hayai_novel_reader_font_size)
                entryValues = (8..72).toList()
                valueFormatter = { context.getString(R.string.hayai_novel_reader_sp_value, it) }
            }
            listPreference(activity) {
                bindTo(novel.novelFontFamily)
                title = context.getString(R.string.hayai_novel_reader_font_family)
                entries = listOf(R.string.hayai_novel_reader_system_sans, R.string.hayai_novel_reader_system_serif, R.string.hayai_novel_reader_monospace).map(context::getString)
                entryValues = listOf("sans-serif", "serif", "monospace")
            }
            preference {
                title = context.getString(R.string.hayai_novel_reader_imported_fonts)
                summary = context.getString(R.string.hayai_novel_reader_imported_fonts_summary)
                onClick { showImportedFonts() }
            }
            floatChoice(context.getString(R.string.hayai_novel_reader_line_height), novel.novelLineHeight, listOf(1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f)) { context.getString(R.string.hayai_novel_reader_multiplier_value, it) }
            listPreference(activity) {
                bindTo(novel.novelTextAlign)
                title = context.getString(R.string.hayai_novel_reader_text_alignment)
                entries = listOf(R.string.left, R.string.hayai_novel_reader_justified, R.string.center, R.string.right).map(context::getString)
                entryValues = listOf("left", "justify", "center", "right")
            }
            switchPreference { bindTo(novel.novelTextSelectable); title = context.getString(R.string.hayai_novel_reader_selectable_text) }
            switchPreference { bindTo(novel.novelForceTextLowercase); title = context.getString(R.string.hayai_novel_reader_force_lowercase) }
            switchPreference { bindTo(novel.novelUseOriginalFonts); title = context.getString(R.string.hayai_novel_reader_use_source_fonts) }
        }

        preferenceCategory {
            title = context.getString(R.string.page_layout)
            sliderPreference { bindTo(novel.novelMarginLeft); title = context.getString(R.string.hayai_novel_reader_left_margin); entryValues = (0..64 step 2).toList(); valueFormatter = { context.getString(R.string.hayai_novel_reader_dp_value, it) } }
            sliderPreference { bindTo(novel.novelMarginRight); title = context.getString(R.string.hayai_novel_reader_right_margin); entryValues = (0..64 step 2).toList(); valueFormatter = { context.getString(R.string.hayai_novel_reader_dp_value, it) } }
            sliderPreference { bindTo(novel.novelMarginTop); title = context.getString(R.string.hayai_novel_reader_top_margin); entryValues = (0..100 step 5).toList(); valueFormatter = { context.getString(R.string.hayai_novel_reader_dp_value, it) } }
            sliderPreference { bindTo(novel.novelMarginBottom); title = context.getString(R.string.hayai_novel_reader_bottom_margin); entryValues = (0..100 step 5).toList(); valueFormatter = { context.getString(R.string.hayai_novel_reader_dp_value, it) } }
            floatChoice(context.getString(R.string.hayai_novel_reader_paragraph_indent), novel.novelParagraphIndent, listOf(0f, 0.5f, 1f, 1.5f, 2f, 3f)) { context.getString(R.string.hayai_novel_reader_em_value, it) }
            floatChoice(context.getString(R.string.hayai_novel_reader_paragraph_spacing), novel.novelParagraphSpacing, listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 1.5f)) { context.getString(R.string.hayai_novel_reader_em_value, it) }
            listPreference(activity) {
                bindTo(novel.novelRenderingMode)
                title = context.getString(R.string.hayai_novel_reader_rendering_mode)
                entries = listOf(R.string.hayai_novel_reader_native_text, R.string.hayai_novel_reader_webview).map(context::getString)
                entryValues = listOf("default", "webview")
            }
        }

        preferenceCategory {
            title = context.getString(R.string.hayai_novel_reader_reading_behavior)
            switchPreference {
                bindTo(novel.novelInfiniteScroll)
                title = context.getString(R.string.hayai_novel_reader_infinite_scroll)
                summary = context.getString(R.string.hayai_novel_reader_infinite_scroll_summary)
            }
            intListPreference(activity) {
                bindTo(novel.novelKeepChaptersLoaded)
                title = context.getString(R.string.hayai_novel_reader_keep_adjacent_loaded)
                entries = listOf(R.string.hayai_novel_reader_current_only, R.string.previous, R.string.next, R.string.hayai_novel_reader_previous_and_next).map(context::getString)
                entryValues = listOf(0, 1, 2, 3)
            }
            sliderPreference { bindTo(novel.novelAutoLoadNextChapterAt); title = context.getString(R.string.hayai_novel_reader_load_next_at); entryValues = (50..100 step 5).toList(); valueFormatter = { context.getString(R.string.hayai_novel_reader_percent_value, it) } }
            sliderPreference { bindTo(novel.novelMarkAsReadThreshold); title = context.getString(R.string.hayai_novel_reader_mark_as_read_at); entryValues = (50..100 step 5).toList(); valueFormatter = { context.getString(R.string.hayai_novel_reader_percent_value, it) } }
            switchPreference { bindTo(novel.novelMarkShortChapterAsRead); title = context.getString(R.string.hayai_novel_reader_mark_short_as_read) }
            switchPreference { bindTo(novel.novelHideChapterTitle); title = context.getString(R.string.hayai_novel_reader_hide_chapter_title) }
            intListPreference(activity) {
                bindTo(novel.novelChapterTitleDisplay)
                title = context.getString(R.string.hayai_novel_reader_chapter_title_format)
                entries = listOf(R.string.name, R.string.hayai_novel_reader_number, R.string.hayai_novel_reader_number_and_name).map(context::getString)
                entryValues = listOf(0, 1, 2)
            }
            switchPreference { bindTo(novel.novelAutoSplitText); title = context.getString(R.string.hayai_novel_reader_split_blocks_auto) }
            sliderPreference { bindTo(novel.novelAutoSplitWordCount); title = context.getString(R.string.hayai_novel_reader_split_after_word_count); entryValues = (20..200 step 10).toList() }
            switchPreference { bindTo(novel.novelKeepScreenOn); title = context.getString(R.string.keep_screen_on) }
        }

        preferenceCategory {
            title = context.getString(R.string.hayai_novel_reader_navigation_progress)
            switchPreference { bindTo(novel.novelShowProgressSlider); title = context.getString(R.string.hayai_novel_reader_show_progress_control) }
            switchPreference { bindTo(novel.novelVerticalScrollbar); title = context.getString(R.string.hayai_novel_reader_use_vertical_progress) }
            listPreference(activity) {
                bindTo(novel.novelVerticalScrollbarPosition)
                title = context.getString(R.string.hayai_novel_reader_vertical_progress_position)
                entries = listOf(R.string.left, R.string.right).map(context::getString)
                entryValues = listOf("left", "right")
            }
            listPreference(activity) {
                bindTo(novel.novelVerticalProgressSliderSize)
                title = context.getString(R.string.hayai_novel_reader_vertical_progress_height)
                entries = listOf(R.string.hayai_novel_reader_half_screen, R.string.hayai_novel_reader_full_screen).map(context::getString)
                entryValues = listOf("half", "full")
            }
            switchPreference { bindTo(novel.novelSwipeNavigation); title = context.getString(R.string.hayai_novel_reader_swipe_chapter_navigation) }
            switchPreference { bindTo(novel.novelVolumeKeysScroll); title = context.getString(R.string.hayai_novel_reader_volume_keys_scroll) }
            switchPreference { bindTo(novel.novelTapToScroll); title = context.getString(R.string.hayai_novel_reader_tap_to_scroll) }
            intListPreference(activity) {
                bindTo(novel.novelNavigationMode)
                title = context.getString(R.string.hayai_novel_reader_novel_tap_zones)
                entries = listOf(R.string.default_value, R.string.hayai_novel_reader_tap_zone_l, R.string.hayai_novel_reader_tap_zone_kindlish, R.string.edge_nav, R.string.hayai_novel_reader_left_right, R.string.disabled, R.string.hayai_novel_reader_center_only).map(context::getString)
                entryValues = listOf(0, 1, 2, 3, 4, 5, 6)
            }
            listPreference(activity) {
                bindTo(novel.novelNavigationInverted)
                title = context.getString(R.string.invert_tapping)
                entries = listOf(R.string.none, R.string.hayai_novel_reader_horizontal, R.string.vertical_viewer, R.string.hayai_novel_reader_both).map(context::getString)
                entryValues = listOf("NONE", "HORIZONTAL", "VERTICAL", "BOTH")
            }
            switchPreference { bindTo(novel.novelFullscreen); title = context.getString(R.string.hayai_novel_reader_fullscreen_reader) }
            sliderPreference {
                bindTo(novel.novelAutoScrollSpeed)
                title = context.getString(R.string.hayai_novel_reader_auto_scroll_speed)
                entryValues = (2..20).toList()
                valueFormatter = { context.getString(R.string.hayai_novel_reader_multiplier_value, it / 2f) }
            }
        }

        preferenceCategory {
            title = context.getString(R.string.hayai_novel_reader_status_bar)
            switchPreference { bindTo(novel.novelStatusBarEnabled); title = context.getString(R.string.hayai_novel_reader_novel_status_bar) }
            switchPreference { bindTo(novel.novelStatusBarShowTime); title = context.getString(R.string.hayai_novel_reader_show_time) }
            switchPreference { bindTo(novel.novelStatusBarShowBattery); title = context.getString(R.string.hayai_novel_reader_show_battery) }
            switchPreference { bindTo(novel.novelStatusBarShowCharging); title = context.getString(R.string.hayai_novel_reader_show_charging) }
            switchPreference { bindTo(novel.novelStatusBarShowChapterNumber); title = context.getString(R.string.hayai_novel_reader_show_chapter_number) }
            switchPreference { bindTo(novel.novelStatusBarShowChapterTitle); title = context.getString(R.string.hayai_novel_reader_show_chapter_title) }
            switchPreference { bindTo(novel.novelStatusBarShowProgress); title = context.getString(R.string.hayai_novel_reader_show_progress) }
            listPreference(activity) {
                bindTo(novel.novelStatusBarPosition)
                title = context.getString(R.string.hayai_novel_reader_position)
                entries = listOf(R.string.top, R.string.bottom).map(context::getString)
                entryValues = listOf("top", "bottom")
            }
            listPreference(activity) {
                bindTo(novel.novelStatusBarSize)
                title = context.getString(R.string.hayai_novel_reader_size)
                entries = listOf(R.string.hayai_novel_reader_small, R.string.hayai_novel_reader_medium, R.string.hayai_novel_reader_large).map(context::getString)
                entryValues = listOf("small", "medium", "large")
            }
        }

        preferenceCategory {
            title = context.getString(R.string.hayai_novel_reader_text_to_speech)
            floatChoice(context.getString(R.string.hayai_novel_reader_speech_speed), novel.novelTtsSpeed, (5..60 step 5).map { it / 10f }) { context.getString(R.string.hayai_novel_reader_multiplier_value, it) }
            floatChoice(context.getString(R.string.hayai_novel_reader_speech_pitch), novel.novelTtsPitch, (5..60 step 5).map { it / 10f }) { context.getString(R.string.hayai_novel_reader_multiplier_value, it) }
            editTextPreference(activity) { bindTo(novel.novelTtsVoice); title = context.getString(R.string.hayai_novel_reader_preferred_voice_id); summary = context.getString(R.string.hayai_novel_reader_voice_default_summary) }
            switchPreference { bindTo(novel.novelTtsAutoNextChapter); title = context.getString(R.string.hayai_novel_reader_continue_into_next) }
            switchPreference { bindTo(novel.novelTtsEnableHighlight); title = context.getString(R.string.hayai_novel_reader_highlight_spoken_text) }
            switchPreference { bindTo(novel.novelTtsKeepHighlightInView); title = context.getString(R.string.hayai_novel_reader_keep_highlight_view) }
            listPreference(activity) {
                bindTo(novel.novelTtsHighlightStyle)
                title = context.getString(R.string.hayai_novel_reader_highlight_style)
                entries = listOf(R.string.hayai_novel_reader_background, R.string.hayai_novel_reader_underline, R.string.hayai_novel_reader_outline).map(context::getString)
                entryValues = listOf("background", "underline", "outline")
            }
            colorPreference(R.string.hayai_novel_reader_highlight_color, novel.novelTtsHighlightColor, 0xFFFFD54F.toInt())
            colorPreference(R.string.hayai_novel_reader_highlight_text_color, novel.novelTtsHighlightTextColor, 0xFF1A1A1A.toInt())
            switchPreference { bindTo(novel.novelTtsBackgroundPlayback); title = context.getString(R.string.hayai_novel_reader_background_playback) }
            switchPreference { bindTo(novel.novelTtsControlsVisible); title = context.getString(R.string.hayai_novel_reader_keep_tts_controls) }
            switchPreference { bindTo(novel.novelTtsAutoStartOnPanelOpen); title = context.getString(R.string.hayai_novel_reader_start_tts_panel_long) }
        }

        preferenceCategory {
            title = context.getString(R.string.hayai_novel_reader_source_content_customization)
            switchPreference { bindTo(novel.novelEnableEpubStyles); title = context.getString(R.string.hayai_novel_reader_use_epub_styles) }
            switchPreference { bindTo(novel.novelEnableEpubJs); title = context.getString(R.string.hayai_novel_reader_allow_epub_js); summary = context.getString(R.string.hayai_novel_reader_trusted_books_only) }
            switchPreference { bindTo(novel.novelSourceCssPriority); title = context.getString(R.string.hayai_novel_reader_prefer_source_css) }
            switchPreference { bindTo(novel.novelBlockMedia); title = context.getString(R.string.hayai_novel_reader_block_media) }
            switchPreference { bindTo(novel.novelShowRawHtml); title = context.getString(R.string.hayai_novel_reader_show_raw_html) }
            switchPreference { bindTo(novel.novelWebViewDevTools); title = context.getString(R.string.hayai_novel_reader_webview_tools); summary = context.getString(R.string.hayai_novel_reader_webview_tools_summary) }
            switchPreference { bindTo(novel.novelConsoleErrorToast); title = context.getString(R.string.hayai_novel_reader_webview_errors) }
            editTextPreference(activity) { bindTo(novel.novelCustomCss); title = context.getString(R.string.hayai_novel_reader_custom_css); summary = context.getString(R.string.hayai_novel_reader_custom_css_summary) }
            editTextPreference(activity) { bindTo(novel.novelCustomJs); title = context.getString(R.string.hayai_novel_reader_custom_js); summary = context.getString(R.string.hayai_novel_reader_custom_js_summary) }
            preference {
                title = context.getString(R.string.hayai_novel_reader_css_snippets)
                summary = context.getString(R.string.hayai_novel_reader_css_snippets_summary)
                onClick { showSnippetList(SnippetKind.Css) }
            }
            preference {
                title = context.getString(R.string.hayai_novel_reader_js_snippets)
                summary = context.getString(R.string.hayai_novel_reader_js_snippets_summary)
                onClick { showSnippetList(SnippetKind.JavaScript) }
            }
            preference {
                title = context.getString(R.string.hayai_novel_reader_text_replacements)
                summary = context.getString(R.string.hayai_novel_reader_text_replacements_summary)
                onClick { showReplacementList() }
            }
            preference {
                title = context.getString(R.string.hayai_novel_reader_presets)
                summary = context.getString(R.string.hayai_novel_reader_presets_summary)
                onClick { showPresetList() }
            }
        }

        preferenceCategory {
            title = context.getString(R.string.hayai_novel_reader_language_sources_data)
            preference {
                title = context.getString(R.string.hayai_novel_reader_translation_dictionary)
                summary = context.getString(R.string.hayai_novel_reader_translation_dictionary_summary)
                onClick { router.pushController(NovelLanguageToolsSettingsController().withFadeTransaction()) }
            }
            preference {
                title = context.getString(R.string.hayai_novel_reader_source_builder)
                summary = context.getString(R.string.hayai_novel_reader_source_builder_summary)
                onClick { router.pushController(NovelCustomSourceBuilderController().withFadeTransaction()) }
            }
            preference {
                title = context.getString(R.string.hayai_novel_reader_remote_extensions)
                summary = context.getString(R.string.hayai_novel_reader_remote_extensions_summary)
                onClick { router.pushController(NovelApkExtensionManagerController().withFadeTransaction()) }
            }
            preference {
                title = context.getString(R.string.hayai_novel_reader_import_external)
                summary = context.getString(R.string.hayai_novel_reader_import_external_summary)
                onClick { router.pushController(NovelDataToolsController().withFadeTransaction()) }
            }
        }

        preferenceCategory {
            title = context.getString(R.string.hayai_novel_reader_screen_brightness)
            switchPreference { bindTo(novel.novelCustomBrightness); title = context.getString(R.string.hayai_novel_reader_override_brightness) }
            sliderPreference {
                bindTo(novel.novelCustomBrightnessValue)
                title = context.getString(R.string.hayai_novel_reader_brightness_adjustment)
                entryValues = (-100..100 step 5).toList()
                valueFormatter = { context.getString(if (it > 0) R.string.hayai_novel_reader_signed_percent_value else R.string.hayai_novel_reader_percent_value, it) }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != IMPORT_FONT || resultCode != Activity.RESULT_OK) return
        val context = activity ?: return
        val uri = data?.data ?: return
        val store = NovelFontStore(context, novel)
        viewScope.launch {
            val result = withContext(Dispatchers.IO) { store.importFont(uri) }
            result.fold(
                onSuccess = { font -> novel.novelFontFamily.set(store.token(font)); context.toast(context.getString(R.string.hayai_novel_reader_imported_font_success, font.name)) },
                onFailure = { context.toast(it.message ?: context.getString(R.string.hayai_novel_reader_imported_font_error)) },
            )
        }
    }

    private fun showImportedFonts() {
        val context = activity ?: return
        val store = NovelFontStore(context, novel)
        val fonts = store.fonts()
        val labels = fonts.map { "${if (store.token(it) == novel.novelFontFamily.get()) "✓  " else ""}${it.name}" }.toTypedArray()
        val dialog =
            context.materialAlertDialog()
                .setTitle(R.string.hayai_novel_reader_imported_fonts)
                .setItems(labels) { _, index ->
                    val font = fonts[index]
                    context.materialAlertDialog()
                        .setTitle(font.name)
                        .setItems(arrayOf(context.getString(R.string.hayai_novel_reader_use_font), context.getString(R.string.delete))) { _, action ->
                            if (action == 0) {
                                novel.novelFontFamily.set(store.token(font))
                            } else {
                                if (novel.novelFontFamily.get() == store.token(font)) novel.novelFontFamily.set("sans-serif")
                                if (store.delete(font.id)) context.toast(R.string.hayai_novel_reader_font_deleted)
                            }
                        }.show()
                }.setPositiveButton(R.string.hayai_novel_reader_import, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "font/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("font/ttf", "font/otf", "application/font-sfnt", "application/octet-stream"))
                    },
                    IMPORT_FONT,
                )
                dialog.dismiss()
            }
        }
        dialog.show()
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
        @androidx.annotation.StringRes titleRes: Int,
        valuePreference: Preference<Int>,
        defaultColor: Int,
        activateCustomTheme: Boolean = false,
        onApplied: () -> Unit = {},
    ) {
        preference {
            title = context.getString(titleRes)
            summary = colorSummary(valuePreference.get())
            onClick {
                NovelColorPickerDialog.show(
                    context = context,
                    titleRes = titleRes,
                    initialColor = valuePreference.get(),
                    defaultColor = defaultColor,
                    onConfirm = { color ->
                        valuePreference.set(color)
                        if (activateCustomTheme) novel.novelTheme.set("custom")
                        summary = colorSummary(color)
                        onApplied()
                    },
                    onDefault = {
                        valuePreference.set(0)
                        if (activateCustomTheme) novel.novelTheme.set("custom")
                        summary = colorSummary(0)
                        onApplied()
                    },
                )
            }
        }
    }

    private fun colorSummary(color: Int): String = if (color == 0) activity?.getString(R.string.automatic).orEmpty() else NovelThemeColors.formatRgb(color)

    private fun showSnippetList(kind: SnippetKind) {
        val items = if (kind == SnippetKind.Css) customization.cssSnippets() else customization.jsSnippets()
        val labels = items.map { "${if (it.enabled) "✓" else "○"}  ${it.title}" }.toTypedArray()
        val dialog =
            activity!!.materialAlertDialog()
                .setTitle(if (kind == SnippetKind.Css) R.string.hayai_novel_reader_css_snippets else R.string.hayai_novel_reader_js_snippets)
                .setItems(labels) { _, index -> showSnippetActions(kind, items[index]) }
                .setPositiveButton(R.string.add, null)
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
            .setItems(arrayOf(activity!!.getString(if (snippet.enabled) R.string.hayai_novel_reader_disable else R.string.enable), activity!!.getString(R.string.edit), activity!!.getString(R.string.hayai_novel_reader_move_up_short), activity!!.getString(R.string.hayai_novel_reader_move_down_short), activity!!.getString(R.string.delete))) { _, action ->
                when (action) {
                    0 -> saveSnippet(kind, snippet.copy(enabled = !snippet.enabled))
                    1 -> editSnippet(kind, snippet)
                    2 -> moveSnippet(kind, snippet, -1)
                    3 -> moveSnippet(kind, snippet, 1)
                    4 -> confirmDelete(snippet.title) {
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
        val titleInput = textInput(activity!!.getString(R.string.name), existing?.title.orEmpty(), singleLine = true)
        val codeInput = textInput(activity!!.getString(if (kind == SnippetKind.Css) R.string.hayai_novel_reader_css else R.string.hayai_novel_reader_javascript), existing?.code.orEmpty())
        val enabled = CheckBox(activity).apply { setText(R.string.enabled); isChecked = existing?.enabled ?: true }
        val runOnAppend = CheckBox(activity).apply {
            setText(R.string.hayai_novel_reader_run_after_append)
            isChecked = existing?.runOnAppend ?: false
            visibility = if (kind == SnippetKind.JavaScript) View.VISIBLE else View.GONE
        }
        val content = form(titleInput, codeInput, enabled, runOnAppend)
        val dialog =
            activity!!.materialAlertDialog()
                .setTitle(if (existing == null) R.string.hayai_novel_reader_add_snippet else R.string.hayai_novel_reader_edit_snippet)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text?.toString()?.trim().orEmpty()
                val code = codeInput.text?.toString().orEmpty()
                when {
                    title.isBlank() -> titleInput.error = activity!!.getString(R.string.hayai_novel_reader_name_required)
                    code.isBlank() -> codeInput.error = activity!!.getString(R.string.hayai_novel_reader_code_required)
                    else -> {
                        saveSnippet(
                            kind,
                            NovelCodeSnippet(
                                existing?.id ?: UUID.randomUUID().toString(),
                                title,
                                code,
                                enabled.isChecked,
                                runOnAppend = kind == SnippetKind.JavaScript && runOnAppend.isChecked,
                            ),
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

    private fun moveSnippet(kind: SnippetKind, snippet: NovelCodeSnippet, delta: Int) {
        val items = snippetItems(kind).toMutableList()
        val from = items.indexOfFirst { it.id == snippet.id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, items.lastIndex)
        if (to == from) return
        items.add(to, items.removeAt(from))
        saveSnippetItems(kind, items)
        showSnippetList(kind)
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
                .setTitle(R.string.hayai_novel_reader_text_replacements)
                .setItems(labels) { _, index -> showReplacementActions(items[index]) }
                .setPositiveButton(R.string.add, null)
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
            .setItems(arrayOf(activity!!.getString(if (rule.enabled) R.string.hayai_novel_reader_disable else R.string.enable), activity!!.getString(R.string.edit), activity!!.getString(R.string.hayai_novel_reader_test), activity!!.getString(R.string.hayai_novel_reader_move_up_short), activity!!.getString(R.string.hayai_novel_reader_move_down_short), activity!!.getString(R.string.delete))) { _, action ->
                when (action) {
                    0 -> saveReplacement(rule.copy(enabled = !rule.enabled))
                    1 -> editReplacement(rule)
                    2 -> testReplacement(rule)
                    3 -> moveReplacement(rule, -1)
                    4 -> moveReplacement(rule, 1)
                    5 -> confirmDelete(rule.title) {
                        customization.saveReplacements(customization.replacements().filterNot { it.id == rule.id })
                    }
                }
            }.show()
    }

    private fun editReplacement(existing: NovelRegexReplacement?) {
        val titleInput = textInput(activity!!.getString(R.string.name), existing?.title.orEmpty(), singleLine = true)
        val patternInput = textInput(activity!!.getString(R.string.hayai_novel_reader_pattern), existing?.pattern.orEmpty())
        val replacementInput = textInput(activity!!.getString(R.string.hayai_novel_reader_replacement), existing?.replacement.orEmpty())
        val enabled = CheckBox(activity).apply { setText(R.string.enabled); isChecked = existing?.enabled ?: true }
        val regex = CheckBox(activity).apply { setText(R.string.hayai_novel_reader_regular_expression); isChecked = existing?.isRegex ?: true }
        val wholeWord = CheckBox(activity).apply { setText(R.string.hayai_novel_reader_match_whole_words); isChecked = existing?.matchWholeWord ?: false }
        val caseSensitive = CheckBox(activity).apply { setText(R.string.hayai_novel_reader_case_sensitive); isChecked = existing?.caseSensitive ?: false }
        val dialog =
            activity!!.materialAlertDialog()
                .setTitle(if (existing == null) R.string.hayai_novel_reader_add_replacement else R.string.hayai_novel_reader_edit_replacement)
                .setView(form(titleInput, patternInput, replacementInput, enabled, regex, wholeWord, caseSensitive))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text?.toString()?.trim().orEmpty()
                val pattern = patternInput.text?.toString().orEmpty()
                when {
                    title.isBlank() -> titleInput.error = activity!!.getString(R.string.hayai_novel_reader_name_required)
                    pattern.isBlank() -> patternInput.error = activity!!.getString(R.string.hayai_novel_reader_pattern_required)
                    regex.isChecked && NovelRegexSafety.rejectionReason(pattern) != null ->
                        patternInput.error = activity!!.novelFailureMessage(
                            NovelFailure(requireNotNull(NovelRegexSafety.rejectionReason(pattern))),
                            R.string.hayai_novel_reader_rule_apply_error,
                        )
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

    private fun moveReplacement(rule: NovelRegexReplacement, delta: Int) {
        val items = customization.replacements().toMutableList()
        val from = items.indexOfFirst { it.id == rule.id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, items.lastIndex)
        if (to == from) return
        items.add(to, items.removeAt(from))
        customization.saveReplacements(items)
        showReplacementList()
    }

    private fun testReplacement(rule: NovelRegexReplacement) {
        val input = textInput(activity!!.getString(R.string.hayai_novel_reader_sample_text), "")
        val dialog =
            activity!!.materialAlertDialog()
                .setTitle(activity!!.getString(R.string.hayai_novel_reader_test_named, rule.title))
                .setView(input)
                .setPositiveButton(R.string.hayai_novel_reader_test, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                NovelReplacementEngine.apply(input.text?.toString().orEmpty(), rule.copy(enabled = true)).fold(
                    onSuccess = { result ->
                        activity!!.materialAlertDialog().setTitle(R.string.hayai_novel_reader_result).setMessage(result).setPositiveButton(android.R.string.ok, null).show()
                    },
                    onFailure = { input.error = activity!!.novelFailureMessage(it, R.string.hayai_novel_reader_rule_apply_error) },
                )
            }
        }
        dialog.show()
    }

    private fun showPresetList() {
        val items = customization.presets()
        val dialog =
            activity!!.materialAlertDialog()
                .setTitle(R.string.hayai_novel_reader_presets)
                .setItems(items.map(NovelReaderPreset::name).toTypedArray()) { _, index -> showPresetActions(items[index]) }
                .setPositiveButton(R.string.hayai_novel_reader_save_current, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { nameCurrentPreset() }
        }
        dialog.show()
    }

    private fun nameCurrentPreset() {
        val input = textInput(activity!!.getString(R.string.hayai_novel_reader_preset_name), "", singleLine = true)
        val dialog =
            activity!!.materialAlertDialog()
                .setTitle(R.string.hayai_novel_reader_save_current_style)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    input.error = activity!!.getString(R.string.hayai_novel_reader_name_required)
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
            .setItems(arrayOf(activity!!.getString(R.string.apply), activity!!.getString(R.string.hayai_novel_reader_replace_current), activity!!.getString(R.string.delete))) { _, action ->
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
            .setTitle(activity!!.getString(R.string.hayai_novel_reader_delete_named, name))
            .setMessage(R.string.hayai_novel_reader_cannot_undo)
            .setPositiveButton(R.string.delete) { _, _ -> delete() }
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

    private companion object {
        const val IMPORT_FONT = 7412
    }
}
