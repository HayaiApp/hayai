# Novel reader parity reference

This reference maps the Hayai reader to Tsundoku commit `547ddea3ce3e2a4943a1279b517e14b7af422467`. Hayai keeps the J2K image `ReaderActivity` unchanged.

## Ownership

- `NovelReaderActivity` owns novel chapter focus, progress, and typed actions while inflating J2K's unmodified `reader_activity.xml` shell.
- `NativeNovelRenderer` owns selectable native text blocks.
- `WebNovelRenderer` owns isolated WebView chapter blocks.
- `NovelChapterQueue` bounds retained chapter data. A prefetched chapter never becomes current until its block becomes visible.
- `NovelTtsPlaybackService` owns speech state and the media-playback notification.
- `NovelFontStore` owns imported font files under `files/hayai/novel-fonts`.
- J2K remains the source of truth for novels, chapters, bookmarks, progress, history, and tracking.

## Upstream behavior map

| Behavior | Tsundoku source | Hayai implementation |
|---|---|---|
| Reader preferences and stable keys | `ReaderPreferences.kt` | `HayaiPreferences.kt` |
| Reading, Appearance, Controls, TTS, and Advanced tabs | `NovelPage.kt` | Tsundoku's option grouping hosted by J2K's `TabbedBottomSheetDialog`, with J2K filter/text buttons, subtitle labels, sliders, material dialogs, and draggable-card rows in `NovelReaderSettingsSheet.kt`; searchable counterparts in `NovelSettingsController.kt` |
| Tap-zone mode IDs | `NovelConfig.kt` and `viewer/navigation/*` | `NovelTapZones` |
| Continuous chapter loading | `NovelTextViewViewer.kt` and `NovelWebViewViewer.kt` | `NovelChapterQueue`, both renderers, and visible-block callbacks |
| Web styling and append snippets | `NovelWebViewStyler.kt` | `NovelHtmlDocumentBuilder`, `WebNovelRenderer`, and `NovelCustomizationStore` |
| Reader app bars and chapter actions | `NovelReaderAppBars.kt` | the actual J2K `reader_activity.xml`, `reader_nav.xml`, collapsible `reader_chapters_sheet.xml`, and a Hayai-owned chapter adapter |
| Progress controls | `NovelReaderAppBars.kt` | J2K's real `ReaderNavView` and `ReaderSlider`, without the manga page-number cells, plus `NovelVerticalProgressView` when explicitly selected |
| TTS | `viewer/text/shared/TtsController.kt` | `NovelTtsController` and `NovelTtsPlaybackService`; active transport controls replace novel action slots inside J2K's reader sheet |
| Quotes and highlights | the novel reader selection tools | `NovelQuoteStore`, `NovelHighlightStore`, and typed reader actions |

## Continuous-flow rules

- Every renderer block uses the J2K chapter ID as its stable ID.
- Append and prepend reject duplicate IDs.
- Automatic loading uses `focus=false`. Explicit chapter navigation uses `focus=true`.
- Prepending preserves the visible block offset.
- The queue retains at most the configured previous chapter, current chapter, next chapter, or both adjacent chapters.
- A failed block keeps its place and exposes retry after a 15-second cooldown.
- Crossing forward over a chapter writes 100 percent before the reader writes the new chapter progress.
- `progressWriteMutex` serializes chapter progress writes.
- Incognito mode updates chapter progress but does not add history rows.

## Imported-font rules

- The Storage Access Framework accepts local TTF and OTF files.
- `NovelFontStore` rejects unsupported signatures, files smaller than 256 bytes, files larger than 20 MB, and more than 32 saved fonts.
- The native renderer loads the stored file with `Typeface.createFromFile`.
- The WebView renderer uses `@font-face` and the private `hayai-novel-font` scheme.
- Deleting the selected font restores `sans-serif`.

## Verification state

The reader settings sheet deliberately contains no second UI vocabulary. Progress mode uses J2K's filter button layout, actions use J2K's text button layout, numeric values use J2K subtitle styling so values such as 100 percent cannot collide with or split the title, and configurable action/status order uses J2K's draggable download-header card with `ItemTouchHelper`. Hayai owns only the preference mapping and callbacks.

The installed-APK run captured in `artifacts/emulator-verification/reader-settings-refactor-reader-chrome.xml`, `reader-settings-refactor-options.png`, and `reader-settings-refactor-more.png` verifies the reader toolbar safe area, separate title and metadata rows, intact percentage subtitles, all five option tabs, and native drag-handle ordering rows against the restored migration fixture.

The coherent reader batch passed `:app:testDevDebugUnitTest`, `:app:assembleDevDebug`, the upstream-boundary check, and `git diff --check`. The authorized `Pixel_10_Pro_XL` run in `artifacts/emulator-verification/20260822-043650` verified legacy migration, the exact J2K reader shell, the absence of manga page-number labels, active TTS transport controls, all five reader settings tabs, offline save, process restart recovery, the logged-out E-Hentai settings state, and Browse.

The discarded custom-shell workflow remains in `artifacts/emulator-verification/20260822-032809` only as regression evidence. WebView continuous scrolling, prepend offset, retry cooldown, imported fonts, TTS background recovery and notification handoff, incognito history, quote and highlight selection, and orientation changes still need dedicated device flows.
