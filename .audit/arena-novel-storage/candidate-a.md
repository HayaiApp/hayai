# Candidate A: materialized novel identity and capability adapters

## Problem

Hayai currently has the right product boundary, one J2K library, browse model, reader, download queue, and backup envelope, but four facts are represented inconsistently. Novel identity is inferred more thoroughly by migration than by Library and statistics, remote novel plugin icons are special-cased on one source list while J2K cover loading ignores the plugin's request headers, shared-storage defaults still point at raw external paths on Android 11+, and the newly shipped reader/filter slices keep transient or incomplete state. The design must repair those facts without creating a parallel novel library, reader, browse stack, or download queue.

## Usage (caller's view)

The caller sees one operation per concern. It does not coordinate schema evidence, URI permissions, HTTP headers, or filter serialization.

```kotlin
// Library and statistics classify the same batch through one durable index.
val kinds = novelIdentity.resolve(libraryManga)
val visible = libraryManga.filter { libraryPolicy.includes(it, kinds.kindOf(it)) }
val novelStats = novelIntegration.libraryStatistics(libraryManga, kinds)

// Migration uses the same classification for target filtering and all visible copy.
val migration = migrationPresentation.forEntries(selectedManga)
toolbar.title = context.getString(migration.titleRes)
confirmButton.text = resources.getQuantityString(migration.confirmMigrateRes, count, count)
val targets = migrationPolicy.compatibleSources(selectedManga, catalogueSources)
```

```kotlin
// Every source-icon ImageView uses one binder. Remote novel icons, APK icons, bundled
// icons, cancellation, caching, and fallback all stay behind it.
sourceIconBinder.bind(binding.sourceImage, source)

// MangaCoverFetcher remains the single fetcher used by browse, global search,
// migration, Library, details, Recents, widgets, and shortcuts.
val access = sourceImageRequests.forCover(manga.source, manga.thumbnail_url!!)
val response = access.execute(cacheDirective, coilParameters)
```

```kotlin
// Settings never stores an unchecked URI. The worker/provider resolves a typed root.
val result = storageLocations.selectSafTree(StorageUse.Downloads, uri, intent.flags)
show(result.userMessage)

val root = storageLocations.requireWritableRoot(StorageUse.Downloads)
downloadProvider.use(root.directory)

val backupRoot = storageLocations.requireWritableRoot(StorageUse.AutomaticBackups)
backupCreator.createAutomaticBackup(backupRoot.directory)
```

```kotlin
// The reader reports and moves through one measured page geometry.
val geometry = renderer.measurePages(activeChapterId)
progress.onNext(PagePosition(geometry.pageIndex, geometry.pageCount))
renderer.move(PageMove.Next) // tap, swipe, volume key, and slider all call this

// The filter presenter restores a fresh source-owned FilterList, never old instances.
sourceFilters = source.getFilterList()
savedFilters.restore(source.id, sourceFilters)
// Only an applied search persists; reset removes the snapshot.
savedFilters.saveApplied(source.id, sourceFilters)
```

## Shape

### Durable content identity

```kotlin
enum class ContentKind { Manga, Novel }

@JvmInline value class MangaId(val value: Long)

enum class NovelIdentityEvidence {
    RuntimeNovelSource, RememberedNovelSource, LegacyGenre,
    Quote, Highlight, ChapterStatistic, Backup, Migration,
}

data class ContentKindIndex internal constructor(
    private val novelIds: Set<MangaId>,
) {
    fun kindOf(manga: Manga): ContentKind
}

interface NovelContentIdentity {
    /** Batch reads explicit rows plus legacy evidence. Newly proven rows are materialized idempotently. */
    fun resolve(mangas: Collection<Manga>): ContentKindIndex

    /** Used when a novel-producing boundary has a persisted J2K manga id. */
    fun rememberNovel(manga: Manga, evidence: NovelIdentityEvidence)
}
```

`hayai_novel_identities` is a Hayai-owned table, not a second library table:

```sql
CREATE TABLE hayai_novel_identities(
  manga_id INTEGER NOT NULL PRIMARY KEY,
  source_id INTEGER NOT NULL,
  manga_url TEXT NOT NULL,
  evidence TEXT NOT NULL,
  recorded_at INTEGER NOT NULL,
  FOREIGN KEY(manga_id) REFERENCES mangas(_id) ON DELETE CASCADE,
  UNIQUE(source_id, manga_url)
);
```

