# SY source and J2K reader parity design

## Caller shape

E-Hentai remains a normal J2K source whose typed Hayai filter tree reduces to `EhSearchSpec`. Manga details binds independent metadata and preview slots through the existing `MangaDetailsController` adapter. Novel chapters continue through `ReaderLauncher` into J2K's actual `ReaderActivity`, which selects a Hayai `BaseViewer` only for proven novel sources.

## Selected architecture

The selected arena design preserves the existing typed provider and renderer contracts and repairs their divergent seams. It does not merge SY or Tsundoku or introduce another details page. The later unified-reader correction supersedes the former copied-shell design and uses the documented protected `ReaderActivity` adapter.

- `EhHtmlParser` adopts SY's sprite-first preview precedence while retaining Hayai's bounded crop decoder and typed failures.
- `EhFilters` exposes SY's group hierarchy but recursively reduces into the existing `EhSearchSpec`; URLs and pagination stay in `EhRequestBuilder`.
- `MangaDetailsController` remains a lifecycle adapter and delegates Hayai loading, rendering, generation guards, and bitmap cleanup to `SourceDetailsHost`. Phone and tablet layouts provide independent metadata-before-description and preview-after-description anchors.
- `SourcePreviewController` owns one-based page navigation and delegates selected image opening to `ReaderLauncher`.
- `NovelReaderViewer` implements J2K's `BaseViewer`; `NovelReaderAttachment` supplies novel-only actions to the real J2K toolbar and chapter sheet. J2K owns insets, overlay visibility, chapter navigation, slider behavior, progress, history, tracking, and lifecycle.
- Native and Web renderers share `NovelSelectionActionModes`; each renderer is responsible only for producing a stable `NovelSelection`.
- `NovelLookupLauncher` owns external Google Translate and signed-in Custom Tab effects. `NovelQuoteStore` remains the quote authority.

## Rejected alternative

The larger alternative introduced reducers for the entire source-details and novel-reader state, a new preview-asset repository, and a new coordinator before removing the current controller code. Those types could help a future rewrite, but they duplicate already-working models and expand the migration and regression surface without being required to repair these bugs. Putting text pages inside the manga pager/webtoon holders was also rejected because image-viewer, hinge, double-page, crop, and zoom behavior would make the upstream diff larger and more regression-prone. The selected `BaseViewer` adapter reuses the activity without entering those image pipelines.

## Provenance and seams

Behavior is grounded at J2K `7eea215da1a32b7198aabfc3e94ecd71d76f4df7`, SY `14648c7cf0aa84e5a35d48de9dbf1386df6cca42`, and Tsundoku `f166f2672d9a01a3c84a5d920af144598089a4be`. Relevant SY preview history includes `d45563e58d7f5f8f50046e0dbb8dc2e147ac0789`, `7565e51f951328c663ee32ffc101a926bf3334e3`, and `cbb743f995b79d44b796108c7c9fbc6b7820ddbd`. Legacy Hayai is UX evidence only; no legacy application code is copied.

The source-details seam remains a small `MangaDetailsController` callback binding and the two `manga_header_item.xml` variants; source-owned rendering lives in `SourceDetailsHost`. The separate reviewed reader seam is `ReaderActivity`, `ReaderViewModel`, and `ChapterLoader`, each delegating novel-specific work into Hayai types while preserving the existing image branches. Database ownership, migrations, and backups are unchanged. Preview listing cache schema advances to `v3`; this is disposable cache invalidation, not a data migration.
