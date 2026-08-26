# Novel reader parity reference

This reference maps Hayai's text-reader behavior to Tsundoku commit `547ddea3ce3e2a4943a1279b517e14b7af422467` while keeping TachiyomiJ2K's real reader as the only reader shell and interaction owner.

## Ownership

- J2K `ReaderActivity`, `ReaderViewModel`, `ReaderChapter`, `ViewerChapters`, `ReaderChapterSheet`, `ReaderNavView`, toolbar, slider, gestures, lifecycle, history, tracking, and chapter transitions remain authoritative for both manga and novels.
- `ReaderLauncher` always opens `ReaderActivity`. There is no Hayai reader activity, duplicate chapter adapter, or copied reader chrome.
- `NovelChapterPageLoader` adapts a novel document to 101 ready `NovelProgressPage` values. Those pages are a normalized 0–100 persistence protocol for J2K; they never enter an image holder or image viewer.
- `NovelReaderViewer` is a J2K `BaseViewer` that owns only the text viewport and translates renderer movement into the normalized J2K page protocol.
- `NovelReaderAttachment` installs novel-only actions into J2K's existing toolbar and chapter-sheet buttons. It owns quotes, highlights, translation, TTS, offline actions, statistics, editing, and reader settings without owning chapter focus or progress persistence.
- `NativeNovelRenderer` owns selectable native text. `WebNovelRenderer` owns isolated WebView text, paginated layout, and vertical Japanese writing.
- `NovelTtsPlaybackService` owns speech state and its media notification. Notification taps return through `ReaderLauncher` to the same J2K reader.
- J2K remains the sole source of truth for novels, chapters, bookmarks, progress, history, tracking, categories, and queued downloads.

## Upstream behavior map

| Behavior | Tsundoku source | Hayai implementation |
|---|---|---|
| Reader preferences and stable keys | `ReaderPreferences.kt` | `HayaiPreferences.kt` |
| Reading, Appearance, Controls, TTS, and Advanced tabs | `NovelPage.kt` | `NovelReaderSettingsSheet.kt` inside J2K's existing reader settings entry point; searchable counterparts remain in `NovelSettingsController.kt` |
| Tap-zone mode IDs | `NovelConfig.kt` and `viewer/navigation/*` | J2K `ViewerNavigation` implementations selected by the stored novel tap-zone preference |
| Chapter loading and navigation | text viewers and reader app bars | J2K `ChapterLoader`, `ReaderViewModel`, `ViewerChapters`, chapter sheet, toolbar, adjacent-chapter buttons, and `ReaderActivity.loadChapter` |
| Web styling and append snippets | `NovelWebViewStyler.kt` | `NovelHtmlDocumentBuilder`, `WebNovelRenderer`, and `NovelCustomizationStore` |
| Progress controls | `NovelReaderAppBars.kt` | J2K's real `ReaderNavView`, `ReaderSlider`, page overlay, and chapter sheet driven by a 0–100 novel progress adapter |
| TTS | `viewer/text/shared/TtsController.kt` | `NovelTtsController` and `NovelTtsPlaybackService`; transport replaces novel-only action slots in the J2K chapter sheet |
| Quotes and highlights | novel-reader selection tools | `NovelQuoteStore`, `NovelHighlightStore`, and typed selection actions hosted by `NovelReaderAttachment` |

## Reader interaction contract

The J2K toolbar, chapter sheet, navigation overlay, page slider, key handling, adjacent-chapter actions, orientation lifecycle, progress saving, tracking, and back behavior are used directly. The novel viewer does not inflate `reader_activity.xml`, reimplement the sheet, maintain a private chapter index, or write chapter rows itself.

`ReaderActivity` selects `NovelReaderViewer` only when the loaded source proves the novel capability. `ChapterLoader` selects `NovelChapterPageLoader` at the same boundary. Image sources continue through J2K's original loaders and pager/webtoon viewers unchanged. A `NovelProgressPage` carries the loaded document and a 0–100 position; `ReaderViewModel.onPageSelected` persists that position as `last_page_read`, derives `pages_left`, and applies the configured completion threshold before J2K performs its normal tracking and duplicate-chapter work.

The page overlay and slider show a percentage for continuous text. Paginated Web rendering reports its real page number and page count while continuing to persist normalized progress. Chapter changes always pass through J2K's `ViewerChapters` and `loadChapter` paths.

## Rendering modes

Backend, layout, and writing direction are independent preferences:

- Native + continuous + horizontal uses `NativeNovelRenderer`.
- Web supports continuous or paginated layout.
- Vertical Japanese selects Web rendering with `writing-mode: vertical-rl` and `text-orientation: mixed`.
- Paginated Web rendering uses horizontal columns, page-sized stepping, snap behavior, and reports real page counts through the renderer bridge.

Unsupported Native combinations resolve to Web rather than pretending that Native implemented pagination or vertical writing. The existing backend preference key remains stable for restored installations; layout and writing direction use separate Hayai keys.

## Selection and feature contract

Both renderers expose the same typed `NovelSelection`. Native text remains selectable and retains Android editing behavior when edit mode is enabled. Read-only selection keeps Quote, Define, Translate, and Search actions. Web selection caches its complete anchor so an action remains usable after Android's floating toolbar changes the live selection.

`NovelReaderAttachment` preserves the production feature paths previously coupled to the duplicate activity: saved quotes, persistent highlights, selection translation, full-chapter translation, dictionary/search handoff, TTS playback and paragraph highlighting, bookmark actions, offline save/remove, chapter statistics, edit mode, orientation, imported-font settings, presets, CSS/JavaScript snippets, and the full searchable settings page.

## Imported-font rules

- The Storage Access Framework accepts local TTF and OTF files.
- `NovelFontStore` rejects unsupported signatures, files smaller than 256 bytes, files larger than 20 MB, and more than 32 saved fonts.
- The native renderer loads the stored file with `Typeface.createFromFile`.
- The Web renderer uses `@font-face` and the private `hayai-novel-font` scheme.
- Deleting the selected font restores `sans-serif`.

## Verification state

The separate `NovelReaderActivity`, `J2kNovelReaderChrome`, and `NovelReaderChapterAdapter` have been removed. The implementation compiles through the real J2K reader boundary. Focused JVM tests cover reader routing, render-plan selection, normalized progress, paginated HTML/page reporting, and vertical-writing CSS. Final unit, boundary, localization, whitespace, and emulator results are recorded only after those commands complete successfully.
