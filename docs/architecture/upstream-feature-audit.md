# TachiyomiSY and Tsundoku feature audit

Audit refs: TachiyomiSY `14648c7cf0aa84e5a35d48de9dbf1386df6cca42`, Tsundoku `f166f2672d9a01a3c84a5d920af144598089a4be`, and J2K `7eea215da1a32b7198aabfc3e94ecd71d76f4df7`.

**Foundation** means a boundary or partial implementation exists. **Port** means the end-to-end production behavior is implemented and verified. **Audit** means upstream behavior is mapped but production work remains. This distinction prevents the clean baseline from being mistaken for the full multi-upstream port.

## Adult and enhanced sources

| Capability | Hayai boundary | Status |
|---|---|---|
| Global hentai enable (`eh_is_hentai_enabled`) | Reactive discovery-source visibility; installed/library sources remain addressable | port |
| Library lewd filter show/only/hide (`pref_filter_library_lewd_v2`) | J2K library filter sheet plus typed `HayaiLibraryPolicy` | port |
| Fixed adult IDs, full SY adult-source aliases, tags, and scoped `Non-H` exemption | Typed source registry and classifier | port |
| E-Hentai/ExHentai sources and login/session handling | Built-in source delegates and account gateway | foundation verified; logged-out emulator screen verified; authenticated login pending |
| EH source settings, default filters, enhanced details toggle, and remote uconfig | Native J2K settings controller over typed desired settings, idempotent per-site profile uploader, partial retry UI, and local browse preferences | implementation verified; authenticated emulator upload pending |
| EH favorites, categories, notes, watched tags, gallery updates | Durable three-way favorites plan, explicit category mappings, note-preserving remote gateway, resumable journal, updater state/statistics, and revision/download recovery | implemented unverified; authenticated emulator sync and updater flows pending |
| EH metadata, gallery versions, thumbnails/previews, and tag filtering | Typed metadata documents, non-duplicated SY-style inline/full metadata UI in independent phone/tablet anchors, sprite-first current/legacy thumbnail parsing, versioned preview caches, previous/next/direct preview paging, SY-grouped Genres-to-exclude and Advanced filters, reader-page handoff, rich browse rows, and the pinned SY tag catalog with autocomplete chips | port; focused blank-placeholder, pagination, filter, and request validation implemented; authenticated ExHentai and post-fix visual emulator verification remain pending |
| 8Muses/EroMuse, HBrowse, NHentai, Pururin/Puruin, LANraragi | ID-preserving delegated extension wrappers | implemented unverified for the source-specific behavior SY exposes |
| Custom descriptions, open-in-app, batch-add, and page previews | Capability-provided source actions, source-specific SY description layouts, adaptive SY preview rows, bounded listing/image caches, and a J2K-hosted preview controller | port; NHentai and LANraragi expose previews upstream, while 8Muses, HBrowse, and Pururin do not; live authenticated source verification remains pending |
| Raised metadata, titles, and tags | Additive schema, typed source documents, durable enhanced-source cache, inline summary, and a full metadata controller hosted by J2K navigation | port; E-Hentai fixture verified on emulator |
| Merged sources, feeds, saved searches, and update controls | Hayai-owned services and typed side data | legacy retained; runtime audit |
| Data saver, page preview, request interception, reader/source options | Opt-in source delegates; upstream image reader unchanged | source-supported preview and settings behavior implemented unverified |
| Source type and origin badges | Hayai presentation resolver plus SY category colors and EH rich browse adapter | implemented unverified; live browse verification pending |

Source-surface parity at the pinned SY reference is intentionally capability-specific:

| Source family | Rich browse/category badge | Custom metadata | Page previews | Tag autocomplete |
|---|---:|---:|---:|---:|
| E-Hentai / ExHentai | yes | yes | yes | yes |
| 8Muses / EroMuse | upstream list | yes | not exposed by SY | upstream filter UI |
| HBrowse | upstream list | yes | not exposed by SY | upstream filter UI |
| NHentai | upstream list | yes | yes | upstream filter UI |
| Pururin / Puruin | upstream list | yes | not exposed by SY | upstream filter UI |
| LANraragi | upstream list | yes | yes | upstream filter UI |