Presence means Novel; absence does not claim Manga. `resolve` performs one batched lookup, then checks still-unmaterialized entries against the existing durable evidence in `hayai_novel_plugin_sources`, novel genres, quotes, highlights, and chapter statistics plus the installed source's `NovelText` capability. Proven entries are inserted in one transaction, so an installed source becomes durable before it can later disappear. Backup serializes identity by stable `(sourceId, mangaUrl)`, not database id. Restore remaps that stable key after J2K restores manga rows. `NovelMigrationDataMover` records the destination inside its existing transaction and leaves the source marker on copy; a normal J2K delete removes it by cascade.

This is intentionally monotonic. Runtime reader/download routing still requires a live `SourceCapability.NovelText`; stored content identity may select a Library section or migration vocabulary but cannot make an unavailable source executable. `SourceCapabilityRegistry.descriptor` should derive `NovelText` from the ABI-compatible `source.isNovelSource()` check before name heuristics. This keeps content identity and runtime capability separate.

```kotlin
data class MigrationVocabulary(
    @StringRes val titleRes: Int,
    @PluralsRes val confirmMigrateRes: Int,
    @PluralsRes val confirmCopyRes: Int,
    @PluralsRes val completedRes: Int,
    @StringRes val targetPromptRes: Int,
)

class MigrationPresentation(private val identity: NovelContentIdentity) {
    fun forEntries(entries: Collection<Manga>): MigrationVocabulary
}
```

Homogeneous novel selections use new `hayai_` novel strings, homogeneous manga selections retain J2K text, and mixed/unknown selections use generic “series” text. Resource selection is centralized rather than scattered conditionals. This also fixes stale-source novel labels.

### Source icons and cover requests

```kotlin
sealed interface SourceIconRef {
    data class LocalDrawable(val drawable: Drawable) : SourceIconRef
    data class Remote(val url: String, val cacheKey: String) : SourceIconRef
    data class Resource(@DrawableRes val id: Int) : SourceIconRef
    data object LetterFallback : SourceIconRef
}

interface SourceArtworkResolver {
    fun icon(source: Source): SourceIconRef
    suspend fun drawable(source: Source, sizePx: Int): Drawable?
}

interface SourceIconBinder {
    fun bind(target: ImageView, source: Source)
}

internal interface SourceImageRequestResolver {
    suspend fun executeCover(
        sourceId: Long,
        url: String,
        cache: ImageCacheDirective,
        parameters: ImageRequestParameters,
    ): SourceImageResponse
}
```

`SourceArtworkResolver` owns this priority: installed APK icon, bundled/local icon, remote `NovelPluginSource.iconUrl`, then letter fallback. The binder owns request cancellation and Coil target behavior. Non-ImageView consumers such as menu items and shortcuts call the suspending drawable method with a lifecycle scope. Stats rows carry source identity, not a prematurely resolved `Drawable`.

`SourceImageRequestResolver` is the only network seam used by `MangaCoverFetcher`. It selects the source client and headers internally. `HttpSource` keeps its current client/headers; novel plugins use their network client and `getCoverRequestHeaders(url)` including Referer/cookies or plugin image-init policy. J2K surfaces continue to load a `Manga`, so fixing the central fetcher fixes browse grids/lists, global search, migration from/to cards, Library, details/full cover, Recents, widgets, and shortcut cover generation without per-screen header code. Transport types remain internal to the image package.

### Android 11+ storage locations

```kotlin
enum class StorageUse { Downloads, AutomaticBackups }

sealed interface StorageLocation {
    data class AppOwned(val use: StorageUse) : StorageLocation
    data class SafTree(val treeUri: String) : StorageLocation
    data class LegacyFile(val fileUri: String) : StorageLocation
}

sealed interface StorageAccess {
    data class Ready(val directory: UniFile, val displayPath: String) : StorageAccess
    data class NeedsSelection(val reason: StorageFailure) : StorageAccess
}

enum class StorageFailure { Missing, RevokedGrant, NotDirectory, NotWritable, UnsupportedLegacyPath }

interface StorageLocationRepository {
    fun observe(use: StorageUse): Flow<StorageAccess>
    fun selectSafTree(use: StorageUse, uri: Uri, resultFlags: Int): StorageSelectionResult
    fun selectAppOwned(use: StorageUse): StorageSelectionResult
    fun requireWritableRoot(use: StorageUse): StorageAccess.Ready
}
```

