# Hayai Polish & Redesign Roadmap

Synthesized from a 13-area reconnaissance pass across Hayai + the reference clones (mihon, tsundoku, TachiyomiSY, yokai, lnreader). This is both the **morning briefing** and the **autonomous-overnight work order**.

## How to read this
- Every distinct work item has a stable `id`, an **action** (PROCEED = implement now without user input; DEFER = needs a user repro/preference first), an effort (S/M/L/XL) and a risk.
- **PROCEED** items are core-cause-high-confidence and follow a clear reference pattern. They are safe to build overnight.
- **DEFER** items are consolidated at the bottom with one precise question each. Where a bug's *core* cause is high-confidence but a secondary candidate needs a repro, the **core fix proceeds** and only the ambiguous extension defers.

## House rules baked into every item
- **Tsundoku data/logic ports are line-by-line / verbatim** — do not synthesize abstractions Tsundoku lacks. Keep verbatim key names for backup/migration compat.
- **UI is composed from Hayai's own `yokai.presentation.component.*` + M3 Expressive**, never ported from Tsundoku UI primitives.
- **Material 3 Expressive only** (`MaterialExpressiveTheme` + material3 1.5.0-alpha14, already on classpath) — never fall back to plain `MaterialTheme`.
- **Fix the CORE invariant, not symptoms.** Name the broken invariant and restore it everywhere.
- **No shortcuts / no TODOs / no silent fallbacks.** Implement every legacy feature point-by-point.
- **Terse WHY-only comments.** No file-level KDoc paragraphs, no migration-history narration.
- **Builds:** set `$env:JAVA_HOME` to the Android Studio JBR, **batch all writes then build ONCE**, never run two gradle builds at once.

---

## Master item table

| id | title | area | action | effort | risk |
|----|-------|------|--------|--------|------|
| `theme-expressive` | Global MaterialExpressiveTheme swap | M3/Theme | PROCEED | M | med |
| `theme-font` | Outfit font app-wide (Typography) | M3/Theme | PROCEED | S | low |
| `theme-icons` | Icon system (heroicons + style unification) | M3/Theme | DEFER | M | med |
| `sheet-adaptive` | Port AdaptiveSheet, unify sheet stack | M3/Theme | PROCEED | XL | med |
| `settings-observer` | Novel pref observer rewrite (live-apply completeness) | Reader settings | PROCEED | M | med |
| `settings-deadsheet` | Delete dead Compose NovelReaderSettingsSheet | Reader settings | DEFER | S | low |
| `settings-merge` | Shared/merged reader-settings policy | Reader settings | DEFER | M | med |
| `lib-flash` | Library tabbed→continuous flash fix | Unified library | PROCEED | M | high |
| `lib-counts` | Show-counts toggle honored in both modes | Unified library | PROCEED | S | low |
| `lib-filtersheet` | Filter sheet bound to live surface | Unified library | DEFER | M | high |
| `lib-search` | Library search correctness | Unified library | DEFER | M | med |
| `novel-skipmsg` | Reason-aware chapter-skip messaging | Novel reader bugs | PROCEED | M | med |
| `novel-skiplist` | Skip-read honored in novel continuous scroll | Novel reader bugs | PROCEED | M | med |
| `novel-autoscroll-sel` | Auto-scroll pause while text selected | Novel reader bugs | PROCEED | S | low |
| `tts-stop` | In-reader TTS Stop affordance | Novel TTS | PROCEED | M | med |
| `tts-tapstart` | Tap-to-start-from-Idle gate fix | Novel TTS | PROCEED | S | med |
| `gsearch` | Manga-title global search self-pop fix | Global search | PROCEED | M | med |
| `gsearch-menu` | Disambiguate title-tap menu | Global search | DEFER | S | low |
| `appbar-measure` | Hoist per-frame measure off scroll hot path | Top app bar | PROCEED | S | med |
| `appbar-smooth` | Velocity fling + serialized animators + snap interpolator | Top app bar | DEFER | L | high |
| `appbar-behavior` | Adopt real Material AppBarLayout.Behavior (Tier B1) | Top app bar | DEFER | L | high |
| `md-covercolor` | Cover-color toggle relabel + surface | Manga details | PROCEED | S | low |
| `md-multiselect` | Chapter multi-select (mihon model) | Manga details | PROCEED | L | med |
| `md-actionrow` | Action-button row regroup | Manga details | DEFER | M | med |
| `md-trackinput` | Tracking search input restyle | Manga details | DEFER | S | low |
| `md-ehmeta` | EH/ext metadata typography polish | Manga details | PROCEED | S | low |
| `browse-display` | 3-mode display menu (comfortable/compact/list) | Source browse | PROCEED | S | low |
| `browse-theme` | Grid holder ExpressiveYokaiTheme | Source browse | PROCEED | S | low |
| `browse-group` | Filter Group drill renders nested Group/Sort | Source browse | PROCEED | S | low |
| `browse-sheetsize` | Responsive filter-sheet peek/body height | Source browse | PROCEED | S | low |
| `browse-filter` | Toolbar filter entry point + broad redesign | Source browse | DEFER | L | med |
| `inapp-browser` | Partial Custom Tab sheet for selection actions | In-app browser | PROCEED | M | low |
| `inapp-identify` | New "Identify" selection action | In-app browser | DEFER | S | low |
| `db-n1` | Kill library N+1 (batched track flow) | DB batching | PROCEED | L | med |
| `db-paging` | Restore paging primitive | DB batching | PROCEED | M | med |
| `db-batch` | Batch bulk writes (IN-clause updates) | DB batching | PROCEED | M | med |
| `db-bindcost` | Chapter-row bind-cost profiling/fix | DB batching | DEFER | M | med |
| `smart-update` | FetchInterval port + smart-skip restriction | Smart updater | PROCEED | L | med |
| `smart-update-ui` | Per-manga "Expected update" badge + dialog | Smart updater | DEFER | M | med |
| `novel-window` | Infinite-scroll chapter-window cap | Novel parity | PROCEED | M | med |
| `novel-brightness` | Reader custom brightness | Novel parity | PROCEED | S | low |
| `novel-thresholds` | Auto-load + mark-read thresholds | Novel parity | PROCEED | M | med |
| `novel-csspriority` | Source-CSS-priority / EPUB style preservation | Novel parity | PROCEED | S | low |
| `novel-bottombar` | Configurable reader bottom bar | Novel parity | DEFER | L | med |
| `novel-stats` | Novel reading-stats section | Novel parity | DEFER | M | med |
| `novel-presets` | Saved reader style presets | Novel parity | DEFER | M | low |

