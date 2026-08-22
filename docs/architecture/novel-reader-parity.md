# Novel reader parity reference

This reference maps the Hayai reader to Tsundoku commit `547ddea3ce3e2a4943a1279b517e14b7af422467`. Hayai keeps the J2K image `ReaderActivity` unchanged.

## Ownership

- `NovelReaderActivity` owns the reader shell, chapter focus, progress, and typed actions.
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
| Reading, Appearance, Controls, TTS, and Advanced tabs | `NovelPage.kt` | `NovelReaderSettingsSheet.kt` and `NovelSettingsController.kt` |
| Tap-zone mode IDs | `NovelConfig.kt` and `viewer/navigation/*` | `NovelTapZones` |
| Continuous chapter loading | `NovelTextViewViewer.kt` and `NovelWebViewViewer.kt` | `NovelChapterQueue`, both renderers, and visible-block callbacks |
| Web styling and append snippets | `NovelWebViewStyler.kt` | `NovelHtmlDocumentBuilder`, `WebNovelRenderer`, and `NovelCustomizationStore` |
| Progress controls | `NovelReaderAppBars.kt` | the horizontal slider and `NovelVerticalProgressView` |
| TTS | `viewer/text/shared/TtsController.kt` | `NovelTtsController` and `NovelTtsPlaybackService` |
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

The implementation has only received static inspection and `git diff --check` in this batch. Run the focused unit tests, Kotlin compilation, upstream-boundary check, and emulator reader flows before changing the audit status to **Port**. The emulator flow must cover native and WebView continuous scrolling, prepend offset, retry cooldown, imported fonts, TTS background recovery, notification handoff, incognito history, quotes, highlights, process restart, and orientation changes.
