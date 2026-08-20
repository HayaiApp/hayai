package dev.ahmedmohamed.hayai.preferences

import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferenceStore

class HayaiPreferences(
    private val store: PreferenceStore,
) {
    val hentaiFeaturesEnabled: Preference<Boolean> =
        store.getBoolean(KEY_HENTAI_FEATURES, true)

    val lewdLibraryFilter: Preference<Int> =
        store.getInt(KEY_LEWD_LIBRARY_FILTER, LewdLibraryFilter.Disabled.persistedValue)

    val novelLibraryFilter: Preference<Int> =
        store.getInt(KEY_NOVEL_LIBRARY_FILTER, NovelLibraryFilter.Disabled.persistedValue)

    val novelFontSize = store.getInt("pref_novel_font_size", 16)
    val novelFontFamily = store.getString("pref_novel_font_family", "sans-serif")
    val novelTheme = store.getString("pref_novel_theme", "app")
    val novelLineHeight = store.getFloat("pref_novel_line_height", 1.6f)
    val novelTextAlign = store.getString("pref_novel_text_align", "left")
    val novelFontColor = store.getInt("pref_novel_font_color", 0)
    val novelBackgroundColor = store.getInt("pref_novel_background_color", 0)
    val novelParagraphIndent = store.getFloat("pref_novel_paragraph_indent", 0f)
    val novelParagraphSpacing = store.getFloat("pref_novel_paragraph_spacing", 0.5f)
    val novelMarginLeft = store.getInt("pref_novel_margin_left", 16)
    val novelMarginRight = store.getInt("pref_novel_margin_right", 16)
    val novelMarginTop = store.getInt("pref_novel_margin_top", 50)
    val novelMarginBottom = store.getInt("pref_novel_margin_bottom", 16)
    val novelRenderingMode = store.getString("pref_novel_rendering_mode", "default")
    val novelEnableEpubStyles = store.getBoolean("pref_novel_enable_epub_css", true)
    val novelEnableEpubJs = store.getBoolean("pref_novel_enable_epub_js", false)
    val novelSourceCssPriority = store.getBoolean("pref_novel_source_css_priority", false)
    val novelCustomCss = store.getString("pref_novel_custom_css", "")
    val novelCustomJs = store.getString("pref_novel_custom_js", "")
    val novelCustomCssSnippets = store.getString("pref_novel_css_snippets", "[]")
    val novelCustomJsSnippets = store.getString("pref_novel_js_snippets", "[]")
    val novelGlobalPresets = store.getString("pref_novel_global_presets", "[]")
    val novelRegexReplacements = store.getString("pref_novel_regex_replacements", "[]")
    val novelInfiniteScroll = store.getBoolean("pref_novel_infinite_scroll", false)
    val novelKeepChaptersLoaded = store.getInt("pref_novel_keep_chapters_loaded", 0)
    val novelCustomBrightness = store.getBoolean("pref_novel_custom_brightness", false)
    val novelCustomBrightnessValue = store.getInt("pref_novel_custom_brightness_value", 0)
    val novelShowProgressSlider = store.getBoolean("pref_novel_show_progress_slider", true)
    val novelVerticalScrollbar = store.getBoolean("pref_novel_vertical_scrollbar", false)
    val novelVerticalScrollbarPosition = store.getString("pref_novel_vertical_scrollbar_position", "right")
    val novelVerticalProgressSliderSize = store.getString("pref_novel_vertical_progress_slider_size", "half")
    val novelHideChapterTitle = store.getBoolean("pref_novel_hide_chapter_title", false)
    val novelForceTextLowercase = store.getBoolean("pref_novel_force_lowercase", false)
    val novelAutoSplitText = store.getBoolean("pref_novel_auto_split_text", false)
    val novelAutoSplitWordCount = store.getInt("pref_novel_auto_split_word_count", 50)
    val novelUseOriginalFonts = store.getBoolean("pref_novel_use_original_fonts", false)
    val novelKeepScreenOn = store.getBoolean("pref_novel_keep_screen_on", false)
    val novelSwipeNavigation = store.getBoolean("pref_novel_swipe_navigation", false)
    val novelVolumeKeysScroll = store.getBoolean("pref_novel_volume_keys_scroll", false)
    val novelTapToScroll = store.getBoolean("pref_novel_tap_to_scroll", false)
    val novelTextSelectable = store.getBoolean("pref_novel_text_selectable", true)
    val novelBlockMedia = store.getBoolean("pref_novel_block_media", false)
    val novelChapterTitleDisplay = store.getInt("pref_novel_chapter_title_display", 2)
    val novelAutoLoadNextChapterAt = store.getInt("pref_novel_auto_load_next_at", 95)
    val novelMarkAsReadThreshold = store.getInt("pref_novel_mark_read_threshold", 95)
    val novelMarkShortChapterAsRead = store.getBoolean("pref_novel_mark_short_chapter_read", true)
    val novelShowRawHtml = store.getBoolean("pref_novel_show_raw_html", false)
    val novelAutoScrollSpeed = store.getInt("pref_novel_auto_scroll_speed_half", 6)
    val novelTtsSpeed = store.getFloat("pref_novel_tts_speed", 1f)
    val novelTtsPitch = store.getFloat("pref_novel_tts_pitch", 1f)
    val novelTtsVoice = store.getString("pref_novel_tts_voice", "")
    val novelTtsAutoNextChapter = store.getBoolean("pref_novel_tts_auto_next", true)
    val novelTtsHighlightStyle = store.getString("pref_novel_tts_highlight_style", "background")
    val novelTtsHighlightColor = store.getInt("pref_novel_tts_highlight_color", 0xFFFFD54F.toInt())
    val novelTtsHighlightTextColor = store.getInt("pref_novel_tts_highlight_text_color", 0xFF1A1A1A.toInt())
    val novelTtsEnableHighlight = store.getBoolean("pref_novel_tts_enable_highlight", true)
    val novelTtsKeepHighlightInView = store.getBoolean("pref_novel_tts_keep_highlight_in_view", true)
    val novelTtsBackgroundPlayback = store.getBoolean("pref_novel_tts_background_playback", false)
    val novelTtsControlsVisible = store.getBoolean("pref_novel_tts_controls_visible", false)
    val novelTtsAutoStartOnPanelOpen = store.getBoolean("pref_novel_tts_auto_start_on_panel_open", false)
    val novelStatusBarEnabled = store.getBoolean("pref_novel_status_bar_enabled", false)
    val novelStatusBarShowTime = store.getBoolean("pref_novel_status_bar_show_time", true)
    val novelStatusBarShowBattery = store.getBoolean("pref_novel_status_bar_show_battery", true)
    val novelStatusBarShowChapterNumber = store.getBoolean("pref_novel_status_bar_show_chapter_number", true)
    val novelStatusBarShowChapterTitle = store.getBoolean("pref_novel_status_bar_show_chapter_title", true)
    val novelStatusBarShowProgress = store.getBoolean("pref_novel_status_bar_show_progress", true)
    val novelStatusBarPosition = store.getString("pref_novel_status_bar_position", "bottom")
    val novelStatusBarSize = store.getString("pref_novel_status_bar_size", "small")
    val novelStatusBarShowCharging = store.getBoolean("pref_novel_status_bar_show_charging", true)

    companion object {
        const val KEY_HENTAI_FEATURES = "eh_is_hentai_enabled"
        const val KEY_LEWD_LIBRARY_FILTER = "pref_filter_library_lewd_v2"
        const val KEY_NOVEL_LIBRARY_FILTER = "pref_filter_library_novel_v1"
    }
}

enum class NovelLibraryFilter(
    val persistedValue: Int,
) {
    Disabled(0),
    NovelsOnly(1),
    MangaOnly(2),
    ;

    fun includes(isNovel: Boolean): Boolean =
        when (this) {
            Disabled -> true
            NovelsOnly -> isNovel
            MangaOnly -> !isNovel
        }

    companion object {
        fun fromPersistedValue(value: Int): NovelLibraryFilter =
            entries.firstOrNull { it.persistedValue == value } ?: Disabled
    }
}

enum class LewdLibraryFilter(
    val persistedValue: Int,
) {
    Disabled(0),
    Include(1),
    Exclude(2),
    ;

    fun includes(isLewd: Boolean): Boolean =
        when (this) {
            Disabled -> true
            Include -> isLewd
            Exclude -> !isLewd
        }

    companion object {
        fun fromPersistedValue(value: Int): LewdLibraryFilter =
            entries.firstOrNull { it.persistedValue == value } ?: Disabled
    }
}