The default for new/unset locations is `context.getExternalFilesDir(null)/downloads` or `/backups`; internal files are the fallback when external app storage is unavailable. A chosen SAF tree is persisted only after masking the picker-returned read/write/persistable flags, taking the grant, resolving the document, and passing a bounded create/write/delete probe. `content://` roots never consult `Environment.isExternalStorageManager()` and are never converted to `File`.

Legacy `file://` preferences are decoded once. App-owned paths stay valid. A shared raw path is used only when it is already writable under the current Android rules; otherwise it becomes `NeedsSelection` and the UI offers App-owned or Choose folder. It is not silently treated as a content URI and old files are not silently moved. Automatic backup scheduling validates the root first; the worker resolves the same repository at run time and emits a localized failure notification for a revoked grant. `DownloadProvider`, `DownloadCache`, and `BackupCreatorJob` stop parsing preference strings themselves.

The generic all-files prompt is removed from Backup settings and is not part of Downloads/Backup location validity. `MANAGE_EXTERNAL_STORAGE` may remain for separate legacy/local-source flows, but these two features do not depend on it.

### Pagination, offline assets, and saved filters

```kotlin
data class PageGeometry(
    val chapterId: Long,
    val stridePx: Int,
    val pageIndex: Int,
    val pageCount: Int,
    val inlineExtentPx: Int,
)

enum class PageMove { Previous, Next }

interface PaginatedDocument {
    fun measurePages(activeChapterId: Long): PageGeometry
    fun move(move: PageMove): PageMoveResult
    fun moveTo(pageIndex: Int): PageGeometry
}
```

The HTML builder emits one viewport stride contract: column width plus gap equals the viewport inline size. JavaScript measures that same stride and uses it for stepping, settling, count, active page, slider restore, LTR, RTL, and vertical writing. The current `0.85 * innerWidth` path is deleted. Tap, swipe, and volume keys call `move`; at first/last page, `PageMoveResult.ChapterBoundary` routes through J2K's existing `loadChapter` rather than maintaining a second infinite chapter canvas. Rotation remeasures and restores normalized progress.

```kotlin
data class NovelAsset(
    val stream: InputStream,
    val mediaType: String,
    val originUrl: String?,
)

interface NovelAssetProvider {
    suspend fun openChapterAsset(chapterUrl: String, assetPath: String): NovelAsset?
}

data class DownloadedAssetRecord(
    val offlinePath: String,
    val fileName: String,
    val originUrl: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
)
```

Offline manifest v2 records trusted response MIME and original URL before paths are hashed. WebView responses use the record instead of guessing from `offline/<sha256>`. The packager recursively discovers CSS `url(...)` and `@import` dependencies through a bounded queue with cycle, depth, item-count, redirect, per-asset, and total-byte limits; it rewrites only successfully stored dependencies. Existing v1 packages remain readable using stream sniffing and `application/octet-stream`, and are upgraded on the next download. Translation is tested independently against the rendered offline document. Arbitrary remote URLs found only in user-injected custom CSS remain online-only; turning custom CSS into a crawler is rejected.

```kotlin
data class FilterSchemaFingerprint(val value: String)
data class FilterNodeKey(val path: List<String>, val type: FilterValueType, val duplicateOrdinal: Int)
sealed interface FilterValue { data class Bool(...); data class TriState(...); data class Text(...); data class Select(...); data class Sort(...) }
data class SavedFilterSnapshot(val sourceId: Long, val schema: FilterSchemaFingerprint, val values: Map<FilterNodeKey, FilterValue>)

interface SourceFilterStateStore {
    fun restore(sourceId: Long, fresh: FilterList): FilterRestoreReport
    fun saveApplied(sourceId: Long, filters: FilterList)
    fun clear(sourceId: Long)
}
```

