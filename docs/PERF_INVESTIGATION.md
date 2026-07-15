# Hayai navigation & source-browse performance investigation

## 2026-07-14 re-audit — persistent navigation, chrome, and Android motion

This pass re-audited the current persistent-root-tab implementation after the earlier
device profiling below. No Android device was connected to the workspace, so this section
does not invent a new jank percentage; it records hot-path work proven directly from the
current implementation and the validation performed after removing it.

### Root navigation

- `RootTabsController` drove two complete screen trees from a `ValueAnimator` update
  listener. That executed Kotlin and invalidated both trees on the UI thread for every
  animation frame. Root swaps now use `ViewPropertyAnimator` with temporary hardware
  layers, a small direction/RTL-aware translation, deterministic rapid-tap cancellation,
  and one settle point that restores all sibling visibility/alpha state.
- Root-tab activation synchronously ran the full activity-global chrome/menu walk even
  though Library, Recents, and Browse each own a persistent local app bar. Root swaps now
  do only the navigation/app-bar visibility work needed for the first frame; menu/title
  reconciliation runs after the motion settles. Preserved pushed detail stacks still use
  the complete global-chrome path.
- The activity-global app bar was reset twice for a single Conductor enter event (once by
  `BaseController`, once by the activity listener). The sync is now idempotent per active
  controller/visibility state, and local-app-bar-to-local-app-bar swaps no longer clear a
  hidden global tab/search/menu hierarchy.

### App-bar and library binding

- `ExpandedAppBarLayout.applyTabs()` previously called `clearTabs()` on every activation,
  removing every tab, inflating every Library badge view again, reattaching listeners, and
  forcing `TabLayout` to measure from scratch. It now reuses compatible tab views, updates
  labels/counts in place, changes selection without dispatching a false user callback, and
  only rebinds a pager listener when the pager instance changes.
- `LibraryCategoryAdapter.performFilterAsync()` called `updateDataSet()` (which already
  invokes `notifyDataSetChanged`) and then immediately invoked `notifyDataSetChanged()` a
  second time. The duplicate full visible-row bind/layout pass was removed.

### Deeper scroll and Recents audit

- `scrollViewWith()` installed a Conductor lifecycle listener and RecyclerView/NestedScrollView
  callbacks on every view creation without removing them. Controllers outlive their views, so
  rotations and theme/configuration recreations retained old app bars/recyclers and accumulated
  change callbacks. The binding is now symmetric: it removes scroll callbacks, cancels its color
  animator, removes transition shims, and unregisters its lifecycle listener at view destruction.
- Recents and Browse explicitly set `itemAnimator = null`, but `scrollViewWith()` immediately
  replaced that with an app-bar-aware `DefaultItemAnimator`. This silently restored full-row
  change animations on the two screens whose nested rows are most expensive. The helper now
  preserves a screen's disabled-animator policy.
- The scroll hot path repeatedly resolved the same Material theme attributes and reapplied
  identical app-bar/card/status-bar colors. Theme colors are cached per app-bar instance and
  render properties are only written when their value changes. Toolbar title alpha and
  main/search toolbar switching are also state-gated; pill-menu cleanup no longer allocates a
  temporary list.
- `RecentMangaAdapter.updateDataSet()` always dispatches `notifyDataSetChanged()`. Presenter
  fan-out can emit the same logical list repeatedly, causing every visible nested Recents row to
  rebind. Recents now snapshots the fields consumed by its holders and skips only byte-for-byte
  equivalent logical submissions. Three unit tests cover stable snapshots and visible main/extra
  chapter changes.
- `RecentMangaHeaderItem.equals()` treated every `LibraryHeaderItem` as equal due to a self-
  comparison (`recentsType == recentsType`). Cross-type headers now remain distinct, and Recents
  header content callbacks include their labels/source metadata for future granular diffing.

### Modern Android motion/back behavior

- Predictive back is now edge-aware: right-edge gestures translate in the opposite
  direction, RTL pushes use the matching direction, and the completion handler continues
  from the gesture's actual translation instead of jumping to a hard-coded rightward pop.
- Cancelling predictive back now animates the current screen, previous screen, and edge
  shadow back to their resting state instead of snapping in one frame. Reduced-motion mode
  still settles instantly.
- The app previously used hidden reflection to force `ValueAnimator`'s process duration
  scale to either `0` or `1`. Restoring `1` could override Android's system "Remove
  animations" accessibility setting. The app no longer mutates the platform scale;
  `ReducedMotion.isEnabled()` combines the app preference with Android's animator-enabled
  state, while the platform/Compose runtimes retain control of system duration scaling.

### Extension sheet and migration materialization

- Re-analysis of the saved root-navigation trace confirms the remaining worst frames are
  main-thread traversal/layout bursts, not RenderThread saturation: the largest frames were
  435–464ms, with 357–383ms in traversal/layout. `extension_card_item` remained the dominant
  repeated inflation type in that older capture. No new device metric is claimed for this pass.
- Extension, novel-plugin, migration-source, and migration-manga adapters now snapshot every
  field their holders bind. Repeated lifecycle/network/database emissions with identical visible
  content no longer dispatch a full `notifyDataSetChanged()` and rebind all visible rows.
- Browse startup already launches `firstTimeMigration()` from the sheet presenter; the controller
  also scheduled `refreshMigrations()` on the same initial enter. That duplicate query/submission
  is removed while real return/resume refreshes remain intact.
- Legacy ViewPager eagerly creates its adjacent page. The Migration page is now a lightweight host
  while Browse enters and the sheet is collapsed; its adapter can receive data detached, and the
  recycler materializes after the first sheet expansion settles (or immediately if selected first).
- The shared extension `RecycledViewPool` was process-static. Pooled holders retained the old
  Activity context and their constructor-time adapter/click listener across controller recreation.
  It is now scoped to one sheet lifetime, explicitly cleared at destruction, and recycler/adapter
  attachment is idempotent. Persistent root tabs still preserve the pool for ordinary tab swaps.

### Library/source ownership and remaining UI-thread animation work

- Library, Recents, and Browse also held RecyclerView pools in process-static companion fields.
  Their holders capture adapter/controller listeners and Activity-themed contexts, so reuse after
  recreation could retain an old Activity or route clicks through a destroyed controller. Pools
  are now created and cleared with each view. Persistent root views still retain them throughout
  normal tab switching, which is the performance-critical reuse window.
- Library activation refreshed its tabs/mini-bar once through display reconciliation and again
  immediately through local-chrome setup. Activation now performs one chrome refresh. When the
  category set changes, old ViewPager pages are no longer fully rebound immediately before
  `POSITION_NONE` destroys them.
- Library adapters now snapshot all holder-visible manga, unread/download, cover, category,
  placeholder, layout, and display-option state. Equivalent presenter/filter emissions skip the
  full FlexibleAdapter visible-row submission while real content changes still rebind. Deferred
  title-layout callbacks are cancelled on holder reuse to prevent an old item from changing the
  newly-bound subtitle.
- Browse source lists and the scrollable last-used-source header use the same complete-content
  gate. Source icon cache warming now starts once per process on IO; row holders cancel deferred
  icon callbacks when rebound, avoiding stale icons and unnecessary main-loop PackageManager work.
- The activity search-card fade, bottom-navigation fade, and pre-Android-12 splash exit no longer
  run `ValueAnimator` update callbacks on the UI thread. They use render-property animators with
  generation-safe cancellation, temporary hardware layers, and immediate reduced-motion settling.

### Cell-binding and Library header depth pass

- Source-browse grid badges no longer call `removeAllViews()` and allocate a new wedge plus
  `MaterialTextView` hierarchy on every bind. `BrowseBadgeStrip` retains reusable slots, performs
  a content equality check, and only grows its hierarchy when a row needs more segments.
- Browse grid/list holders snapshot visible text/badge and cover-request inputs. Equivalent binds
  do not resolve the same theme/string resources or cancel and restart Coil; recycled holders
  explicitly cancel their view-target request and clear the snapshot. The list holder also resets
  its drawable, scale type, and favourite alpha so a null cover cannot expose recycled state.
- Browse-source teardown now detaches the adapter and clears its view-lifetime pool. Display-mode
  replacement does the same for the discarded recycler, preventing image work and holder references
  from surviving through the removed view tree.
