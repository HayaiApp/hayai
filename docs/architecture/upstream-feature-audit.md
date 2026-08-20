# TachiyomiSY and Tsundoku feature audit

Audit refs: TachiyomiSY `14648c7cf0aa84e5a35d48de9dbf1386df6cca42`, Tsundoku `547ddea3ce3e2a4943a1279b517e14b7af422467`, and J2K `57935d373fab209da0104d1a6ce2cb14ffd762dd`.

**Foundation** means a boundary or partial implementation exists. **Port** means the end-to-end production behavior is implemented and verified. **Audit** means upstream behavior is mapped but production work remains. This distinction prevents the clean baseline from being mistaken for the full multi-upstream port.

## Adult and enhanced sources

| Capability | Hayai boundary | Status |
|---|---|---|
| Global hentai enable (`eh_is_hentai_enabled`) | Reactive discovery-source visibility; installed/library sources remain addressable | port |
| Library lewd filter show/only/hide (`pref_filter_library_lewd_v2`) | J2K library filter sheet plus typed `HayaiLibraryPolicy` | port |
| Fixed adult IDs, full SY adult-source aliases, tags, and scoped `Non-H` exemption | Typed source registry and classifier | port |
| E-Hentai/ExHentai sources and login/session handling | Built-in source delegates and account gateway | foundation verified; emulator login pending |
| EH source settings, default filters, enhanced details toggle, and remote uconfig | Typed desired settings, idempotent per-site profile uploader, partial retry UI, and local browse preferences | implementation verified; authenticated emulator upload pending |
| EH favorites, categories, notes, watched tags, gallery updates | Typed EH tables and Hayai workers/UI | migration foundation; runtime audit |
| EH metadata, gallery versions, thumbnails/previews, and tag filtering | Metadata gateway, EH delegate, and generic manga-details feature seam | foundation verified; emulator details flow pending |
| 8Muses/EroMuse, HBrowse, MangaDex, NHentai, Pururin/Puruin, LANraragi | ID-preserving delegated extension wrappers | URL import and detail foundation verified; source-specific parity audit |
| Custom descriptions, open-in-app, batch-add, related/recommendations | Capability-provided source actions | direct URL and description foundation; batch and relationship actions audit |
| Raised metadata, titles, and tags | Additive schema plus import | migration foundation; DAO/UI audit |
| Merged sources, feeds, saved searches, and update controls | Hayai-owned services and typed side data | legacy retained; runtime audit |
| Data saver, page preview, request interception, reader/source options | Opt-in source delegates; upstream image reader unchanged | preview foundation verified; remaining items audit |
| Source type and origin badges | Hayai presentation resolver plus browse and migration adapters | foundation verified; delegated enhanced badges wait for their ports |

SY areas audited include its EH/ExHentai source, login and preferences; metadata models/parsers; adult classifier and library filters; favorites/update flows; enhanced source handlers; merged/feed/saved-search systems; and reader/network extras. Exact behavior is rebuilt behind Hayai contracts rather than copied into J2K presenters.

## Novels

| Capability | Hayai boundary | Status |
|---|---|---|
| Text chapter source ABI | `NovelSource.getChapterDocument` | foundation |
| Dedicated reader, central routing, and J2K history | `NovelReaderActivity` and `ReaderLauncher` | port |
| Installed novel extension discovery | J2K loader plus Hayai ABI | port |
| Remote novel extension catalogs and installation | J2K extension API plus repository adapters | audit |
| LNReader JavaScript repository sources | Hayai manager, bounded trusted repositories, isolated QuickJS sources, full catalogue/chapter bridge | port |
| JavaScript source filter and settings sheets | Cached source schemas through J2K filters plus Hayai built-in settings router | foundation verified; emulator settings flow pending |
| Visual custom-source builder | Hayai-owned source-definition compiler and editor | audit |
| Local novels and EPUB import/export | Document storage and local novel source | import port; export audit |
| Text downloads/offline reading | Document downloader plus J2K details download/remove controls, separate from image pages | integration foundation verified; emulator bulk-flow pending |
| Typography, themes, spacing, navigation, presets, snippets, search/replace | Text-reader profiles, typed customization store, and bounded document transforms | integration foundation verified; emulator settings-flow pending |
| Translation and dictionary handoff | Reader-owned adapters | audit |
| TTS and playback controls | Reader-owned lifecycle-aware engine and controls | port |
| Quotes | Typed `hayai_quotes`, selection capture, browse/copy/delete UI | port |
| Persistent highlights | Reader ranges anchored to document identity | audit |
| Word count, chapter stats, analytics | `hayai_novel_chapter_stats`, progress-aware J2K chapter rows, and library statistics | integration foundation verified; emulator presentation pending |
| Library content filter, shortcuts, and migration compatibility | Typed content policy with narrow J2K presentation and migration adapters | integration foundation verified; emulator flows pending |
| Novel trackers | J2K tracking boundary adapters | audit |
| Hayai backup | Versioned side payload after stable core ID remapping, including installed JS plugin code and settings | port |
| LNReader/Tsundoku imports | Validated external import adapters | audit |

Tsundoku areas audited include its novel source/text-fetch contract, extension and repository handling, dedicated reader, downloads, TTS, reader tools, local/EPUB flows, tracking, statistics, and backup/import paths.

## Application workflows

| Capability | Hayai boundary | Status |
|---|---|---|
| Hide sources independently in History and Updates, including Grouped/All union | Hayai recents visibility policy plus narrow presenter/options adapters; legacy preference keys retained | port |

## Completion rules

- Never copy code from `legacy/hayai-pre-j2k`; that branch is schema/data evidence only.
- Preserve attribution and license notices for behavior rebuilt from SY or Tsundoku.
- Add a capability under `dev.ahmedmohamed.hayai` before adding the smallest J2K call site.
- A feature is complete only with source-unavailable behavior, migration/backup behavior where applicable, and focused tests—not merely a preference or switch.
