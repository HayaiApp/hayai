# SY source and J2K reader parity design

## Caller shape

E-Hentai remains a normal J2K source whose typed Hayai filter tree reduces to `EhSearchSpec`. Manga details binds independent metadata and preview slots through the existing `MangaDetailsController` adapter. Novel chapters continue through `ReaderLauncher` into `NovelReaderActivity`, which inflates the unmodified J2K `reader_activity` shell and hosts one `NovelRenderer`.

## Selected architecture

The selected arena design preserves the existing typed provider and renderer contracts and repairs their divergent seams. It does not merge SY or Tsundoku, introduce another details page, or edit the protected image `ReaderActivity`.

- `EhHtmlParser` adopts SY's sprite-first preview precedence while retaining Hayai's bounded crop decoder and typed failures.
- `EhFilters` exposes SY's group hierarchy but recursively reduces into the existing `EhSearchSpec`; URLs and pagination stay in `EhRequestBuilder`.
- `MangaDetailsController` remains a lifecycle adapter and delegates Hayai loading, rendering, generation guards, and bitmap cleanup to `SourceDetailsHost`. Phone and tablet layouts provide independent metadata-before-description and preview-after-description anchors.
- `SourcePreviewController` owns one-based page navigation and delegates selected image opening to `ReaderLauncher`.
- `NovelReaderActivity` uses the concrete `J2kNovelReaderChrome` adapter for J2K's existing view IDs, insets, and overlay visibility. Viewer bounds remain J2K-owned while novel content gets one visibility-independent toolbar safe inset.
- Native and Web renderers share `NovelSelectionActionModes`; each renderer is responsible only for producing a stable `NovelSelection`.
- `NovelLookupLauncher` owns external Google Translate and signed-in Custom Tab effects. `NovelQuoteStore` remains the quote authority.

## Rejected alternative

The larger alternative introduced reducers for the entire source-details and novel-reader state, a new preview-asset repository, and a new coordinator before removing the current controller code. Those types could help a future rewrite, but they duplicate already-working models and expand the migration and regression surface without being required to repair these bugs. A shared base extracted from J2K `ReaderActivity` was also rejected because image-viewer, hinge, fullscreen, slider, and chapter-sheet state would make the protected upstream diff large and rebase-sensitive.

## Provenance and seams

Behavior is grounded at J2K `7eea215da1a32b7198aabfc3e94ecd71d76f4df7`, SY `14648c7cf0aa84e5a35d48de9dbf1386df6cca42`, and Tsundoku `f166f2672d9a01a3c84a5d920af144598089a4be`. Relevant SY preview history includes `d45563e58d7f5f8f50046e0dbb8dc2e147ac0789`, `7565e51f951328c663ee32ffc101a926bf3334e3`, and `cbb743f995b79d44b796108c7c9fbc6b7820ddbd`. Legacy Hayai is UX evidence only; no legacy application code is copied.

The reviewed J2K seam is limited to a small `MangaDetailsController` callback binding and the two `manga_header_item.xml` variants; source-owned rendering lives in `SourceDetailsHost`. Database ownership, migrations, backups, image reader state, and application lifecycle are unchanged. Preview listing cache schema advances to `v3`; this is disposable cache invalidation, not a data migration.
