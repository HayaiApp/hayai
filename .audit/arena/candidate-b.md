# Candidate B: Hayai Reset Architecture

## Caller Usage

The reset exposes one small Hayai surface to J2K. J2K should not know whether a source is a novel source, an EH source, an enhanced SY source, or legacy data in flight.

```kotlin
// AppModule, after normal J2K singletons exist.
val hayai = Hayai.install(
    context = app,
    j2k = J2kAdapters(
        db = Injekt.get(),
        sourceManager = Injekt.get(),
        preferences = Injekt.get(),
        network = Injekt.get(),
        downloads = Injekt.get(),
    ),
)

// SourceManager, when installed extensions change.
val wrapped = hayai.sources.decorate(extension.sources)
val builtIns = hayai.sources.builtIns() + LocalSource(context)

// Browse/search/library filtering.
val visible = hayai.contentPolicy.canShow(source.id, source.name)
val filters = hayai.capabilities.filtersFor(source.id, source.getFilterList())
val metadata = hayai.metadata.forManga(manga.id!!)

// Reader entry point, without editing the image reader.
val mode = hayai.reader.modeFor(manga.source)
when (mode) {
    ReaderMode.Image -> ChapterLoader(context, downloadManager, downloadProvider, manga, source)
    ReaderMode.Text -> hayai.reader.textSession(manga, chapter)
}

// First run after reset.
hayai.migration.enqueueIfNeeded()
```

The only public Hayai object J2K receives is `Hayai`. Its children are facades, not utility bags:

```kotlin
interface Hayai {
    val sources: HayaiSourceGateway
    val capabilities: SourceCapabilityRegistry
    val contentPolicy: ContentPolicy
    val reader: HayaiReaderGateway
    val metadata: MetadataGateway
    val migration: LegacyMigrationGateway
    val backup: HayaiBackupBoundary

    companion object {
        fun install(context: Context, j2k: J2kAdapters): Hayai = TODO("wire composition only")
    }
}
```

## Core Data Shapes And Signatures

Types intentionally separate manga, novel, adult, and migration states. A caller cannot accidentally treat unreviewed adult content as normal catalogue content or a copied legacy row as committed J2K state.

```kotlin
@JvmInline value class SourceId(val value: Long)
@JvmInline value class MangaId(val value: Long)
@JvmInline value class ChapterId(val value: Long)

enum class ContentKind { MangaImage, NovelText, Mixed }
sealed interface AdultState {
    data object Safe : AdultState
    data object Unknown : AdultState
    data class Adult(val reason: AdultReason) : AdultState
}
enum class AdultReason { ExtensionFlag, SourceRegistry, MetadataTag, UserOverride }

data class SourceCapability(
    val sourceId: SourceId,
    val kind: ContentKind,
    val adult: AdultState,
    val metadataFamily: MetadataFamily?,
    val enhancedBehavior: EnhancedBehavior?,
    val readerContract: ReaderContract,
    val backupContract: BackupContract,
)

enum class MetadataFamily {
    EHentai, EightMuses, HBrowse, Lanraragi, MangaDex, NHentai, Pururin, Tsumino
}

sealed interface ReaderContract {
    data object ImagePages : ReaderContract
    data class NovelText(
        val oneTextPagePerChapter: Boolean = true,
        val supportsTts: Boolean,
        val supportsQuotes: Boolean,
    ) : ReaderContract
}

sealed interface MigrationState {
    data object NotNeeded : MigrationState
    data class Pending(val legacyPath: File, val planId: String) : MigrationState
    data class Running(val planId: String, val phase: LegacyMigrationPhase) : MigrationState
    data class Complete(val planId: String, val importedAt: Instant) : MigrationState
    data class FailedRecoverably(val planId: String, val message: String) : MigrationState
}

enum class LegacyMigrationPhase {
    SnapshotLegacy,
    ValidateSourceSchema,
    CreateHayaiTables,
    ImportCoreRows,
    ImportMetadata,
    ImportNovelData,
    ImportEhData,
    ImportPreferences,
    VerifyCounts,
    CommitMarker,
}

data class LegacyRowMap(
    val legacyMangaId: Long,
    val newMangaId: MangaId,
    val sourceId: SourceId,
)
```

