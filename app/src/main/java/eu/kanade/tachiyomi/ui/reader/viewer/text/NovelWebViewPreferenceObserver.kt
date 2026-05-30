package eu.kanade.tachiyomi.ui.reader.viewer.text

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import yokai.domain.ui.settings.ReaderPreferences

/**
 * Merges every novel pref flow exposed by the settings sheet into one of five buckets so
 * each preference applies live without a chapter reopen. Ported from Tsundoku's
 * NovelWebViewPreferenceObserver; the bucket structure is verbatim, the pref set is Hayai's.
 *
 * - [onStyleChanged]: re-applies CSS in place (font, theme, margins, colors, custom CSS,
 *   source-CSS-priority …).
 * - [onScriptChanged]: re-injects custom JS.
 * - [onChapterReloadRequested]: prefs that change the parsed CONTENT (EPUB toggles, regex,
 *   lowercase, normalize, auto-split, raw HTML, hide-title strip) — caller must clear the
 *   loaded-chapter cache before re-rendering.
 * - [onBlockMediaChanged]: image/media blocking toggle.
 * - [onTtsSettingsChanged]: TTS voice/speed/pitch.
 * - [onKeepScreenOnChanged]: the novel-specific keep-screen-on window flag.
 * - [onBrightnessChanged]: re-applies the novel custom brightness (toggle + value).
 * - [onChapterWindowChanged]: trims the loaded-chapter DOM window when the cap changes.
 */
internal class NovelWebViewPreferenceObserver(
    private val preferences: ReaderPreferences,
    private val scope: CoroutineScope,
    private val onStyleChanged: () -> Unit,
    private val onScriptChanged: () -> Unit,
    private val onChapterReloadRequested: () -> Unit,
    private val onBlockMediaChanged: (Boolean) -> Unit,
    private val onTtsSettingsChanged: () -> Unit,
    private val onKeepScreenOnChanged: (Boolean) -> Unit,
    private val onBrightnessChanged: () -> Unit,
    private val onChapterWindowChanged: () -> Unit,
) {

    fun observe() {
        scope.launch {
            merge(
                preferences.novelFontSize.changes().drop(1),
                preferences.novelFontFamily.changes().drop(1),
                preferences.novelTheme.changes().drop(1),
                preferences.novelLineHeight.changes().drop(1),
                preferences.novelTextAlign.changes().drop(1),
                preferences.novelMarginLeft.changes().drop(1),
                preferences.novelMarginRight.changes().drop(1),
                preferences.novelMarginTop.changes().drop(1),
                preferences.novelMarginBottom.changes().drop(1),
                preferences.novelMarginsCropped.changes().drop(1),
                preferences.novelFontColor.changes().drop(1),
                preferences.novelBackgroundColor.changes().drop(1),
                preferences.novelParagraphIndent.changes().drop(1),
                preferences.novelParagraphSpacing.changes().drop(1),
                preferences.novelCustomCss.changes().drop(1),
                preferences.novelCustomCssSnippets.changes().drop(1),
                preferences.novelUseOriginalFonts.changes().drop(1),
                preferences.novelTextSelectable.changes().drop(1),
                preferences.novelSourceCssPriority.changes().drop(1),
            ).collect { onStyleChanged() }
        }

        scope.launch {
            merge(
                preferences.novelCustomJs.changes().drop(1),
                preferences.novelCustomJsSnippets.changes().drop(1),
            ).collect { onScriptChanged() }
        }

        scope.launch {
            merge(
                preferences.enableEpubStyles.changes().drop(1),
                preferences.enableEpubJs.changes().drop(1),
                preferences.novelForceTextLowercase.changes().drop(1),
                preferences.novelRegexReplacements.changes().drop(1),
                preferences.novelTextNormalize.changes().drop(1),
                preferences.novelTextAggressiveCleanup.changes().drop(1),
                preferences.novelAutoSplitText.changes().drop(1),
                preferences.novelAutoSplitWordCount.changes().drop(1),
                preferences.novelShowRawHtml.changes().drop(1),
                preferences.novelHideChapterTitle.changes().drop(1),
            ).collect { onChapterReloadRequested() }
        }

        scope.launch {
            preferences.novelBlockMedia.changes()
                .drop(1)
                .collect { blockMedia -> onBlockMediaChanged(blockMedia) }
        }

        scope.launch {
            merge(
                preferences.novelTtsVoice.changes(),
                preferences.novelTtsSpeed.changes(),
                preferences.novelTtsPitch.changes(),
            )
                .drop(TTS_PREF_COUNT)
                .collect { onTtsSettingsChanged() }
        }

        scope.launch {
            preferences.novelKeepScreenOn.changes()
                .drop(1)
                .collect { keepOn -> onKeepScreenOnChanged(keepOn) }
        }

        scope.launch {
            merge(
                preferences.novelCustomBrightness.changes().drop(1),
                preferences.novelCustomBrightnessValue.changes().drop(1),
            ).collect { onBrightnessChanged() }
        }

        scope.launch {
            preferences.novelKeepChaptersLoaded.changes()
                .drop(1)
                .collect { onChapterWindowChanged() }
        }
    }

    companion object {
        private const val TTS_PREF_COUNT = 3
    }
}
