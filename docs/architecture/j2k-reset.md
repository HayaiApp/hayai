# Hayai on TachiyomiJ2K

Hayai is rooted directly at TachiyomiJ2K commit `57935d373fab209da0104d1a6ce2cb14ffd762dd`. The previous mixed Hayai/Rokku tree is preserved at `legacy/hayai-pre-j2k`; none of its Kotlin code is part of the new baseline.

## Boundaries

J2K owns manga, chapters, history, categories, tracking, downloads, image reading, and normal extension loading. New behavior lives under `dev.ahmedmohamed.hayai`:

- `adult`: adult classification and the global/tri-state library policy.
- `source`: typed source capabilities and enhanced-source families.
- `novel`: the text-source contract and dedicated reader.
- `migration`: read-only legacy import and typed Hayai side tables.
- `backup`: versioned serialization and stable-ID remapping for Hayai-owned side data.
- `preferences`: stable Hayai and TachiyomiSY-compatible preference keys.

`tools/verify-upstream-boundary.ps1` rejects unreviewed Kotlin edits outside this namespace. `App.kt`, `MainActivity.kt`, and the J2K image `ReaderActivity.kt` are protected explicitly.

The J2K backup pipeline is an intentional narrow adapter seam: `Backup` owns one optional high-numbered Hayai field, `BackupCreator` fills it, and `BackupRestorer` applies it only after J2K has restored manga and chapters. All payload validation, serialization models, stable identity mapping, and database behavior remain Hayai-owned. This preserves old-backup compatibility and avoids teaching J2K models about Hayai tables.

`ExtensionLoader` is also an intentional adapter seam. Package discovery delegates manifest interpretation to Hayai's pure `NovelExtensionManifest` contract, then uses J2K's existing signature trust, NSFW policy, private-extension, and class-loading paths. A small `eu.kanade.tachiyomi.source.NovelSource` compatibility shim retains the exact legacy binary name and recognizes both the current virtual property and older marker implementations. This adds Tsundoku's `tachiyomi.novelextension` namespace without creating a second extension manager or bypassing J2K's security checks.

LNReader-compatible JavaScript sources use a separate Hayai-owned trust boundary. `AppModule` registers one manager. `SourceManager` merges the manager's source flow with J2K's authoritative APK source flow and rejects source-ID collisions. Repository documents and plugin downloads are bounded. Remote repositories require HTTPS, optional publisher checksums are enforced, and installed code and metadata are published atomically. Each source owns a serialized QuickJS runtime with evaluation, response-size, and cache limits. Installation initializes the runtime before code becomes active. It also caches filter and setting schemas so J2K's synchronous source UI never starts QuickJS on the main thread. Chapter documents expose an absolute base URL. Source-owned asset requests retain cookies and a referrer and enforce a 16 MiB stream limit. Users explicitly accept repository and install warnings because TLS and file integrity do not authenticate a repository publisher. The bridge exposes only the compatibility APIs needed by LNReader plugins, including network, Cheerio and Jsoup, storage, timers, crypto, URL, and protobuf helpers. It does not expose Android APIs or app lifecycle APIs.

## Database reset and migration

Legacy Hayai and current J2K both called their database `tachiyomi.db`, but their schema histories are unrelated (legacy SQLDelight v36+ versus J2K StorIO v20). Hayai therefore uses `hayai-j2k.db` as the active J2K database and treats an existing `tachiyomi.db` as a read-only source.

On first database open, an idempotent importer validates the legacy schema and requires the fresh J2K target to be pristine. It imports compatible J2K core rows in dependency order, imports quotes/novel/E-Hentai/search data into typed tables, and also archives every source row with table and row identity in `hayai_legacy_rows`. The raw archive preserves columns J2K cannot represent. Inserts abort on any identity conflict; a non-pristine target is refused rather than silently merged or overwritten. The importer checks foreign keys and records a durable result. The source database is never modified. A failed import rolls back and records a recoverable diagnostic without entering an app-start retry loop.

## Reader and source architecture

Extensions remain standard J2K `Source`/`SourceFactory` extensions. `SourceCapabilityRegistry` adds behavior by stable source ID or recognized family name instead of scattering source checks through presenters.

`NovelSource` returns one `NovelDocument` per chapter. `ReaderLauncher` centralizes all current direct chapter-launch paths: recognized `NovelSource` implementations open `NovelReaderActivity`, while image sources continue into the untouched J2K reader. Library, recents, notifications, search, and manga details call this router. Local HTML/text/EPUB sources, downloaded source chapters, installed Tsundoku novel APKs, and explicitly trusted LNReader JavaScript repositories are production implementations.

The text reader owns its lifecycle. It loads text off the main thread, renders bounded selectable documents, updates J2K history, supports typography/navigation/search, speaks chapters through TTS, captures and manages quotes, persists Unicode-aware chapter word counts and reading-time estimates, and saves authenticated source assets into a self-contained integrity-checked offline store. Derived-stat persistence failure never prevents reading. Translation/dictionary adapters and the visual custom-source builder remain future Hayai-owned slices rather than changes to the image reader.

## Backup ownership

J2K continues to own backup of library, chapter, category, history, tracking, and selected preferences. Hayai appends a versioned optional protobuf payload for quotes, novel repositories, installed novel plugin code and settings, chapter word counts, and E-Hentai favorites. References to J2K rows use `(sourceId, mangaUrl, chapterUrl)` rather than transient database IDs and are resolved only after core restore. Quote ID collisions are deterministically remapped from the complete quote payload, so repeated restores are idempotent without collapsing distinct quotes. Plugin code and string settings have independent size and count limits. Restore revalidates code through the same atomic installer, replaces each plugin setting namespace, and reloads installed sources in the current process. Invalid or unsupported side payloads do not invalidate the core J2K restore.

Offline novel files are deliberately excluded, matching J2K's treatment of downloaded image pages: backup contains durable user data and state, not potentially large downloaded content.

## Updating upstreams

```powershell
git fetch j2k sy tsundoku
git rebase j2k/master
.\tools\verify-upstream-boundary.ps1
```

TachiyomiSY and Tsundoku are feature references, not architectural parents. Ports are rebuilt behind Hayai contracts with provenance recorded in the feature audit.
