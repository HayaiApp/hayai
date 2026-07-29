# Changelog

All notable changes to this project will be documented in this file.

> **Note:** Hayai is a fork of [Yokai](https://github.com/null2264/yokai). The changelog below reflects upstream Yokai changes prior to the fork.

The format is simplified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/):
- `Additions` - New features
- `Changes` - Behaviour/visual changes
- `Fixes` - Bugfixes
- `Other` - Technical changes/updates

## [Unreleased]

> **Rokku maintenance sync:** Extension-ecosystem compatibility and the maintenance fixes below
> were ported from [Rokku](https://github.com/rokku-app/rokku) through v1.2.5. Many thanks to
> Thiago Rocha, Hiirbaf, Sacha1016, and the Rokku contributors for keeping the Yokai lineage current.

### Rokku maintenance
- Support current Keiyoushi/TachiyomiX extension metadata, extension-lib 1.6, `repo.json`/`index.pb` repository URLs, and protobuf `index_v2` extension indexes
- Adopt the combined source update API while retaining Hayai's built-in and novel-source compatibility
- Update Kotlin to 2.4.10, Compose BOM to 2026.06.01, Okio to 3.18.0, and keep OkHttp Brotli/Zstd extension compatibility
- Raise the minimum Android version to 8.0 (API 26) to match current extension bytecode requirements
- Add selectable backup restore content, extension-repository backup/restore, source suggestions, and an optional SSIV long-strip decoder override
- Rewrite the Shizuku installer around real `PackageInstaller` sessions and harden install/update confirmation fallbacks
- Resume interrupted page downloads, preserve downloads across the “Download with ID” toggle, and reduce grouped-notification churn
- Persist extension chapter/manga memo metadata and propagate combined manga details during library updates
- Port library, manga-details, and migration performance fixes, plus webtoon split/decode stability fixes
- Update Shikimori to its current domain and complete the remaining pt-BR filter translation

### Additions
- Add **Library display onboarding step** during setup that lets users choose between continuous or tabbed library view modes with visual preview cards
- Add **Library update report screen** that displays structured results from library updates, including per-title errors, skipped entries, and reasons, accessible from Library settings and Recents. Report can be accessed via notification or viewed in-app with option to open full log file
- Add **NHentai tag assets** (artist, character, group, parody, tag, language) with prebuilt JSON lists for local filtering and search without external API calls
- Add **Reduce motion support** throughout the app: process-wide ValueAnimator duration scale toggle (Settings → Appearance → Motion) that disables Conductor transitions, Compose animations, Coil image crossfades, and per-Activity transitions when enabled. Reflection fallback for hidden API; per-site fallbacks where unavailable
- Add **Novel reader accessibility & UX improvements**:
  - Previous-chapter infinite-scroll with JS bridge, static top card, and automatic scroll-past to prevent duplicates
  - 3×3 progress slider positioning (top/center/bottom × left/center/right) with backward-compatible migration from old single position
  - Novel-specific default orientation preference (separate from manga) with per-series override
  - Chapter transition card between appended chapters (mirrors manga reader style)
  - Smooth auto-scroll with sub-pixel accumulation, pause/resume on touch, configurable speed
  - Tap-to-scroll gating by preference for both text and WebView viewers
  - Sentence-tap-to-TTS JS handler for WebView
  - Overlay scrollbar for WebView with state driven from scroll metrics; native scrollbar disabled
  - Reverse portrait orientation option
  - Improved WebView scroll restore using requestAnimationFrame polling
  - Text-selection quote menu: persistent localized item, always-show action, stable re-add
- Add **Recommendation system enhancements**:
  - Explicit recommendation content type handling (Manga/Novel) with UI badge
  - NovelList paging source for novel recommendations
  - NovelUpdates marked as novel content type with preference for linked tracker URL
  - AniList and MyAnimeList filter recommendations by viewed entry's seriesType to avoid mixing manga vs novel
- Add **Tabbed library view** as a second display mode in Settings → Library → Display. The continuous (single scroll, headers between categories) layout stays the default; tabbed renders each category on its own swipeable page with a pinned top tab strip and a per-tab item-count badge. Each tab opens at the top of its own content, the appbar collapses with per-tab scroll like the regular library, and pull-to-refresh updates only the active tab's category — refreshing the whole library moved into a new "Update library" entry in the toolbar overflow (3-dots). Custom `HorizontalAwareSwipeRefreshLayout` keeps SwipeRefreshLayout from stealing horizontal swipes, and the presenter ignores the continuous-mode collapsed-categories flag in tabbed mode so every category gets a tab. New `HorizontalEagerViewPager` lowers the pager's internal `mTouchSlop` to `scaledTouchSlop` so horizontal swipes activate at the same threshold as the inner recycler's vertical claim — matching Compose `HorizontalPager` responsiveness.
- Add **novel reader regex find-and-replace** with a full editor in the in-reader settings sheet's Advanced tab. Each rule has a title, pattern, replacement, regex-vs-literal toggle, and a live test field that previews the result against arbitrary sample input. Rules are applied in order and persisted as JSON in `pref_novel_regex_replacements`. Ships with four prefilled (disabled-by-default) rules covering common scraped-text artifacts: zero-width characters, repeated promotional URLs, "Read more at …" footers, and mid-paragraph site watermarks.
- Add **novel reader text normalization** under the regex editor, with two switches:
  - **Normalize text** (default on): runs each chapter through Java's `Normalizer.Form.NFKC`, strips combining marks (`\p{M}+`) and zero-width chars (`U+200B`–`U+200D`, `U+2060`, `U+FEFF`), then folds a hand-curated table of 14 lowercase Cyrillic/Greek homoglyphs (`а→a`, `е→e`, `ο→o`, `ν→v`, …) to their Latin counterparts. Catches obfuscated promotional text like `frеewebnovel.com` (Cyrillic `е`) that survives plain regex matching.
  - **Aggressive cleanup** (default off): additionally drops every non-ASCII codepoint after normalization. Off by default because it deletes legitimate non-Latin content; surfaced for users reading purely English-translated novels heavily polluted with non-Latin obfuscation.
- Add per-tab category-count badge to the tabbed library's tab strip (`library_tab_with_count.xml`) and reorganize the toolbar overflow to surface "Update library" only in single-category / tabbed contexts where pull-to-refresh is per-category.
- Add **Reduce motion** toggle in Settings → Appearance → Motion. Disables Conductor controller transitions, Compose animations (skeleton shimmers etc.), Coil image crossfade, and per-Activity open/close transitions in one switch — aimed at low-end devices, motion-sensitive users, and battery saver. Single source of truth (`ReducedMotion.isEnabled()` / `LocalReducedMotion`); call sites stay clean.
- Add E-Hentai / ExHentai **category badges** to the browse list and grid (Doujinshi / Manga / Image Set / Artist CG / Cosplay / Game CG / Western / Non-H / Asian Porn / Misc), color-coded.
- Add LNReader `parsePage` support for novel sources that paginate chapter listings (rewayatclub, lnmtl, sunovels, lightnoveltranslation, …) — full chapter list is now fetched, not just page 1.
- Add source navigation from recommendation card headers (tap a source header to open `BrowseRecommendsScreen` for that source)
- Add `MetadataMangasPage` and Compose-based genre tags on manga details
- Migrate manga details grid and color filters to Compose
- Add migration parallelism with concurrency limits for faster batch source migration (1–10 concurrent, configurable in Advanced settings)
- Add NovelUpdates dedicated parser and refactor of the NovelUpdates source
- Add novel plugin cache and improved plugin manager integration
- Browse-page filter (Settings → Sources from the browse menu) now lists novel sources alongside manga, grouped under their language. Novel icons are loaded from the plugin's `iconUrl` via Coil with a `ic_book_24dp` fallback.

### Changes
- Library update report is now accessible from the Recents display options sheet (file-open icon in the tab dialog header) instead of the toolbar overflow, matching the pattern used by the library display sheet's settings button
- Library update report app bar now hides on scroll (`enterAlwaysAppBarScrollBehavior`), matching other screens
- Migrate all help and documentation links from `tachiyomi.org` to `mihon.app` (Mihon is the actively maintained Tachiyomi fork)
- Novel browse pagination now follows the LNReader contract: keep loading until the plugin returns an empty list (was hard-stopped at page 1).
- Tolerate plugins that emit `genres` joined with `","` instead of `", "` — they used to render as one big chip; now they split correctly. Duplicates and inconsistent casing are deduped at the novel parser too.
- Novel summary is now HTML-stripped (preserving paragraph breaks) for plugins that return raw markup per the LNReader contract.
- Improve page previews: bigger thumbnails, skeleton loading, and infinite scroll
- Reduce top spacing for app bar title
- Recommendation cards now use a transparent container color to better blend with the surface
- Replace the source filter sheet with a hardened, full-height Compose refinement flow: isolated filter drafts, recursive search, removable active constraints, undo, and extension-reorder-safe saved presets
- Keep library multi-selection stable across filtering, category pages, row refreshes, and mutable manga metadata by tracking selected database IDs
- Consolidate Recents and download-sheet queue observation under one lifecycle-aware presenter, eliminating the duplicate download presenter and its competing listeners
- Fall back to the system installer on MIUI devices when in-app install is unreliable
- Per-language source toggle in the browse-page filter renamed to **Select all** (was "All sources") so it reads as a UI control rather than a phantom iconless source.
- App icons & branding assets refreshed for all density buckets and flavors (main, debug, nightly).

### Changes
- Novel browser pagination follows LNReader contract: keep loading until plugin returns empty list
- Plugins emitting genres joined with `","` instead of `", "` now split correctly
- Novel summary HTML is stripped (preserving paragraph breaks) for plugins returning raw markup
- Remove legacy native NovelViewer; consolidate all novel reading to WebView-based viewer
- Consolidate novel TTS_VIEWPORT button into main TTS control for cleaner bottom bar
- Novel layout preference (Display tab) renamed from Appearance to match manga terminology
- Remove legacy infinite-scroll toggle and related UI (next-chapter button, chapter sort, short-chapter auto-marking) — continuous chapter loading is now always-on
- Overlay menu dispatch: library 3-dot menu now shows in MainActivity's global overflow dialog instead of separate popup; "Update library" action unified with other app actions
- NovelList tracker: correct tracking_url to use `/novels/<slug>#<uuid>` path and preserve server-side status/rating when binding
- Preference migration: convert old single `novelProgressSliderPosition` value to 3×3 grid coordinate system with backward compatibility
- Performance optimization: load novel plugins asynchronously to avoid blocking initial source map emission
- Page preview: ensure ImageLoader key survives state updates to prevent thumbnail flickering
- AppBar color-elevation animator gating for per-tab scroll to avoid unnecessary cancel/restart and visual flicker
- Improve NovelList/Supabase session cookie parsing: handle chunked cookies split across novellist, novellist.0, novellist.1, ... and decode Base64-prefixed payloads
- NovelUpdates recommendations: detect linked tracker URL and prioritize it
- AniList/MyAnimeList recommendations: detect viewer's seriesType and filter recommendations to avoid mixing manga/novel results
- EHentai gallery parsing: ignore optional favorite-color border div, prefer class-based category selection

### Fixes
- **Fix chrome disappearing or becoming unresponsive after tab changes and activity resume.** Roll back the centralized chrome reset and cached layout geometry so top bars, search controls, tabs, and bottom-sheet navigation are restored and measured by their owning screen
- **Fix library update report page always showing empty.** `createFileInCacheDir` writes to `externalCacheDir` but `read()` was reading from `cacheDir` — the JSON was saved but never found. `save()` now writes directly to `cacheDir` (matching `read()`), and `errorLogFile()`/`skippedLogFile()` now read from `externalCacheDir` to match where `writeErrorFile()` puts the `.txt` logs
- **Fix duplicate three-dot overflow menus in Recents.** `action_update_report` (overflow item) and `action_more` (custom three-dot toolbar button) were creating two separate overflow menus simultaneously. Both removed; the update report is now accessible from the display options sheet
- **Fix permission step onboarding not updating Grant button state after returning from Settings.** Switched from `LifecycleEventEffect` (which deferred re-checks until composition) to `DisposableEffect` + `DefaultLifecycleObserver` so install/notification/battery permission checks fire on each resume and button state updates immediately
- **Fix tabbed library appbar not pinned to smallHeight when scrolled.** Fast scroll deltas or residual offsets were pushing appBar.y past smallHeight into the fully-hidden zone, making search/tabs/toolbar disappear. Clamp appBar.y to smallHeight before `updateAppBarAfterY` runs
- **Fix tabbed library appbar not collapsing on scroll.** The bar was pinned via `appBar.lockYPos = true` preventing proper collapse animations. Appbar now follows per-tab recycler scroll with `onPageRecyclerScrolled` mirroring the continuous-mode scroll behavior and `onPageSelected` resets to top
- **Fix novel reader window inset changes not repositioning slider.** Recompute reader navigator vertical position whenever window insets change to handle immersive mode toggles and display cutout transitions
- **Fix novel reader scroll state not restoring reliably.** Replace simple getter with requestAnimationFrame polling to avoid racing with WebView layout completion
- **Fix SaveChapterProgress using stale page index.** ReaderViewModel was saving `chapter.last_page_read` instead of the actual `page.index` being saved, causing pager snap-back on resume
- **Fix NovelUpdatesParser and MdUtil regex crashes on Android's ICU parser.** Escape bare `]` and `}` characters outside character classes (invalid in ICU); `[^\]]` → `[^\\]]`, bare `}` → `\\}`
- **Fix i18n restore_duration string AAPT errors.** Multiple non-positional substitutions invalid; converted all 52 locales to positional format `%1$` and `%2$`
- **Fix chapter transition card missing download state indicators.** Add per-row download lookup (isChapterDownloadedSafe) mirroring manga reader; render check-circle or cloud icon depending on state
- **Fix recommendation sources not disambiguating MangaUpdates vs NovelUpdates.** Thread content type through recommendation paging sources and parser logic so novels bias toward NovelUpdates
- **Fix EHentai gallery description parsing.** Ignore optional favorite-color border div that was breaking category extraction
- **Fix tabbed library flashing the continuous-mode layout when opening a manga.** `mAdapter` (the continuous-mode adapter that backs `libraryGridRecycler.recycler`) was being populated with the full library data even when the user was in tabbed mode. Its view was set to `View.GONE`, but conductor's hardware-accelerated push transition was occasionally compositing the populated content through. Root-cause fix: `onNextLibraryUpdate` now passes `emptyList()` to `mAdapter` whenever `binding.libraryPager.isVisible` (i.e. the per-tab pager is the active surface), and `setupTabbedView` clears `mAdapter` immediately on entering tabbed mode so the next library update can't beat the visibility flip. `teardownTabbedView` repopulates from `presenter.libraryItemsToDisplay` when going back to continuous, so flatten-on-search and other code paths that toggle teardown without firing a fresh library update don't leave the recycler empty. `binding.libraryPager.isVisible` (rather than `isTabbedMode`) is used so sub-class picker mode (`forceShowAllCategories=true` → tabbed view torn down even when the pref is on) keeps populating `mAdapter` correctly.
- **Fix tabbed library briefly rendering "old continuous category headers" on tab switch.** `LibraryPagerAdapter.bindCategoryItems` was passing the presenter's per-category list (which embeds `LibraryHeaderItem` at index 0 for continuous-mode rendering) straight to the per-tab adapter, then calling `hideAllHeaders()`. Flexible-adapter rendered the header item for one frame before the hide took effect. Now the header items are filtered out before `setItems`, so they never enter the page's data set.
- **Fix tabbed library appbar not collapsing on scroll.** The bar was pinned via `appBar.lockYPos = true` so only the per-tab recycler scrolled; combined with a bg-color toggle as the elevation cue, this produced visible flickering on small libraries where scroll deltas just barely crossed the elevated/non-elevated threshold. `applyTabbedAppBarMode` no longer locks `lockYPos`, and `onPageRecyclerScrolled` / `onPageRecyclerScrollIdle` now mirror `scrollViewWith.onScrolled` / `onScrollIdle` (move `appBar.y` by `dy`, call `updateAppBarAfterY`, then `snapAppBarY` on idle). `onPageSelected` resets `appBar.y = 0f` when switching tabs so the new tab's content lines up directly under a fully-expanded bar.
- **Fix novel reader Custom theme text color leaking into Sepia / other themes.** `if (fontColor != 0) fontColor else themeTextColor` unconditionally won, so a yellow color saved while editing the Custom theme persisted after switching to a non-Custom preset. Now gated on `theme == "custom"` in both `NovelViewer.kt:2147` and `NovelWebViewViewer.kt:694, 1822, 1877`.
- **Fix novel reader TTS button needing two clicks to switch to the Play icon.** `pauseTts()` was gated on `tts?.isSpeaking == true`, but the platform `TextToSpeech` engine briefly returns `isSpeaking=false` between utterance chunks — a tap landing in that gap was a no-op, so the icon didn't update until a second tap. Now gated on `ttsChunks.isNotEmpty()` so user intent is honored regardless of engine timing.
- **Fix novel reader auto-scroll and TTS-paused buttons sharing the same Play icon.** Both states rendered `Icons.Outlined.PlayArrow`, so users couldn't tell auto-scroll-off from TTS-paused at a glance. Auto-scroll's idle icon is now `Icons.Outlined.SwapVert`; PlayArrow stays exclusive to TTS's paused state.
- **Fix novel reader action bar visual mismatch with the manga progress slider.** The bar used `surfaceColorAtElevation(3.dp)` and a different rounded radius than `chapter_nav.xml`, button sizes, ripple themeing, and clipping all diverged from the manga reader's bottom slider. `NovelReaderActionBar.kt` now uses translucent `colorSurface` (alpha 200/255) with a 50dp rounded shape, 48dp button targets, 24dp icons, and a `Box` + `Modifier.clickable(indication = ripple(bounded = false, …, color = colorControlHighlight))` helper so the ripple matches the app theme. `ReaderActivity` anchors the bar to `nav_layout` with 12dp horizontal margins so the pill width matches the slider's. The bar fades in lockstep with the chapter sheet's `onSlide` callback and is hidden in `STATE_EXPANDED`.

- **Fix novel sources crashing with `RejectedExecutionException: The task was rejected` on every browse / chapter call** — regression from the per-source dispatcher work. `NovelJsRuntime.close()` was shutting down the executor *and* `doInitialize()` called `close()` first thing for defensive cleanup, so the per-source executor died the moment init finished and every subsequent `withContext(jsDispatcher)` was rejected. Split `close()` (full teardown — only call from `destroy()`) from `closeQuickJsOnly()` (preserve executor — used by init's pre/retry cleanup).
- **Fix all LNReader plugins failing to initialize with `Module not found: node:stream`** after we tried to upgrade to cheerio 1.x. cheerio 1.x's node entry imports node-builtin modules (`node:stream`, `node:string_decoder`, `node:events`, `node:buffer`) that QuickJS has no resolver for. Bundle cheerio with `platform: 'browser'` so esbuild picks the browser entry, alias the few remaining node-builtin imports to an empty stub so esbuild produces a self-contained bundle. Pinned cheerio to `1.0.0` (was `^1.0.0-rc.12`) to match what the LNReader plugin corpus is tested against.
- **Fix sources disappearing from the browse list / extensions filter showing nothing.** Two `collectLatest` blocks in `SourceManager` were both doing read-modify-write on the same `MutableStateFlow` source map; their writes could clobber each other on cold start, extension install, or novel-plugin refresh, leaving the list with only LocalSource + EH + MergedSource + novels. Collapsed into a single `combine()` so there's exactly one writer.
- **Fix novel sources not working** ([#12](https://github.com/HayaiApp/hayai/issues/12)). Root causes ranged across the JS bridge, the novel runtime, and LNReader-plugin compatibility:
  - `__bridge.fetch / fetchText / fetchFile / sleep` are now suspend on the Kotlin side and bound via QuickJS `asyncFunction` so OkHttp work runs on `Dispatchers.IO` instead of stalling the JS worker thread.
  - Each `NovelJsRuntime` now gets its own single-thread executor (8 MB stack) shut down on `close()` instead of sharing one global dispatcher — uninstalled plugins no longer leak threads, and one slow source no longer blocks another.
  - `NovelSource.ensureRuntime()` and `destroy()` are guarded by a `Mutex` so racing coroutines (browse + reader prefetch, etc.) can't double-initialize and leak QuickJS contexts or close a runtime mid-init.
  - `NovelPluginManager` now uses a `ConcurrentHashMap` + `installMutex` for all installed-source mutations, `SupervisorJob` so one failing plugin can't kill the manager scope, and caches the system UA at construction (calling `WebSettings.getDefaultUserAgent` from IO threads threw on some Android versions).
  - New `timers_shim.js` overrides the polyfill `setTimeout` / `setInterval` with async-aware versions backed by `__bridge.sleep` — the polyfill's sync impl silently fired callbacks immediately under the new async bridge, breaking every plugin that throttles via `await new Promise(r => setTimeout(r, ms))`.
  - `fetchProto` is now implemented entirely in JS using the bundled protobufjs (mirrors `lnreader-plugins/src/lib/fetch.ts:fetchProto`, gRPC-Web framing included). The Kotlin stub that returned `{}` is gone, so WuxiaWorld and other proto-based plugins actually work now.
  - Default headers synced with LNReader's `makeInit` (drop `Cache-Control: max-age=0`, add `Accept-Encoding: gzip, deflate`); only auto-managed encoding values are stripped on the Kotlin side so plugins can still pass `identity` etc. through.
  - `LocalStorage` / `SessionStorage` shims now match LNReader's per-instance dict semantics instead of returning a single SharedPreferences blob, which silently broke any plugin that tried to read individual keys off the result.
  - Added `@/types/constants` to `require_impl.js` so plugins like novelfire that import path-aliased modules from the LNReader build resolve at runtime (tsc preserves these aliases verbatim in compiled output).
  - Bundled 7 additional runtime libraries (buffer, fetch-globals, text-encoding, htmlparser2, crypto-js, pako, protobufjs) needed by the full LNReader plugin surface (~1.3 MB total).
- Fix `MergedSource` and other entries in `BlacklistedSources.HIDDEN_SOURCES` leaking into global search, the migration target list, and the per-language source toggle. Centralized via new `SourceManager.getVisibleCatalogueSources()` / `getVisibleOnlineSources()` helpers (mirrors TachiyomiSY's `AndroidSourceManager` pattern); call sites switched over.
- Fix the browse-page extension filter screen sometimes rendering permanently blank when opened before the bottom-sheet ever loaded available extensions/plugins. `ExtensionFilterController` now kicks `findAvailableExtensions()` / `refreshAvailablePlugins()` if either flow is empty, and re-renders whenever either emits.
- Fix metadata enrichment, namespace search, and page previews silently not firing for Pururin / Tsumino / MangaDex / HBrowse / 8Muses / LANraragi extensions: the EXH `DELEGATED_SOURCES` table only had NHentai. Restored the other six entries (matches TachiyomiSY); installed extensions now get wrapped with `EnhancedHttpSource`. `exh.source.handleSourceLibrary()` (previously a no-op stub) now recomputes `metadataDelegatedSourceIds`, `nHentaiSourceIds`, `mangaDexSourceIds`, `lanraragiSourceIds`, and `LIBRARY_UPDATE_EXCLUDED_SOURCES` whenever a source delegates.
- Fix `LibraryUpdateJob` running Pururin / NHentai delegated sources through the standard library updater (likely producing 429s or wrong chapter data) — replaced the hardcoded `EH_SOURCE_ID || EXH_SOURCE_ID` check with `LIBRARY_UPDATE_EXCLUDED_SOURCES`, which is now correctly populated.
- Fix the eHentai extension showing as installable in browse and counting toward update-badges. `ExtensionApi` now filters `BlacklistedSources.BLACKLISTED_EXTENSIONS` at the parse step.
- Fix `ExtensionManager.updatedInstalledExtensionsStatuses` pinning an extension to `isObsolete = true` forever once flagged, even after it returned to the available list (inverted condition rewrote the same value back).
- Fix novel chapter downloads silently sticking at `DOWNLOADING` forever and blocking the queue — replaced the brittle `download.source is TextSource && is NovelSource` / `is HttpSource` else-if chain with a typed `when()` that errors on unknown source types.
- Fix `.nomedia` not being created in download chapter directories on devices using SAF — `UniFile.renameTo` invalidates the `DocumentFile` handle on many devices, so the post-rename create silently no-op'd. Now writes `.nomedia` before the rename. CBZ branch correctly skips.
- Guard `DownloadManager.buildPageList` against `TextSource` so callers can't surface a misleading "no pages found" for novel chapters.
- Fix novel sources rendering without their icon in the Migrations tab — the migration `SourceHolder` now falls back to the plugin's `iconUrl` (or a book glyph) when the manga `ExtensionManager` has no app icon, matching the regular browse list. The same fallback was added to the per-manga Migrate target list.

### Other
- Migrate CI workflows to Blacksmith for improved reliability and cost optimization
- Add Gradle sticky-disk cache (separate cache keys for PR vs release builds for untrusted fork code)
- Pin R8 to 8.13.19 to parse Kotlin 2.3 metadata (AGP 8.12 ships an older R8 that spams warnings)
- Update repository URLs from ig4e to HayaiApp organization (tracker user-agents, AppUpdateChecker, nightly release target, GitHub links)
- Add scraped NHentai tags JSON assets and scrape_nhtags.py tool script
- Separate splash and notification icon drawables: splash uses 288dp at 0.72 scale, notification uses 24dp at 0.92 scale for status bar visibility
- Performance optimizations from perf-optimizations branch:
  - BrowseSourcePresenter: cheap sourceManager.get() before awaitCatalogueSource; load saved searches async
  - MangaDetailsPresenter: tracks fetch and chapters load on presenterScope/viewScope
  - LibraryPresenter: getFirstUnread suspension and FirstUnread computation via viewScope
  - Dynamic category lookup uses libraryPresenter.categoryNamesById cache rebuilt on library emit
  - yokai.util.coil: drop Size.ORIGINAL for list/grid thumbnails to decode at view bounds
  - MangaDetailsController: early-return when vibrantCoverColor cached; defer ComposeView setup with postOnAnimation
  - DynamicGridCells annotated @Immutable for Compose stability
  - MainActivity bottom-nav badge: distinctUntilChanged on (isDownloading, queueSize) pair
  - ControllerExtensions fade transitions skip AnimatorSet pipeline when ReducedMotion enabled
- Startup refactor: centralize splash lifecycle in BaseActivity via installSplash/configure/release gates; fix lifecycle leaks by cancelling SourcePresenter scope
- MangaCoverMetadata: merge on-disk entries into in-memory maps instead of replacing; add loaded flag to prevent premature saves during IO race; use ConcurrentHashMap for thread safety
- Fix novel sources rendering oversized in the browse-page filter list — raw `BitmapDrawable`s from Coil filled the icon slot 100% while manga `AdaptiveIconDrawable`s have a built-in ~16.7% safe zone. Novel icons are now wrapped in an `InsetDrawable` with the same fraction so both source types render at consistent visual size.
- Fix `MergedSource` appearing in the per-manga Migrate target source list — `PreMigrationController` now filters out `BlacklistedSources.HIDDEN_SOURCES` for both manga and novel migrations.
- Per-manga Migrate now picks the right catalogue for the entry being migrated: novels see only novel (`TextSource`) sources, manga see only `HttpSource` sources. `MigrationSourceItem`/`MigrationSourceHolder` widened from `HttpSource` to `CatalogueSource`.
- Fix `PreMigrationController.isNovelMigration` deadlocking against `SourceManager.getOrStub()` while novel plugins were still loading. The lookup is now `lifecycleScope`-launched on `Dispatchers.IO` and gates adapter creation on completion, instead of `runBlocking{}` on the UI thread.
- Fix tapping a page-preview thumbnail opening page 1 instead of the tapped page (race between two state observers in `ReaderViewModel`; now the requested page is plumbed through `ChapterLoader` before `viewerChapters` is published).
- Fix recommendation card clicks being silently dropped for external sources (AniList / MyAnimeList / NovelUpdates / etc.); they now route to global search by title across installed sources, matching TachiyomiSY's behavior.
- Fix `NovelUpdatesParser.<clinit>` and `MdUtil.<clinit>` crashes (`PatternSyntaxException` on Android's ICU regex engine: `[^]]` is invalid; escaped to `[^\]]`). The first crash also took down anything that loaded the recommendations screen.
- Fix follow-up `NovelUpdatesParser.<clinit>` crash (`PatternSyntaxException` near index 28) — the bracket-stripping regex still had bare `]` and `}` outside their character classes, which Android's ICU parser rejects. Escaped both closing delimiters so the pattern compiles and recommendations stop crashing on launch. Same fix applied preventatively to the matching bare `]` in `MdUtil.markdownLinksRegex` and `ShizukuInstaller.SESSION_ID_REGEX`.
- Fix QuickJS plugin errors (e.g. `Could not find chapter content`) crashing the whole app — they were rethrown out of a `launch(IO)` with no `CoroutineExceptionHandler`. The novel page loaders now match `HttpPageLoader`'s pattern: set `Page.State.Error` and let the existing reader UI show the retry state.
- Fix novel chapter list missing pages 2+ for plugins that report `totalPages` (`parsePage` is now called and merged before reversal).
- Fix null `transitionName` crash when opening a chapter from the header button
- Fix AutoComplete filter crash, dark mode handling, and live chip updates
- Fix Injekt `TypeReference` crash and null-safety issues across EXH handlers and `SettingsEhScreen`
- Harden EXH recommendation sources and config parsing against crashes
- Harden general crash handling and extension loading
- Handle source popups as separate WebView instances (#608)
- Fix E-Hentai previews and description layout
- Fix namespace tag position/style, page preview, and description state
- Fix preview images, popular pagination, and manga detail UX
- Manga detail and EHentai miscellaneous fixes

### Other
- Speed up CI builds with Gradle caching and parallelism
- Cold-start hardening: WorkManager auto-init disabled in `AndroidManifest`; `App` implements `Configuration.Provider` so workers don't try to instantiate before Koin is started. `MainActivity` flips `splashState.ready` immediately rather than waiting inside a coroutine that didn't run until past the 5 s splash cap. `AppModule.initExpensiveComponents` launches on `Dispatchers.IO` so cold start doesn't block the main thread.
- `SourceManager.toInternalSource` now `error()`s in debug builds when a delegate's 2-param constructor goes missing, instead of silently degrading to the unwrapped source — catches signature drift fast in dev without crashing release users.
- `NovelPluginCache` writes are now atomic (write-tmp + rename) so an OOM kill mid-write can't truncate the cache and force a JS metadata re-extraction (~700 ms per plugin) on the next cold start.
- `NovelSource.pluginCode` is no longer held resident for the source's lifetime — replaced with a `() -> String` provider that reads from the on-disk file when the runtime initializes, freeing tens-to-hundreds of KB per installed plugin.

## [1.14.0]

### Additions
- Add first-class hentai source support (ported from TachiyomiSY)
  - E-Hentai / ExHentai: full browsing, login, metadata, favorites sync, auto-update, gallery import
  - NHentai, 8Muses, HBrowse, Pururin, Tsumino, LANraragi built-in sources
  - MangaDex enhanced: OAuth login, follows sync, similar manga, API metadata
  - MergedSource for combining chapters from multiple sources
- Add metadata system with namespaced tags, database storage, and per-source detail screens
- Add smart search engine with fuzzy title matching
- Add recommendations system (AniList, MyAnimeList, MangaUpdates, Comick, MangaDex)
- Add page preview system for gallery thumbnails
- Add Data Saver with 4 modes: Off, wsrv.nl, Custom Server, Proxy (wsrv.nl via custom)
  - Full image processing: quality, format, resize, fit modes, brightness, contrast, saturation, sharpen, blur, filters
  - Support for custom hayai-image-proxy server
- Add E-Hentai tag autocomplete with 136,000+ tags loaded from JSON assets
- Add deep link handling for 7 domains (e-hentai.org, exhentai.org, nhentai.net, mangadex.org, etc.)
- Add EH/ExH and MangaDex source logos

### Changes
- E-Hentai alt server retry now supported (Page.url changed to mutable)
- Metadata automatically persists to database via MetadataSource interface
- Genre chips now have dark mode color variants
- Rating display now color-coded (red → orange → yellow → green)
- InterceptActivity shows app branding during link loading
- All EXH screens use YokaiScaffold, Material 3, and proper string resources

### Fixes
- Fix ComikeyHandler NPE on missing href field
- Fix HBrowse empty error messages for missing table sections
- Fix recommendation screen navigation (clicking manga now opens details)
- Fix MangaDex OAuth token persistence (uses PreferenceStore instead of missing MdList tracker)
- Fix smart search engine missing async import
- Fix Manga type mismatches across recommendation system

### Other
- Delete dead code: Version.kt, EXHMigrations.kt, OkHttpUtil.kt, OkHttpExtensions.kt, duplicate LewdMangaChecker
- Remove hardcoded strings across all EXH screens (90+ string resources added)
- Add accessibility contentDescription to all icons
- Add proper loading and empty states to all EXH screens
- Consolidate preference duplication (useJapaneseTitle)
- Wire MetadataSource DI with 3 interactor implementations
- Refactor E-Hentai tags from 28K lines of Kotlin to JSON assets (2.9 MB, runtime loading with cache)

## [1.15.0]

### Additions
- Add configurable preload-ahead slider in reader settings (1–20 pages, applies immediately while reading)
- Add Compose-based metadata and continue/start-reading UI on manga details
- Add Compose-based filter UI, E-Hentai settings, and source icons
- Allow sharing crash logs directly from the crash screen
- Add "Refresh igneous cookie" option in E-Hentai advanced login settings
- Add custom igneous cookie dialog in E-Hentai login
- Add random library sort
- Add the ability to save search queries
- Add toggle to enable/disable hide source on swipe (@Hiirbaf)
- Add the ability to mark duplicate read chapters as read (@AntsyLich)
- Add option to zoom into full covers (@Hiirbaf)
- Add APNG support for Android 9+ (@lalalasupa0)
- Add markdown support to entry description (@luigidotmoe)
  - Fix text disappeared when it's surrounded by `<>` (@lalalasupa0)
- Add first-class novel source support with plugin repos, install/update/uninstall flow, and LNReader-compatible QuickJS runtime
- Add native novel reader with themed chapter transitions, percentage-based resume/progress, and downloaded chapter support
- Add novel type integration across badges, library filters, statistics, chapter/recents progress text, and source/detail surfaces
- Add novel-only recommendation flow using NovelUpdates

### Changes
- Temporarily disable log file
- Categories' header now show filtered count when you search the library when you have "Show number of items" enabled (@LeeSF03)
- Chapter progress now saved everything the page is changed
- Adjust sorting order to be more consistent (@Astyyyyy)
- Improve Local Source when loading from `android/data` (@lalalasupa0)
- Refresh available extensions list when an extension repo is added or removed
- Replace filter FAB with Floating Toolbar when browsing source
- Show FAB button to read/resume chapter when start/continue reading button is off-screen
- LocalSource entries no longer auto-refresh when opened (@lalalasupa0)
- Long tap chapters on Reader now mark it as read (@lalalasupa0)
- Browse source list now reacts to novel source install/remove changes without reopening the tab
- Novel repo validation now normalizes and probes candidate indexes before saving a repo
- Novel HTML parsing is now app-side and strips only unsafe/hidden content instead of broad content heuristics

### Fixes
- Allow users to bypass onboarding's permission step if Shizuku is installed
- Fix Recents page shows "No recent chapters" instead of a loading screen
- Fix not fully loaded entries can't be selected on Library page
- Fix certain Infinix devices being unable to use any "Open link in browser" actions, including tracker setup (@MajorTanya)
- Fix source filter checkboxes/tri-state not visually updating when tapped
- Fix source filter bottom sheet unable to be fully scrolled to the bottom
- Prevent potential "Comparison method violates its general contract!" crash
- Fix staggered grid cover being squashed for local source (@AwkwardPeak7)
- Fix GPU crash when setting cover from downloaded chapters (@Angrevol)
- Fix crashes when handling certain sources' deep links (@Hiirbaf)
- Properly filter sources by extension (@Hiirbaf)
- Fix crashes caused by RecyclerView stable id (@MuhamadSyabitHidayattulloh)
- Fix paused download notification is not shown (@MuhamadSyabitHidayattulloh)
- Disable auto refresh entry from Local Source (@lalalasupa0)
- Fix E-Hentai/ExHentai igneous cookie "mystery" being stored as valid (now rejected with error)
- Fix E-Hentai page loading and retry (retry now re-fetches image URL with nl= server switch)
- Fix ExHentai 509 quota exceeded detection
- Fix E-Hentai chapter list NPE on missing/malformed gallery data
- Fix Data Saver not applied in reader and downloads
- Fix extension download stuck on pending state
- Only solve Cloudflare with WebView if it's not geoblock (@AwkwardPeak7)
- Fix cover from LocalSource sometimes didn't load (@lalalasupa0)
- Fix novel reader oversized blank scroll regions and inaccurate progress caused by WebView-based sizing
- Fix novel reader restore loops, stale image callbacks, and premature 100% read state on chapter open
- Fix novel source search showing page 1 results and then an incorrect "nothing found" follow-up state
- Fix invalid or empty novel repos being accepted and later surfacing as silent empty source lists
- Fix edit-manga series type handling for novels and preserve explicit novel tagging cleanly

### Translation
- Update translations from Weblate

### Other
- Refactor Library to utilize Flow even more
- Refactor EmptyView to use Compose
- Refactor Reader ChapterTransition to use Compose (@arkon)
- [Experimental] Add modified version of LargeTopAppBar that mimic J2K's ExpandedAppBarLayout
- Refactor About page to use Compose
- Adjust Compose-based pages' transition to match J2K's Conductor transition
- Resolve deprecation warnings
  - Kotlin's context-receiver, schedule for removal on Kotlin v2.1.x and planned to be replaced by context-parameters on Kotlin v2.2
  - Project.exec -> Providers.exec
  - Remove internal API usage to retrieve Kotlin version for kotlin-stdlib
- Move :core module to :core:main
  - Move archive related code to :core:archive (@AntsyLich)
- Refactor Library to store LibraryMap instead of flatten list of LibraryItem
  - LibraryItem abstraction to make it easier to manage
  - LibraryManga no longer extend MangaImpl
- Update dependency gradle to v8.12
- Update user agent (@Hiirbaf)
- Update serialization to v1.8.1
- Update dependency io.github.fornewid:material-motion-compose-core to v2.0.1
- Update lifecycle to v2.9.0
- Update dependency org.jsoup:jsoup to v1.21.2
- Update dependency org.jetbrains.kotlinx:kotlinx-collections-immutable to v0.4.0
- Update dependency io.mockk:mockk to v1.14.2
- Update dependency io.coil-kt.coil3:coil-bom to v3.4.0
- Update dependency com.squareup.okio:okio to v3.12.0
- Update dependency com.google.firebase:firebase-bom to v33.14.0
- Update dependency com.google.accompanist:accompanist-themeadapter-material3 to v0.36.0
- Update dependency com.github.requery:sqlite-android to v3.49.0
- Update dependency com.getkeepsafe.taptargetview:taptargetview to v1.15.0
- Update dependency androidx.window:window to v1.4.0
- Update dependency androidx.webkit:webkit to v1.13.0
- Update dependency androidx.sqlite:sqlite-ktx to v2.5.1
- Update dependency androidx.sqlite:sqlite to v2.5.1
- Update dependency androidx.recyclerview:recyclerview to v1.4.0
- Update dependency androidx.core:core-ktx to v1.17.0
- Update dependency androidx.core:core-splashscreen to v1.2.0
- Update dependency androidx.compose:compose-bom to v2026.02.00
- Update aboutlibraries to v13.1.0
- Update plugin kotlinter to v5.1.0
- Update plugin gradle-versions to v0.52.0
- Update okhttp monorepo to v5.0.0-alpha.16
- Update moko to v0.25.1
- Update dependency org.jetbrains.kotlinx:kotlinx-coroutines-bom to v1.10.2
- Update dependency me.zhanghai.android.libarchive:library to v1.1.5
- Update dependency io.insert-koin:koin-bom to v4.0.4
- Update dependency com.android.tools:desugar_jdk_libs to v2.1.5
- Update dependency androidx.work:work-runtime-ktx to v2.10.1
- Update dependency androidx.constraintlayout:constraintlayout to v2.2.1
- Update plugin firebase-crashlytics to v3.0.3
- Update null2264/actions digest to 363cb9c
- Update dependency io.github.pdvrieze.xmlutil:core-android to v0.91.1
- Improve X-Requested-With spoof to support newer WebView versions (@Hiirbaf)
- Update agp to v8.12.2
- Update activity to v1.11.0
- Update lifecycle to v2.9.4
- Update sqldelight to v2.2.1
- Update dependency com.google.android.material:material to v1.14.0-alpha09
- Update dependency androidx.compose.material3:material3 to v1.5.0-alpha14
- Minimize memory usage by reducing in-memory cover cache size (@Lolle2000la)

## [1.9.7.4]

### Other
- Prioritize extension classpath over app
- Update kotlin monorepo to v2.3.10
- Update dependency gradle to v8.14.4

## [1.9.7.3]

### Fixes
- More `Comparison method violates its general contract!` crash prevention

## [1.9.7.2]

### Fixes
- Fix MyAnimeList timeout issue

## [1.9.7.1]

### Fixes
- Prevent `Comparison method violates its general contract!` crashes

## [1.9.7]

### Changes
- Adjust log file to only log important information by default

### Fixes
- Fix sorting by latest chapter is not working properly
- Prevent some NPE crashes
- Fix some flickering issues when browsing sources
- Fix download count is not updating

### Translation
- Update Korean translation (@Meokjeng)

### Other
- Update NDK to v27.2.12479018

## [1.9.6]

### Fixes
- Fix some crashes

## [1.9.5]

### Changes
- Entries from local source now behaves similar to entries from online sources

### Fixes
- Fix new chapters not showing up in `Recents > Grouped`
- Add potential workarounds for duplicate chapter bug
- Fix favorite state is not being updated when browsing source

### Other
- Update dependency androidx.compose:compose-bom to v2024.12.01
- Update plugin kotlinter to v5
- Update plugin gradle-versions to v0.51.0
- Update kotlin monorepo to v2.1.0

## [1.9.4]

### Fixes
- Fix chapter date fetch always null causing it to not appear on Updates tab

## [1.9.3]

### Fixes
- Fix slow chapter load
- Fix chapter bookmark state is not persistent

### Other
- Refactor downloader
  - Replace RxJava usage with Kotlin coroutines
  - Replace DownloadQueue with Flow to hopefully fix ConcurrentModificationException entirely

## [1.9.2]

### Changes
- Adjust chapter title-details contrast
- Make app updater notification consistent with other notifications

### Fixes
- Fix "Remove from read" not working properly

## [1.9.1]

### Fixes
- Fix chapters cannot be opened from `Recents > Grouped` and `Recents > All`
- Fix crashes caused by malformed XML
- Fix potential memory leak

### Other
- Update dependency io.github.kevinnzou:compose-webview to v0.33.6
- Update dependency org.jsoup:jsoup to v1.18.3
- Update voyager to v1.1.0-beta03
- Update dependency androidx.annotation:annotation to v1.9.1
- Update dependency androidx.constraintlayout:constraintlayout to v2.2.0
- Update dependency androidx.glance:glance-appwidget to v1.1.1
- Update dependency com.google.firebase:firebase-bom to v33.7.0
- Update fast.adapter to v5.7.0
- Downgrade dependency org.conscrypt:conscrypt-android to v2.5.2

## [1.9.0]

### Additions
- Sync DoH provider list with upstream (added Mullvad, Control D, Njalla, and Shecan)
- Add option to enable verbose logging
- Add category hopper long-press action to open random series from **any** category
- Add option to enable reader debug mode
- Add option to adjust reader's hardware bitmap threshold (@AntsyLich)
  - Always use software bitmap on certain devices (@MajorTanya)
- Add option to scan local entries from `/storage/(sdcard|emulated/0)/Android/data/<yokai>/files/local`

### Changes
- Enable 'Split Tall Images' by default (@Smol-Ame)
- Minor visual adjustments
- Tell user to restart the app when User-Agent is changed (@NGB-Was-Taken)
- Re-enable fetching licensed manga (@Animeboynz)
- Bangumi search now shows the score and summary of a search result (@MajorTanya)
- Logs are now written to a file for easier debugging
- Bump default user agent (@AntsyLich)
- Custom cover is now compressed to WebP to prevent OOM crashes

### Fixes
- Fix only few DoH provider is actually being used (Cloudflare, Google, AdGuard, and Quad9)
- Fix "Group by Ungrouped" showing duplicate entries
- Fix reader sometimes won't load images
- Handle some uncaught crashes
- Fix crashes due to GestureDetector's firstEvent is sometimes null on some devices
- Fix download failed due to invalid XML 1.0 character
- Fix issues with shizuku in a multi-user setup (@Redjard)
- Fix some regional/variant languages is not listed in app language option
- Fix browser not opening in some cases in Honor devices (@MajorTanya)
- Fix "ConcurrentModificationException" crashes
- Fix Komga unread badge, again
- Fix default category can't be updated manually
- Fix crashes trying to load Library caused by cover being too large

### Other
- Simplify network helper code
- Fully migrated from StorIO to SQLDelight
- Update dependency com.android.tools:desugar_jdk_libs to v2.1.3
- Update moko to v0.24.4
- Refactor trackers to use DTOs (@MajorTanya)
  - Fix AniList `ALSearchItem.status` nullibility (@Secozzi)
- Replace Injekt with Koin
- Remove unnecessary permission added by Firebase
- Remove unnecessary features added by Firebase
- Replace BOM dev.chrisbanes.compose:compose-bom with JetPack's BOM
- Update dependency androidx.compose:compose-bom to v2024.11.00
- Update dependency com.google.firebase:firebase-bom to v33.6.0
- Update dependency com.squareup.okio:okio to v3.9.1
- Update activity to v1.9.3
- Update lifecycle to v2.8.7
- Update dependency me.zhanghai.android.libarchive:library to v1.1.4
- Update agp to v8.7.3
- Update junit5 monorepo to v5.11.3
- Update dependency androidx.test.ext:junit to v1.2.1
- Update dependency org.jetbrains.kotlinx:kotlinx-collections-immutable to v0.3.8
- Update dependency org.jsoup:jsoup to v1.18.1
- Update dependency org.jetbrains.kotlinx:kotlinx-coroutines-bom to v1.9.0
- Update serialization to v1.7.3
- Update dependency gradle to v8.11.1
- Update dependency androidx.webkit:webkit to v1.12.0
- Update dependency io.mockk:mockk to v1.13.13
- Update shizuku to v13.1.5
  - Use reflection to fix shizuku breaking changes (@Jobobby04)
- Bump compile sdk to 35
  - Handle Android SDK 35 API collision (@AntsyLich)
- Update kotlin monorepo to v2.0.21
- Update dependency androidx.work:work-runtime-ktx to v2.10.0
- Update dependency androidx.core:core-ktx to v1.15.0
- Update dependency io.coil-kt.coil3:coil-bom to v3.0.4
- Update xml.serialization to v0.90.3
- Update dependency co.touchlab:kermit to v2.0.5
- Replace WebView to use Compose (@arkon)
  - Fixed Keyboard is covering web page inputs
- Increased `tryToSetForeground` delay to fix potential crashes (@nonproto)
- Update dependency org.conscrypt:conscrypt-android to v2.5.3
- Port upstream's download cache system

## [1.8.5.13]

### Fixed
- Fix version checker

## [1.8.5.12]

### Fixed
- Fixed scanlator data sometimes disappear

## [1.8.5.11]

### Fixed
- Fixed crashes caused by Bangumi invalid status

## [1.8.5.10]

### Fixes
- Fixed scanlator filter not working properly

## [1.8.5.9]

### Changes
- Revert create backup to use file picker

## [1.8.5.8]

### Other
- Separate backup error log when destination is null or not a file
- Replace com.github.inorichi.injekt with com.github.null2264.injekt

## [1.8.5.7]

### Fixes
- Fixed more NPE crashes

## [1.8.5.6]

### Fixes
- Fixed NPE crash on tablets

## [1.8.5.5]

### Fixes
- Fixed crashes caused by certain extension implementation
- Fixed "Theme buttons based on cover" doesn't work properly
- Fixed library cover images looks blurry then become sharp after going to
  entry's detail screen

### Other
- More StorIO to SQLDelight migration effort
- Update dependency dev.chrisbanes.compose:compose-bom to v2024.08.00-alpha02
- Update kotlin monorepo to v2.0.20
- Update aboutlibraries to v11.2.3
- Remove dependency com.github.leandroBorgesFerreira:LoadingButtonAndroid

## [1.8.5.4]

### Fixes
- Fixed custom cover set from reader didn't show up on manga details

## [1.8.5.3]

### Additions
- Add toggle to enable/disable chapter swipe action(s)
- Add toggle to enable/disable webtoon double tap to zoom

### Changes
- Custom cover now shown globally

### Fixes
- Fixed chapter number parsing (@Naputt1)
- Reduced library flickering (still happened in some cases when the cached image size is too different from the original image size, but should be reduced quite a bit)
- Fixed entry details header didn't update when being removed from library

### Other
- Refactor chapter recognition (@stevenyomi)
- (Re)added unit test for chapter recognition
- More StorIO to SQLDelight migration effort
- Target Android 15
- Adjust manga cover cache key
- Refactor manga cover fetcher (@ivaniskandar, @AntsyLich, @null2264)

## [1.8.5.2]

### Fixes
- Fixed some preference not being saved properly

### Other
- Update dependency co.touchlab:kermit to v2.0.4
- Update lifecycle to v2.8.4

## [1.8.5.1]

### Fixes
- Fixed library showing duplicate entry when using dynamic category

## [1.8.5]

### Additions
- Add missing "Max automatic backups" option on experimental Data and Storage setting menu
- Add information on when was the last time backup automatically created to experimental Data and Storage setting menu
- Add monochrome icon

### Changes
- Add more info to WorkerInfo page
  - Added "next scheduled run"
  - Added attempt count
- `english` tag no longer cause reading mode to switch to LTR (@mangkoran)
- `chinese` tag no longer cause reading mode to switch to LTR
- `manhua` tag no longer cause reading mode to switch to LTR
- Local source manga's cover now being invalidated on refresh
- It is now possible to create a backup without any entries using experimental Data and Storage setting menu
- Increased default maximum automatic backup files to 5
- It is now possible to edit a local source entry without adding it to library
- Long Strip and Continuous Vertical background color now respect user setting
- Display Color Profile setting no longer limited to Android 8 or newer
- Increased long strip cache size to 4 for Android 8 or newer (@FooIbar)
- Use Coil pipeline to handle HEIF images

### Fixes
- Fixed auto backup, auto extension update, and app update checker stop working
  if it crash/failed
- Fixed crashes when trying to reload extension repo due to connection issue
- Fixed tap controls not working properly after zoom (@arkon, @Paloys, @FooIbar)
- Fixed (sorta, more like workaround) ANR issues when running background tasks, such as updating extensions (@ivaniskandar)
- Fixed split (downloaded) tall images sometimes doesn't work
- Fixed status bar stuck in dark mode when app is following system theme
- Fixed splash screen state only getting updates if library is empty (Should slightly reduce splash screen duration)
- Fixed kitsu tracker issue due to domain change
- Fixed entry custom cover won't load if entry doesn't have cover from source
- Fixed unread badge doesn't work properly for some sources (notably Komga)
- Fixed MAL start date parsing (@MajorTanya)

### Translation
- Update Japanese translation (@akir45)
- Update Brazilian Portuguese translation (@AshbornXS)
- Update Filipino translation (@infyProductions)

### Other
- Re-added several social media links to Mihon
- Some code refactors
  - Simplify some messy code
  - Rewrite version checker
  - Rewrite Migrator (@ghostbear)
  - Split the project into several modules
  - Migrated i18n to use Moko Resources
  - Removed unnecessary dependencies (@null2264, @nonproto)
- Update firebase bom to v33.1.0
- Replace com.google.android.gms:play-services-oss-licenses with com.mikepenz:aboutlibraries
- Update dependency com.google.gms:google-services to v4.4.2
- Add crashlytics integration for Kermit
- Replace ProgressBar with ProgressIndicator from Material3 to improve UI consistency
- More StorIO to SQLDelight migrations
  - Merge lastFetch and lastRead query into library_view VIEW
  - Migrated a few more chapter related queries
  - Migrated most of the manga related queries
- Bump dependency com.github.tachiyomiorg:unifile revision to a9de196cc7
- Update project to Kotlin 2.0 (v2.0.10)
- Update compose bom to v2024.08.00-alpha01
- Refactor archive support to use `libarchive` (@FooIbar)
- Use version catalog for gradle plugins
- Update dependency org.jsoup:jsoup to v1.7.1
- Bump dependency com.github.tachiyomiorg:image-decoder revision to 41c059e540
- Update dependency io.coil-kt.coil3 to v3.0.0-alpha10
- Update Android Gradle Plugin to v8.5.2
- Update gradle to v8.9
- Start using Voyager for navigation
- Update dependency androidx.work:work-runtime-ktx to v2.9.1
- Update dependency androidx.annotation:annotation to v1.8.2

## [1.8.4.6]

### Fixes
- Fixed scanlator filter not working properly if it contains " & "

### Other
- Removed dependency com.dmitrymalkovich.android:material-design-dimens
- Replace dependency br.com.simplepass:loading-button-android with
  com.github.leandroBorgesFerreira:LoadingButtonAndroid
- Replace dependency com.github.florent37:viewtooltip with
  com.github.CarlosEsco:ViewTooltip

## [1.8.4.5]

### Fixes
- Fixed incorrect library entry chapter count

## [1.8.4.4]

### Fixes
- Fixed incompatibility issue with J2K backup file

## [1.8.4.3]

### Fixes
- Fixed "Open source repo" icon's colour

## [1.8.4.2]

### Changes
- Changed "Open source repo" icon to prevent confusion

## [1.8.4.1]

### Fixes
- Fixed saving combined pages not doing anything

## [1.8.4]

### Additions
- Added option to change long tap browse and recents nav behaviour
  - Added browse long tap behaviour to open global search (@AshbornXS)
  - Added recents long tap behaviour to open last read chapter (@AshbornXS)
- Added option to backup sensitive settings (such as tracker login tokens)
- Added beta version of "Data and storage" settings (can be accessed by long tapping "Data and storage")

### Changes
- Remove download location redirection from `Settings > Downloads`
- Moved cache related stuff from `Settings > Advanced` to `Settings > Data and storage`
- Improve webview (@AshbornXS)
  - Show url as subtitle
  - Add option to clear cookies
  - Allow zoom
- Handle urls on global search (@AshbornXS)
- Improve download queue (@AshbornXS)
  - Download badge now show download queue count
  - Add option to move series to bottom
- Only show "open repo url" button when repo url is not empty

### Fixes
- Fix potential crashes for some custom Android rom
- Allow MultipartBody.Builder for extensions
- Refresh extension repo now actually refresh extension(s) trust status
- Custom manga info now relink properly upon migration
- Fixed extension repo list did not update when a repo is added via deep link
- Fixed download unread trying to download filtered (by scanlator) chapters
- Fixed extensions not retaining their repo url
- Fixed more NullPointerException crashes
- Fixed split layout caused non-split images to not load

### Other
- Migrate some StorIO queries to SQLDelight, should improve stability
- Migrate from Timber to Kermit
- Update okhttp monorepo to v5.0.0-alpha.14
- Refactor backup code
  - Migrate backup flags to not use bitwise
  - Split it to several smaller classes
- Update androidx.compose.material3:material3 to v1.3.0-beta02

## [1.8.3.4]

### Fixes
- Fixed crashes caused by invalid ComicInfo XML

  If this caused your custom manga info to stop working, try resetting it by deleting `ComicInfoEdits.xml` file located in `Android/data/eu.kanade.tachiyomi.yokai`

- Fixed crashes caused by the app trying to round NaN value

## [1.8.3.3]

### Changes
- Crash report can now actually be disabled

### Other
- Loading GlobalExceptionHandler before Crashlytics

## [1.8.3.2]

### Other
- Some more NullPointerException prevention that I missed

## [1.8.3.1]

### Other
- A bunch of NullPointerException prevention

## [1.8.3]

### Additions
- Extensions now can be trusted by repo

### Changes
- Extensions now required to have `repo.json`

### Other
- Migrate to SQLDelight
- Custom manga info is now stored in the database

## [1.8.2]

### Additions
- Downloaded chapters now include ComicInfo file
- (LocalSource) entry chapters' info can be edited using ComicInfo

### Fixes
- Fixed smart background colour by page failing causing the image to not load
- Fixed downloaded chapter can't be opened if it's too large
- Downloaded page won't auto append chapter ID even tho the option is enabled

### Other
- Re-route nightly to use its own repo, should fix "What's new" page

## [1.8.1.2]

### Additions
- Added a couple new tags to set entry as SFW (`sfw` and `non-erotic`)

### Fixes
- Fixed smart background colour by page failing causing the image to not load

### Other
- Re-route nightly to use its own repo, should fix "What's new" page

## [1.8.1.1]

### Fixes
- Fixed crashes when user try to edit an entry

## [1.8.1]

### Additions
- (Experimental) Option to append chapter ID to download filename to avoid conflict

### Changes
- Changed notification icon to use Yōkai's logo instead
- Yōkai is now ComicInfo compliant. [Click here to learn more](https://anansi-project.github.io/docs/comicinfo/intro)
- Removed "Couldn't split downloaded image" notification to reduce confusion. It has nothing to do with unsuccessful split, it just think it shouldn't split the image

### Fixes
- Fixed not being able to open different chapter when a chapter is already opened
- Fixed not being able to read chapters from local source
- Fixed local source can't detect archives

### Other
- Wrap SplashState to singleton factory, might fix issue where splash screen shown multiple times
- Use Okio instead of `java.io`, should improve reader stability (especially long strip)

## [1.8.0.2]

### Fixes
- Fixed app crashes when backup directory is null
- Fixed app asking for All Files access permission when it's no longer needed

## [1.8.0.1]

### Additions
- Added CrashScreen

### Fixes
- Fixed version checker for nightly against hotfix patch version
- Fixed download cache causes the app to crash

## [1.8.0]

### Additions
- Added cutout support for some pre-Android P devices
- Added option to add custom colour profile
- Added onboarding screen

### Changes
- Permanently enable 32-bit colour mode
- Unified Storage™ ([Click here](https://mihon.app/docs/faq/storage#migrating-from-tachiyomi-v0-14-x-or-earlier) to learn more about it)

### Fixes
- Fixed cutout behaviour for Android P
- Fixed some extensions doesn't detect "added to library" entries properly ([GH-40](https://github.com/null2264/yokai/issues/40))
- Fixed nightly and debug variant doesn't include their respective prefix on their app name
- Fixed nightly version checker

### Other
- Update dependency com.github.tachiyomiorg:image-decoder to e08e9be535
- Update dependency com.github.null2264:subsampling-scale-image-view to 338caedb5f
- Added Unit Test for version checker
- Use Coil pipeline instead of SSIV for image decode whenever possible, might improve webtoon performance
- Migrated from Coil2 to Coil3
- Update compose compiler to v1.5.14
- Update dependency androidx.compose.animation:animation to v1.6.7
- Update dependency androidx.compose.foundation:foundation to v1.6.7
- Update dependency androidx.compose.material:material to v1.6.7
- Update dependency androidx.compose.ui:ui to v1.6.7
- Update dependency androidx.compose.ui:ui-tooling to v1.6.7
- Update dependency androidx.compose.ui:ui-tooling-preview to v1.6.7
- Update dependency androidx.compose.material:material-icons-extended to v1.6.7
- Update dependency androidx.lifecycle:lifecycle-viewmodel-compose to v2.8.0
- Update dependency androidx.activity:activity-ktx to v1.9.0
- Update dependency androidx.activity:activity-compose to v1.9.0
- Update dependency androidx.annotation:annotation to v1.8.0
- Update dependency androidx.browser:browser to v1.8.0
- Update dependency androidx.core:core-ktx to v1.13.1
- Update dependency androidx.lifecycle:lifecycle-viewmodel-ktx to v2.8.0
- Update dependency androidx.lifecycle:lifecycle-livedata-ktx to v2.8.0
- Update dependency androidx.lifecycle:lifecycle-common to v2.8.0
- Update dependency androidx.lifecycle:lifecycle-process to v2.8.0
- Update dependency androidx.lifecycle:lifecycle-runtime-ktx to v2.8.0
- Update dependency androidx.recyclerview:recyclerview to v1.3.2
- Update dependency androidx.sqlite:sqlite to v2.4.0
- Update dependency androidx.webkit:webkit to v1.11.0
- Update dependency androidx.work:work-runtime-ktx to v2.9.0
- Update dependency androidx.window:window to v1.2.0
- Update dependency com.google.firebase:firebase-crashlytics-gradle to v3.0.1
- Update dependency com.google.gms:google-services to v4.4.1
- Update dependency com.google.android.material:material to v1.12.0
- Update dependency com.squareup.okio:okio to v3.8.0
- Update dependency com.google.firebase:firebase-bom to v33.0.0
- Update dependency org.jetbrains.kotlin:kotlin-gradle-plugin to v1.9.24
- Update dependency org.jetbrains.kotlin:kotlin-serialization to v1.9.24
- Update dependency org.jetbrains.kotlinx:kotlinx-serialization-json to v1.6.2
- Update dependency org.jetbrains.kotlinx:kotlinx-serialization-json-okio to v1.6.2
- Update dependency org.jetbrains.kotlinx:kotlinx-serialization-protobuf to v1.6.2
- Update dependency org.jetbrains.kotlinx:kotlinx-coroutines-android to v1.8.0
- Update dependency org.jetbrains.kotlinx:kotlinx-coroutines-core to v1.8.0
- Resolved some compile warnings
- Update dependency com.github.tachiyomiorg:unifile to 7c257e1c64

## [1.7.14]

### Changes
- Added splash to reader (in case it being opened from shortcut)
- Increased long strip split height
- Use normalized app name by default as folder name

### Fixes
- Fixed cutout support being broken

### Other
- Move AppState from DI to Application class to reduce race condition

## [1.7.13]

### Additions
- Ported Tachi's cutout option
- Added Doki theme (dark only)

### Changes
- Repositioned cutout options in settings
- Splash icon now uses coloured variant of the icon
- Removed deep link for sources, this should be handled by extensions
- Removed braces from nightly (and debug) app name

### Fixes
- Fixed preference summary not updating after being changed once
- Fixed legacy appbar is visible on compose when being launched from deeplink
- Fixed some app icon not generated properly
- Fixed splash icon doesn't fit properly on Android 12+

### Other
- Migrate to using Android 12's SplashScreen API
- Clean up unused variables from ExtensionInstaller

## [1.7.12]

### Additions
- Scanlator filter is now being backed up (@jobobby04)

### Fixes
- Fixed error handling for MAL tracking (@AntsyLich)
- Fixed extension installer preference incompatibility with modern Tachi

### Other
- Split PreferencesHelper even more
- Simplify extension install issue fix (@AwkwardPeak7)
- Update dependency com.github.tachiyomiorg:image-decoder to fbd6601290
- Replace dependency com.github.jays2kings:subsampling-scale-image-view with com.github.null2264:subsampling-scale-image-view
- Update dependency com.github.null2264:subsampling-scale-image-view to e3cffd59c5

## [1.7.11]

### Fixes
- Fixed MAL tracker issue (@AntsyLich)
- Fixed trusting extension caused it to appear twice

### Other
- Change Shikimori client from Tachi's to Yōkai's
- Move TrackPreferences to PreferenceModule

## [1.7.10]

### Addition
- Content type filter to hide SFW/NSFW entries
- Confirmation before revoking all trusted extension

### Changes
- Revert Webcomic -> Webtoon

### Fixes
- Fix app bar disappearing on (scrolled) migration page
- Fix installed extensions stuck in "installable" state
- Fix untrusted extensions not having an icon

### Other
- Changed (most) trackers' client id and secret
- Add or changed user-agent for trackers

## [1.7.9]

### Other
- Sync project with J2K [v1.7.4](https://github.com/Jays2Kings/tachiyomiJ2K/releases/tag/v1.7.4)

## [1.7.8]

### Changes
- Local source now try to find entries not only in `Yōkai/` but also in `Yokai/` and `TachiyomiJ2K/` for easier migration

### Other
- Changed AniList and MAL clientId, you may need to logout and re-login

## [1.7.7]

### Changes
- Hopper icon now changes depending on currently active group type (J2K)

### Fixes
- Fixed bookmarked entries not being detected as bookmarked on certain extensions

## [1.7.6]

### Additions
- Shortcut to Extension Repos from Browser -> Extensions page
- Added confirmation before extension repo deletion

### Changes
- Adjusted dialogs background colour to be more consistent with app theme

### Fixes
- Fixed visual glitch where page sometime empty on launch
- Fixed extension interceptors receiving compressed responses (T)

### Other
- Newly added strings from v1.7.5 is now translatable

## [1.7.5]

### Additions
- Ported custom extension repo from upstream

### Changes
- Removed built-in extension repo
- Removed links related to Tachiyomi
- Ported upstream's trust extension logic
- Rebrand to Yōkai

### Other
- Start migrating to Compose

## [1.7.4]

### Changes
- Rename project to Yōkai (Z)
- Replace Tachiyomi's purged extensions with Keiyoushi extensions (Temporary solution until I ported custom extension repo feature) (Z)
- Unread count now respect scanlator filter (J2K)

### Fixes
- Fixed visual glitch on certain page (J2K)