- Library grid/list holders now gate equivalent holder binds, cover requests, deferred title-layout
  tasks, and freeform cover-ratio layout-param writes independently. FlexibleAdapter recycle events
  cancel callbacks and Coil targets rather than letting detached rows finish stale work.
- `LibraryBadge` caches its complete unread/download/total/language/shape input, avoiding repeated
  resource lookup, shape drawable allocation, padding mutation, and child visibility traversal when
  the badge has not changed.
- Category headers previously counted visible manga by scanning the complete adapter once per header
  bind (O(categories × items)). The adapter now builds the filtered category-count map once per
  submission and header binds use O(1) lookups; flag resource IDs are cached per holder as well.
  Header structural content is snapshotted too, so live queue/selection ticks can update their status
  without rewriting margins, labels, icons, sort controls, and collapse drawables.

### MangaDetails predictive back, chapter binding, and selection motion

- Predictive-back progress previously transformed MangaDetails before the controller could consume
  the committed press to close chapter selection. The selection cleared, but no pop or cancellation
  restored the transform, leaving the screen half-transparent and partially translated. Controllers
  now expose whether gesture progress represents navigation; active MangaDetails selection suppresses
  only the navigation preview, consumes the committed back normally, and leaves the screen at rest.
- `MangaDetailsAdapter` now snapshots every chapter field rendered by `ChapterHolder`, plus filter,
  title-display, and translation-button state. Equivalent presenter emissions stop before
  `FlexibleAdapter.updateDataSet()` and avoid a complete visible-row binding/layout pass.
- Chapter holders independently gate full row content and download/status visuals. Theme tint lookup,
  status-string construction, bookmark drawable work, and download-button updates only run when their
  inputs change. Recycled holders cancel the swipe tutorial and clear all render state.
- Download progress averaging no longer allocates an intermediate page-progress list. Exiting chapter
  selection repaints FlexibleAdapter's bounded attached/cached holder set instead of scanning every
  chapter and performing a RecyclerView lookup for each.
- The chapter action bar replaced expanding/shrinking size and animated row weights with fade/slide
  render motion. This avoids per-frame sibling remeasurement and follows app/system reduced-motion.
  The swipe tutorial similarly switches its midpoint state at an animator boundary instead of using
  a Kotlin update listener every frame, and duplicate tutorial animators are prevented.

### Verification

- `:app:compileStandardDebugKotlin` — passed.
- `:app:testStandardDebugUnitTest` — passed, 88 tests, 0 failures/errors/skips.
- `git diff --check` — passed.
- A full `lintStandardDebug` analysis exceeded the 15-minute validation window without
  producing a report or diagnostic; it was stopped, and is not represented as passing.

Captured during an extended profiling session against a release-equivalent debug build on a
Samsung A35 (1080×2340 @ 450dpi, 120Hz, animation scales = 1.0). All measurements via
`adb shell dumpsys gfxinfo <pkg>` and `adb shell perfetto -t … sched gfx view input am wm res`.

## Baseline (before any fixes)

Five reported problem flows, each captured cleanly with a `gfxinfo reset` before the action and
a dump immediately after:

| Flow | Jank % | Legacy % | p90 / p95 / p99 | Missed vsync | Slow UI thread | ≥150 ms frames |
|------|-------:|---------:|----------------:|-------------:|---------------:|---------------:|
| 1 — Library ↔ Recents (tabbed mode) | 5.03 % | 10.75 % | 12 / 16 / 200 ms | 13 | 23 | 16 |
| 2 — Browse ↔ Recents | 2.34 % | 3.61 % | 10 / 12 / 200 ms | 13 | 10 | 19 |
| 3 — Library → Manga ×5 | **12.79 %** | **32.41 %** | 24 / 61 / 200 ms | 60 | 84 | 18 + heavy mid-tail |
| 4 — Source browse scroll | **30.18 %** | **54.24 %** | 89 / 117 / 150 ms | **364** | **509** | 50+ + chronic grinding |
| 5 — Recents tab switching | 3.21 % | 7.72 % | 11 / 14 / 48 ms | 1 | 41 | 15 |

Source-browse scroll dominated by jank percentage and missed vsync count, so it became the focus
of this session.

## Source browse — chain of fixes

### 1. `BrowseSourceItem` ComposeView recycling

`createViewHolder` was building a fresh `ComposeView` with the default
`DisposeOnDetachedFromWindow` strategy. Every recycle disposed the Composition; every rebind
booted a new one. Six other places in the codebase already use the correct
`DisposeOnDetachedFromWindowOrReleasedFromPool`.

**Fix:** `app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/BrowseSourceItem.kt` — set the
correct strategy on the per-cell ComposeView.

### 2. M3 `LoadingIndicator` per cell

Each grid cell had `MangaCover` showing a `ColorPainter(0x1F888888)` placeholder **and** a
`LoadingIndicator` over it while loading. The indicator is the M3 Expressive animated one —
one continuously-animating instance per visible cell, all running on the main thread. The
placeholder alone is sufficient feedback.

**Fix:** `app/src/main/java/yokai/presentation/manga/components/CommonMangaItem.kt` — drop the
`isLoading` state + `LoadingIndicator` from both `MangaCompactGridItem` and
`MangaComfortableGridItem`. Also removed the `onState` Coil callback that was driving cell
recompositions per state transition.

**Result:** 30.18 % → 14.06 % jank, p90 89 ms → 29 ms, missed-vsync 364 → 86.

### 3. Coil crossfade off globally

`crossfade(!ReducedMotion.isEnabled())` was on by default. Each loaded cover ran a 100 ms
main-thread alpha animator. During fast scroll, N concurrent crossfades pumped per-frame alpha
work.

**Fix:** `app/src/main/java/eu/kanade/tachiyomi/App.kt` — `crossfade(false)`. Only opt-in
callers (recommends screens) keep it.

**Result:** 14.06 % → 8.84 % jank, p99 89 ms → 69 ms, missed-vsync 86 → 27.

### 4. Skip speculative palette extraction for non-library covers

`MangaCoverFetcher.fetch()` calls `setRatioAndColorsInScope()` for every cover. Inside,
`MangaCoverMetadata.setRatioAndColors` decodes the bitmap at inSampleSize=4 and runs
`Palette.from(bitmap).generate()`. `Palette` dispatches its completion callback on the **main
looper**. With many covers loading concurrently on first source entry, these callbacks queued
on the main thread.

For source browse the work is pure waste: only `LibraryGridHolder` reads
`dominantCoverColors`; `vibrantCoverColor` is only used by `MangaDetailsController`, which
computes its own when needed (see `MangaDetailsController.kt:670-681`).

**Fix:** `app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt` — short-circuit
`setRatioAndColorsInScope` when `!isInLibrary && !force`.

### 5. `BoxWithConstraints` → `Box` in `MangaGridCover`

`MangaGridCover` wrapped in `BoxWithConstraints`, but its callers ignore the constraints.
`BoxWithConstraints` uses `SubcomposeLayout`, which adds an extra measure pass per cell.

**Fix:** `app/src/main/java/yokai/presentation/manga/components/CommonMangaItem.kt` — swap to
plain `Box`.

### 6. Bigger item view cache + null item animator

RecyclerView default `itemViewCacheSize` is 2 — small enough that scrolling churned cells
through the recycler pool. `DefaultItemAnimator` was also running fade-in animations on every
newly bound item, compounding the first-page burst.

**Fix:** `app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/BrowseSourceController.kt`
— `setItemViewCacheSize(8)` + `itemAnimator = null`.

### 7. Drop the wasted inflate of `manga_grid_item.xml`

`BrowseSourceItem.getLayoutRes()` returned `R.layout.manga_grid_item` (the Library's full
ConstraintLayout + MaterialCardView + CircularProgressIndicator + 4 TextViews + ImageView +
LibraryBadge) for grid mode. `createViewHolder` then **discarded the inflated view** and
constructed a fresh `ComposeView` in its place. Every grid cell paid the inflation cost for a
tree that was never attached. Perfetto showed up to 22 ms per inflate dominating the killer
frame.

**Fix:**
- New layout `app/src/main/res/layout/browse_source_compose_grid_item.xml` — just a wrapping
  `ComposeView`.
- `BrowseSourceItem.getLayoutRes()` returns the new id in grid mode.
- `createViewHolder` casts the inflated view to `ComposeView` directly instead of building a
  new one. Strategy is set on the cast.
