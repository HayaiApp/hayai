# TachiyomiSY and Tsundoku feature audit

Audit refs: TachiyomiSY `14648c7cf0aa84e5a35d48de9dbf1386df6cca42`, Tsundoku `f166f2672d9a01a3c84a5d920af144598089a4be`, and J2K `8df100d6e616851e329b274964c726dcef0556b6`.

**Foundation** means a boundary or partial implementation exists. **Port** means the end-to-end production behavior is implemented and verified. **Audit** means upstream behavior is mapped but production work remains. This distinction prevents the clean baseline from being mistaken for the full multi-upstream port.

## Adult and enhanced sources

| Capability | Hayai boundary | Status |
|---|---|---|
| Global hentai enable (`eh_is_hentai_enabled`) | Reactive discovery-source visibility; installed/library sources remain addressable | port |
| Library lewd filter show/only/hide (`pref_filter_library_lewd_v2`) | J2K library filter sheet plus typed `HayaiLibraryPolicy` | port |
| Fixed adult IDs, full SY adult-source aliases, tags, and scoped `Non-H` exemption | Typed source registry and classifier | port |
| E-Hentai/ExHentai sources and login/session handling | Built-in source delegates and account gateway | foundation verified; logged-out emulator screen verified; authenticated login pending |
| EH source settings, default filters, enhanced browse/details toggles, and remote uconfig | Conditional dedicated main-settings page over typed desired settings, idempotent per-site profile uploader, partial retry UI, searchable controls, and independent SY browse/Hayai details preferences | port; settings placement and preference behavior implemented; authenticated emulator upload pending |
| EH favorites, categories, notes, watched tags, gallery updates | Durable three-way favorites plan, explicit category mappings, note-preserving remote gateway, resumable journal, updater state/statistics, and revision/download recovery | implemented unverified; authenticated emulator sync and updater flows pending |
| EH metadata, gallery versions, thumbnails/previews, and tag filtering | Typed metadata documents, non-duplicated SY-style inline/full metadata UI in independent phone/tablet anchors, sprite-first current/legacy thumbnail parsing, versioned preview caches, SY-exact direct preview paging and geometry, reader-page handoff, detailed browse rows or image grids with metadata badges, SY-grouped filters, and the pinned SY tag catalog with autocomplete chips | port; live E-Hentai detailed/grid browse and page 1 to final-page preview navigation verified on emulator; authenticated ExHentai verification pending |
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
| One J2K reader, central routing, and J2K history | The actual J2K `ReaderActivity`, `ReaderViewModel`, `ReaderChapter`, `ViewerChapters`, toolbar, navigation overlay, slider, and chapter sheet; Hayai adds only a `BaseViewer`, normalized progress loader, and novel-action attachment. Native `TextView` and isolated `WebView` renderers remain document surfaces; the duplicate activity/chrome/chapter adapter are removed. | port; focused tests cover routing, render-plan selection, normalized progress, paginated reporting, vertical CSS, chapter progress handoff, and TTS continuation; the Android 17 emulator verified the real J2K chrome, a persisted 72 percent threshold, the full novel settings path, the offline-translation action, and automatic read completion for a one-viewport chapter |
| Installed novel extension discovery | J2K loader plus Hayai ABI | port |
| Remote novel extension catalogs and installation | A Novels tab beside Manga and Migration over J2K APK authority and Hayai JavaScript authority; namespaced identities; explicit load failures; consistent duplicate reconciliation; shared-safe tagged repository ownership; and J2K's inline repository editor reused for both backends | port verified on emulator with a checksum-verified Keiyoushi Comikey APK and the production LNReader v3 repository; install, detection, settings, and source registration passed |
| LNReader JavaScript repository sources | Novels-tab J2K cards, explicit application-context runtime construction, construction and warm-up error retention, version-tolerant v3/legacy repository boundary, bounded trusted repositories with idempotent repair for persisted repositories missing trust, isolated QuickJS sources, relative repository icon resolution, and full catalogue/chapter bridge | port verified on emulator by installing production Komga 1.0.2, initializing QuickJS, evaluating filters/settings, registering the source, and loading its remote icon in Browse without a raw-Context DI lookup; deleting only the trust preference while retaining two configured repositories recreated both trust records on startup |
| JavaScript source filter and settings sheets | Cached source schemas through J2K filters plus Hayai built-in settings router | foundation verified; emulator settings flow pending |
| Visual custom-source builder | Validated Hayai source definitions, live preview, JavaScript compilation, persistence, and install/remove UI | implemented unverified |
| Local novels and EPUB import/export | Document storage, local novel source, and bounded EPUB3 exporter | implemented unverified |
| Text downloads/offline reading | J2K `DownloadQueue`, `DownloadJob`, status flow, notification, details and reader actions, and Downloads sheet with a Hayai storage delegate for novel documents; explicit auto-start resumes stopped non-empty queues; offline assets retain MIME-relevant extensions; a cancellable per-source pacer delays only queued metered novel saves with global and stable-source overrides | queue/offline flow previously verified on emulator; asset rewrite and pacing behavior have focused tests, while downloaded custom-style and translation device verification remains pending |
| Typography, themes, spacing, navigation, presets, snippets, search/replace | Five Tsundoku icon tabs hosted from J2K's reader settings action; adjacent theme and text/background color controls; Tsundoku-style preset swatches plus synchronized hex/RGB picker; stable backend preference plus independent continuous/paginated layout and horizontal/vertical-rl writing direction; exact tap-zone tables; SAF-imported fonts; searchable full settings; ordered snippets and replacements | render-plan, single-stride paginated HTML, and vertical-writing CSS have focused tests; the full Android 16 settings page is crash-free for persisted integer margins; unified-reader picker, imported-font, paginated gesture, and vertical Japanese device flows remain pending |
| Translation, definition, search, and dictionary handoff | Bounded provider clients; a Hayai-owned durable completed-result store with stable chapter identity, hash-checked lookup, separate lease-based job state, legacy completed-cache promotion, source migration, and bounded backup; one reader action queues every chapter through a foreground WorkManager job; shared native/Web selection actions with retained Web anchors; Google Translate process-text/send routing; forced resizable Custom Tabs with browser login state; and a secure J2K bottom-sheet WebView fallback | completed-result storage, typed reader lookup, retry-safe bulk queueing, offline transport fallback, migration, and backup are implemented with focused tests; on-device provider translation, process-death lease recovery, Web selection, and floating translation-result verification remain pending |
| TTS and playback controls | Foreground media-playback service, notification actions, Tsundoku transport actions surfaced through J2K's reader-sheet buttons, chapter handoff, highlight, and activity reattachment | active exact-shell transport verified on emulator; background and restart recovery pending |
| Quotes | Typed `hayai_quotes`, versioned non-destructive replay of archived `series_quotes` and legacy `quotes/novel_<id>.json` files, multi-file SAF recovery for scoped-storage devices, visible semantic Quote action in native/Web selection, retained Web anchors, duplicate-safe create, edit, attributed copy, reorder, browse, and confirmed delete UI | exact archived/legacy JSON shapes, idempotent merge, unresolved-identity retry, and identity-collision remap are covered by focused tests; emulator displayed archived database and safely remapped JSON quotes together; Web selection lifecycle remains pending |
| Persistent highlights | Stable source/chapter anchors, bounded edit recovery, normalized cross-node rendering, navigation, editing, schema, and backup | implemented unverified |
| Word count, chapter stats, analytics | `hayai_novel_chapter_stats`, progress-aware J2K chapter rows, and library statistics | integration foundation verified; emulator presentation pending |
| Library content selector, shortcuts, and migration compatibility | One durable-evidence content policy with an always-visible All/Manga/Novels selector in the J2K Library toolbar; shared localized Manga/Novel migration sections; source-unavailable legacy identity recovery; catalogue-wide novel targets and shared source artwork; atomic progress, quote, word-count, and highlight transfer | selector persistence, stale-source classification, and migration mapping/copy/conflict behavior covered by focused tests; end-to-end library and migration emulator flows pending |
| Novel trackers | Concrete NovelUpdates, NovelList, and RanobeDB services through J2K tracking | implemented unverified |
| Hayai backup | Versioned side payload after stable core ID remapping, including plugins, highlights, visual sources, and tagged APK repositories | implemented unverified for the new v4 data |
| LNReader/Tsundoku imports | Bounded dry-run parsers and transactional J2K import target with stable conflicts and resumable partial runs | implemented unverified |

