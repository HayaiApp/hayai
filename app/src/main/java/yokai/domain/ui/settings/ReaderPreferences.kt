package yokai.domain.ui.settings

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.core.preference.getEnum
import eu.kanade.tachiyomi.data.preference.PreferenceKeys
import eu.kanade.tachiyomi.ui.reader.settings.OrientationType
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
import yokai.i18n.MR

private val DEFAULT_NOVEL_REGEX_RULES: String by lazy {
    val rules = listOf(
        eu.kanade.presentation.reader.settings.RegexReplacement(
            title = "Strip 'visit/read at <site>' lines",
            pattern = "(?im)^.*\\b(?:visit|please\\s+visit|continue\\s+reading|read\\s+more|read\\s+chapter|find\\s+(?:this|next)|update\\s+fastest|fastest\\s+update|bookmark|browse)\\b[^\\n]*\\b[a-z0-9][\\w-]*\\.(?:com|net|org|io|co|xyz|me|info|us|app)\\b[^\\n]*$",
            replacement = "",
            enabled = false,
        ),
        eu.kanade.presentation.reader.settings.RegexReplacement(
            title = "Strip 'Source: <url>' attributions",
            pattern = "(?im)^\\s*(?:source|origin(?:al)?|website|site|link|raw|tl|trans(?:lation)?)\\s*[:\\uFF1A][^\\n]+$",
            replacement = "",
            enabled = false,
        ),
        eu.kanade.presentation.reader.settings.RegexReplacement(
            title = "Strip Patreon / Ko-fi / donation calls",
            pattern = "(?im)^.*\\b(?:patreon|ko[- ]?fi|paypal|donat(?:e|ion)|support\\s+(?:us|me)|consider\\s+supporting)\\b[^\\n]*$",
            replacement = "",
            enabled = false,
        ),
        eu.kanade.presentation.reader.settings.RegexReplacement(
            title = "Strip standalone domain lines",
            pattern = "(?im)^\\s*(?:https?://)?(?:www\\.)?[a-z0-9][\\w-]*\\.(?:com|net|org|io|co|xyz|me|info)\\b\\S*\\s*$",
            replacement = "",
            enabled = false,
        ),
    )
    kotlinx.serialization.json.Json.encodeToString(rules)
}

class ReaderPreferences(private val preferenceStore: PreferenceStore) {
    fun cutoutShort() = preferenceStore.getBoolean("cutout_short", true)

    fun pagerCutoutBehavior() = preferenceStore.getEnum(PreferenceKeys.pagerCutoutBehavior, CutoutBehaviour.IGNORE)

    fun landscapeCutoutBehavior() = preferenceStore.getEnum("landscape_cutout_behavior", LandscapeCutoutBehaviour.HIDE)

    enum class CutoutBehaviour(val titleResId: StringResource) {
        HIDE(MR.strings.pad_cutout_areas),  // Similar to CUTOUT_MODE_NEVER / J2K's pad
        SHOW(MR.strings.start_past_cutout), // Similar to CUTOUT_MODE_SHORT_EDGES / J2K's start past
        IGNORE(MR.strings.cutout_ignore),   // Similar to CUTOUT_MODE_DEFAULT / J2K's ignore
        ;

        companion object {
            fun migrate(oldValue: Int) =
                when (oldValue) {
                    PagerConfig.CUTOUT_PAD -> CutoutBehaviour.HIDE
                    PagerConfig.CUTOUT_IGNORE -> CutoutBehaviour.IGNORE
                    else -> CutoutBehaviour.SHOW
                }
        }
    }

    enum class LandscapeCutoutBehaviour(val titleResId: StringResource) {
        HIDE(MR.strings.pad_cutout_areas),  // Similar to CUTOUT_MODE_NEVER / J2K's pad
        DEFAULT(MR.strings.cutout_ignore),  // Similar to CUTOUT_MODE_SHORT_EDGES / J2K's ignore
        ;

        companion object {
            fun migrate(oldValue: Int) =
                when (oldValue) {
                    0 -> LandscapeCutoutBehaviour.HIDE
                    else -> LandscapeCutoutBehaviour.DEFAULT
                }
        }
    }