- `BrowseSourceController` `spanSizeLookup` recognises both old (`manga_grid_item`) and new id
  for `spanSize = 1`.

### 8. Cache `createMdc3Theme` result per (theme, uiMode)

`YokaiTheme` calls Accompanist's `createMdc3Theme` (deprecated) inside each cell's
composition. That call walks ~20 theme attributes via `TypedArray` lookups every time. With 18
cells visible on first load, that's 18× full theme attribute resolution.

**Fix:** `app/src/main/java/yokai/presentation/theme/Theme.kt` — process-wide
`ConcurrentHashMap<Long, ColorScheme>` keyed on (theme hash, uiMode). First cell pays; the
remaining 17 take a cache hit.

## Final state for source browse

| Trace | Jank % | Legacy % | p90 / p95 / p99 | Missed vsync | Slow UI thread |
|-------|-------:|---------:|----------------:|-------------:|---------------:|
| Baseline | 30.18 % | 54.24 % | 89 / 117 / 150 ms | 364 | 509 |
| After 1 (ComposeView strategy) | 14.06 % | 51.34 % | 29 / 42 / 89 ms | 86 | 248 |
| After 1–2 (+ LoadingIndicator removal) | 14.06 % | 51.34 % | 29 / 42 / 89 ms | 86 | 248 |
| After 1–3 (+ crossfade off) | **8.84 %** | 27.05 % | 21 / 27 / 69 ms | 27 | 151 |
| After 1–4 (+ palette skip) | 7.08 % | 19.97 % | 16 / 24 / 133 ms | 13 | 73 |
| After 1–7 (+ inflate fix etc.) | ~7 % | ~18 % | ≈ 16 / 25 / 125 ms | low | low |

Steady-state scrolling is smooth. First-entry burst still has a perceived stutter when the data
arrives and the grid lays out 18+ cells in a single frame — that's the per-cell `ComposeView` +
first-composition cost (12–25 ms each) which is fundamental to the current architecture.

## Compose-native rewrite — attempted and reverted

To kill the first-entry burst, attempted a `LazyVerticalGrid`-based rewrite of the grid path
(Option C in the discussion):

- New files: `BrowseSourceComposeGrid.kt`, `BrowseSourceAppBarScrollBridge.kt`,
  `browse_source_compose_grid_item.xml`.
- Modified `BrowseSourceController` to host a single `ComposeView` containing the entire grid
  in grid mode; list mode kept the legacy `RecyclerView` path.
- AppBar collapse re-implemented as a `NestedScrollConnection` translating Compose scroll
  deltas back into the legacy `ExpandedAppBarLayout.updateAppBarAfterY()` machinery.