Tsundoku areas audited include its novel source/text-fetch contract, extension and repository handling, dedicated reader, downloads, TTS, reader tools, local/EPUB flows, tracking, statistics, and backup/import paths.

## Application workflows

| Capability | Hayai boundary | Status |
|---|---|---|
| Hide sources independently in History and Updates, including Grouped/All union | Hayai recents visibility policy plus narrow presenter/options adapters; legacy preference keys retained | port |
| Group History by source | Restored `BySource` enum mode with J2K Recents rows, source headers, source icons, per-section hide action, and legacy preference compatibility | port |
| Stable and nightly releases | Separate application IDs, Hayai-branded ABI assets, channel-aware updates, monotonic `rN` numbering, isolated nightly publishing, master-triggered workflow, and generated release notes since the preceding nightly even after a patch-stack rebase | prior nightly publication verified; generated per-nightly changelog awaiting the next hosted run; no reset stable release published |
| Download and automatic-backup locations | Shared Hayai SAF validation under the existing J2K preferences, download provider, and backup worker; app-owned defaults avoid broad storage permission while selected trees retain durable write grants | implemented with focused path tests; Android picker, download, and scheduled-backup device verification pending |
| Localized Hayai interface | `hayai_` Android resources, typed presentation failures, a reviewed nonlocalizable-literal inventory, per-locale coverage, and deterministic SY translation imports | implemented; static gate verified; translation coverage remains incomplete and is reported per locale |
| Browse content tabs and local novel discovery | J2K main tabs backed by shared `ContentKind`; scoped global search; language-independent local novel entry | port; repeated Browse/Recents navigation verified on emulator |
| Manga and novel extension management | J2K Extensions sheet with Manga, Novels, and Migration tabs; novel APK and JavaScript entries reuse the J2K row and action pipeline; both repository types reuse J2K's one-field inline repository UI; supplied Keiyoushi, Cursed Manga, and LNReader indexes seed once on first run and remain user-removable | port verified on emulator; Comikey 1.6.9 and Komga 1.0.2 installed and detected without false update or load-failed states |
| Custom application color | Hayai seed preference and picker in J2K Appearance settings; both base activity families apply Material's content-based color scheme after the selected base theme and before view inflation, with an explicit unsupported-device state | preference/policy/application seams covered by focused tests; live light/dark/AMOLED and unsupported-device verification pending |
| Cloudflare challenge bypass | J2K interceptor contract synchronized to Mihon commit `ac249e3668a57f24782a49218834064459ba2d09` with official challenge-header detection and interactive-challenge early exit | focused detector tests passed; authenticated Cloudflare emulator flow pending |

## Completion rules

- Never copy code from `legacy/hayai-pre-j2k`; that branch is schema/data evidence only.
- Preserve attribution and license notices for behavior rebuilt from SY or Tsundoku.
- Add a capability under `dev.ahmedmohamed.hayai` before adding the smallest J2K call site.
- A feature is complete only with source-unavailable behavior, migration/backup behavior where applicable, and focused tests—not merely a preference or switch.