    fun webtoonDoubleTapZoomEnabled() = preferenceStore.getBoolean("pref_enable_double_tap_zoom_webtoon", true)

    fun debugMode() = preferenceStore.getBoolean("pref_enable_reader_debug_mode", BuildConfig.DEBUG)

    // region Novel
    val novelFontSize: Preference<Int> = preferenceStore.getInt("pref_novel_font_size", 16)
    val novelFontFamily: Preference<String> = preferenceStore.getString("pref_novel_font_family", "sans-serif")
    val novelTheme: Preference<String> = preferenceStore.getString("pref_novel_theme", "app")
    val novelLineHeight: Preference<Float> = preferenceStore.getFloat("pref_novel_line_height", 1.6f)
    val novelTextAlign: Preference<String> = preferenceStore.getString("pref_novel_text_align", "left")
    // Auto-scroll speed: 1..50 (Phase B #4). Stored as a small int that's multiplied by
    // a per-frame px/sec factor inside the WebView's rAF loop. Default 15 keeps the prior
    // "comfortable but a bit fast" feel after the cap was raised 5×; the previous clamp
    // forced anything above 10 down anyway.
    val novelAutoScrollSpeed: Preference<Int> = preferenceStore.getInt("pref_novel_auto_scroll_speed", 15)
    val novelVolumeKeysScroll: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_volume_keys_scroll", false)
    val novelTapToScroll: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_tap_to_scroll", false)
    // Double-tap and long-press actions, both routed through NovelTapAction.dispatch.
    // Defaults: double-tap toggles the menu (matches single-tap MENU region) so users get
    // a discoverable affordance; long-press defaults to NONE so the WebView's native
    // text-selection behaviour is preserved out of the box.
    val novelDoubleTapAction: Preference<Int> = preferenceStore.getInt(
        "pref_novel_double_tap_action",
        hayai.novel.reader.NovelTapAction.TOGGLE_MENU.ordinal,
    )
    val novelLongTapAction: Preference<Int> = preferenceStore.getInt(
        "pref_novel_long_tap_action",
        hayai.novel.reader.NovelTapAction.NONE.ordinal,
    )
    val novelTextSelectable: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_text_selectable", true)

    // Block media elements (images, videos) in WebView and TextView readers
    val novelBlockMedia: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_block_media", false)

    // Font color (stored as ARGB int, 0 means use theme default)
    // Note: 0xFFFFFFFF (white) = -1 as signed int, so 0 is used as the "unset" marker
    val novelFontColor: Preference<Int> = preferenceStore.getInt("pref_novel_font_color", 0)

    // Background color (stored as ARGB int, 0 means use theme default)
    val novelBackgroundColor: Preference<Int> = preferenceStore.getInt("pref_novel_background_color", 0)

    // Paragraph indentation in em units (0 = no indent, default 2em)
    val novelParagraphIndent: Preference<Float> = preferenceStore.getFloat("pref_novel_paragraph_indent", 0f)

    // Margin preferences (in dp)
    val novelMarginLeft: Preference<Int> = preferenceStore.getInt("pref_novel_margin_left", 16)
    val novelMarginRight: Preference<Int> = preferenceStore.getInt("pref_novel_margin_right", 16)
    val novelMarginTop: Preference<Int> = preferenceStore.getInt("pref_novel_margin_top", 50)
    val novelMarginBottom: Preference<Int> = preferenceStore.getInt("pref_novel_margin_bottom", 16)
    // Toggled by the chapters-sheet "crop borders" button while a novel viewer is active.
    // When true, the four margin prefs above are read as 0 by the viewers without being mutated;
    // toggling back off restores the saved values.
    val novelMarginsCropped: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_margins_cropped", false)

    // novelRenderingMode preference removed in viewer consolidation: novels render via WebView
    // only. The legacy `pref_novel_rendering_mode` key may still exist in users' prefs storage
    // but is no longer read anywhere; leaving it orphaned is preferable to a destructive
    // migration on first launch.