- Favourite-badge reactivity via a `composeRevision` counter (Hayai doesn't have Mihon's per-
  item `StateFlow<Manga>` so direct DB-observation per cell wasn't available).
- Reused Hayai's existing `MangaCompactGridItem` / `MangaComfortableGridItem` composables.

**Outcome:** built and ran, but the user reported it was "not smooth at all". Causes
(post-mortem): the per-cell composition cost is still paid; Mihon's smoothness comes from
`androidx.paging.compose.LazyPagingItems<StateFlow<Manga>>` + `MaterialTheme` once per Screen
+ Material3 `Scaffold` instead of `ExpandedAppBarLayout`. The compose-grid alone, without
those, doesn't move the needle.

Reverted entirely. The new files are deleted and the controller is back to its post-fix-7
state. None of the Compose grid code is in the tree.

## Future work

### Option B — view-based grid holder (SHIPPED 2026-05-30)

Implemented. The per-cell `ComposeView` (which paid a 12–25 ms first-composition on the
first-entry burst / fast scroll) is replaced by a pure XML/ViewBinding + ImageView + Coil
holder mirroring `LibraryGridHolder`. Per-cell cost now sits in the ~2–5 ms View range.

Files:
- New `app/src/main/res/layout/browse_source_grid_item.xml` — `MaterialCardView` (12dp
  corners, 2:3 `H,2:3` book-ratio cover ImageView) + bottom gradient + `compact_title`
  overlay + `BrowseBadgeStrip` overlay + below-card `title`. Cover background uses the
  existing `@color/cover_placeholder` (`#1F888888`, from `presentation/core`), which is the
  same value as the Compose `MangaCover` placeholder.
- New `app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/BrowseBadgeStrip.kt` — a
  `LinearLayout` that builds the three-segment slanted badge strip programmatically (Novel /
  EH category / In Library), using the existing `unread_angled_badge` wedge as the
  forward-slant separator (mirrors Compose `Badge` / `ForwardSlantedShape`, slant 6dp,
  height 18dp).
- `BrowseSourceGridHolder.kt` — rewritten View-based. Compact vs comfortable handled by
  toggling `compact_title`/`gradient` vs the below `title`. Favourite state dims the cover
  to alpha 0.34 (matches Compose `isSelected`). Cover via `loadManga(manga.cover())`, which
  applies the singleton `maxBitmapSize(2048)` + `precision(INEXACT)` defaults.
- `BrowseSourceItem.kt` — `getLayoutRes()` returns `R.layout.browse_source_grid_item`;
  `createViewHolder` builds the View holder directly (no ComposeView cast/strategy).
- `BrowseSourceController.kt` — `spanSizeLookup` recognises `browse_source_grid_item` for
  `spanSize = 1`.
- Deleted the now-unused `browse_source_compose_grid_item.xml`.

List mode, the display-mode overflow menu (comfortable/compact/list), and the
data/presenter layer are unchanged.

## Recents tab switching — chain of fixes

Baseline 3.21 % jank, p99 48 ms, 41 slow-UI frames, 15 frames ≥ 150 ms.

### 1. Recycler hygiene on `RecentsController`

`onViewCreated` had:

- `recycledViewPool.setMaxRecycledViews(0, 0)` — dead code (FlexibleAdapter keys
  `viewType` to layout-res id, so type `0` is never used).
- Default `itemViewCacheSize` (2) — tab swap thrashed the pool.
- `DefaultItemAnimator` left attached — stacked with `TransitionManager.beginDelayedTransition`
  used in `markAsRead`.

**Fix:** `app/src/main/java/eu/kanade/tachiyomi/ui/recents/RecentsController.kt:200-203`
— drop the dead `setMaxRecycledViews`, set `itemAnimator = null`,
`setItemViewCacheSize(8)`. Mark-read still animates via the explicit
`beginDelayedTransition` (view-level Fade independent of the item animator).

### 2. Cache layout-param cascade in `RecentMangaHolder`

`bind()` ran ~6 `updateLayoutParams { … }` calls unconditionally per row (card
dims, title/subtitle constraints, button-layout constraints, coverThumbnail/card
widths). Each fires `requestLayout()`; ConstraintLayout solver re-runs. The
values only depend on `(isSmallUpdates, freeformCovers)`, which are constant for
all rows of a given tab.

**Fix:** `app/src/main/java/eu/kanade/tachiyomi/ui/recents/RecentMangaHolder.kt`
— cache the last-applied pair per holder; skip the cascade when unchanged. First
bind to each holder (and after tab swap) pays once; subsequent same-tab binds
take the fast path.

### 3. Pre-warm extension icon cache for source headers

In History-by-Source view, `RecentMangaHeaderItem.bindSource` calls
`Source.icon()` → `ExtensionManager.getAppIconForSource()` →
`iconMap.getOrPut(pkgName) { PackageManager.loadIcon(...) }`. **First call per
package per process is a synchronous Binder/IPC on the UI thread**, ~5–50 ms
each. Cluster of N source headers materialising in one frame = N stacked Binder
calls. Matched the user-reported "slowest swap is Grouped → History-by-Source"
exactly.

**Fix:**

- `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt` —
  `iconMap` → `ConcurrentHashMap` for safe concurrent population; new
  `preloadInstalledIcons()` that waits for `installedExtensionsFlow` to populate,
  then iterates each source's id from the IO scope and forces the `getOrPut`
  path so PackageManager calls happen off the UI thread.
- `app/src/main/java/eu/kanade/tachiyomi/ui/recents/RecentsPresenter.kt` —
  call `preloadInstalledIcons()` in `onCreate()` so the warm starts when Recents
  first mounts; the warm completes well before any user navigation to
  History-by-Source.

### Recents final state

| Trace | Jank % | p90/p95/p99 | Missed vsync | Slow UI | Frames ≥ 150 ms |
|-------|-------:|------------:|-------------:|--------:|----------------:|
| Baseline | 3.21 % | 11 / 14 / 48 ms | 1 | 41 | 15 |
| After 1 (recycler) | 2.43 % | 10 / 12 / 150 ms (capped) | 6 | 13 | 21 |
| After 1–2 (+ holder cache) | 2.04 % | 10 / 13 / 65 ms | 3 | 26 | 23 |
| After 1–3 (+ icon preload) | **2.34 %** | **9 / 13 / 65 ms** | **1** | **9** | **10** |

`a0e48451cc` had source browse; these three Recents fixes ship in the follow-up
commit. Slow-UI-thread frames down 78 % from baseline; missed-vsync flat at
baseline level (was peaking at 6 after fix #1, smoothed back by fix #3); long
tail (≥150 ms) down 33 %.

### Residual stutter (left as known issue)

There are still ~10 frames ≥ 150 ms in a 6-swap run — roughly **one per swap**,
each being the single layout pass that materialises the new tab's rows from
`adapter.updateDataSet(list)` → `notifyDataSetChanged`. To eliminate, two paths:

- **DiffUtil + payloads + `ConcatAdapter`** per tab: dataset swap becomes
  "hide adapter A, show adapter B" — no rebind of unchanged rows. ~2–3 hr.
- **Visual masking**: fade `recentsFrameLayout` to alpha 0 during the swap,
  fade back to 1 in `showLists`. Cheap; perceived smoothness only — actual
  frame cost unchanged.

Neither shipped — current state is acceptable (97.66 % non-jank, 99.22 %
non-stutter). Flagged for follow-up if user revisits.

### 4. Mark-read fade no longer animates the whole reorder (2026-05-30)

User report: recents "lags a bit because it's trying to animate long updates and
moving chapters around" (mark-as-read reorders, history refresh). Root cause:
`markAsRead` ran `TransitionManager.beginDelayedTransition(binding.recycler, Fade)`
scoped to the **entire recycler**. That delayed transition stays pending across the
async `markChapterRead → getRecents() → showLists() → updateDataSet()` reorder, so
when the list re-lays-out, every moved/changed row fades — the list-wide churn the
user sees. (`itemAnimator = null` already kills RecyclerView's own move animations;
`updateDataSet` already uses the non-animated `notifyDataSetChanged` path, so the
churn came purely from this transition.)

**Fix:** `RecentsController.markAsRead` — `addTarget(holder.itemView)` so the Fade
only animates the marked row, and skip the delayed transition entirely when the
holder isn't on-screen. The intended single-row mark-read fade is preserved; the
bulk reorder swaps without per-row animation.

The expand/collapse `ChangeBounds + Slide` transition in `RecentMangaHolder` (sub-
chapter "show more") is a discrete user-tap animation, not a bulk-update path, and
is left untouched.

## Library → MangaDetails push — chain of fixes

User-reported sequence on this push: "library tabbed-mode tabs scatter and splatter
under the top bar that is being created as if it's trying to animate to the top,
then everything freezes then the manga page appears in one frame, then the first two
chapters jump then go down." Yokai (parent repo) doesn't have any of this. Each
distinct symptom maps to a different file. Diagnosed in parallel via four explorer
agents; one combined commit ships all fixes.

### 1. Tabs scatter — `LibraryController.teardownTabbedView` leaving branch

`teardownTabbedView(restoreAppBar = false)` was setting
`activityBinding?.mainTabs?.tabMode = MODE_FIXED` immediately on push-out, mid-frame
with the alpha-fade. `TabLayout` responds to a `tabMode` change by invalidating its
layout and re-measuring every tab — tabs visibly snap from variable scrollable widths
to equal fixed widths in one layout pass while the bar is still fully opaque. This
re-measure is what the user reads as "scatter/splatter".

**Fix:** `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryController.kt` —
delete the `tabMode = MODE_FIXED` line in the leaving branch of `teardownTabbedView`.
`showTabBar`'s `doOnEnd` already calls `removeAllTabs()`; the next controller that
binds tabs sets its own mode. No regression. Keep `lockYPos = false` (harmless state
release that doesn't trigger re-measurement).

### 2. Freeze — three synchronous Compose first-compositions in `MangaHeaderHolder.bind()`

The transition is `CrossFadeChangeHandler` (200 ms alpha + 20 % translateX), NOT a
`MaterialContainerTransform`. So the freeze isn't shared-element setup — it's pure
Compose composition cost paid synchronously on the main thread before the animator's
first frame can render.

Hayai added three ComposeViews to the header layout that Yokai didn't have:

- `buttonGroupCompose` (`MangaContinueReadingButton`)
- `metadataCompose` (`MangaMetadataSection` — EH metadata, empty for non-EH)
- `mangaGenresTags` (`GenreTagsSection` / `NamespaceGenreTagsSection`)

Each pays Compose runtime startup + `YokaiTheme` (MaterialExpressiveTheme)
composition + slot table + measure synchronously during `bind()`, which is called
from RecyclerView's first layout pass — which is on the same frame the push
animation starts. With 3 first-compositions stacked, the animator's frame budget
is starved and the user sees a freeze.

`pagePreviewCompose` was already deferred via `postOnAnimation` (good for the
freeze, but caused a separate shift — see #4).

**Fix:** `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaHeaderHolder.kt` —
wrap the three `setContent` calls in `postOnAnimation { ... }` so they compose on
the next animation frame, off the push critical path. Layout space is reserved by
`android:minHeight` on each ComposeView so when content lands there's no shift:

- `button_group_compose`: `minHeight="56dp"` (Material Button)
- `manga_genres_tags`: `minHeight="40dp"` (typical 1-row chip strip)
- `metadata_compose`: no minHeight needed (typically 0 for non-EH)

### 3. Freeze (palette fast-path) — `setItemColors` on Coil's `onSuccess`

`MangaDetailsController.setPaletteColor` has a cache-hit fast-path that fires
`setAccentColorValue` + `setHeaderColorValue` + `setItemColors()` synchronously
inside Coil's `onSuccess` callback. Coil delivers `onSuccess` on the main
dispatcher; `setItemColors()` iterates every visible `ChapterHolder` calling
`notifyStatus()` — all mid-frame. Yokai always defers via
`Palette.from(bitmap).generate { launchUI { ... } }`.

**Fix:** wrap the fast-path body in `launchUI { ... }` so the palette application
happens on the next UI tick instead of synchronously during the push frame.

### 4. Chapter rows jump after landing — `pagePreviewCompose` skeleton

`PagePreviewInlineSection` renders a 150 dp shimmer skeleton in its `Loading`
state. For sources that don't implement `PagePreviewSource`, the `LaunchedEffect`
hits `Unavailable` 1–3 frames later and collapses back to 0 dp. Result for
non-EH manga (most users, most of the time): chapter rows shift **down 150 dp
then back up** as the page-preview view loads then disappears.

**Fix:** in `MangaHeaderHolder.bind()`, do a synchronous
`presenter.source.getMainSource<PagePreviewSource>()` check before
`setContent`. If the source doesn't implement `PagePreviewSource`, set
`isVisible = false` on the ComposeView and skip `setContent` entirely. Only
EH/NHentai/Lanraragi etc. inflate the Compose subtree.

### 5. Top-bar appbar Y snap — `scrollViewWith` push-enter

`scrollViewWith`'s `onChangeStart` enter branch (line 435 of
`ControllerExtensions.kt`) called `updateAppBarAfterY(recycler)` immediately,
snapping the activity-level appBar Y from the outgoing controller's position
(could be collapsed if the user had scrolled) to the incoming controller's fresh
scroll position (Y=0) before the crossfade even started. The user reads this as
"topbar snaps to the top without animating".

**Fix:** moved the `updateAppBarAfterY(recycler)` call from `onChangeStart` to
`onChangeEnd`. The appbar now stays at the outgoing controller's position during
the 200 ms fade and only repositions once the transition completes. There's still
a small visible snap at the end (the appbar can't be in two positions at once
without an animator, which would be a bigger refactor) but it's masked by the
controller having already faded into place.

### Side effect — Library view "text/icon splatter" closed

The user-reported Library view splatter (task #14, baseline 5.03 %) turned out to
be the same root causes as fixes 1 + 2 (tabs re-measure + Compose first-compositions
firing mid-transition). Once those landed, the splatter went away. Task closed
without a dedicated fix.

### Known residual (left for follow-up)

- The appbar snap-at-end (#5) is still slightly visible. A truly clean fix would
  animate the appbar Y change over 200 ms in sync with the Conductor crossfade.
  User opted to accept the residual ("if it doesn't cost performance just leave
  it as that") rather than ship the bigger animation refactor.
- `setupSearchTBMenu` full menu rebuild on every controller change (Agent D's
  Patch D) — left untouched. Coalescing the 3 separate `ValueAnimator`s
  (`tabAnimation`, `searchBarAnimation`, `toolbarColorAnim`) into a single
  `AnimatorSet` would also help, deferred.

## MangaDetails page — polish + color fit + perf

User asked to polish the manga details surface itself (separate from the push
into it, which is the section above): "spacing and the description there's a
gap between the rating, the previews in eh looks a bit bad and cramped … make
the color detection a bit better and more fitting. make the layout overall more
consistent and stable." Scope: surgical changes only, no header rewrite — the
push critical path was just stabilized and a header-Compose rewrite would touch
it. Skipped baselining (gfxinfo/perfetto) because the changes are surface-level
and the perf-sensitive paths (push, scroll) weren't reported regressed.

### 1. `Palette.getBestColor` — too eager to pick dominant

`LibraryMangaImageTarget.getBestColor()` picked `dominantSwatch.rgb` whenever
its saturation was ≥ 0.25 and luminance in (0.2, 0.8). On many covers the
dominant swatch is a faintly-tinted background plate (sky, paper, large flat
shading) that meets those bounds while the cover's actual accent lives in
`vibrantSwatch`. Result: washed-out accents on the manga page.

**Fix:** `app/src/main/java/eu/kanade/tachiyomi/data/coil/LibraryMangaImageTarget.kt`
— raised dominant saturation threshold 0.25 → 0.35 and added a population
guard requiring `dominantPop > vibPop * 2.5` before preferring dominant.
Otherwise vibrant wins. Final `?: dominantSwatch?.rgb` fallback preserves
behavior for flat covers where only dominant exists. Shared with
`MangaCoverMetadata.kt:88`, so library tints get the same improvement.

Cached colors in `vibrantCoverColorMap` / `coverColorMap` are untouched —
existing library covers keep their previous picks until refreshed; new covers
go through the new logic. Non-regression for users with established libraries.

### 2. Soften accent blend in `setAccentColorValue`

`MangaDetailsController.setAccentColorValue` blended the accent toward
`colorOnDownloadBadgeDayNight` (a contrast text color) with factor
`luminance * 0.5` light / `-(luminance - 1) * 0.33` dark. The blend ran even
when the accent was already legible, washing out covers that didn't need
correction.

**Fix:** `MangaDetailsController.kt:317-326` — factors softened to
`luminance * 0.35` / `-(luminance - 1) * 0.22`. The same luminance gates
(`> 0.4` light / `≤ 0.6` dark) still trigger correction so out-of-theme
accents still get pulled into range, just less aggressively.

### 3. `EHentaiDescription` rating row + spacing

The EH metadata block had a Row with the genre chip on the left and a
two-line Column on the right (stars over `"%.2f"`). The right column made
Row 1 taller than the chip and produced visible vertical slack against the
uploader row below it. Outer padding was asymmetric
(`start=16, end=4, top=8, bottom=4`) while the rest of the header uses
`marginEnd=16dp`, so the right edge "ran out" before the rest of the page did.
Internal `spacedBy(4.dp)` was tighter than the header's 8/12 rhythm.

**Fix:** `app/src/main/java/exh/ui/metadata/adapters/EHentaiDescription.kt`
— stars + numeric rating inline on one line; outer padding `horizontal=16,
vertical=8`; vertical `spacedBy(8.dp)`. Rating is hidden when `≤ 0` instead
of showing five empty stars. FlowRow stat-row vertical spacing `2 → 4dp` so
wrapping stats line up cleanly.

### 4. `manga_header_item.xml` rhythm

Header XML mixed 6/8/12/14/16dp margins between blocks. Normalized to the
8/12/16 scale:

- `button_layout marginTop`: 14 → 12
- `manga_summary_label marginTop`: 6 → 8

Net header height unchanged (one block tightened by 2dp, the next loosened
by 2dp). Closes the user-reported "gap between rating and description" by
making the metadata→description-label transition match the 8dp rhythm of
the rest of the header.

### 5. EH page-preview strip polish + stability

`PagePreviewInlineSection` had three issues:

- LazyRow had `Modifier.padding(start=16.dp)` instead of `contentPadding` —
  no end padding, so the last card kissed the right edge with no fade-out
  affordance.
- "View all" CTA was a centered `Text` in a full-width `Box` *below* the
  strip, adding ~32dp to the section height when previews resolved but not
  during Loading. The Loading→Success transition therefore jumped from
  154dp to 186dp.
- Page-index label below each thumb was the default surface-text colour
  on the surface background — low contrast and visually disconnected from
  the thumb.
- No `key` on `items()`, so reorder/recompose churned all cells.

**Fix:** `app/src/main/java/exh/ui/pagepreview/components/PagePreviewInlineSection.kt`
— rewrote the section:

- Single `LazyRow` for both Loading and Success states with shared
  `contentPadding = horizontal=16dp` and `clipToPadding = false`.
- `THUMB_HEIGHT/WIDTH` 150/105 → 152/108 (corners now `shapes.medium`).
- Page number rendered as white text over a bottom-aligned 28dp vertical
  gradient scrim on the thumb itself (`Color.Transparent → Color.Black @ 0.55`),
  so it's readable regardless of cover content.
- "View all" moved to a trailing tail card in the LazyRow, same dimensions
  as the previews — keeps `ContentScale.FillWidth` for the thumbs (was kept
  intentionally, `Crop` would trim character heads at the top of pages).
- `items(s.previews, key = { it.imageUrl })` for stable recomposition.

Stability win: section height is now `152dp` in both Loading and Success,
`0dp` in Unavailable (still gated by the source-check from §"Library →
MangaDetails push #4"). No 32dp jump between Loading and Success means
chapter rows below no longer shift when previews arrive.

### 6. `MangaHeaderHolder` micro-perf

Two cleanups in `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaHeaderHolder.kt`:

- `applyBlur()` was called both in `init` (when the backdrop ImageView has
  no bitmap yet) and in `updateCover.onSuccess` (when the bitmap lands).
  The init call sets `alpha=0.2f` + `RenderEffect.createBlurEffect()` on an
  empty view — no visible effect, just wasted allocations on every header
  view holder construction. Dropped the init call; the XML's default
  `android:alpha="0.1"` is what shows until the cover loads, which is the
  same visual state as before.
- `mangaSummary.post { … }` ran a post-layout `lineCount` probe after every
  `bind()` to decide whether to auto-expand short descriptions. `bind()` is
  called on every `notifyDataSetChanged`, so this fired repeatedly even when
  the description hadn't changed. Cached `(description, genre, initialized)`
  hash in `lastBoundDescSignature`; the post-layout probe only re-runs when
  the signature changes. The synchronous `expand()/collapse()` based on
  `adapter.hasFilter()` moved out of the post — they only toggle visibility
  flags and don't need a layout pass.

### Known residuals (left for follow-up)

- **Unified header ComposeView** — the four ComposeViews
  (`buttonGroupCompose`, `metadataCompose`, `mangaGenresTags`,
  `pagePreviewCompose`) still pay 4× Compose runtime entry + `YokaiTheme`
  composition. Coalescing into a single `ComposeView` hosting one
  `YokaiTheme { Column { … } }` would cut slot-table allocations and give
  a cohesive Compose subtree. Deferred because the push critical path was
  just stabilized and the unified header touches the same surface.
- **`setGenreTags` re-`setContent` on color change** — `updateColors(true)`
  re-runs `setContent` on `mangaGenresTags` to flow the new accent into the
  chip container colour. Should pipe the accent through `mutableStateOf`
  state instead; coupled with the unified-ComposeView follow-up.
- **No re-baseline** — user picked B/C/D/E and skipped the gfxinfo/perfetto
  pass. Surface didn't have a captured baseline before this work, so the
  perf wins are estimated from the source rather than measured. Open if a
  regression report comes in.

## Sources bottom-sheet tab swap — chain of fixes

User report: swapping between the Extensions / Novel / Migration tabs inside
`ExtensionBottomSheet` stutters. Worst on Migration → Extensions/Novel.

### 1. Per-row item animator

Each tab's `RecyclerWithScrollerView` recycler had no `itemAnimator = null` and a
default cache size of 2. Same fix as Recents / Browse.

**Fix:** `app/src/main/java/eu/kanade/tachiyomi/ui/extension/RecyclerWithScrollerView.kt`
— `setItemViewCacheSize(8)` + `itemAnimator = null` in `setUp`.

### 2. "Renders empty then fills in" on Novel tab

`presenter.onCreate()` eagerly loaded extensions but **not** novel plugins. The
novel adapter was bound to its recycler at pager init but the data wasn't
fetched until the user actually tapped the Novel tab — so the first tap showed
an empty list, then `refreshNovelPlugins()` ran, then the list populated.

**Fix:** `app/src/main/java/eu/kanade/tachiyomi/ui/extension/ExtensionBottomPresenter.kt`
— call `refreshNovelPlugins()` at the end of `onCreate()` so novel data is
loaded in the background as soon as the sheet is created. By the time the user
taps the tab, cached data shows immediately.

### 3. Redundant per-tap work in `onTabSelected`

Every tab tap called:
- `controller.updateTitleAndMenu()` — which on Migration↔{Extensions, Novel}
  crosses menu groups (`migration_main` vs `extension_main`) and does
  synchronous `toolbar.menu.clear()` + `inflateMenu(...)` + SearchView wiring.
- `recycler.requestLayout()` on the destination — re-measures heavy items
  mid-swipe (redundant).
- `presenter.refreshNovelPlugins()` for tab position 1 — duplicate of the
  onCreate preload; fires a network call on every tap.

**Fix:** `app/src/main/java/eu/kanade/tachiyomi/ui/extension/ExtensionBottomSheet.kt`
— drop `requestLayout()`, drop the per-tap `refreshNovelPlugins` (still in
`onTabReselected` for explicit user refresh), and defer `updateTitleAndMenu()`
by `SWAP_DEFER_MS = 350L` past the typical ViewPager settle.

### 4. Migration adapter swap on tab leave

`onTabUnselected(2)` called `presenter.deselectSource()` which fires
`setMigrationSources()` that swaps the migration adapter (MangaAdapter →
SourceAdapter when the user had drilled into a source) — `RecyclerView.setAdapter`
triggers a full layout pass on the still-attached migration recycler. With a
plain `View.post`, the swap landed on the swipe-settle frame and was visible.

**Fix:** same file — change `binding.pager.post { ... }` to
`postDelayed({ ... }, SWAP_DEFER_MS)` so the adapter swap fires after the
swipe has visually settled.

### Residual

User reports a remaining slight stutter on Migration → Extensions/Novel —
accepted as-is. Open candidates for future work if revisited:

- `SourceHolder.bind` at `app/src/main/java/eu/kanade/tachiyomi/ui/migration/SourceHolder.kt:56-69`
  posts `itemView.post { source.icon() }` for every visible source row.
  `source.icon()` → `ExtensionManager.getAppIconForSource()` → `PackageManager.loadIcon()`
  is a synchronous Binder IPC. On the first entry to the Migration tab per
  process, N posted Binder calls fire on the same frame. The `iconMap` warm
  via `RecentsPresenter.onCreate.preloadInstalledIcons()` covers users who
  opened Recents first; users who navigate straight to Browse → sources sheet →
  Migration hit the cold path. Triggering `preloadInstalledIcons()` from
  `BrowseController` initialization too would close this gap.
- Pre-resolve source icons off-thread inside `BaseMigrationPresenter` and cache
  on `SourceItem` (matching how `ExtensionHolder` pre-resolves), so
  `SourceHolder.bind` reads a cached `Drawable?` instead of triggering an IPC.
- Migration's MangaAdapter path still pays a full layout pass on the adapter
  swap — even deferred 350ms it's visible if the user re-enters Migration
  quickly. Could skip the swap entirely and lazy-reset on re-entry.

## Root nav transitions (Library/Recents/Browse) — chain of fixes

Baseline (mixed 6-swap run across all three roots, post sources-sheet fixes):

- Jank: 7.26 %, p99 300ms, Missed vsync 41, Slow UI 59, ~35 frames >= 200ms
  with peaks at 740ms / 506ms / 438ms

Diagnosis driven by a perfetto trace with `android.os.Trace.beginSection`
markers around all suspect functions (`Hayai/...`). Markers persist in the
code for future re-runs — capture with:

```
adb shell perfetto -o /data/misc/perfetto-traces/trace.pftrace -t 15s \
  -a dev.ahmedmohamed.hayai.debug \
  sched gfx view input am wm res binder_driver
```

then analyse via the Python `perfetto` package. Scripts at `.perf/analyze_rootnav*.py`.

### Trace findings (dominant costs per long frame)

- `RV OnLayout` runs 300–435ms per long frame.
- Inside: 10–15 `RV onCreateViewHolder` + `inflate` calls, each 15–40ms.
- Layout IDs of the heavy inflations:
  - `extension_card_item` (0x7f0d017e) — extension bottom sheet rows
  - `extension_card_header` (0x7f0d017d) — extension list headers
  - `manga_grid_item` (0x7f0d02e1) — Library/Browse grid items
- `LibraryController.onChangeStarted.enter` is the only controller hook >= 30ms (~35ms each).
  Other controllers' onChangeStarted are sub-10ms.

### 1. Skip no-op `nav` alpha animation in `syncActivityViewWithController`

`MainActivity.kt` always fired a 150ms `nav` alpha ValueAnimator on every
controller change, even when target alpha matched current (the common case
for root↔root since all three root controllers keep the bottom nav visible
at `alpha=1f`). 150ms of pure wasted per-frame invalidates.

**Fix:** short-circuit when `nav.alpha == targetAlpha`.

### 2. Cache `setSearchTBLongClick`

`setFloatingToolbar` re-attached the same `OnLongClickListener` on every
controller change. Set once behind a `searchTBLongClickSet` flag.

### 3. Defer `setupSearchTBMenu` via `post`

The menu diff + add/update/remove + actionMenuView requestLayout was running
synchronously on the change frame, blocking the Conductor crossfade.
`binding.toolbar.post { setupSearchTBMenu(...) }` moves it past the current
frame so the rebuild lands during the fade rather than gating it.

### 4. Defer `BrowseController.onChangeStarted` presenter refreshes to `onChangeEnded`

`BrowseController` was calling four presenter refreshes synchronously on every
controller change (`refreshExtensions`, `refreshNovelPlugins`, `updateSources`,
`refreshMigrations` + `updateSheetMenu` + `updateTitleAndMenu`). Each fired
`updateDataSet` on the corresponding recycler, which triggered the multi-hundred
ms `RV OnLayout` with ~15 `onCreateViewHolder` + `inflate` per frame.

**Fix:** moved the entire refresh block from `onChangeStarted` to
`onChangeEnded`. Refreshes run on the post-fade idle frame instead of mid-swipe.

### Root nav final state

| Trace | Jank % | p90 | Missed vsync | Slow UI | Frames >= 200 ms |
|-------|-------:|----:|-------------:|--------:|-----------------:|
| Baseline | 7.26 % | 16 ms | 41 | 59 | ~35 |
| After 1–3 (anim/menu) | 5.32 % | 14 ms | 37 | 45 | ~38 |
| After 1–4 (+ Browse refresh defer) | 6.22 %* | 14 ms | **11** | **14** | **15** |

\* Total frames smaller this run (595 vs 1729), so the % comparison is noisy.
Underlying signals (missed vsync, slow UI, draw cmds) all dropped 60–70 %.

### Residual (left for follow-up)

Three killer frames remain per nav cycle (700–750ms each) — these are the
**first-time controller-creation cost** where the recyclers inflate their
entire visible set. Mostly unavoidable without:

- **Pre-warm `RecycledViewPool`** during app startup so the first attach of
  Library/Recents/Browse recyclers pulls from a hot pool instead of inflating.
- **Done 2026-07-15 — lazy-bind the extension bottom sheet's adjacent Migration page.**
  The ViewPager still creates a cheap page host for its adjacent item, but the Migration
  RecyclerView and its visible row holders are not materialized until the sheet has expanded.
- **Defer Library's `applyDisplayMode`** out of `onChangeStarted.enter`. It's
  the only controller's enter hook over 30ms (~35ms each). Likely candidates:
  the `setupTabbedView` / `teardownTabbedView` path.

The Trace markers (`Hayai/...`) stay in the source — re-running the perfetto
workflow above will show the same regions if these get revisited.

## Remaining problem flows (untouched)

| Flow | Baseline jank | Likely culprits |
|------|--------------:|------------------|
| Library ↔ Recents (tabbed) "text splatter" | 5.03 % | Cross-controller race: `LibraryController.onChangeStarted` calls `showTabBar(false, animate=true)` while `RecentsController.onChangeStarted` calls `mainTabs.bindStringTabs(...)` then `showTabBar(true)`. Library category labels get **overwritten in-place** by Recents labels mid-fade; TabLayout re-measures during the alpha animation. Surgical fix to `MainActivity.showTabBar` + the two controllers' `onChangeStarted`. |
| Browse hub — first entry | n/a | Sources hub entry cost (extension repo/lang reads, bottom sheet pre-warm). Trace required. |
| Recents item open (history → reader/manga) | n/a | Confirm whether it shares the same push cost as Library → Manga (now mostly fixed). |

### Profiling workflow

- Per-flow gfxinfo: `adb shell dumpsys gfxinfo <pkg> reset` → action → `adb shell dumpsys gfxinfo <pkg>`.
- Perfetto trace: `adb shell setprop security.perf_harden 0` (one-shot per session) then
  `adb shell perfetto -o /data/misc/perfetto-traces/trace.pftrace -t 15s sched gfx view input am wm res hal binder_driver`.
  Pull via PowerShell to avoid MSYS path mangling.
- Analyse via Python `perfetto` package (`pip install perfetto`); scripts in `.perf/`
  (gitignored).

## Browse cold-entry — background warmup + deferred sheet init (2026-05-17)

User report: "the browse first entry/render jank is still very strong". Earlier
defer of `refreshMigrations` + `updateTitleAndMenu` (POST_ENTRY_DEFER_MS = 280 ms)
trimmed the post-fade frame but the cold frame itself was still dominated by:

1. `BrowseControllerBinding.inflate` — `browse_controller.xml` `<include>`s the
   full `extensions_bottom_sheet.xml` (ConstraintLayout + CenteredToolbar +
   MaterialTextView + TabLayout + ViewPager).
2. `binding.bottomSheet.root.onCreate(this)` — assigns
   `pager.adapter = TabbedSheetAdapter()` which immediately inflates ViewPager
   pages 0 + 1 (offscreen-page-limit defaults to 1). Each page is a
   `recycler_with_scroller.xml` (RecyclerView + MaterialFastScroll + EmptyView)
   plus the adapter instantiation work. ~50–80 ms.
3. `setSheetToolbar()` — `inflateMenu(R.menu.extension_main)` synchronously
   parses + constructs the Menu.

All three stacked into the same frame the cross-fade was animating.

### Fix 1 — `BrowseWarmup` background prefetch from MainActivity startup

New `eu.kanade.tachiyomi.ui.source.BrowseWarmup` (single-shot, gated by
`AtomicBoolean`, launched from `MainActivity.onCreate` after `super.onCreate` on
`Dispatchers.IO`). It:

- **Drains `Resources.getXml(id)` to end-of-document** for every Browse-cold-path
  layout + menu (`browse_controller`, `extensions_bottom_sheet`,
  `recycler_with_scroller`, `source_item`, `source_header_item`,
  `extension_card_item`, `extension_card_header`, `extension_main`,
  `migration_main`, `catalogue_main`). This forces the AssetManager to fully
  decode + cache the binary XML block. Subsequent `LayoutInflater.inflate` /
  `MenuInflater.inflate` on the UI thread reuse the cached `XmlBlock` and skip
  the parse step (~5–15 ms saved per layout).
- **`Class.forName`** for the cold-path classes (`ExtensionBottomSheet`,
  `ExtensionAdapter`, `ExtensionBottomPresenter`, `RecyclerWithScrollerView`,
  `SourceAdapter` ×2, `SourcePresenter`, `MangaAdapter`, `NovelPluginAdapter`).
  Triggers dexopt + class verification + static-init on IO instead of on the
  cold UI frame.

Both swallow exceptions via `runCatching` — a removed/missing resource
shouldn't take down the cold path.

### Fix 2 — Deferred bottom-sheet wiring in `BrowseController`

`onViewCreated` split into a cold-frame phase and a deferred phase:

- **Cold frame:** source recycler setup, scroll insets listener, `presenter.onCreate()`
  (which already dispatches the heavy load to `Dispatchers.Default`), and an
  **eager** `binding.bottomSheet.root.sheetBehavior = BottomSheetBehavior.from(...)`
  so `setBottomSheetTabs` can compute the initial pill alpha. `setBottomSheetTabs(0f)`
  is correct because the BottomSheetBehavior's default state is `STATE_COLLAPSED`.
- **Deferred at +280 ms:** new `initBottomSheet()` method runs the rest —
  `binding.bottomSheet.root.onCreate(this)`, `addBottomSheetCallback(...)`,
  the `extensionInstaller` pref flow registration, the `if (showingExtensions) expand()`
  branch, `setSheetToolbar()`. Lands on the first idle frame after the
  cross-fade settles.

Guard surface for the defer window:

- `showSheet() / hideSheet() / toggleSheet()` — `if (!bottomSheetReady) initBottomSheet()`
  before the actual expand/collapse. Cold cross-fade is 250 ms so the user
  effectively can't trigger these inside the 30 ms gap, but the guard is cheap.
- `onChangeEnded(!type.isPush)` — POP_ENTER returning from a pushed controller
  forces `initBottomSheet` immediately (user is back, not in cold-fade).
- `onActivityResumed` — forces init if the user backgrounded the app during
  the defer window.
- `onChangeEnded(type.isEnter)` postDelayed branch — guarded by `bottomSheetReady`.
  Both `initBottomSheet` and this `postDelayed` are scheduled from the same
  view's handler at the same +280 ms target; FIFO ordering puts initBottomSheet
  first (scheduled in onViewCreated, earlier).
- `updateTitleAndMenu` — wrapped the `updateSheetMenu()` call in
  `if (bottomSheetReady)` because pre-init `tabs.selectedTabPosition` returns
  `-1` (no selection), which would fall through to `inflateMenu(R.menu.migration_main)`
  — the wrong menu. `initBottomSheet → setSheetToolbar → updateSheetMenu` does
  the first correct inflate.
- `onDestroyView` — `if (bottomSheetReady) binding.bottomSheet.root.onDestroy()`
  and reset `bottomSheetReady = false` so pop-back recreation re-runs init.

**Files touched:**
- `app/src/main/java/eu/kanade/tachiyomi/ui/source/BrowseWarmup.kt` (new)
- `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt` —
  `BrowseWarmup.primeAsync(resources)` alongside the existing
  `lifecycleScope.launchIO { extensionManager.getExtensionUpdates(true) }`.
- `app/src/main/java/eu/kanade/tachiyomi/ui/source/BrowseController.kt` —
  split init; `initBottomSheet` method; guards on showSheet/hideSheet/toggleSheet,
  onChangeEnded, onActivityResumed, updateTitleAndMenu, onDestroyView.

Trace markers: `Hayai/Browse.initBottomSheet`, plus the pre-existing
`Hayai/BrowseController.onChangeStarted.*` and `Hayai/Browse.refresh*` regions.

## Image-loading audit: site-specific loaders defeating the singleton

The app's Coil singleton (`App.newImageLoader` in `app/src/main/java/eu/kanade/tachiyomi/App.kt:363`)
is carefully tuned — `OkHttpNetworkFetcherFactory`, `TachiyomiImageDecoder` (JXL/AVIF),
`BufferedSourceFetcher`, `MangaCoverFetcher.MangaFactory`, animated GIF/decoder,
`MemoryCache.Builder().maxSizePercent(context)`, `allowRgb565(isLowRamDevice)`,
`crossfade(false)` (explicit — per-cell alpha animators saturated the main thread in
scroll grids), `fetcherCoroutineContext = Dispatchers.IO.limitedParallelism(8)`,
`decoderCoroutineContext = Dispatchers.IO.limitedParallelism(3)`.

### Sites that bypassed the singleton (fixed 2026-05-17)

| File | Problem | Fix |
|------|---------|-----|
| `exh/ui/pagepreview/PagePreviewScreen.kt` | `ImageLoader.Builder(context)…build()` per source — zero memory cache size, zero disk cache, no JXL/AVIF decoder, no parallelism caps | Routed through new `yokai.util.coil.loaderForSource(context, sourceClient)` which calls `singleton.newBuilder().components { add(OkHttpNetworkFetcherFactory(sourceClient::value)) }.build()` — inherits cache + decoders + pools from singleton |
| `exh/ui/pagepreview/components/PagePreviewInlineSection.kt` | Same | Same `loaderForSource` helper |
| `exh/recs/RecommendsScreen.kt` | Used singleton implicitly but re-enabled `crossfade(!ReducedMotion.isEnabled())` per-request — re-introduces the per-cell alpha animators in a scrollable LazyRow | Dropped per-request `crossfade()`; inherits singleton's `crossfade(false)` |
| `exh/recs/BrowseRecommendsScreen.kt` | Same | Same |

### New `yokai.util.coil.AppImageLoader.kt` surface

- `appImageLoader(context)` → just delegates to `context.imageLoader` (Coil's
  singleton accessor). Exists as the One Obvious Import for screens that just
  want the singleton — easier to grep + swap.
- `loaderForSource(context, sourceClient)` → returns
  `context.imageLoader.newBuilder().components { add(OkHttpNetworkFetcherFactory(sourceClient::value)) }.build()`.
  Coil tries factories in reverse-registration order, so the source-specific
  factory wins for requests the source can serve while everything else (JXL,
  AVIF, animated GIF, BufferedSource, MangaCover) stays available.
- `ImageRequest.Builder.hayaiPagePreviewDefaults()` → `precision(INEXACT)
  .maxBitmapSize(Size(1024, 1024)).crossfade(false)`. Applied in
  `PagePreviewItem` and `PreviewThumb`. The maxBitmapSize cap matters because
  both use `ContentScale.FillWidth` on 108–120dp cells — without the cap they'd
  decode full-resolution page images (often >2000px wide) into RAM only to
  draw them at ~350px wide.

### Pattern for future image-loading sites

1. If the surface just renders a manga cover (Library, Recents, MangaDetails, etc.),
   use `loadManga(...)` from `ImageViewExtensions.kt` for views or the
   `MangaCover` composable for Compose. Both already route through the singleton
   and apply the right defaults (`maxBitmapSize(2048)` + `precision(INEXACT)`).
2. If the surface loads source-served images that need source-specific OkHttp
   (preview pages, source-rendered thumbs), use
   `loaderForSource(context, sourceClient)` — never `ImageLoader.Builder(context)`.
3. If the request is a small thumbnail in a scroll grid, apply
   `.hayaiPagePreviewDefaults()` to the request builder so the bitmap cap +
   precision match.
4. Never set `.crossfade(true)` per-request unless the surface is explicitly
   a one-shot non-scrolling display.

### Other sites that already follow the pattern

- All `loadManga()` callers (LibraryGridHolder, LibraryListHolder, RecentMangaHolder,
  GlobalSearchMangaHolder, the migration source/manga holders, MangaHeaderHolder,
  EditMangaDialog, ExtensionHolder, SourceHolder, the Compose MangaCover surface).
- The glance widgets (`UpdatesMangaCover`, `UpdatesGridGlanceWidget`) which use
  `context.imageLoader` directly.

### Residual / not-touched

- `RecentMangaHeaderItem` (line 138) and `ExtensionHolder` (line 113) call
  `coil3.load(...)` directly with a transformation. Functionally correct
  (singleton-routed) but they could move behind a `loadSourceIcon` helper for
  consistency. Low priority.
- `PagePreviewItem`'s shimmer alpha drives a `Surface(color = surfaceVariant.copy(alpha = …))`
  which recomposes every frame while loading. Cheap (empty Surface), but a
  `drawBehind` would avoid it entirely. Not worth the surface area until
  someone profiles it as a hotspot.

## Open backlog (updated 2026-07-15)

Carrying forward — pick any of these next session.

### Verification — owed for today's work

- **Cross-fade tab-swap feel at 250 ms** (was 150 ms; bumped 2026-05-16). User
  reported the 150 ms version felt "very subtle, maybe increase it a bit i don't
  feel it". Verify the 250 ms feels intentional but not sluggish on rapid
  Library ↔ Recents ↔ Browse taps.
- **Browse first-entry feel after defer + warmup**. The deferred init lands at
  +280 ms; cross-fade is 250 ms. Verify the cold frame no longer looks like a
  blank flash and the sheet doesn't pop in awkwardly when its wiring lands.
- **"Fast tab swap = content never renders"** — was likely fixed by the
  AsyncLayoutInflater revert (task #35) but never explicitly reconfirmed.
  Reproduce by tapping bottom-nav items as fast as possible across 10-15 swaps;
  every page should resolve to its real content.

### Open flow profiling

- **Recents item open path** (history → reader / history → MangaDetails).
  Never profiled post-refactor; might share the Library → MangaDetails push
  cost (now mostly fixed) but the Recents item path is distinct.
- **Library ↔ Recents "text splatter"** in tabbed mode (5.03 % baseline jank).
  Root cause already understood — cross-controller race where
  `LibraryController.onChangeStarted` calls `showTabBar(false, animate=true)`
  while `RecentsController.onChangeStarted` calls `mainTabs.bindStringTabs(...)`
  + `showTabBar(true)` simultaneously, and TabLayout re-measures during the
  alpha animation. Fix is surgical to `MainActivity.showTabBar` + the two
  controllers' `onChangeStarted`.

### Cold-path improvements (extends today's Browse work)

- **Done 2026-07-10 — apply the BrowseWarmup pattern to Recents and Library cold paths.**
  `BrowseWarmup` is now `RootTabWarmup` and primes the Library controller/pager,
  grid/list/header rows, plus the Recents controller and its manga/subchapter/
  section/header/footer rows. It also class-loads their controllers, adapters,
  holders, and pager adapter on IO. This extends the existing trace-backed XML
  parse mitigation; no new frame-time claim is made because no device was attached.
- **Done — replace Library's eager `applyDisplayMode` path with idempotent surface
  reconciliation.** Tabbed/continuous trees now rebuild only when the computed target or
  category set changes; ordinary persistent-tab activation reuses the existing surface.
- **Pre-warm `RecycledViewPool`** during app startup so the first attach of
  Library/Recents/Browse recyclers pulls from a hot pool instead of inflating
  the visible row deficit on the first frame.
- **Done 2026-07-15 — lazy-bind the extension bottom sheet's adjacent Migration page.**
  `TabbedSheetAdapter` now leaves page 1 as a lightweight host during Browse entry and
  materializes its recycler after sheet expansion, with a selection fallback.

### Image loading (extends today's audit)

- **`loadSourceIcon(view, source)` helper** behind which the two raw
  `coil3.load(...)` callsites (`RecentMangaHeaderItem`, `ExtensionHolder`,
  plus a third `SourceHolder` pattern) consolidate. Wraps the singleton +
  `PaddedSourceIconTransformation` + the standard placeholder/error fallback.
- **Apply `hayaiPagePreviewDefaults()` to the Recommends thumbs** (100dp wide).
  Today's change just dropped crossfade; the maxBitmapSize cap still isn't
  applied so a 4k cover decodes to RAM for a 300px-wide card.
- **`PagePreviewItem` / `PreviewThumb` shimmer**: replace the per-frame
  recomposing `Surface(color = surfaceVariant.copy(alpha = animatedAlpha))`
  with a `Modifier.drawBehind` shimmer that animates without invalidating the
  composition. Only matters if profiling shows the loading state as a hotspot
  on the previews grid (15-cell page × continuous Surface recomposition).

### Architecture / cleanup (not perf-critical)

- **Done — View-based grid holder for source browse.** The grid now uses pure
  XML/ViewBinding + ImageView + Coil, including reusable overlay badge views.

### Profiling debt

- Re-run the `perfetto -t 15s sched gfx view input am wm res hal binder_driver`
  baseline against the current build to capture the post-defer + post-warmup
  numbers. Today's work is wired with `android.os.Trace` markers
  (`Hayai/Browse.initBottomSheet`, `Hayai/BrowseController.onChangeStarted.*`)
  so the new spans show up under the same query.