The store serializes supported primitive states, never extension objects. Keys come from semantic ancestry, filter type, option labels, and a duplicate ordinal. Exact schema fingerprints restore fully; changed schemas restore only entries whose complete node signature still matches and ignore the rest. Applied Search saves; Reset clears; incognito sources neither read nor write. Each snapshot is size/count bounded. `hayai_source_filter_states` owns the JSON and timestamp, and Hayai backup carries the portable snapshot.

## Module map

| Owner | Proposed files / responsibility |
|---|---|
| `dev.ahmedmohamed.hayai.novel.identity` | `NovelContentIdentity`, SQLite store/backfill, backup DTO, migration vocabulary |
| `dev.ahmedmohamed.hayai.source.image` | source icon resolver/binder and cover request policy |
| `dev.ahmedmohamed.hayai.storage` | typed location codec, SAF grant validation, app-owned defaults, access status |
| `dev.ahmedmohamed.hayai.novel.reader` | measured pagination contract and typed WebView asset response |
| `dev.ahmedmohamed.hayai.novel.download` | manifest v2 and bounded recursive asset packager |
| `dev.ahmedmohamed.hayai.source.filter` | schema fingerprint, portable values, state store |
| J2K adapters | call the above contracts; retain authoritative models and navigation |

## J2K seams

- `AppModule.kt`: register the five Hayai services.
- Library presenter/policy and stats presenter: pass one `ContentKindIndex`; no new library state.
- Existing migration presenter/controllers: use identity for filtering and one vocabulary object for labels; existing migration engine remains.
- `MangaCoverFetcher.kt`: replace the `HttpSource?` cast/header selection with `SourceImageRequestResolver`.
- Existing `source.icon()` UI call sites: bind through `SourceIconBinder`; synchronous menu/shortcut sites use its drawable method. No extension ABI change.
- `DownloadProvider.kt`, `DownloadCache.kt`, Downloads settings, Backup settings, `BackupCreatorJob.kt`: consume `StorageLocationRepository`; remove their duplicate URI parsing and backup's all-files prompt.
- `BrowseSourcePresenter.kt`: restore after `getFilterList()`, save only in `setSourceFilter`, clear on reset. The existing sheet and pager stay.
- Existing J2K `ReaderActivity`/`ReaderViewModel` seams are not enlarged. `NovelReaderViewer` maps all page inputs to the geometry contract and calls the existing chapter loader at boundaries.

Every additional J2K file is an adapter seam requiring the boundary allowlist, `docs/architecture/j2k-reset.md`, `.audit/j2k-reset.tsv`, and feature-audit updates. No changes to `App.kt`, `MainActivity.kt`, or image-reader branches are required.

## Tests

- Identity schema/backfill fixture: active APK novel, remembered JS source, stale source with each legacy evidence type, ordinary manga, duplicate invocation, delete cascade, migration copy/move, backup id remap.
- One integration test proves Library filter, migration source compatibility, migration vocabulary, and novel statistics agree for the same stale-source entry.
- Icon resolver priority/cancellation/fallback tests; cover fetch MockWebServer tests assert Referer/cookie/custom headers on browse and migration `Manga` requests while ordinary `HttpSource` behavior is unchanged.
- Storage tests for app-owned default, valid SAF grant, revoked grant, provider without write support, legacy shared `file://`, no `MANAGE_EXTERNAL_STORAGE`, restart/WorkManager resolution, and non-destructive failure.
- Pagination tests for exact one-stride movement, page count, LTR/RTL/vertical, volume key/tap/swipe equivalence, rotation resume, and chapter-boundary delegation.
- Offline fixture with extensionless hashed image, CSS, nested background/font, `@import` cycle, script, redirect/size limits, v1 fallback, v2 MIME response, and offline translation.
- Filter round trip for every supported filter type, fresh-instance restore, reorder/schema drift, duplicate labels, reset, incognito, corrupted/oversized snapshot, and backup restore.
- Final validation: localization coverage, upstream boundary, focused unit tests, `:app:compileDevDebugKotlin`, `:app:testDevDebugUnitTest -x :app:formatKotlin`, `git diff --check`, then Android 11 and Android 16 device flows for SAF/download/automatic backup, migration images, pagination inputs, and offline assets.

## Sequencing