Key interfaces:

```kotlin
interface SourceCapabilityRegistry {
    fun all(): Map<SourceId, SourceCapability>
    fun capabilityOf(source: Source): SourceCapability
    fun filtersFor(sourceId: Long, j2kFilters: FilterList): FilterList
    fun enhancedDelegateFor(source: HttpSource): EnhancedSourceDelegate?
}

interface ContentPolicy {
    fun globalAdultEnabled(): Boolean
    fun canShow(sourceId: Long, sourceName: String): Boolean
    fun classify(source: Source, extension: Extension.Installed?): AdultState
}

interface EnhancedSourceDelegate {
    val family: MetadataFamily
    suspend fun fetchMetadata(manga: Manga): RaisedMetadataDraft
    suspend fun enhanceUpdate(update: SMangaUpdate): SMangaUpdate
    suspend fun rewriteRequest(request: Request): Request = request
}

interface NovelSourceContract : CatalogueSource {
    suspend fun getText(chapter: SChapter): NovelChapterText
}

data class NovelChapterText(
    val html: String?,
    val plainText: String?,
    val baseUrl: String?,
    val wordCount: Int?,
)

interface HayaiReaderGateway {
    fun modeFor(sourceId: Long): ReaderMode
    suspend fun textSession(manga: Manga, chapter: Chapter): NovelReaderSession
}

enum class ReaderMode { Image, Text }

interface LegacyMigrationGateway {
    fun state(): Flow<MigrationState>
    suspend fun enqueueIfNeeded()
    suspend fun dryRun(legacyDb: File): LegacyMigrationReport
    suspend fun run(plan: LegacyMigrationPlan): LegacyMigrationReport
}
```

All functions above are contract sketches. Bodies are `TODO` or pseudocode during scaffold:

```kotlin
suspend fun LegacyImporter.run(plan: LegacyMigrationPlan): LegacyMigrationReport {
    // 1. open legacy tachiyomi.db read-only
    // 2. create temp import DB under Hayai database name
    // 3. copy in deterministic order inside one transaction per phase
    // 4. verify row counts and foreign key checks
    // 5. atomically mark imported; never write to legacy DB
    TODO("design only")
}
```

## Module Map

All new implementation lives under `app/src/main/java/dev/ahmedmohamed/hayai`.

- `dev.ahmedmohamed.hayai.core`: `Hayai`, `J2kAdapters`, composition root, feature flags.
- `dev.ahmedmohamed.hayai.source`: capability registry, source ID registry, extension decorators, local/JS/custom/novel sources.
- `dev.ahmedmohamed.hayai.source.enhanced`: SY-style delegates for E-Hentai/ExHentai, 8Muses/EroMuse, HBrowse, MangaDex, NHentai, Pururin, LANraragi.
- `dev.ahmedmohamed.hayai.content`: adult classifier, global adult enable, lewd filter, library/search visibility policy.
- `dev.ahmedmohamed.hayai.metadata`: RaisedSearchMetadata, tags, titles, EH metadata tables, metadata UI adapters.
- `dev.ahmedmohamed.hayai.novel`: Tsundoku novel source ABI, JS runtime, plugin repos, custom source builder, local novel support.
- `dev.ahmedmohamed.hayai.reader.text`: novel reader session, text page loader, quote manager, TTS, style presets.
- `dev.ahmedmohamed.hayai.migration`: read-only legacy importer, plan/report models, idempotency marker, legacy schema readers.
- `dev.ahmedmohamed.hayai.backup`: Hayai proto extension models and backup/restore adapters.
- `dev.ahmedmohamed.hayai.j2k`: the only package allowed to depend on `eu.kanade.*` internals directly.

Keep legacy upstream source copied by provenance package comments, not by old package names. Old `hayai.*`, `exh.*`, and `yokai.*` should either move under `dev.ahmedmohamed.hayai` or be shimmed only in extension-facing ABI where binary compatibility requires it.

## Migration Transaction Design

