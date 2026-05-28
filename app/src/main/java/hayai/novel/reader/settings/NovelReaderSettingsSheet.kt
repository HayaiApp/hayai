package hayai.novel.reader.settings

import yokai.util.koin.get
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.FormatAlignJustify
import androidx.compose.material.icons.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.FormatAlignRight
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.launch
import yokai.domain.ui.settings.ReaderPreferences
import yokai.i18n.MR
import yokai.presentation.component.preference.widget.SliderPreferenceWidget
import yokai.presentation.component.preference.widget.SwitchPreferenceWidget
import yokai.presentation.theme.ReducedMotion
import yokai.presentation.theme.YokaiTheme

/**
 * In-reader settings bottom sheet for the novel reader.
 *
 * Adapts Tsundoku's tabbed reader settings UI to Hayai's component palette and Material 3
 * Expressive design. Each preference change writes directly to [ReaderPreferences]; the active
 * viewer's flow-based observers (in `NovelViewer`/`NovelWebViewViewer`) propagate the change live.
 *
 * Out-of-scope items deferred to Phase 10:
 * - Color picker (uses pref-defined ARGB sentinels for now)
 * - Custom CSS/JS/regex snippet editor
 * - Voice picker (uses default voice; speed/pitch only)
 */

private enum class SettingsTab(
    val icon: ImageVector,
    val labelRes: dev.icerock.moko.resources.StringResource,
) {
    Reading(Icons.Outlined.TextFields, MR.strings.reading),
    Appearance(Icons.Outlined.Palette, MR.strings.appearance),
    Controls(Icons.Outlined.Swipe, MR.strings.controls),
    Advanced(Icons.Outlined.Code, MR.strings.advanced),
    Tts(Icons.Outlined.RecordVoiceOver, MR.strings.text_to_speech),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelReaderSettingsSheet(
    preferences: ReaderPreferences,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pagerState = rememberPagerState { SettingsTab.entries.size }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Sheet header — matches the manga reader's titled tab sheet.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.settings),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            PrimaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                divider = {},
            ) {
                SettingsTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch {
                            if (ReducedMotion.isEnabled()) pagerState.scrollToPage(index)
                            else pagerState.animateScrollToPage(index)
                        } },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = stringResource(tab.labelRes),
                            )
                        },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            HorizontalDivider()

            HorizontalPager(
                state = pagerState,
                verticalAlignment = Alignment.Top,
            ) { page ->
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = 24.dp),
                ) {
                    when (SettingsTab.entries[page]) {
                        SettingsTab.Reading -> ReadingTab(preferences)
                        SettingsTab.Appearance -> AppearanceTab(preferences)
                        SettingsTab.Controls -> ControlsTab(preferences)
                        SettingsTab.Advanced -> AdvancedTab(preferences)
                        SettingsTab.Tts -> TtsTab(preferences)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingTab(prefs: ReaderPreferences) {
    Column {
        // Rendering mode chip removed: novel reading is WebView-only after the
        // viewer consolidation. The native NovelViewer was deleted; no fallback exists.
        ChipRow(
            label = stringResource(MR.strings.novel_font_family),
            options = listOf(
                "sans-serif" to stringResource(MR.strings.novel_font_sans_serif),
                "serif" to stringResource(MR.strings.novel_font_serif),
                "monospace" to stringResource(MR.strings.novel_font_monospace),
                "Georgia, serif" to stringResource(MR.strings.novel_font_georgia),
                "Times New Roman, serif" to stringResource(MR.strings.novel_font_times),
                "Arial, sans-serif" to stringResource(MR.strings.novel_font_arial),
            ),
            preference = prefs.novelFontFamily,
        )
        IconToggleRow(
            label = stringResource(MR.strings.novel_text_align),
            options = listOf(
                Icons.Outlined.FormatAlignLeft to "left",
                Icons.Outlined.FormatAlignCenter to "center",
                Icons.Outlined.FormatAlignRight to "right",
                Icons.Outlined.FormatAlignJustify to "justify",
            ),
            preference = prefs.novelTextAlign,
        )
        IntSliderPref(
            preference = prefs.novelFontSize,
            title = stringResource(MR.strings.novel_font_size),
            min = 10,
            max = 40,
        )
        FloatSliderPref(
            preference = prefs.novelLineHeight,
            title = stringResource(MR.strings.novel_line_height),
            minTimes10 = 10,
            maxTimes10 = 30,
        )
        FloatSliderPref(
            preference = prefs.novelParagraphIndent,
            title = stringResource(MR.strings.novel_paragraph_indent),
            minTimes10 = 0,
            maxTimes10 = 50,
        )
        FloatSliderPref(
            preference = prefs.novelParagraphSpacing,
            title = stringResource(MR.strings.novel_paragraph_spacing),
            minTimes10 = 0,
            maxTimes10 = 30,
        )
        IntSliderPref(prefs.novelMarginLeft, stringResource(MR.strings.novel_margin_left), 0, 100)
        IntSliderPref(prefs.novelMarginRight, stringResource(MR.strings.novel_margin_right), 0, 100)
        IntSliderPref(prefs.novelMarginTop, stringResource(MR.strings.novel_margin_top), 0, 200)
        IntSliderPref(prefs.novelMarginBottom, stringResource(MR.strings.novel_margin_bottom), 0, 200)
        BoolPref(prefs.novelUseOriginalFonts, stringResource(MR.strings.novel_use_original_fonts))
        BoolPref(prefs.novelAutoSplitText, stringResource(MR.strings.novel_auto_split_text))
        IntSliderPref(prefs.novelAutoSplitWordCount, stringResource(MR.strings.novel_auto_split_word_count), 10, 200)
    }
}

@Composable
private fun AppearanceTab(prefs: ReaderPreferences) {
    Column {
        ChipRow(
            label = stringResource(MR.strings.novel_reader_theme),
            options = listOf(
                "app" to stringResource(MR.strings.novel_theme_app),
                "light" to stringResource(MR.strings.novel_theme_light),
                "dark" to stringResource(MR.strings.novel_theme_dark),
                "sepia" to stringResource(MR.strings.novel_theme_sepia),
                "black" to stringResource(MR.strings.novel_theme_black),
                "grey" to stringResource(MR.strings.novel_theme_grey),
                "custom" to stringResource(MR.strings.novel_theme_custom),
            ),
            preference = prefs.novelTheme,
        )
        CustomThemeColors(prefs)
        BoolPref(prefs.novelHideChapterTitle, stringResource(MR.strings.novel_hide_chapter_title))
        BoolPref(prefs.novelForceTextLowercase, stringResource(MR.strings.novel_force_lowercase))
        ChipRow(
            label = stringResource(MR.strings.novel_chapter_title_display),
            options = listOf(
                "0" to stringResource(MR.strings.novel_chapter_title_name),
                "1" to stringResource(MR.strings.novel_chapter_title_number),
                "2" to stringResource(MR.strings.novel_chapter_title_both),
            ),
            preference = prefs.novelChapterTitleDisplay,
        )
        BoolPref(prefs.novelKeepScreenOn, stringResource(MR.strings.keep_screen_on))
        BoolPref(prefs.novelBlockMedia, stringResource(MR.strings.novel_block_media))
        BoolPref(prefs.novelShowRawHtml, stringResource(MR.strings.novel_show_raw_html))
    }
}

@Composable
private fun ControlsTab(prefs: ReaderPreferences) {
    Column {
        // Auto-scroll cap raised 5× per Phase B #4. The previous clamp inside the viewer
        // collapsed anything > 10 anyway; now the JS loop honours the full 1..50 range
        // and the slider exposes it linearly. The minimum stays at 1 so users who want
        // the super-slow scroll get it unchanged.
        IntSliderPref(prefs.novelAutoScrollSpeed, stringResource(MR.strings.novel_auto_scroll_speed), 1, 50)
        BoolPref(prefs.novelVolumeKeysScroll, stringResource(MR.strings.novel_volume_keys_scroll))
        BoolPref(prefs.novelTapToScroll, stringResource(MR.strings.novel_tap_to_scroll))
        // Double-tap + long-press chip rows. Both use the same NovelTapAction enum
        // (Phase B #16). The integer ChipRow stores ordinal values, parsed via
        // NovelTapAction.fromOrdinal at the dispatch site.
        NovelTapActionChipRow(
            label = stringResource(MR.strings.novel_double_tap_action),
            preference = prefs.novelDoubleTapAction,
        )
        NovelTapActionChipRow(
            label = stringResource(MR.strings.novel_long_tap_action),
            preference = prefs.novelLongTapAction,
        )
        // Clarifies the relationship between the three independent tap-handling prefs above
        // — tap-to-scroll, double-tap action, and long-press action — since the action enums
        // include SCROLL_UP/SCROLL_DOWN that semantically overlap with the toggle.
        Text(
            text = stringResource(MR.strings.novel_tap_actions_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        BoolPref(prefs.novelSwipeNavigation, stringResource(MR.strings.novel_swipe_navigation))
        BoolPref(prefs.novelTextSelectable, stringResource(MR.strings.novel_text_selectable))
        BoolPref(prefs.novelShowProgressSlider, stringResource(MR.strings.novel_show_progress_slider))
        ChipRow(
            label = stringResource(MR.strings.novel_progress_slider_position),
            options = listOf(
                "top-left" to stringResource(MR.strings.novel_progress_slider_position_top_left),
                "top-center" to stringResource(MR.strings.novel_progress_slider_position_top_center),
                "top-right" to stringResource(MR.strings.novel_progress_slider_position_top_right),
                "center-left" to stringResource(MR.strings.novel_progress_slider_position_center_left),
                "center-center" to stringResource(MR.strings.novel_progress_slider_position_center_center),
                "center-right" to stringResource(MR.strings.novel_progress_slider_position_center_right),
                "bottom-left" to stringResource(MR.strings.novel_progress_slider_position_bottom_left),
                "bottom-center" to stringResource(MR.strings.novel_progress_slider_position_bottom_center),
                "bottom-right" to stringResource(MR.strings.novel_progress_slider_position_bottom_right),
            ),
            preference = prefs.novelProgressSliderPosition,
        )
        BoolPref(prefs.novelVerticalScrollbar, stringResource(MR.strings.novel_vertical_scrollbar))
        ChipRow(
            label = stringResource(MR.strings.novel_scrollbar_position),
            options = listOf(
                "right" to stringResource(MR.strings.novel_scrollbar_position_right),
                "left" to stringResource(MR.strings.novel_scrollbar_position_left),
            ),
            preference = prefs.novelVerticalScrollbarPosition,
        )
        ChipRow(
            label = stringResource(MR.strings.novel_vertical_slider_size),
            options = listOf(
                "half" to stringResource(MR.strings.novel_vertical_slider_half),
                "full" to stringResource(MR.strings.novel_vertical_slider_full),
            ),
            preference = prefs.novelVerticalProgressSliderSize,
        )
    }
}

@Composable
private fun AdvancedTab(prefs: ReaderPreferences) {
    Column {
        BoolPref(prefs.enableEpubStyles, stringResource(MR.strings.novel_enable_epub_styles))
        BoolPref(prefs.enableEpubJs, stringResource(MR.strings.novel_enable_epub_js))
        RegexReplacementSection(prefs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsTab(prefs: ReaderPreferences) {
    Column {
        TtsEngineDropdown(prefs)
        TtsVoiceDropdown(prefs)

        FloatSliderPref(prefs.novelTtsSpeed, stringResource(MR.strings.novel_tts_speed), minTimes10 = 5, maxTimes10 = 20)
        FloatSliderPref(prefs.novelTtsPitch, stringResource(MR.strings.novel_tts_pitch), minTimes10 = 5, maxTimes10 = 20)
        BoolPref(prefs.novelTtsAutoNextChapter, stringResource(MR.strings.novel_tts_auto_next_chapter))
        BoolPref(prefs.novelTtsEnableHighlight, stringResource(MR.strings.novel_tts_enable_highlight))
        ChipRow(
            label = stringResource(MR.strings.novel_tts_highlight_style),
            options = listOf(
                "background" to stringResource(MR.strings.novel_tts_highlight_background),
                "underline" to stringResource(MR.strings.novel_tts_highlight_underline),
                "outline" to stringResource(MR.strings.novel_tts_highlight_outline),
            ),
            preference = prefs.novelTtsHighlightStyle,
        )
        BoolPref(prefs.novelTtsKeepHighlightInView, stringResource(MR.strings.novel_tts_keep_highlight_in_view))
        BoolPref(prefs.novelTtsBackgroundPlayback, stringResource(MR.strings.novel_tts_background_playback))
    }
}

/**
 * Engine selector. Discovers installed TTS engines via `TextToSpeech.engines` and lets
 * the user pick one — useful when the OEM default is poor and the user has installed
 * Google TTS / Acapela / etc. Empty string ⇒ system default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsEngineDropdown(prefs: ReaderPreferences) {
    val context = LocalContext.current
    val selectedEngine by remember(prefs) { prefs.novelTtsEngine }.collectAsState()

    // (packageName, label). First entry is "" → system default.
    val engines = remember { mutableStateListOf<Pair<String, String>>() }

    val systemDefaultLabel = stringResource(MR.strings.novel_tts_engine_default)

    DisposableEffect(Unit) {
        var probe: TextToSpeech? = null
        probe = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val list = mutableListOf<Pair<String, String>>()
                list += "" to systemDefaultLabel
                probe?.engines?.forEach { e ->
                    list += e.name to (e.label ?: e.name)
                }
                engines.clear()
                engines.addAll(list)
            }
        }
        onDispose { probe?.shutdown() }
    }

    var expanded by remember { mutableStateOf(false) }
    val displayName = engines.firstOrNull { it.first == selectedEngine }?.second
        ?: systemDefaultLabel

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(MR.strings.novel_tts_engine),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = displayName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
            )
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                engines.forEach { (name, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            prefs.novelTtsEngine.set(name)
                            // Picking a new engine invalidates the previously stored voice
                            // (voices are engine-scoped). Reset so the user reselects.
                            prefs.novelTtsVoice.set("")
                            expanded = false
                        },
                    )
                }
            }
        }
        TextButton(
            onClick = {
                val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching { context.startActivity(intent) }
            },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(stringResource(MR.strings.novel_tts_open_settings))
        }
    }
}

/**
 * Voice selector — lists voices reported by the *currently selected* engine. Filters out
 * voices that require a network connection (matches Tsundoku's NovelPage.kt:1372).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsVoiceDropdown(prefs: ReaderPreferences) {
    val context = LocalContext.current
    val selectedVoice by remember(prefs) { prefs.novelTtsVoice }.collectAsState()
    val selectedEngine by remember(prefs) { prefs.novelTtsEngine }.collectAsState()

    val voices = remember { mutableStateListOf<Pair<String, String>>() }
    val voiceDefaultLabel = stringResource(MR.strings.novel_tts_voice_default)

    // Re-probe whenever the engine pref changes so the voice list reflects the
    // currently active engine.
    DisposableEffect(selectedEngine) {
        voices.clear()
        var probe: TextToSpeech? = null
        val ttsListener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                val list = mutableListOf<Pair<String, String>>()
                list += "" to voiceDefaultLabel
                probe?.voices
                    ?.filter { !it.isNetworkConnectionRequired }
                    ?.sortedBy { "${it.locale.displayLanguage} (${it.name})" }
                    ?.forEach { v ->
                        list += v.name to "${v.locale.displayLanguage} (${v.name})"
                    }
                voices.clear()
                voices.addAll(list)
            }
        }
        probe = if (selectedEngine.isNotBlank()) {
            TextToSpeech(context, ttsListener, selectedEngine)
        } else {
            TextToSpeech(context, ttsListener)
        }
        onDispose { probe?.shutdown() }
    }

    var expanded by remember { mutableStateOf(false) }
    val displayName = voices.firstOrNull { it.first == selectedVoice }?.second ?: voiceDefaultLabel

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(MR.strings.novel_tts_voice),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = displayName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
            )
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                voices.forEach { (name, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            prefs.novelTtsVoice.set(name)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

// region Helpers

@Composable
private fun BoolPref(preference: Preference<Boolean>, title: String) {
    val checked = remember(preference) { preference }.collectAsState()
    SwitchPreferenceWidget(
        title = title,
        checked = checked.value,
        onCheckedChanged = { preference.set(it) },
    )
}

@Composable
private fun IntSliderPref(
    preference: Preference<Int>,
    title: String,
    min: Int,
    max: Int,
) {
    val value = remember(preference) { preference }.collectAsState()
    SliderPreferenceWidget(
        title = title,
        value = value.value,
        min = min,
        max = max,
        onValueChange = { preference.set(it.toInt()) },
        subtitle = value.value.toString(),
    )
}

@Composable
private fun FloatSliderPref(
    preference: Preference<Float>,
    title: String,
    minTimes10: Int,
    maxTimes10: Int,
) {
    val value = remember(preference) { preference }.collectAsState()
    val intValue = (value.value * 10f).toInt().coerceIn(minTimes10, maxTimes10)
    SliderPreferenceWidget(
        title = title,
        value = intValue,
        min = minTimes10,
        max = maxTimes10,
        onValueChange = { preference.set(it / 10f) },
        subtitle = "%.1f".format(value.value),
    )
}

@Composable
private fun ChipRow(
    label: String,
    options: List<Pair<String, String>>,
    preference: Preference<String>,
) {
    val current = remember(preference) { preference }.collectAsState()
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = current.value == value,
                    onClick = { preference.set(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun IconToggleRow(
    label: String,
    options: List<Pair<ImageVector, String>>,
    preference: Preference<String>,
) {
    val current = remember(preference) { preference }.collectAsState()
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (icon, value) ->
                IconToggleButton(
                    checked = current.value == value,
                    onCheckedChange = { preference.set(value) },
                    colors = IconButtonDefaults.iconToggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Icon(imageVector = icon, contentDescription = value)
                }
            }
        }
    }
}

/**
 * Chip row for [hayai.novel.reader.NovelTapAction] ordinal-backed preferences.
 * Builds the option list from the enum's entries so adding a new action is a single
 * change in [hayai.novel.reader.NovelTapAction] (the enum + the string resource).
 */
@Composable
private fun NovelTapActionChipRow(
    label: String,
    preference: Preference<Int>,
) {
    val current = remember(preference) { preference }.collectAsState()
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            hayai.novel.reader.NovelTapAction.entries.forEach { action ->
                FilterChip(
                    selected = current.value == action.ordinal,
                    onClick = { preference.set(action.ordinal) },
                    label = { Text(stringResource(action.labelRes)) },
                )
            }
        }
    }
}

@Composable
@JvmName("IntChipRow")
private fun ChipRow(
    label: String,
    options: List<Pair<String, String>>,
    preference: Preference<Int>,
) {
    val current = remember(preference) { preference }.collectAsState()
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (key, label) ->
                val intValue = key.toIntOrNull() ?: return@forEach
                FilterChip(
                    selected = current.value == intValue,
                    onClick = { preference.set(intValue) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun <T> Preference<T>.collectAsState(): androidx.compose.runtime.State<T> {
    val state = androidx.compose.runtime.remember(this) { androidx.compose.runtime.mutableStateOf(get()) }
    androidx.compose.runtime.LaunchedEffect(this) {
        changes().collect { state.value = it }
    }
    return state
}

/**
 * Custom-theme color rows. Visible only when `novelTheme == "custom"`. Each row shows a swatch
 * for the current ARGB-int value (sentinel `0` = theme default) and opens a color picker dialog
 * on tap that writes back to the corresponding `Preference<Int>`. Mirrors Tsundoku's
 * `NovelAppearanceTab` (`tsundoku/.../NovelPage.kt:309-332`).
 */
@Composable
private fun CustomThemeColors(prefs: ReaderPreferences) {
    val theme by remember(prefs) { prefs.novelTheme }.collectAsState()
    if (theme != "custom") return

    val fontColor by remember(prefs) { prefs.novelFontColor }.collectAsState()
    val bgColor by remember(prefs) { prefs.novelBackgroundColor }.collectAsState()

    var showFontPicker by remember { mutableStateOf(false) }
    var showBgPicker by remember { mutableStateOf(false) }

    if (showFontPicker) {
        ColorPickerDialog(
            title = stringResource(MR.strings.novel_font_color),
            initialColor = if (fontColor != 0) fontColor else 0xFF000000.toInt(),
            onDismiss = { showFontPicker = false },
            onConfirm = { c ->
                prefs.novelFontColor.set(c)
                showFontPicker = false
            },
        )
    }
    if (showBgPicker) {
        ColorPickerDialog(
            title = stringResource(MR.strings.novel_background_color),
            initialColor = if (bgColor != 0) bgColor else 0xFFFFFFFF.toInt(),
            onDismiss = { showBgPicker = false },
            onConfirm = { c ->
                prefs.novelBackgroundColor.set(c)
                showBgPicker = false
            },
        )
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(MR.strings.novel_custom_theme_colors),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        ColorSwatchRow(
            label = stringResource(MR.strings.novel_font_color),
            colorArgb = fontColor.takeIf { it != 0 } ?: 0xFF000000.toInt(),
            onClick = { showFontPicker = true },
        )
        ColorSwatchRow(
            label = stringResource(MR.strings.novel_background_color),
            colorArgb = bgColor.takeIf { it != 0 } ?: 0xFFFFFFFF.toInt(),
            onClick = { showBgPicker = true },
        )
    }
}

@Composable
private fun ColorSwatchRow(label: String, colorArgb: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 12.dp)
                .size(28.dp)
                .background(Color(colorArgb), shape = RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(4.dp)),
        )
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Lightweight ARGB color picker. Sliders for R/G/B/A; live preview swatch on top.
 * Mirrors Tsundoku's `ColorPickerDialog` (NovelPage.kt:1554-1655) — same RGBA-slider approach
 * rather than a hue/saturation wheel so it's small and dependency-free.
 */
@Composable
private fun ColorPickerDialog(
    title: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var alpha by remember { mutableStateOf(((initialColor shr 24) and 0xFF) / 255f) }
    var red by remember { mutableStateOf(((initialColor shr 16) and 0xFF) / 255f) }
    var green by remember { mutableStateOf(((initialColor shr 8) and 0xFF) / 255f) }
    var blue by remember { mutableStateOf((initialColor and 0xFF) / 255f) }

    val argb: Int = (
        ((alpha * 255).toInt() and 0xFF) shl 24
        ) or (
        ((red * 255).toInt() and 0xFF) shl 16
        ) or (
        ((green * 255).toInt() and 0xFF) shl 8
        ) or (
        ((blue * 255).toInt() and 0xFF)
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .size(80.dp)
                        .background(Color(argb), shape = RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(8.dp)),
                )
                Text(
                    text = stringResource(MR.strings.novel_color_red),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(value = red, onValueChange = { red = it })
                Text(
                    text = stringResource(MR.strings.novel_color_green),
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(value = green, onValueChange = { green = it })
                Text(
                    text = stringResource(MR.strings.novel_color_blue),
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(value = blue, onValueChange = { blue = it })
                Text(
                    text = stringResource(MR.strings.novel_color_alpha),
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(value = alpha, onValueChange = { alpha = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(argb) }) { Text(stringResource(MR.strings.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

// endregion

// region Launcher

/**
 * Mounts [NovelReaderSettingsSheet] as a transient `ComposeView` overlay on the activity's content
 * frame. Removes itself from the view hierarchy on dismissal so subsequent shows start fresh.
 */
fun showNovelReaderSettingsSheet(activity: ReaderActivity) {
    val preferences: ReaderPreferences = get()
    val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
    val composeView = ComposeView(activity)
    composeView.setContent {
        YokaiTheme {
            NovelReaderSettingsSheet(
                preferences = preferences,
                onDismissRequest = {
                    (composeView.parent as? ViewGroup)?.removeView(composeView)
                },
            )
        }
    }
    rootView.addView(composeView)
}

// endregion
