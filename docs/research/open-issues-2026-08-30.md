# Open issue triage — 2026-08-30

## Scope

GitHub reports exactly six open issues in `HayaiApp/hayai`. None has an assignee, milestone, project item, linked pull request, linked commit, duplicate marker, or blocking relationship. This review compares the live issue bodies and comments with `master` at `8e258568b7`, the commit published as [nightly r6670](https://github.com/HayaiApp/hayai-nightly/releases/tag/r6670).

The tracker is behind the shipped state: #30, #36, and #37 have substantial implementations in r6670, while #38 and #41 describe regressions in shipped behavior. The next work should therefore stabilize the reader and complete shared novel identity before adding another surface.

## Recommendation

| Rank | Issue | Classification | Priority | Recommendation |
|---:|---|---|---|---|
| 1 | [#41 — Can't translate on downloaded chapters](https://github.com/HayaiApp/hayai/issues/41) | Actionable bug, but conflates translation, offline CSS/JS, and a remote custom background | P0/P1 | Reproduce each symptom separately, then fix the offline resource package and any independent translation failure. |
| 2 | [#38 — Paginated reading mode](https://github.com/HayaiApp/hayai/issues/38) | Partially shipped feature with concrete regressions | P1 | Treat the follow-up as the acceptance test for a pagination repair, not as a new feature. |
| 3 | [#36 — Separate novels from manga](https://github.com/HayaiApp/hayai/issues/36) | Shipped UI with an important identity gap | P1 | Keep one Library and its All/Manga/Novels selector; unify stale-source novel classification across Library, statistics, migration, and routing. |
| 4 | [#39 — Save filters](https://github.com/HayaiApp/hayai/issues/39) | Worthwhile bounded quality-of-life feature | P2 | Add generic per-source filter persistence/presets on the existing J2K browse surface after reader fixes. Clarify whether the reporter wants last-used state, named presets, or both. |
| 5 | [#30 — Vertical Japanese text and local novels](https://github.com/HayaiApp/hayai/issues/30) | Largely shipped; typography needs device verification | P2 verification | Split the two requests, verify Japanese typography on a real WebView/device, and close the local-source part. |
| 6 | [#37 — Custom theme color](https://github.com/HayaiApp/hayai/issues/37) | Already shipped | Close after smoke test | r6670 includes a custom dynamic-color seed picker; document its runtime-support limitation and close. |

## Issue notes

### 1. #41 — downloaded translation and offline resources

The issue was reproduced by the reporter on r6670, Android 16, and has no diagnostic comment yet. Its title says translation fails, while its body also says CSS/JavaScript and a remote `background-image` fail offline. Those are distinct acceptance cases.

The offline-resource report is credible from inspection:

- `NovelDownloadStore` discovers resources only from the chapter document, rewrites them to hashed `offline/<sha256>` paths, and stores no MIME type or original URL (`app/src/main/java/dev/ahmedmohamed/hayai/novel/download/NovelDownloadStore.kt:80-128`).
- The reader derives MIME type from that rewritten path, which has no extension, and deliberately rejects HTTP(S) resources when a chapter is offline (`app/src/main/java/dev/ahmedmohamed/hayai/novel/reader/NovelAssetWebViewClient.kt:53-62`, `:84-90`).
- Linked stylesheets are fetched as single assets, but nested `url(...)`/`@import` dependencies inside those stylesheets are not recursively discovered or rewritten (`app/src/main/java/dev/ahmedmohamed/hayai/novel/archive/HtmlAssetRewriter.kt:87-110`). Custom reader CSS is injected only at render time, after download packaging (`app/src/main/java/dev/ahmedmohamed/hayai/novel/reader/NovelReaderViewer.kt:307-310`), so its remote background was never promised to be bundled.
- Full-chapter translation reads the already-rendered document and uses a separate HTTP client/cache path, so it should not inherently depend on the source being online (`app/src/main/java/dev/ahmedmohamed/hayai/novel/reader/NovelReaderAttachment.kt:745-768`; `app/src/main/java/dev/ahmedmohamed/hayai/novel/translation/NovelTranslation.kt:125-152`). That symptom needs a focused downloaded-chapter reproducer rather than being assumed to share the asset cause.

Suggested slice: add an offline HTML fixture with linked CSS, nested background/font references, and script; retain bounded MIME/origin metadata; recursively package only allowlisted CSS dependencies with cycle, byte, count, and redirect limits; and test translation from an offline-loaded document independently. A remote image referenced only by user custom CSS should either be explicitly imported into managed storage or clearly remain online-only. Avoid silently turning arbitrary CSS into an unbounded crawler.

### 2. #38 — pagination regressions

Pagination exists, but the reporter's [post-implementation comment](https://github.com/HayaiApp/hayai/issues/38#issuecomment-5433005166) says page boundaries/alignment, volume-key paging, and continuous chapter transitions are broken.

The current implementation makes those reports plausible:

- CSS creates viewport-derived columns, but `step()` moves by `0.85 * innerWidth`, while page number/count divide by `innerWidth`; those are different geometries (`app/src/main/java/dev/ahmedmohamed/hayai/novel/reader/NovelHtmlDocumentBuilder.kt:57-60`, `:133-139`, `:194-207`). Snap alignment is on the chapter block, not every generated column.
- A volume-key handler is present (`app/src/main/java/dev/ahmedmohamed/hayai/novel/reader/NovelReaderViewer.kt:112-123`), but the live report says it does not work in paged mode; this needs an event-to-page regression test and device verification.
- `NovelReaderViewer.show()` replaces the whole renderer with only the active chapter (`app/src/main/java/dev/ahmedmohamed/hayai/novel/reader/NovelReaderViewer.kt:193-230`). Although append/prepend APIs and an adjacent loader exist, the viewer does not wire them, explaining the missing seamless chapter flow.

Suggested slice: define one measured page-stride/page-range contract, step exactly one page, keep page numbering scoped to the active chapter, route boundary crossings through J2K's existing `loadChapter`, and wire bounded adjacent preloading only if continuous mode requires it. Verify tap, swipe, volume keys, slider resume, both reading directions, rotation, and chapter edges.

### 3. #36 — separate novels from manga

r6670 already provides a Library-section selector with All, Manga, and Novels (`app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryController.kt:638-654`) and the feature audit records the selector and migration sections as ported (`docs/architecture/upstream-feature-audit.md:59`). This is the architecture-compatible interpretation: J2K remains the single Library owner, rather than introducing duplicate novel library/history/progress models.

However, the implementation is incomplete for imported novels whose old source is unavailable. Migration now recovers identity from legacy genre, remembered plugin source IDs, quotes, highlights, or chapter statistics (`app/src/main/java/dev/ahmedmohamed/hayai/novel/integration/NovelMigrationPolicy.kt:26-27`, `:57-86`). The Library filter and novel statistics still classify solely through `sourceManager.getOrStub(...).isNovelSource()` (`app/src/main/java/dev/ahmedmohamed/hayai/adult/HayaiLibraryPolicy.kt:14-22`; `app/src/main/java/dev/ahmedmohamed/hayai/novel/integration/NovelJ2kIntegration.kt:24-30`). Therefore the same stale-source novel can appear as Manga in the Library even though migration recognizes it correctly.

Suggested slice: extract one Hayai-owned `NovelContentIdentity` policy using the durable evidence already implemented for migration, inject it into Library filtering/statistics/migration, and keep runtime source capability as a separate question from stored content identity. Ask the reporter whether the selector satisfies “different sections”; do not add a second Library database or parallel root model.

### 4. #39 — save filters

This is worth adding after the reader bugs because it reduces repeated setup for filter-heavy sources and applies equally to manga and novel sources. The current browse presenter creates a fresh `source.getFilterList()` and keeps applied values only in controller memory (`app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/BrowseSourcePresenter.kt:75-87`, `:104-126`). Novel plugins likewise deliberately parse a fresh stateful list from their cached schema (`app/src/main/java/dev/ahmedmohamed/hayai/novel/plugin/source/NovelPluginSource.kt:827-834`).

Implement this on the existing J2K source-filter sheet, not in a novel-only duplicate. Persist supported primitive filter states by stable source ID plus a schema fingerprint; reject or partially restore snapshots after a source changes filter names/types/options. Start with “remember last applied filters” unless the reporter confirms named presets are needed. Back up only portable snapshots, never extension class instances.

Risks: extension updates reorder/change filters; groups and sort selections require typed encoding; source IDs can outlive installations; sensitive query text may be stored. A schema fingerprint, bounded storage, explicit clear action, and tests for changed schemas are dependencies.

### 5. #30 — vertical Japanese and local source

The issue bundles two unrelated requests; GitHub's timeline shows “local source for novel” was added to the title after creation, while the body specifies vertical typography only.

Both foundations are present in r6670:

- `vertical-rl` and `text-orientation:mixed` are emitted by the Web renderer (`app/src/main/java/dev/ahmedmohamed/hayai/novel/reader/NovelHtmlDocumentBuilder.kt:61-62`), and unsupported Native combinations resolve to Web (`docs/architecture/novel-reader-parity.md:42-46`).
- `LocalNovelSource` is registered alongside J2K's local manga source (`app/src/main/java/eu/kanade/tachiyomi/source/SourceManager.kt:69-80`).

This should not prompt another renderer. Verify the reporter's punctuation, brackets, prolonged marks, mixed Latin/numerals, wrapping, pagination, and orientation cases on Android WebView; fix only observed CSS/font gaps. Close the local-source request separately. The feature audit already states that the vertical Japanese device flow remains pending (`docs/architecture/upstream-feature-audit.md:53`).

### 6. #37 — custom theme color

r6670 contains a custom theme seed toggle and color picker in Appearance settings (`app/src/main/java/dev/ahmedmohamed/hayai/theme/HayaiThemeSettings.kt:20-66`). It applies a Material dynamic-color seed to both base activity paths (`app/src/main/java/dev/ahmedmohamed/hayai/theme/HayaiThemeSeed.kt:33-58`; `app/src/main/java/eu/kanade/tachiyomi/ui/base/activity/BaseActivity.kt:27`; `app/src/main/java/eu/kanade/tachiyomi/ui/base/activity/BaseThemedActivity.kt:21`).

The setting is intentionally disabled when Material's runtime dynamic colors are unavailable. Smoke-test the picker/recreation on one supported device and explain that limitation in the closing comment; no additional theme engine is justified unless a supported device still cannot apply the selected seed.

## Proposed tracker cleanup

1. Keep #41 and #38 open, retitle/split their distinct acceptance cases, and mark them as regressions in r6670.
2. Keep #36 open until the shared stale-source identity gap is fixed; then ask whether the single-Library selector satisfies the request.
3. Keep #39 as the next bounded enhancement after reader stabilization.
4. Split #30, close the local-source half, and retain only device-verified vertical typography gaps.
5. Close #37 after a supported-device smoke test.