The details adapter resolves metadata and previews independently so a failure in one surface does not suppress the other. MangaDex-specific expansion remains excluded by the requested scope.

SY areas audited include its EH/ExHentai source, login and preferences; metadata models/parsers; adult classifier and library filters; favorites/update flows; enhanced source handlers; merged/feed/saved-search systems; and reader/network extras. Exact behavior is rebuilt behind Hayai contracts rather than copied into J2K presenters.

## Novels

| Capability | Hayai boundary | Status |
|---|---|---|
| Text chapter source ABI | `NovelSource.getChapterDocument` | foundation |
| Dedicated reader, central routing, and J2K history | The unmodified J2K `reader_activity`/navigation/chapter-sheet resources, literal overlay geometry, confirmed-single-tap gestures, always-available progress control, non-mutating chapter open, Hayai chapter/action adapters, native `TextView` and isolated `WebView` renderers, typed actions, bounded continuous append/prepend with stable chapter blocks and visible-chapter progress/history, incognito-aware J2K state, and `ReaderLauncher` | real AllNovel source loaded 3,956 chapters and opened Chapter 1 at zero progress on emulator; single-tap chrome dismissal, scroll-without-chrome-reentry, and Quote/Define/Google Translate selection ordering were verified against the real chapter; WebView, prepend, and incognito flows remain pending |
| Installed novel extension discovery | J2K loader plus Hayai ABI | port |
| Remote novel extension catalogs and installation | A Novels tab beside Manga and Migration over J2K APK authority and Hayai JavaScript authority; namespaced identities; explicit load failures; consistent duplicate reconciliation; shared-safe tagged repository ownership; and J2K's inline repository editor reused for both backends | port verified on emulator with a checksum-verified Keiyoushi Comikey APK and the production LNReader v3 repository; install, detection, settings, and source registration passed |
| LNReader JavaScript repository sources | Novels-tab J2K cards, explicit application-context runtime construction, construction and warm-up error retention, version-tolerant v3/legacy repository boundary, bounded trusted repositories with idempotent repair for persisted repositories missing trust, isolated QuickJS sources, relative repository icon resolution, and full catalogue/chapter bridge | port verified on emulator by installing production Komga 1.0.2, initializing QuickJS, evaluating filters/settings, registering the source, and loading its remote icon in Browse without a raw-Context DI lookup; deleting only the trust preference while retaining two configured repositories recreated both trust records on startup |
| JavaScript source filter and settings sheets | Cached source schemas through J2K filters plus Hayai built-in settings router | foundation verified; emulator settings flow pending |
| Visual custom-source builder | Validated Hayai source definitions, live preview, JavaScript compilation, persistence, and install/remove UI | implemented unverified |
| Local novels and EPUB import/export | Document storage, local novel source, and bounded EPUB3 exporter | implemented unverified |
| Text downloads/offline reading | J2K `DownloadQueue`, `DownloadJob`, status flow, notification, details and reader actions, and Downloads sheet with a Hayai storage delegate for novel documents; explicit auto-start resumes stopped non-empty queues | emulator verified with real NovelFull Chapter 1 entering and completing through J2K's notification and queue, and a stopped queue resuming a real E-Hentai gallery from 37 to 383 pages; the gallery's final source image remained retrying at 383/384 |
| Typography, themes, spacing, navigation, presets, snippets, search/replace | Five Tsundoku icon tabs with J2K spinner, switch, slider, and toolbar-settings controls; adjacent theme and text/background color controls; Tsundoku-style preset swatches plus a synchronized hex/RGB custom picker; isolated preset/custom color resolution; Tsundoku preference keys and exact tap-zone tables; SAF-imported validated fonts shared by native/Web renderers; progress modes; compact ordered bottom/status registries; searchable full settings; ordered snippets with append semantics; and testable ordered replacements | custom theme logic and compilation verified; picker UI emulator verification and imported-font flow pending |
| Translation, definition, search, and dictionary handoff | Bounded provider clients and cache; shared native/Web selection actions with retained Web anchors; Google Translate process-text/send routing; forced resizable Custom Tabs with browser login state; and a secure J2K bottom-sheet WebView fallback | real NovelFull native selection verified Quote, Define, and Google Translate as the first actions; Web selection and floating translation-result verification remain pending |
| TTS and playback controls | Foreground media-playback service, notification actions, Tsundoku transport actions surfaced through J2K's reader-sheet buttons, chapter handoff, highlight, and activity reattachment | active exact-shell transport verified on emulator; background and restart recovery pending |
| Quotes | Typed `hayai_quotes`, visible semantic Quote action in native/Web selection, retained Web anchors, duplicate-safe create, edit, attributed copy, reorder, browse, and confirmed delete UI | native real-source selection opened the populated Save quote flow on emulator; Web selection lifecycle remains pending |
| Persistent highlights | Stable source/chapter anchors, bounded edit recovery, normalized cross-node rendering, navigation, editing, schema, and backup | implemented unverified |
| Word count, chapter stats, analytics | `hayai_novel_chapter_stats`, progress-aware J2K chapter rows, and library statistics | integration foundation verified; emulator presentation pending |
| Library content filter, shortcuts, and migration compatibility | Typed content policy with narrow J2K presentation and migration adapters | integration foundation verified; emulator flows pending |
| Novel trackers | Concrete NovelUpdates, NovelList, and RanobeDB services through J2K tracking | implemented unverified |
| Hayai backup | Versioned side payload after stable core ID remapping, including plugins, highlights, visual sources, and tagged APK repositories | implemented unverified for the new v4 data |
| LNReader/Tsundoku imports | Bounded dry-run parsers and transactional J2K import target with stable conflicts and resumable partial runs | implemented unverified |