    // EPUB specific toggles
    val enableEpubStyles: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_enable_epub_css", true)
    val enableEpubJs: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_enable_epub_js", false)
    // Let source/EPUB CSS win — when on, skip the `!important` overrides and don't force the font.
    val novelSourceCssPriority: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_source_css_priority", false)

    // Infinite scroll - automatically load next/previous chapters. Hayai's continuous-scroll
    // is core (always-on in the viewer); the key is ported verbatim for tsundoku migration
    // compat and is honored only as an explicit user override of continuous loading.
    val novelInfiniteScroll: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_infinite_scroll", false)

    // Keep chapters loaded in DOM during continuous scroll (0 = unlimited; >0 caps the window).
    val novelKeepChaptersLoaded: Preference<Int> = preferenceStore.getInt("pref_novel_keep_chapters_loaded", 0)

    // Custom brightness for novel reader
    val novelCustomBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_custom_brightness", false)

    // Brightness value for novel reader (-75 to 100, 0 = system)
    val novelCustomBrightnessValue: Preference<Int> = preferenceStore.getInt("pref_novel_custom_brightness_value", 0)

    // Auto-load next chapter at percentage (legacy 0 may exist; treated as default)
    val novelAutoLoadNextChapterAt: Preference<Int> = preferenceStore.getInt("pref_novel_auto_load_next_at", 95)

    // Mark chapter as read when progress reaches this percentage
    val novelMarkAsReadThreshold: Preference<Int> = preferenceStore.getInt("pref_novel_mark_read_threshold", 95)

    // If enabled, chapters that fully fit in the viewport are marked read immediately.
    val novelMarkShortChapterAsRead: Preference<Boolean> =
        preferenceStore.getBoolean("pref_novel_mark_short_chapter_read", true)

    // Custom CSS/JS stored as JSON array of {title, code} objects
    val novelCustomCss: Preference<String> = preferenceStore.getString("pref_novel_custom_css", "")
    val novelCustomJs: Preference<String> = preferenceStore.getString("pref_novel_custom_js", "")
    val novelCustomCssSnippets: Preference<String> = preferenceStore.getString("pref_novel_css_snippets", "[]")
    val novelCustomJsSnippets: Preference<String> = preferenceStore.getString("pref_novel_js_snippets", "[]")

    // Regex find/replace rules stored as JSON array of {title, pattern, replacement, enabled, isRegex}
    // Applied to chapter HTML content before rendering in both WebView and TextView modes
    val novelRegexReplacements: Preference<String> = preferenceStore.getString("pref_novel_regex_replacements", DEFAULT_NOVEL_REGEX_RULES)

    // NFKC + combining-mark + zero-width strip applied before regex rules. Catches stylised
    // Unicode obfuscation (𝒇𝓻𝓮𝓮 → free, ｆｒｅｅ → free) so a plain `freewebnovel\.com` rule matches.
    val novelTextNormalize: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_text_normalize", true)

    // Strip ALL non-ASCII after normalisation. Off by default — destroys CJK content.
    val novelTextAggressiveCleanup: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_text_aggressive_cleanup", false)