1. Add Hayai schema v5 for novel identity and saved filters, backup v5 DTOs, and the identity backfill/store.
2. Switch Library, statistics, and migration classification plus novel-specific migration vocabulary; complete #36 after device verification.
3. Add central icon/cover request adapters and migrate every current source-icon call site; verify migration rows first.
4. Add typed storage locations, migrate defaults, and switch download/backup callers; verify periodic backup after process restart on Android 11+.
5. Repair the pagination geometry and input convergence; complete #38 only after the device matrix passes.
6. Ship manifest v2 and bounded CSS packaging, then reproduce translation separately; complete #41 only when both reported cases are accounted for.
7. Add last-applied filter persistence on the existing sheet; complete #39 after restart and schema-change tests.
8. Update the audit docs and post short human issue replies describing what was actually verified. Do not close an issue on compile-only evidence.

## Rationale

### Shape

The materialized novel marker is the load-bearing decision. It converts scattered historical evidence into one monotonic fact while leaving all J2K content rows authoritative. Batch resolution hides SQL and source availability from consumers, per boundary-discipline and minimize-reader-load. The source image and storage interfaces are deep because they absorb provider-specific headers, asynchronous icon loading, Android URI permissions, legacy preference decoding, and recovery behind one operation each. Pagination and filters make state explicit and serializable instead of relying on coincidentally matching mutable objects, per encode-lessons-in-structure. All writes are idempotent and use stable identities where database ids can change.

### Synthesis decision

Candidate A deliberately chooses a small number of policy-heavy Hayai services plus thin J2K adapters. Its distinctive choice is to materialize proven novel identity per J2K manga row, then back it up by stable source/url. Arena synthesis should prefer this base if source-unavailable correctness and consistent UI outweigh adding one table; it can graft a less persistent candidate's query strategy only if schema change is ruled out.

### Tradeoffs accepted

- We accept one Hayai identity row per proven novel in exchange for constant, source-independent classification across every surface.
- We accept touching each source-icon presentation seam once in exchange for eliminating screen-specific remote-icon behavior.
- We accept app-owned defaults being deleted on uninstall in exchange for downloads/backups working immediately without broad storage permission; users who need durable shared files choose a SAF tree.
- We accept partial filter restore being conservative in exchange for never applying a value to a changed option/type.
- We accept bounded CSS dependency packaging rather than perfect arbitrary-web mirroring in exchange for safe, deterministic offline downloads.

### Alternatives considered

- Re-run the current UNION identity query independently in Library, migration, and statistics. It avoids a table but exposes evidence policy to every caller, repeats expensive reads, and still fails to remember an APK novel once its source disappears.
- Store content kind in J2K `mangas`. This gives simple reads but modifies an upstream-owned schema/model and enlarges every backup/rebase seam; the Hayai side table hides more complexity with less upstream coupling.
- Teach each image screen about `NovelPluginSource`. This is locally easy and globally shallow: callers must know icon URLs and headers, and the next surface will regress again.
- Request `MANAGE_EXTERNAL_STORAGE` for the old raw default. This does not solve revoked/content-provider grants, is unnecessary for SAF, and makes a broad permission part of ordinary download/backup correctness.
- Build a second paginated reader or novel browse/filter screen. Both duplicate J2K ownership and are outside the contributor contract.

### Open questions and risks

- Should an explicit future “treat as manga” override be allowed to remove a false-positive legacy marker, or is monotonic novel identity sufficient for this release?
- Which installed novel APK source records can be enumerated during the post-open backfill before any Library screen reads identity?
- Do any supported document providers pass the create/write probe but later reject WorkManager access despite a persisted grant?
- Does the reporter expect remote images referenced only from custom reader CSS to be imported, or is documenting them as online-only acceptable?
- Are named filter presets desired later, or does last-applied state fully satisfy #39? Named presets are not part of this slice.

### Explicit rejected scope

No second library/database, novel-only browse stack, second reader activity, image-reader changes, named filter preset UI, arbitrary custom-CSS crawler, automatic copying of old shared downloads, storage-provider implementation, or removal of `MANAGE_EXTERNAL_STORAGE` from unrelated local-source features. Issue #30's full Japanese typography verification and #37's theme smoke test may be tracker cleanup work, but they are not bundled into these architectural changes.

### Next implementation step

Implement schema v5, the identity backfill/store, and the stable-key backup DTO first, then make Library, statistics, and migration consume its batch result before touching the independent image, storage, reader, or filter slices.
