# TachiyomiSY and Tsundoku feature audit

Audit refs: TachiyomiSY `14648c7cf0aa84e5a35d48de9dbf1386df6cca42`, Tsundoku `547ddea3ce3e2a4943a1279b517e14b7af422467`, and J2K `57935d373fab209da0104d1a6ce2cb14ffd762dd`.

**Foundation** means a boundary or partial implementation exists. **Port** means the end-to-end production behavior is implemented and verified. **Audit** means upstream behavior is mapped but production work remains. This distinction prevents the clean baseline from being mistaken for the full multi-upstream port.

## Adult and enhanced sources

| Capability | Hayai boundary | Status |
|---|---|---|
| Global hentai enable (`eh_is_hentai_enabled`) | Stable SY-compatible key and central policy | foundation |
| Library lewd filter show/hide/only (`pref_filter_library_lewd_v2`) | `LewdClassifier` and `HayaiLibraryPolicy` | foundation |
| Fixed adult IDs, adult-source rules, tags, and `Non-H` exemption | Typed source registry and classifier | foundation |
| E-Hentai/ExHentai sources and login/session handling | Built-in source delegates and account gateway | audit |
| EH favorites, categories, notes, watched tags, gallery updates | Typed EH tables and Hayai workers/UI | migration foundation; runtime audit |
| EH metadata, gallery versions, archives/H@H, thumbnails, tag filtering | Metadata gateway and EH delegate | audit |
| 8Muses/EroMuse, HBrowse, MangaDex, NHentai, Pururin/Puruin, LANraragi | Registered enhanced-source families | registry foundation; behavior audit |
| Custom descriptions, open-in-app, batch-add, related/recommendations | Capability-provided source actions | audit |
| Raised metadata, titles, and tags | Additive schema plus import | migration foundation; DAO/UI audit |
| Merged sources, feeds, saved searches, and update controls | Hayai-owned services and typed side data | legacy retained; runtime audit |
| Data saver, page preview, request interception, reader/source options | Opt-in source delegates; upstream image reader unchanged | audit |

SY areas audited include its EH/ExHentai source, login and preferences; metadata models/parsers; adult classifier and library filters; favorites/update flows; enhanced source handlers; merged/feed/saved-search systems; and reader/network extras. Exact behavior is rebuilt behind Hayai contracts rather than copied into J2K presenters.

## Novels

| Capability | Hayai boundary | Status |
|---|---|---|
| Text chapter source ABI | `NovelSource.getChapterDocument` | foundation |
| Dedicated reader, central routing, and J2K history | `NovelReaderActivity` and `ReaderLauncher` | port |
| Novel extension discovery | J2K loader plus Hayai ABI | contract foundation; compatibility port |
| JavaScript repository sources and custom-source builder | Sandboxed novel runtime and signed repositories | audit |
| Local novels and EPUB import/export | Document storage and local novel source | import port; export audit |
| Text downloads/offline reading | Document downloader, separate from image pages | port |
| Typography, themes, spacing, navigation, search/replace | Text-reader profiles and document transforms | foundation; replace audit |
| Translation and dictionary handoff | Reader-owned adapters | audit |
| TTS and playback controls | Reader-owned lifecycle-aware engine and controls | port |
| Quotes | Typed `hayai_quotes`, selection capture, browse/copy/delete UI | port |
| Persistent highlights | Reader ranges anchored to document identity | audit |
| Word count, chapter stats, analytics | `hayai_novel_chapter_stats` | migration foundation; runtime audit |
| Novel trackers | J2K tracking boundary adapters | audit |
| Hayai backup | Versioned side payload after stable core ID remapping | port |
| LNReader/Tsundoku imports | Validated external import adapters | audit |

Tsundoku areas audited include its novel source/text-fetch contract, extension and repository handling, dedicated reader, downloads, TTS, reader tools, local/EPUB flows, tracking, statistics, and backup/import paths.

## Completion rules

- Never copy code from `legacy/hayai-pre-j2k`; that branch is schema/data evidence only.
- Preserve attribution and license notices for behavior rebuilt from SY or Tsundoku.
- Add a capability under `dev.ahmedmohamed.hayai` before adding the smallest J2K call site.
- A feature is complete only with source-unavailable behavior, migration/backup behavior where applicable, and focused tests—not merely a preference or switch.
