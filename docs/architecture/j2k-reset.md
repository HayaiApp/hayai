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
- `recents`: presentation-only source visibility shared by History, Updates, and mixed views.

`tools/verify-upstream-boundary.ps1` rejects unreviewed Kotlin edits outside this namespace. `App.kt`, `MainActivity.kt`, and the J2K image `ReaderActivity.kt` are protected explicitly.

The J2K backup pipeline is an intentional narrow adapter seam: `Backup` owns one optional high-numbered Hayai field, `BackupCreator` fills it, and `BackupRestorer` applies it only after J2K has restored manga and chapters. All payload validation, serialization models, stable identity mapping, and database behavior remain Hayai-owned. This preserves old-backup compatibility and avoids teaching J2K models about Hayai tables.

`ExtensionLoader` is also an intentional adapter seam. Package discovery delegates manifest interpretation to Hayai's pure `NovelExtensionManifest` contract, then uses J2K's existing signature trust, NSFW policy, private-extension, and class-loading paths. A small `eu.kanade.tachiyomi.source.NovelSource` compatibility shim retains the exact legacy binary name and recognizes both the current virtual property and older marker implementations. This adds Tsundoku's `tachiyomi.novelextension` namespace without creating a second extension manager or bypassing J2K's security checks.

LNReader-compatible JavaScript sources use a separate Hayai-owned trust boundary. `AppModule` registers one manager. `SourceManager` merges the manager's source flow with J2K's authoritative APK source flow and rejects source-ID collisions. Repository documents and plugin downloads are bounded. Remote repositories require HTTPS, optional publisher checksums are enforced, and installed code and metadata are published atomically. Each source owns a serialized QuickJS runtime with evaluation, response-size, and cache limits. Installation initializes the runtime before code becomes active. It also caches filter and setting schemas so J2K's synchronous source UI never starts QuickJS on the main thread. Chapter documents expose an absolute base URL. Source-owned asset requests retain cookies and a referrer and enforce a 16 MiB stream limit. Users explicitly accept repository and install warnings because TLS and file integrity do not authenticate a repository publisher. The bridge exposes only the compatibility APIs needed by LNReader plugins, including network, Cheerio and Jsoup, storage, timers, crypto, URL, and protobuf helpers. It does not expose Android APIs or app lifecycle APIs.

## Database reset and migration

Legacy Hayai and current J2K both called their database `tachiyomi.db`, but their schema histories are unrelated (legacy SQLDelight v36+ versus J2K StorIO v20). Hayai therefore uses `hayai-j2k.db` as the active J2K database and treats an existing `tachiyomi.db` as a read-only source.

On first database open, an idempotent importer validates the legacy schema and requires the fresh J2K target to be pristine. It imports compatible J2K core rows in dependency order, imports quotes/novel/E-Hentai/search data into typed tables, and also archives every source row with table and row identity in `hayai_legacy_rows`. The raw archive preserves columns J2K cannot represent. Inserts abort on any identity conflict; a non-pristine target is refused rather than silently merged or overwritten. The importer checks foreign keys and records a durable result. The source database is never modified. A failed import rolls back and records a recoverable diagnostic without entering an app-start retry loop.

## Reader and source architecture

Extensions remain standard J2K `Source`/`SourceFactory` extensions. `SourceCapabilityRegistry` adds behavior by stable source ID or recognized family name instead of scattering source checks through presenters.

The Advanced setting `eh_is_hentai_enabled` controls adult source discovery. Disabling it reactively removes recognized adult sources from browse, global search, and migration pickers without unregistering them, deleting data, or making existing library entries unreadable. The independent library filter uses SY's `pref_filter_library_lewd_v2` contract: disabled shows all entries, include shows only lewd entries, and exclude hides lewd entries. Classification includes SY's legacy ID range, complete source-name rules, adult genre tags, and the `Non-H` exception only for E-Hentai, ExHentai, and nHentai families.

`NovelSource` returns one `NovelDocument` per chapter. `ReaderLauncher` centralizes all current direct chapter-launch paths: recognized `NovelSource` implementations open `NovelReaderActivity`, while image sources continue into the untouched J2K reader. Library, recents, notifications, search, and manga details call this router. Local HTML/text/EPUB sources, downloaded source chapters, installed Tsundoku novel APKs, and explicitly trusted LNReader JavaScript repositories are production implementations.

The text reader owns its lifecycle. It loads text off the main thread, renders bounded selectable documents, updates J2K history, supports typography/navigation/search, speaks chapters through TTS, captures quotes and stable highlights, persists Unicode-aware chapter word counts and reading-time estimates, and saves authenticated source assets into a self-contained integrity-checked offline store. Translation providers, dictionary handoff, highlight recovery, visual custom-source compilation, and EPUB export remain Hayai-owned and do not change the image reader.

Novel integration uses a small set of presentation adapters rather than parallel J2K models. The library filter delegates content-kind classification to Hayai. Manga details decorate the existing chapter rows with percent, word count, reading time, and verified offline state; the normal download/remove controls delegate novel chapters to `NovelOfflineManager`. J2K statistics aggregate Hayai's derived word data, shortcuts preserve novel identity, and migration searches reject novel-to-image and image-to-novel targets. `NovelSettingsController` owns the complete text-reader preference surface, typed reusable presets, CSS/JavaScript snippets, and bounded text-replacement rules. J2K remains authoritative for chapters, progress, history, categories, and the enclosing screens.