Use a new active J2K database file, for example `hayai.db`, because legacy Hayai SQLDelight schema v36 and J2K StorIO schema v20 both use `tachiyomi.db` with unrelated migration histories. The legacy file remains read-only.

Crash-safe protocol:

1. Resolve files:
   - legacy source: existing `tachiyomi.db`, opened `OPEN_READONLY`.
   - active target: `hayai.db`.
   - staging target: `hayai.importing.db`.
2. If `hayai_import_marker` says `Complete`, exit.
3. If staging exists without a complete marker, delete only staging, never legacy.
4. Create staging from J2K schema v20 plus Hayai additive tables.
5. Attach legacy read-only as `legacy`.
6. Inside target transaction, import in dependency order:
   - categories
   - mangas, mapping `hide_title` to J2K `hideTitle`
   - chapters
   - manga-category joins
   - history
   - tracks
   - custom manga info
   - excluded scanlators
   - search metadata/tags/titles
   - EH favorites and EH side data
   - novel repos, novel chapter stats
   - series knowledge quotes
   - saved searches, feeds, merged records, recents hidden where representable
7. Write `legacy_id_map` rows for every imported ID.
8. Run `PRAGMA foreign_key_check`, table count checks, and sample joins.
9. Write `hayai_import_marker(plan_id, legacy_db_sha256, completed_at, app_version)`.
10. Close both DBs, atomically rename staging to active.

Evidence for idempotency: the committed marker is in the target DB; row import uses deterministic natural keys and `legacy_id_map`; a crash before rename leaves only staging; a crash after rename has a complete marker. The legacy DB handle is read-only for the entire run.

Tables to add to J2K:

- `search_metadata`, `search_tags`, `search_titles`: J2K has model/mappers for `search_metadata`, but creation is missing.
- `hayai_import_marker`, `legacy_id_map`, `hayai_import_errors`.
- `hayai_eh_favorites`, `hayai_eh_sessions`, `hayai_eh_settings` as additive SY side tables.
- `hayai_novel_repos`, `hayai_novel_chapter_stats`.
- `hayai_quotes` using legacy quote fields: `quote_id`, `manga_id`, `novel_name`, `chapter_name`, `displayed_content`, `original_content`, `translated_content`, `language`, `timestamp`.

Legacy data that has no first-class J2K destination lands in a typed Hayai side table, never in a JSON junk drawer unless the legacy value was already JSON (`memo`, metadata `extra`).

## J2K Adapter Allowlist

Measurable allowlist target: fewer than 12 J2K files touched outside package/application branding and resources.

Expected J2K edits:

- `app/build.gradle.kts`: set `applicationId = "dev.ahmedmohamed.hayai"`, remove J2K application ID suffixes for distributable builds, keep namespace stable unless a later package rename is deliberately scoped.
- `strings.xml` and launcher assets: Hayai branding only.
- `AppModule.kt`: register `Hayai` and its gateways.
- `DbOpenCallback.kt`: add Hayai additive table creation/migration hook; do not merge SQLDelight v36 history.
- `DatabaseHelper.kt`: register fixed SearchMetadata creation and optional Hayai DAO gateway.
- `SourceManager.kt`: decorate extension sources and register Hayai built-ins/delegates.
- `ExtensionLoader.kt` or `ExtensionManager.kt`: replace static NSFW check with `ContentPolicy` classification while preserving extension ABI.
- `BrowseSourcePresenter.kt`/source list presenter: ask `ContentPolicy` and `SourceCapabilityRegistry`.
- `FilteredLibraryController`/`LibraryPresenter`: use registry-backed source filters instead of per-feature conditionals.
- `ChapterLoader.kt`: one branch to ask `HayaiReaderGateway` for text reader mode. Do not touch J2K image `ReaderActivity`, pager, webtoon, or image loaders.
- `BackupCreator.kt` and `BackupRestorer.kt`: call `HayaiBackupBoundary` for Hayai-only payloads.

Explicitly avoid:

- `App.kt` lifecycle churn.
- `ReaderActivity` image-reader changes.
- main navigation rewrites in `MainActivity`.
- spreading source-name checks through presenters.