Tsundoku areas audited include its novel source/text-fetch contract, extension and repository handling, dedicated reader, downloads, TTS, reader tools, local/EPUB flows, tracking, statistics, and backup/import paths.

## Application workflows

| Capability | Hayai boundary | Status |
|---|---|---|
| Hide sources independently in History and Updates, including Grouped/All union | Hayai recents visibility policy plus narrow presenter/options adapters; legacy preference keys retained | port |
| Group History by source | Restored `BySource` enum mode with J2K Recents rows, source headers, source icons, per-section hide action, and legacy preference compatibility | port |
| Stable and nightly releases | Separate application IDs, Hayai-branded ABI assets, channel-aware updates, monotonic `rN` numbering, isolated nightly publishing, and master-triggered workflow | nightly port verified by hosted r6661 build, tests, signed/minified startup, and publication; no reset stable release published |
| Localized Hayai interface | `hayai_` Android resources, typed presentation failures, a reviewed nonlocalizable-literal inventory, per-locale coverage, and deterministic SY translation imports | implemented; static gate verified; translation coverage remains incomplete and is reported per locale |
| Browse content tabs and local novel discovery | J2K main tabs backed by shared `ContentKind`; scoped global search; language-independent local novel entry | port; repeated Browse/Recents navigation verified on emulator |
| Manga and novel extension management | J2K Extensions sheet with Manga, Novels, and Migration tabs; novel APK and JavaScript entries reuse the J2K row and action pipeline; both repository types reuse J2K's one-field inline repository UI; supplied Keiyoushi, Cursed Manga, and LNReader indexes seed once on first run and remain user-removable | port verified on emulator; Comikey 1.6.9 and Komga 1.0.2 installed and detected without false update or load-failed states |
| Cloudflare challenge bypass | J2K interceptor contract synchronized to Mihon commit `ac249e3668a57f24782a49218834064459ba2d09` with official challenge-header detection and interactive-challenge early exit | focused detector tests passed; authenticated Cloudflare emulator flow pending |

## Completion rules

- Never copy code from `legacy/hayai-pre-j2k`; that branch is schema/data evidence only.
- Preserve attribution and license notices for behavior rebuilt from SY or Tsundoku.
- Add a capability under `dev.ahmedmohamed.hayai` before adding the smallest J2K call site.
- A feature is complete only with source-unavailable behavior, migration/backup behavior where applicable, and focused tests—not merely a preference or switch.