Source-specific details use one generic provider registry on manga details. E-Hentai, NHentai, and LANraragi providers return typed paginated previews. Images and sprite regions are decoded with response, dimension, concurrency, and aggregate-memory limits, and stale pages are closed when the view is rebound. Tapping a preview uses the source's authenticated WebView path. No source parser, cookie rule, or bitmap-cropping branch lives in the J2K controller.

Built-in source settings are another deliberate adapter seam. `BrowseSourceController` sends E-Hentai sources to the Hayai account screen and sends other built-in `ConfigurableSource` implementations to `SourceSettingsController`. This fixes JavaScript novel source settings without adding a second settings model. Extension sources continue to use `ExtensionDetailsController`.

J2K's extension-facing `JavaScriptEngine` delegates evaluation to Hayai's QuickJS runtime. The novel plugin bridge needs the Dokar binding API, while J2K previously packaged a second QuickJS implementation with the same native `libquickjs.so` name. One Hayai-owned evaluator preserves the extension API and prevents an unresolvable APK native-library collision without changing application lifecycle code.

Source badges use a Hayai-owned presentation resolver. The browse list and both migration lists render only facts that the live source proves: `Bundled`, `JS`, `Novel`, and `Adult`. The resolver does not label an enhanced-source family until its delegated behavior exists. This keeps the badge UI honest while limiting J2K changes to three holders and their layouts.

Enhanced extension sources pass through `EnhancedSourceRegistry` during J2K source registration. A matching wrapper keeps the extension source ID and delegates filters, settings, browse pages, chapters, pages, images, normal URLs, cookies, and headers to the installed extension. Hayai adds the source-specific behavior SY exposes for 8Muses, HBrowse, Pururin, NHentai, and LANraragi. This includes host-validated URL import where supported, richer descriptions, generic batch add for importable sources, NHentai previews, and authenticated LANraragi previews. MangaDex-specific recommendations, related titles, and follows are outside this requested slice. A secondary parser failure returns the extension result and never removes a usable source.

E-Hentai preferences retain SY keys and affect the source directly. Japanese-title selection changes `SManga.title`, watched-list and category choices initialize each fresh filter sheet, and the enhanced-view switch controls page previews. Malformed category and language strings fall back to safe defaults. Remote uconfig uses a Hayai-owned typed settings model and an idempotent per-site profile upsert. It reuses Hayai and legacy SY application profiles, creates only in slots 1 through 3, commits cookies and the selected slot only after a verified apply, and reports partial two-site results for targeted retry. The settings activity owns the complete matrix and updater controls.

Favorites synchronization uses a persisted three-way plan over the last completed Hayai snapshot, current J2K library state, and a bounded ExHentai snapshot. Remote mutations are journaled and executed before local mutations, use expected preconditions, preserve existing favorite notes during category moves, and verify their postconditions. Local favorite and category changes remain J2K-owned and transition the journal in the same database transaction. Ten explicit slot-to-category mappings prevent remote positions from renaming arbitrary J2K categories, while unrelated categories are retained. Alias components are canonicalized before comparison, ambiguous duplicates and multiple mapped categories become review conflicts, cancellation leaves an active resumable run, and the final remote fingerprint is verified before atomically advancing the baseline and checkpoint.

The gallery updater uses unique WorkManager jobs with connected, unmetered, and charging constraints from user settings. It checks bounded batches, persists freshness, aged, and failure state, and classifies transient failures for retry. Revision merging keeps J2K manga, chapters, history, categories, favorites, and downloads authoritative. A durable download journal stages and verifies file moves before the matching database transaction, so cancellation and process death converge safely on retry.

Novel tracking uses three Hayai-owned services registered through the existing J2K `TrackManager` and tracking settings screen. Manga details asks the manager for services appropriate to the current source, which keeps the novel-only services out of image-manga tracking without adding a second track model. The services own their remote authentication and protocol boundaries, while J2K continues to own manga-track rows, progress updates, status display, and automatic reader-driven tracking.

Recents source visibility uses one Hayai-owned policy seam. History and Updates retain independent
sets under the legacy Hayai preference keys; Grouped and All derive the union. The presenter takes
one immutable source-ID snapshot per loaded page before it groups or decorates rows. The options
sheet delegates source discovery and editing to Hayai, including installed sources, sources still
referenced by J2K manga rows, and unavailable sources that were hidden previously. This is strictly
a presentation filter: J2K remains authoritative for every manga, chapter, history, and update row,
and pagination advances over hidden rows so a page containing only hidden sources cannot loop.

## Backup ownership

J2K continues to own backup of library, chapter, category, history, tracking, and selected preferences. Hayai appends a versioned optional protobuf payload for quotes, novel repositories, installed novel plugin code and settings, chapter word counts, highlights, visual custom-source definitions, tagged novel APK repository URLs, and E-Hentai state. References to J2K rows use `(sourceId, mangaUrl, chapterUrl)` rather than transient database IDs and are resolved only after core restore. Quote ID collisions are deterministically remapped from the complete quote payload, and highlight identity conflicts are rejected while mutable color, note, and timestamps converge. Plugin and custom-source code is revalidated through the same installer before publication. Repository signing trust, tracker credentials, translation API keys, import completion markers, and downloaded files are excluded.

Offline novel files are deliberately excluded, matching J2K's treatment of downloaded image pages: backup contains durable user data and state, not potentially large downloaded content.

## Updating upstreams

```powershell
git fetch j2k sy tsundoku
git rebase j2k/master
.\tools\verify-upstream-boundary.ps1
```

TachiyomiSY and Tsundoku are feature references, not architectural parents. Ports are rebuilt behind Hayai contracts with provenance recorded in the feature audit.