---

# Phase 0 — FOUNDATION (theming + font) [serial first, then parallel]

These unblock all redesign work; the theme swap must land before any Expressive-component or sheet work so visuals look right. `theme-expressive` and `theme-font` touch the same files (`Theme.kt`, `Typography.kt`) — do them in **one commit**.

### `theme-font` — Outfit font app-wide  (PROCEED · S · low)
**What/why:** No app-wide custom font exists today; `Typography.kt` has only a `header` extension, so the whole UI is default Roboto. User wants Outfit everywhere.
**Key files:** `app/src/main/java/yokai/presentation/theme/Typography.kt`; new `app/src/main/res/font/` (Outfit-Regular/Medium/SemiBold/Bold .ttf); `app/src/main/java/yokai/presentation/theme/Theme.kt:45`.
**Approach:** Drop Outfit `.ttf` into `res/font/`, build a `FontFamily`, construct a `Typography()` applying it to all roles (keep default sizes/line-heights), pass to `MaterialExpressiveTheme(typography = …)`. No in-repo reference (neither mihon/tsundoku/yokai ship a UI font — their `Typography.kt` is identical to Hayai's); this is the standard Compose approach. Note the reader *content* font is separate (user-selectable in `ReaderPreferences`/`NovelWebViewViewer`) — leave it alone.
**Deps:** none. **Parallel:** with nothing (shares files with `theme-expressive`). **Verify:** every Compose surface renders Outfit; reader content font unaffected.

### `theme-expressive` — Global MaterialExpressiveTheme swap  (PROCEED · M · med)
**What/why:** `material3 1.5.0-alpha14` + Expressive opt-in are already enabled in `build.gradle.kts:296`, but `YokaiTheme` (`Theme.kt:45`) still wraps everything in plain `MaterialTheme`. `MaterialExpressiveTheme` is used in exactly ONE place (`ExpressiveTheme.kt` → filter sheet). ~25 ComposeView call sites + all `yokai.presentation.component.*` render non-expressive M3.
**Key files:** `yokai/presentation/theme/Theme.kt:45`, `yokai/presentation/theme/ExpressiveTheme.kt:38`, `gradle/compose.versions.toml:8`, `app/build.gradle.kts:296`.
**Approach:** In `Theme.kt` swap `MaterialTheme(colorScheme=…)` → `MaterialExpressiveTheme(colorScheme=…, typography=AppTypography)`. Fold `ExpressiveTheme.kt` into the global wrapper so the filter sheet and everything else share ONE theme. MDC3 color extraction is color-only/font-agnostic → safe.
**Deps:** none. **Parallel:** one commit with `theme-font`. **Verify:** build once at end of Phase 0; spot-check filter sheet still themed, library/details/reader chrome render Expressive defaults.

---

# Phase 1 — HIGH-CONFIDENCE ISOLATED BUG FIXES [parallel, grouped by file]

All PROCEED. Group A (novel viewer file) must serialize internally; everything else is parallel across distinct files.

### Group A — Novel WebView viewer + reader (single hot file: `NovelWebViewViewer.kt`)
These three all edit `NovelWebViewViewer.kt` (+ adjacent reader/viewmodel files); **serialize within the group, one commit**.

#### `novel-autoscroll-sel` — Auto-scroll pauses while text selected  (PROCEED · S · low)
**What/why:** The in-WebView rAF auto-scroll loop pauses only while a finger is physically down (ACTION_DOWN). A completed selection leaves no finger down → scrolling continues, WebView re-lays-out selection handles + highlight rects every frame → jank. High confidence, no repro needed.
**Key files:** `NovelWebViewViewer.kt:3439-3467` (`installAutoScrollScript`/`__hayaiAutoStep`), `:655-665` (touch pause/resume), `:1116-1118` (existing sentence-tap selection guard to mirror).
**Approach:** In `__hayaiAutoStep`, early-return (without stopping the loop) while `window.getSelection().toString().length > 0`, mirroring the sentence-tap guard. Keep Kotlin `isAutoScrolling` intact so it self-resumes when the selection clears. Optionally call `removeAllRanges()` on chapter-nav like tsundoku (`NovelWebViewViewer.kt:1292,1322`).
**Verify:** highlight text mid-auto-scroll → scrolling stops; dismiss selection → resumes.

#### `novel-skipmsg` — Reason-aware chapter-skip messaging  (PROCEED · M · med)
**What/why:** `ChapterFilter.filterChaptersForReader` removes read/filtered/dupe chapters up front; `adjacentChapter` indexes the pre-filtered list; at the boundary every reason collapses into one generic "There's no next chapter" toast/card. The user's "wrong message" is this single message standing in for three distinct causes. Core cause high-confidence.
**Key files:** `ReaderViewModel.kt:550-563` (`adjacentChapter`), `:181-184` (unfiltered list), `:295-306`; `util/chapter/ChapterFilter.kt:44-85`; `ReaderActivity.kt:1267-1294` (`loadAdjacentChapter` toast); `NovelWebViewViewer.kt:3268` (hardcoded English inline card), `:2190-2303` (transition fallback).
**Approach:** No donor implementation exists (tsundoku also collapses reasons) — **design it**. Add a `ReaderViewModel` method that, when the next filtered slot is null, consults `unfilteredChapterList` to determine WHY and returns a small sealed result `{NextChapter | SkippedRead | FilteredOut | EndOfList}`. `loadAdjacentChapter` picks the matching localized string. Add moko strings (`novel_chapter_skipped_read`, `novel_chapter_filtered_out`). Replace the hardcoded English inline card + transition fallback with the same reason-aware strings.
**Verify:** mark a chapter read with skip-read on → next-nav shows "skipped (already read)" not "no next chapter".

#### `novel-skiplist` — Skip-read honored in novel continuous scroll  (PROCEED · M · med)
**What/why:** The novel infinite-scroll append/prepend loader builds its own transition cards (`buildTransitionCardHtml`) and never consults `alwaysShowChapterTransition` or skip logic — read chapters still get rendered/auto-transitioned in continuous scroll regardless of the manga toggle. The data filter is already shared (`ReaderViewModel:303`); the in-reader auto-advance is the gap.
**Key files:** `NovelWebViewViewer.kt` infinite-scroll append/prepend (`loadNext/PrevChapter`, `prependPreviousChapterIfAvailable`, ~`3254-3343`, `1886+`), `buildTransitionCardHtml` (~`1380+`); mirror manga `ViewerConfig.alwaysShowChapterTransition` (`PagerViewer:344`/`WebtoonViewer:231`).
**Approach:** Source the novel next/prev append/prepend loader from the **filtered** `chaptersForReader` list (not the raw chapter list) and gate transition-card rendering on `alwaysShowChapterTransition`, mirroring the manga viewer's config usage.
**Verify:** with skip-read on, scrolling past a read chapter does not auto-load/transition it.

### Group B — Novel TTS (file: `NovelTtsController.kt`, `NovelReaderActionBar.kt`, `ReaderActivity.kt`, `NovelWebViewViewer.kt`)
`tts-stop` and `tts-tapstart` are independent of Group A logic but `tts-tapstart` touches `NovelWebViewViewer.kt` — **sequence `tts-tapstart` after Group A** to avoid conflicts in that file. `tts-stop` is parallel.

#### `tts-stop` — In-reader TTS Stop affordance  (PROCEED · M · med)
**What/why:** Stop exists at controller/service/notification/lockscreen (all functional), but the in-reader UI collapsed to ONE icon whose only stop affordance is an undiscoverable long-press. `controller.toggle()` has no Speaking→Stop arm. User: "no way to stop." Discoverability gap, high confidence.
**Key files:** `hayai/novel/reader/bars/NovelReaderActionBar.kt:145-167,169-171`; `ReaderActivity.kt:1481-1497`; `NovelTtsController.kt:94-101` (`toggle()`).
**Approach (preferred, matches tsundoku):** Expand `NovelReaderActionBar` to render an inline TTS controls row when active — `[prev-para, play/pause, stop, next-para, read-from-here]` — modeled on tsundoku `NovelReaderAppBars.kt` `NovelTtsControlsOverlay` (`:611-657`). Wire stop → `novelWeb.stopTts()` + `stopBackgroundTtsIfRunning()`. Add a `Speaking→Stop` variant (or `toggleStop()`) to the controller. Keep notification/lockscreen stop as-is. Build the row from Hayai's own Compose components + M3 Expressive (not tsundoku primitives).
**Verify:** during playback an explicit Stop button is one tap away and stops the engine + clears highlight.

#### `tts-tapstart` — Tap-to-start-from-Idle gate fix  (PROCEED · S · med)
**What/why:** Tap-to-start is wired end-to-end (`startTtsAtParagraph` → `StartAt`, and `onStartAt()` handles cold start) BUT gated to only be live while TTS is already speaking/paused — a logical contradiction with the "tap to START" intent. From Idle a tap never starts TTS. High confidence.
**Key files:** `NovelWebViewViewer.kt:1179-1186` (`refreshSentenceTapToTtsState`), `:213` (`onSingleTapConfirmed` gate), `:1104` (click handler), `:3096-3114` (`startTtsAtParagraph`); `ReaderPreferences.kt:242` (`novelTtsTapToStart` default true).
**Approach:** Enable `__novelTtsClickEnabled` whenever `novelTtsTapToStart` is ON (regardless of TTS state), and in `onSingleTapConfirmed` forward center-zone taps to the WebView whenever the pref is ON. Keep the side/top nav-zone exclusions (`:1122-1125`) and text-selection skip to prevent accidental starts.
**Verify:** from Idle, tapping a paragraph starts TTS at that paragraph. (Tagging-coverage edge is a DEFER follow-up — see deferred questions.)

### Group C — Manga details (independent files)

#### `md-covercolor` — Cover-color toggle relabel + surface  (PROCEED · S · low)
**What/why:** A `themeMangaDetails` pref already gates all cover tinting but is mislabeled ("Theme buttons based on cover" — actually themes the whole screen) and has no on-screen control. User wants a discoverable toggle.
**Key files:** `data/preference/PreferenceKeys.kt` (`themeMangaDetails`), `ui/setting/controllers/SettingsAppearanceController.kt:175`, `ui/manga/MangaDetailsController.kt:338,364,407,631,678`, `res/menu/manga_details.xml`.
**Approach:** Relabel `themeMangaDetails` → "Color details from cover" and surface it on the details overflow menu. All gating already keys off `preferences.themeMangaDetails().get()`. The OFF-path fallback (currently blends `colorSecondary`) is a DEFER decision — keep the existing blend for now unless the deferred answer says otherwise.
**Verify:** toggling from the overflow menu live-removes cover tint from header/chapters/toolbar.

#### `md-ehmeta` — EH/ext metadata typography polish  (PROCEED · S · low)
**What/why:** Already a modern Compose port (ahead of TachiyomiSY's view-based adapters). Just needs spacing/typography polish under `MaterialExpressiveTheme`.
**Key files:** `ui/manga/MangaDetailCompose.kt:99` (`MangaMetadataSection`), `exh/ui/metadata/adapters/EHentaiDescription.kt` (+ siblings).
**Approach:** Polish spacing/typography; confirm it composes under the new global Expressive theme (after `theme-expressive`). Use SY only to cross-check which fields/labels to show.
**Deps:** `theme-expressive`. **Verify:** EH metadata renders with Outfit + Expressive spacing.

### Group D — Source browse (file: `BrowseSourceController.kt`, browse compose files, menu xml)
All four are small and self-contained; **one commit**.

#### `browse-display` — 3-mode display menu  (PROCEED · S · low)
**What/why:** The toolbar display-mode action is a binary list↔grid toggle; the compact-vs-comfortable grid choice lives in a `catalogueListType` pref the toolbar never surfaces.
**Key files:** `BrowseSourceController.kt:754` (`swapDisplayMode`), `res/menu/browse_source.xml`, `BrowseSourceItem.kt` (reads `catalogueListType`).
**Approach:** Replace the binary toggle with a 3-option dropdown (comfortable grid / compact grid / list) mirroring mihon `presentation/browse/components/BrowseSourceToolbar.kt` `RadioMenuItem` dropdown, wiring `browseAsList` + `catalogueListType` together. Keep the in-place `FilterMutations` contract intact.
**Verify:** all three modes selectable from the toolbar and persist.

#### `browse-theme` — Grid holder ExpressiveYokaiTheme  (PROCEED · S · low)
**What/why:** `BrowseSourceGridHolder` uses `YokaiTheme` while the rest uses Expressive — visual inconsistency.
**Key files:** `ui/source/browse/BrowseSourceGridHolder.kt`.
**Approach:** Switch from `YokaiTheme` → the (now-unified) global Expressive theme. After `theme-expressive` folds the two, this is just confirming the grid holder uses the global wrapper.
**Deps:** `theme-expressive`. **Verify:** grid cells match list/library theming.

#### `browse-group` — Group drill renders nested Group/Sort  (PROCEED · S · low)
**What/why:** Group drill-down only renders CheckBox/TriState/Select/Text children (`else->Unit`); a nested Group or Sort silently renders nothing.
**Key files:** `ui/source/browse/compose/SourceFilterSheetContent.kt:488`.
**Approach:** Add Group/Sort branches to the drill-down `when`. Keep `FilterMutations` in-place mutation intact.
**Verify:** a source with a nested Group/Sort renders its children.

#### `browse-sheetsize` — Responsive filter-sheet height  (PROCEED · S · low)
**What/why:** Fixed `peekHeight=560dp` / `BodyMaxHeight=400dp` ignore screen size (too tall on short screens, cramped on tall).
**Key files:** `ui/source/browse/compose/ComposeSourceFilterSheet.kt`, `SourceFilterSheetContent.kt`.
**Approach:** Derive peek/body height from `LocalConfiguration` screen height instead of fixed dp.
**Verify:** sheet sizes sensibly on a short (≤600dp) and a tall screen.

---

# Phase 2 — DATA-LAYER PERFORMANCE [parallel, data/presenter only — no UI]

All PROCEED, all in `data/`/presenter layer (no UI redesign). `db-n1`, `db-paging`, `db-batch` touch overlapping SQLDelight/handler files — **serialize the schema/handler edits, parallelize the presenter edits**.

### `db-n1` — Kill library N+1 (batched track flow)  (PROCEED · L · med)
**What/why:** `LibraryPresenter` re-derives data already on the `library_view` row by issuing per-manga `getChapter`/`getHistory`/`getTrack` queries inside filter/grouping passes over the whole library — classic N+1 fired on every reload when a tracking/start-year filter or BY_TRACK_STATUS group is active. Highest impact, high confidence.
**Key files:** `ui/library/LibraryPresenter.kt:533,574-575,598,1092`; `yokai/domain/track/TrackRepository.kt` (only `getAllByMangaId`, no batch); `data/.../sqldelight/tachiyomi/data/manga_sync.sq`; `data/.../view/library_view.sq`.
**Approach (port mihon shape verbatim):** (a) Replace `getStartYear`'s per-manga chapter/history reads with values already on `LibraryManga` (`has_read`/`lastRead`); add a `start-year` column to `library_view` only if needed. (b) Extend `TrackRepository` with `getAll()/getAllAsFlow()` backed by a new `getTracks` query in `manga_sync.sq`; add a `GetTracksPerManga` interactor (mihon `GetTracksPerManga.kt` shape) returning `Flow<Map<Long,List<Track>>>`; load ONCE per reload; have `matchesCustomFilters`/`matchesFilterTracking`/`BY_TRACK_STATUS` look up `tracksByManga[id]` from the map. Reference: mihon `LibraryScreenModel.getFavoritesFlow():386-409` reads counts straight off the view row, never per-manga.
**Verify:** with a tracking filter active, library reload issues one track query, not N.

### `db-paging` — Restore paging primitive  (PROCEED · M · med)
**What/why:** Paging is dead — `DatabaseHandler.subscribeToPagingSource` and `AndroidDatabaseHandler.QueryPagingSource` are commented out, so every list query materializes its full result set.
**Key files:** `data/src/androidMain/.../AndroidDatabaseHandler.kt:88-103`, `data/src/commonMain/.../DatabaseHandler.kt:48-54`; back recents (and optionally long chapter lists) with the existing LIMIT/OFFSET queries.
**Approach:** Un-comment `QueryPagingSource`/`subscribeToPagingSource`, wire `androidx.paging`, back recents with a `PagingSource`. Reference: mihon keeps bounded view queries consumed via combine flows.
**Verify:** recents loads incrementally; large history no longer fully materialized.

### `db-batch` — Batch bulk writes  (PROCEED · M · med)
**What/why:** `insertBulk`/`updateAll`/`setMultipleMangaCategories` loop row-by-row inside a transaction rather than batched statements.
**Key files:** `yokai/data/chapter/ChapterRepositoryImpl.kt:88-134,158-178`, `yokai/data/manga/MangaRepositoryImpl.kt:128-134`, `data/.../chapters.sq`.
**Approach:** Keep the single transaction but prefer a single `UPDATE … WHERE _id IN (:ids)` over the per-id loop for read/bookmark toggles; verify SQLDelight statement reuse for inserts. Reference: mihon `ChapterRepositoryImpl` `transactionWithResult` + `awaitAsList`.
**Verify:** bulk mark-read of N chapters issues one UPDATE.

---

# Phase 3 — NET-NEW FEATURES [parallel — each lands in its own area]

### `inapp-browser` — Partial Custom Tab sheet for selection actions  (PROCEED · M · low)
**What/why:** Selecting text → Translate/Web Search/Define fires external `ACTION_PROCESS_TEXT`/`ACTION_WEB_SEARCH` intents that context-switch out of Hayai. User wants results in an in-app sheet using their browser engine. Infra already present: `androidx.browser:1.8.0` on classpath, `Context.openInBrowser()` wraps `CustomTabsIntent`.
**Key files:** `util/system/ContextExtensions.kt:263,332`; `NovelWebViewViewer.kt:1518,1573,1632` (the three `launch*Intent` bodies to REPLACE), `:1491,1548,1607` (selection readers to KEEP), `:386-576` (action-mode dispatch), `:3804` (menu IDs); `gradle/androidx.versions.toml:14`.
**Approach (primary = Custom Tabs, matches "user browser engine"):** Add `openInBrowserSheet(url, heightPx)` to `ContextExtensions.kt` building a `CustomTabsIntent` with `setInitialActivityHeightPx(heightPx, ACTIVITY_HEIGHT_ADJUSTABLE)`, themed toolbar color, corner radius (per developer.chrome.com partial-custom-tabs guide). Replace the three `launch*Intent` bodies so each builds a URL and calls `activity.openInBrowserSheet(url)`: Define → define search URL; Translate → translate URL; Web Search → search URL. Keep the async `getSelection()` readers unchanged. Delete the external-intent bodies + their `*_no_handler` strings. No reference clone implements partial tabs (all use full Custom Tabs) — this follows the Chrome guide.
**Deps:** none. **Verify:** selecting text → Translate opens a bottom-sheet Custom Tab in the default browser; app is not backgrounded. (`inapp-identify` and the embedded-WebView fallback are DEFER — see questions.)

### `md-multiselect` — Chapter multi-select (mihon model)  (PROCEED · L · med)
**What/why:** Confirmed feature gap — Hayai has only transient 2-tap RangeMode + bulk menu items; no persistent checkbox multi-select, no select-all/invert, no multi-action contextual bar. The selection-visual primitive already exists (`ChapterHolder.bind:99` renders CHECKED when `adapter.isSelected()`), only used transiently. High confidence, well-referenced.
**Key files:** `ui/manga/MangaDetailsController.kt:1002-1099,1601-1616,1967-2022` (range action mode), `ui/manga/chapter/ChapterHolder.kt:99-104`, `ui/manga/MangaDetailsAdapter.kt`, `res/menu/manga_details.xml`.
**Approach (port mihon selection model onto FlexibleAdapter):** FlexibleAdapter already supports `SelectableAdapter.MULTI`. Replace the long-press `MaterialMenuSheet` with: long-press = enter selection mode + select that row; tap-in-mode = toggle; long-press a second row = select in-between range (mihon `MangaScreenModel.toggleSelection(item, selected, fromLongPress)` `:961` + `selectedPositions` trick). Add a contextual ActionMode with download/delete/bookmark/mark-read/mark-unread + overflow select-all (`toggleAllSelection` `:1024`)/invert (`invertSelection` `:1036`) — mirror `MangaBottomActionMenu.kt`'s action set. Update `ChapterHolder` to show activated state in selection mode. Whether this fully replaces RangeMode is a DEFER decision; default to **coexist** (keep custom-range menu entries) unless told otherwise.
**Deps:** none. **Verify:** long-press → contextual bar with count; range-select via second long-press; select-all/invert work; each bulk action applies to the set.

### `smart-update` — FetchInterval port + smart-skip restriction  (PROCEED · L · med)
**What/why:** Never existed in the Yokai fork base. No per-manga fetch-interval learning, no predicted next-release, no "skip outside release window." Confirmed by grep (`next_update`/`fetch_interval`/`calculateInterval` match nothing). mihon/SY implement it fully.
**Key files:** `data/library/LibraryUpdateJob.kt:477-518,711-742`; `data/.../sqldelight/tachiyomi/data/mangas.sq:3-23,65-85`; `domain/.../manga/models/MangaUpdate.kt`; `yokai/data/manga/MangaRepositoryImpl.kt:65-92`; `yokai/domain/manga/interactor/UpdateManga.kt`; `util/chapter/ChapterSourceSync.kt`; `data/database/models/Manga.kt`; `data/preference/PreferencesHelper.kt:215-217`; `ui/setting/controllers/SettingsLibraryController.kt:150-205`.
**Approach (port mihon line-by-line into data/domain):** (1) New migration `33.sqm` adding `next_update INTEGER` + `fetch_interval INTEGER DEFAULT 0` to `mangas`; add binds to the `update:` query (mihon coalesce pattern). (2) Add fields to `Manga`/`MangaImpl`/`copyFrom`/mapper + `MangaUpdate` + `MangaRepositoryImpl.partialUpdate()`. (3) Copy `FetchInterval.kt` **verbatim** (mihon/SY identical) into a Hayai domain package, adapting imports to `yokai.domain` models + `GetChapter`; add `awaitUpdateFetchInterval()` to `UpdateManga`. (4) In `ChapterSourceSync.syncChaptersWithSource` call `awaitUpdateFetchInterval` after computing new chapters (mihon `SyncChaptersWithSource.kt:143`); in `LibraryUpdateJob.filterMangaToUpdate` add a `MANGA_OUTSIDE_RELEASE_PERIOD` branch skipping manga whose `next_update > upperBound` via `getWindow()`. (5) Add the `MANGA_OUTSIDE_RELEASE_PERIOD` pref constant + checkbox in `SettingsLibraryController`. (6) Add the 7 mihon strings verbatim. Novels covered automatically (same `LibraryUpdateJob` path). **Ship as opt-in** restriction so existing fixed-interval behavior is unchanged unless enabled (default-on vs opt-in is a DEFER question; default to opt-in to preserve behavior).
**Deps:** DB migration. **Verify:** with the restriction on, a manga whose predicted next-update is in the future is skipped; `fetch_interval` is recomputed after a chapter sync.

### Novel parity reader-config ports — `novel-window`, `novel-brightness`, `novel-thresholds`, `novel-csspriority` [batch into ONE iteration]
Each adds one pref to `ReaderPreferences.kt` (verbatim tsundoku key names) + wiring in `NovelWebViewViewer.kt` + a row in `TabbedNovelReaderSettingsSheet`/`NovelPageModels`. They share these files → **single serial iteration, one commit.**

#### `novel-window` — Infinite-scroll chapter-window cap  (PROCEED · M · med)
**What/why:** Infinite scroll is hardcoded always-on (`infiniteScrollActuallyEnabled=true`) and never trims off-screen chapters → unbounded DOM/`loadedChapters` growth → likely perf/OOM cliff on long novels. tsundoku caps via `novelKeepChaptersLoaded`.
**Key files:** `ReaderPreferences.kt:85-253`, `NovelWebViewViewer.kt` (append/prepend, `injectScrollTracking`); tsundoku `NovelConfig.kt`/`NovelWebViewViewer.kt`.
**Approach:** Add `novelKeepChaptersLoaded` (Int, 0=unlimited) + optional `novelInfiniteScroll` toggle (verbatim keys). After append/prepend, when `loadedChapters.size` exceeds the window, remove the farthest off-screen chapter's DOM node and drop it from `loadedChapters`/`loadedChapterIds`/`chapterParagraphsById`.
**Verify:** with a cap of N, scrolling far keeps only N chapters in the DOM.

#### `novel-brightness` — Reader custom brightness  (PROCEED · S · low)
**Key files:** `ReaderPreferences.kt`, `ReaderActivity.kt` (apply when a novel viewer is active); tsundoku `ReaderPreferences.kt:270-273`, `NovelPage.kt:495-509`.
**Approach:** Add `novelCustomBrightness`/`novelCustomBrightnessValue` (verbatim); apply via `WindowManager.LayoutParams.screenBrightness` when a novel viewer is active (reuse Hayai's manga equivalent if present). **Verify:** brightness slider changes screen brightness live in the novel reader.

#### `novel-thresholds` — Auto-load + mark-read thresholds  (PROCEED · M · med)
**Key files:** `ReaderPreferences.kt`, `NovelWebViewViewer.kt` (`injectScrollTracking` hardcoded `AUTO_LOAD_NEXT_THRESHOLD`/98%); tsundoku `ReaderPreferences.kt:325,328,331`.
**Approach:** Add `novelAutoLoadNextChapterAt`, `novelMarkAsReadThreshold`, `novelMarkShortChapterAsRead` (verbatim); replace the hardcoded threshold constants with pref-driven values; mark-short-chapter handles tiny chapters that never reach the scroll threshold. **Verify:** thresholds configurable and respected.

#### `novel-csspriority` — Source-CSS-priority / EPUB style preservation  (PROCEED · S · low)
**Key files:** `ReaderPreferences.kt`, `NovelWebViewViewer.kt` (CSS injection); tsundoku `NovelWebViewStyler.kt:81-87,219`.
**Approach:** Add `novelSourceCssPriority` (verbatim); when on, drop the `!important` flags + font-override CSS so source/EPUB styling wins. Completes the existing `enableEpubStyles`. **Verify:** with the toggle on, EPUB/source CSS is not overridden.

---

# Phase 4 — CROSS-CUTTING POLISH / REDESIGN [serial after Phase 0; sheet work is XL]

### `settings-observer` — Novel pref observer rewrite (live-apply completeness)  (PROCEED · M · med)
**What/why:** `NovelWebViewViewer.observePreferences()` (`707-865`) is a hand-maintained allowlist subscribing to only a SUBSET of novel prefs — any pref not in the list needs a chapter reload to take effect. Confirmed gaps. Reload-requiring prefs can silently no-op because `setChapters()` early-returns when `loadedChapterIds.contains(chapterId)`.
**Key files:** `NovelWebViewViewer.kt:707-865,768,782,789,822,1772`; `TabbedNovelReaderSettingsSheet.kt`; tsundoku `NovelWebViewPreferenceObserver.kt` + `NovelWebViewStyler.kt`.
**Approach (port tsundoku verbatim):** Refactor `observePreferences()` into a dedicated `NovelWebViewPreferenceObserver` (port tsundoku's class line-by-line) enumerating EVERY novel pref the sheet exposes and routing each to style/script/reload/blockMedia/tts callbacks. Audit the sheet's full pref list against the observer to close gaps. For reload-requiring prefs, ALWAYS clear `loadedChapterIds` before `setChapters()` (as the `hideChapterTitle` path already does at `:789`) so the loaded chapter actually re-renders.
**Deps:** Group A novel-viewer edits (serialize — same file). **Verify:** every sheet toggle/slider applies live without exiting the chapter.

### `gsearch` — Manga-title global search self-pop fix  (PROCEED · M · med)
**What/why:** `GlobalSearchController` was converted to a `LocalAppBarOwner` with `hostsOwnAppBar=true` + its own wired search toolbar, but `MainActivity` still keeps a global `searchToolbar` collapse listener that calls `onActionViewCollapse → router.popCurrentController()`. A stray collapse can self-pop GlobalSearch back toward the library. The `controllerChangeInProgress` guard is a fragile band-aid. (The "which menu item" candidate is the `gsearch-menu` DEFER; the LocalAppBarOwner regression is the core fix and PROCEEDS.)
**Key files:** `ui/source/globalsearch/GlobalSearchController.kt:66,89,245`, `ui/main/MainActivity.kt:680-697,685,1477,1528-1529`, `util/view/ControllerExtensions.kt:911-922`.
**Approach (fix the core invariant — LocalAppBarOwner owns its own search collapse):** Gate `MainActivity`'s activity-global `searchToolbar` collapse dispatch (`680-697`) so `LocalAppBarOwner` controllers are SKIPPED — they manage their own search lifecycle, mirroring how `appBar()/searchToolbar()` already route locally for them. Verify `wireSearchToolbar`'s `expandActionView()` doesn't trigger a spurious activity-toolbar collapse. Do NOT widen the `controllerChangeInProgress` band-aid. Reference: yokai `GlobalSearchController` (no LocalAppBarOwner) reads `activityBinding?.searchToolbar` directly.
**Deps:** none (but coordinate with `appbar-*` since both touch app-bar routing). **Verify:** title-tap → Global Search → screen stays put and renders results; does not bounce to library.

### `appbar-measure` — Hoist per-frame measure off scroll hot path  (PROCEED · S · med)
**What/why:** `updateAppBarAfterY` runs on every scroll frame and calls `preLayoutHeightWhileSearching → getEstimatedLayout` which runs `bigTitleView.measure()` with a fresh MeasureSpec each call — per-frame TextView measure on the scroll hot path is a jank source. This sub-fix is isolated and safe; the broader smoothness rework (`appbar-smooth`) defers pending a repro.
**Key files:** `ui/base/ExpandedAppBarLayout.kt:389,447-549`, `util/view/ControllerExtensions.kt:320-681`.
**Approach:** Cache `preLayoutHeight` and invalidate only on layout/config/tab change, not on every `updateAppBarAfterY`. **Verify:** no `measure()` call during steady-state scroll (trace).

### `sheet-adaptive` — Port AdaptiveSheet, unify sheet stack  (PROCEED · XL · med)
**What/why:** Two fully parallel sheet paradigms — legacy XML `E2EBottomSheetDialog`/`TabbedBottomSheet`/`MaterialMenuSheet` vs Compose `ModalBottomSheet` (~20 distinct sources) — plus 3 dialog stacks. No shared primitive. mihon/tsundoku solved this with ONE `AdaptiveSheet`.
**Key files:** `widget/E2EBottomSheetDialog.kt`, `widget/TabbedBottomSheet.kt`, `ui/base/MaterialMenuSheet.kt`, `hayai/novel/reader/settings/NovelReaderSettingsSheet.kt:114,898`, `ComposeSourceFilterSheet.kt`, `yokai/presentation/component/`; mihon `presentation-core/.../components/AdaptiveSheet.kt` + app-level `eu.kanade.presentation.components.AdaptiveSheet.kt`.
**Approach (incremental — per MEMORY, NOT big-bang):** Port mihon's `AdaptiveSheet.kt` **verbatim** into `yokai.presentation.component` + the app-level `isTabletUi` wrapper (feeds from `LocalConfiguration`). Migrate Compose `ModalBottomSheet` call sites first (novel reader settings/quotes/find-replace, source filter — cheap). Then incrementally replace legacy `E2EBottomSheetDialog`/`TabbedBottomSheet`/`MaterialMenuSheet` usages per-screen with `AdaptiveSheet` + Expressive content. Standardize on one dialog primitive (Compose `AlertDialog` via a shared wrapper) over time. **Do this incrementally across commits** — the migration appetite (big-bang vs incremental) is settled as incremental by the MEMORY rule; only the *worst-offender "sheets-in-sheets" naming* defers (see questions).
**Deps:** `theme-expressive`. **Verify:** migrated sheets adapt phone↔tablet; no visual regression on the first batch.

---

# Deferred — needs user input

These cannot be implemented correctly without a repro or a preference decision. Each has one precise question. The orchestrator should surface these; do not guess.

- **`theme-icons`** — Confirm heroicons specifically (outline vs solid set), whether to add a Compose icon dependency vs vendoring SVGs as `ImageVector`, and which Material style family (Outlined?) should be the fallback for glyphs heroicons doesn't cover — and which of the 6 existing `AnimatedVectorDrawable`s (reader, manga header, overflow menu, filter sheet, download button, image-view) you want kept-animated vs replaced with static modern icons?
- **`settings-deadsheet`** — Should the dead Compose `NovelReaderSettingsSheet.kt` be deleted, or do you want to migrate the novel sheet TO Compose (retiring the View-based `TabbedNovelReaderSettingsSheet`)?
- **`settings-merge`** — Which specific options do you expect shared between the manga and novel readers (keep-screen-on, font size, theme, orientation, brightness), or do you want a single unified settings screen for both?
- **`lib-flash` (repro for the secondary candidates)** — The core flash fix proceeds, but to confirm it fully: does the flash happen on EVERY library entry (bottom-nav swap), only on pop-back from manga details, only on app resume, or only when editing the search field?
- **`lib-filtersheet`** — Is "filtering not working" in tabbed mode or continuous mode, and which filter (unread/downloaded/tracked/type) — does the count change but the list not, or neither?
- **`lib-search`** — Does typing produce no change at all, or wrong/too-few matches (the `FuzzyMatcher` threshold-70)? In tabbed mode does it filter the current tab, all tabs, or flatten — and is `librarySearchAcrossTabs` on?
- **`appbar-smooth`** — Which exact screen shows the jank (Library/Recents/Browse/manga details/settings — different paths), is it during finger-drag collapse or the release/snap or both, at fast flings vs slow drags, and on what device/refresh rate (60 vs 90/120Hz)?
- **`appbar-behavior`** — Appetite for the Tier-B rewrite (attach real Material `AppBarLayout.Behavior` to the 3 root tabs and delete the manual `appBar.y` math) vs Tier-A polish on the existing View pipeline?
- **`gsearch-menu`** — When you tap the manga title and pick the search action, which exact entry do you tap — "Global search" or "Search library" (they sit adjacent)? And does the Global Search screen ever briefly appear before you land back in the library?
- **`md-actionrow`** — Keep the action-button row as a View/XML row (faster) or migrate it to a Compose M3 Expressive button group (consistent with the existing Compose islands)?
- **`md-trackinput`** — Which exact field has "no size/background/styling" — the search box in the tracking sheet, or the date/chapters/score dialogs (`track_chapters_dialog.xml` / `track_score_dialog.xml`)? A screenshot would pin it.
- **`md-multiselect` (scope only — core proceeds)** — Should chapter multi-select FULLY replace the current long-press `MaterialMenuSheet` + 2-tap RangeMode, or coexist? (Defaulting to coexist.)
- **`browse-filter`** — Which surface specifically feels unpolished — the (already-redesigned) filter sheet, the result grid/list, or the toolbar/floating bar? And should filters be reachable from the toolbar as well as the floating button bar? Is a full Compose rewrite of the browse result grid (retiring FlexibleAdapter) in scope this pass?
- **`inapp-identify`** — What should "Identify" resolve to: Google Lens, a reverse-image/images search of the selected text, a Wikipedia/encyclopedia lookup, or an AI lookup? And should the search/translate target engines be user-configurable (a pref) or hardcode Google for now? Keep the external-intent paths as a fallback, or remove entirely?
- **`db-bindcost`** — Which screen lags (MangaDetails chapter list / library grid / recents) and roughly how many items (500 vs 2000+)? Does it reproduce with tracking filters OFF? A Perfetto/systrace of a laggy scroll would confirm bind-cost vs query vs GC.
- **`smart-update-ui`** — Do you want the per-manga "Expected next update" badge + custom-interval dialog on the manga details screen, or just the background smart-skip behavior for now? And should "Predict next release time" be ON by default (mihon ships default-on) or opt-in?
- **`novel-bottombar`** — Do you want the configurable reader bottom bar (port tsundoku's `BottomBarItem` list + drag-to-reorder editor sheet), and is that a priority vs the reader-config polish items?
- **`novel-stats`** — Do you want a true novel reading-stats screen (word count, reading speed, time-per-novel)? Word count requires a new per-chapter DB column — acceptable, or keep novel stats to chapter counts only?
- **`novel-presets`** — For saved reader style presets, is a simple named-snapshot of current style prefs enough, or do you want per-source/per-novel preset binding? (Note tsundoku's `novelGlobalPresets` pref is effectively dead — design fresh.)

---

## Open questions / decisions

- **Library long-term direction (`lib-*`):** keep the bespoke tabbed View mode and harden it (Tier A — the flash/counts fixes here), or move toward mihon's single-pager / Compose model (Tier B, larger)? Tier A proceeds now; Tier B is a future decision.
- **`themeMangaDetails` OFF-path:** when cover-color theming is off, should the screen use plain M3 colors, or keep the current `colorSecondary` blend? (Proceeding with the existing blend; flag for confirmation.)
- **Outfit font scope:** apply to ALL text roles including reader content, or app-chrome only (the reader content font is separately user-selectable)? (Proceeding with app-chrome only; reader content font untouched.)
- **Smart-update default:** shipping as opt-in `MANGA_OUTSIDE_RELEASE_PERIOD` to preserve current fixed-interval behavior unless the user enables it; confirm if default-on is preferred.
- **Novel infinite-scroll cap default:** shipping `novelKeepChaptersLoaded=0` (unlimited) by default to match current behavior; the cap is opt-in.
- **Build discipline:** Phase 0 builds once at the end; each subsequent phase builds once after its batch. Never two gradle builds at once (parallel agents share this repo).