## Feature Capability Registry

Capabilities are registered once by source ID and metadata family.

```kotlin
object HayaiSourceIds {
    val EHENTAI = SourceId(/* SY value */ TODO())
    val EXHENTAI = SourceId(/* SY value */ TODO())
    val EIGHT_MUSES = SourceId(/* SY value */ TODO())
    val EROMUSE = SourceId(/* legacy/SY value */ TODO())
    val HBROWSE = SourceId(/* SY value */ TODO())
    val MANGADEX = SourceId(/* SY value */ TODO())
    val NHENTAI = SourceId(/* SY value */ TODO())
    val PURURIN = SourceId(/* Pururin legacy spelling, UI may show Puruin */ TODO())
    val LANRARAGI = SourceId(/* SY value */ TODO())
}
```

The registry owns adult classification, enhanced behavior, metadata parsing, and reader mode:

```kotlin
fun registryDefaults(): List<SourceCapability> = listOf(
    SourceCapability(EHENTAI, MangaImage, Adult(AdultReason.SourceRegistry), EHentai, EhDelegate, ImagePages, MetadataAndEhBackup),
    SourceCapability(EXHENTAI, MangaImage, Adult(AdultReason.SourceRegistry), EHentai, EhDelegate, ImagePages, MetadataAndEhBackup),
    SourceCapability(NHENTAI, MangaImage, Adult(AdultReason.SourceRegistry), NHentai, NhentaiDelegate, ImagePages, MetadataBackup),
    SourceCapability(LANRARAGI, MangaImage, Unknown, Lanraragi, LanraragiDelegate, ImagePages, MetadataBackup),
    SourceCapability(/* novel ids */, NovelText, Safe, null, null, NovelText(...), NovelBackup),
)
```

This keeps conditionals out of J2K. Callers ask one question: `capabilityOf(source)`.

## Reader And Source Contracts

Novel features from Tsundoku:

- `NovelSourceContract.getText(chapter)` returns the full chapter text.
- `NovelPageLoader` creates exactly one `ReaderPage` equivalent per chapter, but that page carries `NovelChapterText`, not image bytes.
- `NovelReaderSession` owns scroll progress, styles, find/replace, TTS, translation handoff, quote capture, and word count recording.
- JS/custom/local novel sources implement `NovelSourceContract`; their runtime and repo system live under `dev.ahmedmohamed.hayai.novel`.

Image reader rule:

- J2K image reader remains untouched.
- The only shared reader contract is `ReaderMode`.
- Text reader is launched as a Hayai-owned view/session from a minimal J2K loader branch or a separate Hayai reader activity if implementation shows the branch would dirty `ReaderActivity`.

Enhanced source contract:

- `EnhancedSourceDelegate` wraps source behavior without subclass checks in J2K.
- Metadata writes flow through `MetadataGateway.upsert(mangaId, RaisedMetadataDraft)`.
- Source-specific preferences remain backed by existing `ConfigurableSource.sourcePreferences()` and are namespaced by source ID.

## Backup Boundary

J2K backup remains authoritative for core manga, chapters, categories, tracks, history, app prefs, source prefs, custom info, and memo fields.

Hayai backup adds separate proto sections with high, reserved numbers:

- `BackupHayaiSearchMetadata`
- `BackupHayaiSearchTag`
- `BackupHayaiSearchTitle`
- `BackupHayaiEhFavorite`
- `BackupHayaiNovelRepo`
- `BackupHayaiNovelChapterStats`
- `BackupHayaiQuote`
- `BackupHayaiImportMarker` for diagnostics only, not restore authority

Restore order mirrors migration: core J2K restore first, then Hayai side tables by current manga ID. Quote restore uses `(source, url)` to remap manga IDs before inserting quote rows.

## Update Workflow

Upstream update stays boring:

1. `git fetch j2k sy tsundoku`.
2. Rebase or merge J2K into `codex/j2k-reset`.
3. Resolve only allowlisted J2K adapter files.
4. Re-run Hayai adapter tests:
   - migration dry-run against a copy of legacy v36 DB
   - SearchMetadata table creation
   - content policy adult filter
   - source decoration
   - novel text page loading
   - backup round trip for Hayai side data