    // Show progress slider in novel reader (allows scrolling to position in current chapter)
    val novelShowProgressSlider: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_show_progress_slider",
        true,
    )

    // 3x3 grid position for the horizontal progress slider. Format: "<v>-<h>" where v is
    // top/center/bottom and h is left/center/right. The *-center positions stretch the
    // slider full width (matching the manga reader); the *-left/*-right positions render
    // a compact pill (~360dp) anchored to that side. Default "bottom-center" matches the
    // manga-reader behaviour. Backward-compat: legacy values "top"/"center"/"bottom" are
    // mapped to the *-center equivalent at read time in applyNovelNavLayoutPosition.
    val novelProgressSliderPosition: Preference<String> = preferenceStore.getString(
        "pref_novel_progress_slider_position",
        "bottom-center",
    )

    // Show the platform vertical scrollbar in novel readers.
    val novelVerticalScrollbar: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_vertical_scrollbar",
        false,
    )

    // Vertical scrollbar side in novel readers: "left" or "right".
    val novelVerticalScrollbarPosition: Preference<String> = preferenceStore.getString(
        "pref_novel_vertical_scrollbar_position",
        "right",
    )

    // Vertical progress slider height mode: "half" or "full".
    val novelVerticalProgressSliderSize: Preference<String> = preferenceStore.getString(
        "pref_novel_vertical_progress_slider_size",
        "half",
    )

    // Hide chapter title in novel content
    val novelHideChapterTitle: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_hide_chapter_title", false)

    // Force lowercase for all chapter content
    val novelForceTextLowercase: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_force_lowercase", false)

    // Auto-split text after X words until punctuation mark (0 = disabled)
    val novelAutoSplitText: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_auto_split_text", false)
    val novelAutoSplitWordCount: Preference<Int> = preferenceStore.getInt("pref_novel_auto_split_word_count", 50)

    // Use source's original fonts (don't force a specific font family)
    val novelUseOriginalFonts: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_use_original_fonts", false)

    // Keep screen on while reading
    val novelKeepScreenOn: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_keep_screen_on", false)

    // Paragraph spacing (additional space between paragraphs in em units)
    val novelParagraphSpacing: Preference<Float> = preferenceStore.getFloat("pref_novel_paragraph_spacing", 0.5f)

    // Swipe navigation - swipe left/right to change chapters
    val novelSwipeNavigation: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_swipe_navigation", false)

    // Chapter title display format: 0 = name only, 1 = number only, 2 = both (name + number)
    val novelChapterTitleDisplay: Preference<Int> = preferenceStore.getInt("pref_novel_chapter_title_display", 2)


    // Show raw HTML (display HTML tags without parsing) - useful for debugging
    val novelShowRawHtml: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_show_raw_html", false)

    // TTS (Text-to-Speech) preferences
    val novelTtsSpeed: Preference<Float> = preferenceStore.getFloat("pref_novel_tts_speed", 1.0f)
    val novelTtsPitch: Preference<Float> = preferenceStore.getFloat("pref_novel_tts_pitch", 1.0f)
    val novelTtsVoice: Preference<String> = preferenceStore.getString("pref_novel_tts_voice", "")
    // Empty string ⇒ system default engine. Otherwise the engine package name (e.g.
    // "com.google.android.tts"); fed into `TextToSpeech(context, listener, engineName)`.
    val novelTtsEngine: Preference<String> = preferenceStore.getString("pref_novel_tts_engine", "")
    val novelTtsAutoNextChapter: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_tts_auto_next", true)
    val novelTtsHighlightStyle: Preference<String> = preferenceStore.getString("pref_novel_tts_highlight_style", "background") // background, underline, outline
    val novelTtsHighlightColor: Preference<Int> = preferenceStore.getInt("pref_novel_tts_highlight_color", 0xFFFFD54F.toInt())
    val novelTtsHighlightTextColor: Preference<Int> = preferenceStore.getInt("pref_novel_tts_highlight_text_color", 0xFF1A1A1A.toInt())
    val novelTtsEnableHighlight: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_tts_enable_highlight", true)
    val novelTtsKeepHighlightInView: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_tts_keep_highlight_in_view", true)
    val novelTtsBackgroundPlayback: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_tts_background_playback", false)
    val novelTtsLastReadParagraph: Preference<String> = preferenceStore.getString("pref_novel_tts_last_read_para", "{}") // JSON map of chapterId -> paragraphIndex

    // Tap a paragraph to start TTS from there. On by default per user request; can be
    // disabled if the user finds it noisy with the existing tap-to-scroll / nav zones.
    val novelTtsTapToStart: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_tts_tap_to_start", true)

    // App-level default screen orientation when reading novels. Stored as a packed
    // OrientationType.flagValue (matches the manga `defaultOrientationType` storage shape) so the
    // same `OrientationType.fromPreference()` decoder works for both. Per-novel overrides live on
    // `Manga.orientationType` and take precedence; this is the fallback used when the per-novel
    // value is `OrientationType.DEFAULT.flagValue`. Key is referenced by string in
    // SettingsMangaReaderController — keep both in sync.
    val novelDefaultOrientationType: Preference<Int> = preferenceStore.getInt(
        "pref_novel_default_orientation_type_key",
        OrientationType.FREE.flagValue,
    )
    // endregion
}
