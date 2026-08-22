# TachiyomiSY and Tsundoku feature audit

Audit refs: TachiyomiSY `14648c7cf0aa84e5a35d48de9dbf1386df6cca42`, Tsundoku `547ddea3ce3e2a4943a1279b517e14b7af422467`, and J2K `cdae5f2d77d63529c974d97c4a37c0c8ac188397`.

**Foundation** means a boundary or partial implementation exists. **Port** means the end-to-end production behavior is implemented and verified. **Audit** means upstream behavior is mapped but production work remains. This distinction prevents the clean baseline from being mistaken for the full multi-upstream port.

## Adult and enhanced sources

| Capability | Hayai boundary | Status |
|---|---|---|
| Global hentai enable (`eh_is_hentai_enabled`) | Reactive discovery-source visibility; installed/library sources remain addressable | port |
| Library lewd filter show/only/hide (`pref_filter_library_lewd_v2`) | J2K library filter sheet plus typed `HayaiLibraryPolicy` | port |
| Fixed adult IDs, full SY adult-source aliases, tags, and scoped `Non-H` exemption | Typed source registry and classifier | port |
| E-Hentai/ExHentai sources and login/session handling | Built-in source delegates and account gateway | foundation verified; logged-out emulator screen verified; authenticated login pending |
| EH source settings, default filters, enhanced details toggle, and remote uconfig | Typed desired settings, idempotent per-site profile uploader, partial retry UI, and local browse preferences | implementation verified; authenticated emulator upload pending |
| EH favorites, categories, notes, watched tags, gallery updates | Durable three-way favorites plan, explicit category mappings, note-preserving remote gateway, resumable journal, updater state/statistics, and revision/download recovery | implemented unverified; authenticated emulator sync and updater flows pending |
| EH metadata, gallery versions, thumbnails/previews, and tag filtering | Metadata gateway, lazy cached details rows, full preview browser, and typed J2K reader-page handoff | implemented unverified; authenticated emulator details flow pending |
| 8Muses/EroMuse, HBrowse, NHentai, Pururin/Puruin, LANraragi | ID-preserving delegated extension wrappers | implemented unverified for the source-specific behavior SY exposes |
| Custom descriptions, open-in-app, batch-add, and page previews | Capability-provided source actions plus bounded listing/image caches and a full preview browser | implemented unverified; NHentai and LANraragi expose previews upstream, while 8Muses, HBrowse, and Pururin do not |
| Raised metadata, titles, and tags | Additive schema plus import | migration foundation; DAO/UI audit |
| Merged sources, feeds, saved searches, and update controls | Hayai-owned services and typed side data | legacy retained; runtime audit |
| Data saver, page preview, request interception, reader/source options | Opt-in source delegates; upstream image reader unchanged | source-supported preview and settings behavior implemented unverified |
| Source type and origin badges | Hayai presentation resolver plus browse and migration adapters | implemented unverified |

SY areas audited include its EH/ExHentai source, login and preferences; metadata models/parsers; adult classifier and library filters; favorites/update flows; enhanced source handlers; merged/feed/saved-search systems; and reader/network extras. Exact behavior is rebuilt behind Hayai contracts rather than copied into J2K presenters.

## Novels

| Capability | Hayai boundary | Status |
|---|---|---|
| Text chapter source ABI | `NovelSource.getChapterDocument` | foundation |
| Dedicated reader, central routing, and J2K history | The unmodified J2K `reader_activity`/navigation/chapter-sheet resources, Hayai chapter/action adapters, native `TextView` and isolated `WebView` renderers, typed actions, bounded continuous append/prepend with stable chapter blocks and visible-chapter progress/history, incognito-aware J2K state, and `ReaderLauncher` | exact J2K shell and native renderer verified on emulator; WebView, prepend, and incognito emulator flows pending |
| Installed novel extension discovery | J2K loader plus Hayai ABI | port |
| Remote novel extension catalogs and installation | J2K extension manager; Tsundoku metadata; Manga/Novel installer tabs; shared-safe tagged repository ownership and separate management entry points | implemented unverified; compile and emulator install flows pending |
| LNReader JavaScript repository sources | Hayai manager, bounded trusted repositories, isolated QuickJS sources, full catalogue/chapter bridge | port |
| JavaScript source filter and settings sheets | Cached source schemas through J2K filters plus Hayai built-in settings router | foundation verified; emulator settings flow pending |
| Visual custom-source builder | Validated Hayai source definitions, live preview, JavaScript compilation, persistence, and install/remove UI | implemented unverified |
| Local novels and EPUB import/export | Document storage, local novel source, and bounded EPUB3 exporter | implemented unverified |
| Text downloads/offline reading | Document downloader plus J2K details download/remove controls, separate from image pages | implemented; local chapter offline save and restart recovery verified; bulk-flow pending |
| Typography, themes, spacing, navigation, presets, snippets, search/replace | Five Tsundoku icon tabs with J2K spinner, switch, slider, and toolbar-settings controls; adjacent theme and text/background color controls; Tsundoku-style preset swatches plus a synchronized hex/RGB custom picker; isolated preset/custom color resolution; Tsundoku preference keys and exact tap-zone tables; SAF-imported validated fonts shared by native/Web renderers; progress modes; compact ordered bottom/status registries; searchable full settings; ordered snippets with append semantics; and testable ordered replacements | custom theme logic and compilation verified; picker UI emulator verification and imported-font flow pending |
| Translation and dictionary handoff | Bounded provider clients, cache, selection/chapter reader actions, installed-app lookup, and web fallback | implemented unverified |
| TTS and playback controls | Foreground media-playback service, notification actions, Tsundoku transport actions surfaced through J2K's reader-sheet buttons, chapter handoff, highlight, and activity reattachment | active exact-shell transport verified on emulator; background and restart recovery pending |
| Quotes | Typed `hayai_quotes`, native/Web selection capture, duplicate-safe create, edit, attributed copy, reorder, browse, and confirmed delete UI | implemented unverified; emulator selection lifecycle pending |
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
| Stable and nightly releases | Separate application IDs, Hayai-branded ABI assets, channel-aware updates, monotonic `rN` numbering, and isolated nightly publishing | implemented unverified; hosted nightly run pending |
| Localized Hayai interface | `hayai_` Android resources, typed presentation failures, a reviewed nonlocalizable-literal inventory, per-locale coverage, and deterministic SY translation imports | implemented; static gate verified; translation coverage remains incomplete and is reported per locale |
| Browse content tabs and local novel discovery | J2K main tabs backed by shared `ContentKind`; scoped global search; language-independent local novel entry | implemented unverified; compile and emulator navigation pending |

## Completion rules

- Never copy code from `legacy/hayai-pre-j2k`; that branch is schema/data evidence only.
- Preserve attribution and license notices for behavior rebuilt from SY or Tsundoku.
- Add a capability under `dev.ahmedmohamed.hayai` before adding the smallest J2K call site.
- A feature is complete only with source-unavailable behavior, migration/backup behavior where applicable, and focused tests—not merely a preference or switch.