5. Pull SY/Tsundoku feature code into Hayai modules by source package, not by changing J2K surfaces.

Success metric: a normal J2K pull should not require edits in `ReaderActivity`, `App.kt`, or navigation controllers.

## Implementation Phases

1. Branding and database filename: set Hayai app ID/branding, create active `hayai.db`, add SearchMetadata table creation.
2. Hayai composition root: register `Hayai`, `J2kAdapters`, registry, content policy.
3. Migration: implement dry-run, staging import, id map, marker, reports; import every legacy table including quotes.
4. Metadata and adult content: port RaisedSearchMetadata, tags/titles, EH side data, lewd filter, global enable.
5. Enhanced sources: port source IDs and delegates for EH/ExHentai, 8Muses/EroMuse, HBrowse, MangaDex, NHentai, Pururin/Puruin, LANraragi.
6. Novel sources: port Tsundoku local/JS/custom source contracts and one-text-page loading.
7. Novel reader: build Hayai-owned text session, quotes, TTS, style presets, stats.
8. Backup: add Hayai side payloads and restore mapping.
9. Validation: legacy DB fixture migration, backup round trip, source visibility tests, source capability tests, novel reader smoke test.

## Rationale

This design makes J2K the direct upstream baseline by concentrating every fork feature behind one Hayai package and one adapter allowlist. SY and Tsundoku are treated as feature suppliers, not as architectural parents.

The most important choice is the database reset. Reusing `tachiyomi.db` would force J2K schema v20 and Hayai SQLDelight v36 to pretend they share one migration history. They do not. A read-only legacy import into a fresh J2K-shaped database is easier to prove, easier to retry, and safer for users.

The second important choice is capability registration. Hentai behavior, metadata, and novel behavior all vary by source. A registry keeps that variability attached to the source boundary instead of leaking it into browse, library, update, reader, and backup code.

## Alternatives Considered

- Merge legacy Hayai directly into J2K packages. Rejected because future J2K pulls would repeatedly conflict in reader, app lifecycle, and navigation.
- Keep SQLDelight v36 as the active DB. Rejected because J2K at this commit uses StorIO schema v20 and the migration lineages are unrelated.
- Fork `ReaderActivity` for all reading. Rejected because J2K image reader is high-churn and should stay upstream-owned.
- Store all legacy extras in `memo`. Rejected because metadata, EH favorites, and quotes need queryable, backupable, typed state.

## Tradeoffs

- The adapter layer adds a little ceremony, but it buys short call chains and keeps J2K unaware of SY/Tsundoku internals.
- A new DB filename means one explicit migration step, but it avoids corrupting or half-upgrading the legacy DB.
- Text reading as a Hayai-owned reader/session may duplicate some reader chrome, but it protects J2K image-reader mergeability.
- Source ID constants must be verified during implementation against SY and legacy Hayai; placeholders in this artifact are intentional until source registries are copied with tests.

## Risks

- Extension ABI risk: old extensions may expect `exh.*` metadata classes. Mitigation: provide explicit ABI shims only where extension binaries require them, with tests.
- Adult visibility risk: J2K currently has extension NSFW filtering. Mitigation: content policy must combine extension metadata, registry IDs, user global enable, and per-source override.
- Migration risk: source IDs or URLs may have changed across forks. Mitigation: import report lists unmapped rows and uses `(source, url)` plus legacy ID maps.
- Backup risk: restoring Hayai side data before manga IDs are known would orphan rows. Mitigation: backup boundary runs strictly after core restore.
- Upstream risk: if J2K changes source or reader APIs, adapter files absorb it; CI should fail when allowlist expands.

## Synthesis Decision

Candidate B favors a narrow Hayai kernel plus additive J2K adapters. It gives up the speed of direct file-level porting from legacy Hayai, SY, and Tsundoku, but it best satisfies the reset goal: practical J2K pulls, read-only idempotent migration, explicit source capabilities, and a small public Hayai surface.
