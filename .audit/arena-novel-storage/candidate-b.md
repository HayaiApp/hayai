# Candidate B: derived identity and narrow gateways

This candidate keeps one J2K library, one migration flow, one reader host, one browse presenter, one download provider, and one backup pipeline. Hayai adds small owned contracts at the seams where J2K currently has manga-only assumptions or Android 10 era storage assumptions.

The shape is intentionally not a second novel stack. It is a derived identity service plus three boundary gateways:

1. `ContentIdentityService` decides durable content kind for existing J2K `Manga` rows.
2. `SourceVisuals` decides source icons and source-aware image requests.
3. `ScopedStorageGateway` decides whether a configured downloads or backup location is writable under scoped storage.
4. Focused feature contracts repair offline novel assets, paged geometry, source preview grid math, and last-applied filters without duplicating J2K models.

## Caller usage first

### Library and stats ask one question

```kotlin
class HayaiLibraryPolicy(
    private val preferences: HayaiPreferences,
    private val contentIdentity: ContentIdentityService,
) {
    fun includes(manga: Manga): Boolean {
        val identity = contentIdentity.classify(manga)
        return preferences.novelLibraryFilter().get().includes(identity.kind == ContentKind.Novel)
    }
}

class NovelJ2kIntegration(
    private val contentIdentity: ContentIdentityService,
    private val statsRepository: NovelStatisticsRepository,
) {
    fun isNovel(manga: Manga): Boolean =
        contentIdentity.classify(manga).kind == ContentKind.Novel

    fun libraryStatistics(mangas: List<Manga>): NovelLibraryStatistics =
        statsRepository.forMangaIds(
            mangas
                .map(contentIdentity::classify)
                .filter { it.kind == ContentKind.Novel }
                .mapNotNull { it.mangaId?.value }
        )
}
```

The important caller rule is that Library, stats, recents, and migration do not ask `SourceManager` directly when they need durable manga-vs-novel identity. They ask `ContentIdentityService`.

### Migration labels and target sources use the same identity

```kotlin
val entries = selectedMangaIds.mapNotNull(database::getManga)
val batch = contentIdentity.classify(entries)
val kind = batch.singleKindOrNull() ?: return showMixedContentNotSupported()
val labels = contentLabels.forKind(kind)

dialog.title = context.resources.getQuantityString(labels.migrateQuestion, entries.size, entries.size)
val targets = contentIdentity.compatibleSources(kind, allCatalogueSources)
```

This removes the stale-source split where migration can know a legacy novel is a novel while Library and stats still treat it as manga.

Visible text should use `hayai_` resources for novel-specific words and keep generic resources where the wording already says "series". Internal J2K class names such as `MigrationListController` do not need a rename.

### Source rows bind through one visuals policy

```kotlin
class MigrationSourceHolder(...) {
    fun bind(source: CatalogueSource, sourceEnabled: Boolean) {
        binding.title.text = source.nameBasedOnEnabledLanguages(...)
        sourceVisuals.bindIcon(binding.sourceImage, source)
    }
}

class SourceHolder(...) {
    fun bind(item: SourceItem) {
        sourceVisuals.bindIcon(binding.sourceImage, item.source)
    }
}
```

Browse already special-cases `NovelPluginSource.iconUrl`. Migration rows do not. The caller should not know which source type needs a remote icon. It should only bind an icon model.

### Cover requests keep using `.data(manga)`

```kotlin
ImageRequest.Builder(context)
    .data(manga)
    .setParameter(MangaCoverFetcher.useCustomCover, false)
    .target(CoverViewTarget(image))
    .build()
```

The J2K image surfaces stay unchanged at call sites. `MangaCoverFetcher` becomes source-aware through `SourceVisuals.coverRequest(manga, thumbnailUrl)` instead of casting the source to `HttpSource`. That fixes novel cover headers everywhere existing J2K surfaces already use the cover fetcher: browse grids, global search, migration process cards, details, widgets, and edit dialogs.

### Downloads and automatic backups resolve storage before writing

```kotlin
val root = scopedStorage.resolve(StoragePurpose.Downloads)
val mangaDir = root.requireWritableDirectory()
    .createDirectory(provider.sanitizedSourceDir(source))
    .createDirectory(provider.sanitizedMangaDir(manga))

if (root.requiresManageExternalStorage && !storagePermission.hasManageExternalStorage()) {
    return download.status = Download.ERROR
}
```

`requiresManageExternalStorage` is false for SAF tree URIs and app-owned external files directories. It is only true for legacy raw shared-external file paths on Android 11+.

Automatic backups use the same resolver:

```kotlin
val root = scopedStorage.resolve(StoragePurpose.AutomaticBackups)
val automaticDir = root.requireWritableDirectory().createDirectory("automatic")
val file = automaticDir.createFile(Backup.getBackupFilename())
```

### Browse restores last-applied filters after the source creates them

```kotlin
sourceFilters = source.getFilterList()
savedFilterStore.restoreLastApplied(source.id, sourceFilters)

fun setSourceFilter(filters: FilterList) {
    savedFilterStore.saveLastApplied(source.id, filters)
    restartPager(filters = filters)
}
```

The browse presenter still owns query and paging. Hayai only serializes primitive filter state against a schema fingerprint and refuses unsafe restores.

## Type sketch

```kotlin
@JvmInline
value class MangaId(val value: Long)

@JvmInline
value class SourceId(val value: Long)

enum class ContentKind {
    Manga,
    Novel,
}

enum class ContentEvidenceKind {
    LiveNovelSource,
    LegacyGenre,
    NovelPluginSourceRow,
    NovelQuote,
    NovelHighlight,
    NovelStatistics,
}

data class ContentIdentity(
    val mangaId: MangaId?,
    val sourceId: SourceId,
    val kind: ContentKind,
    val evidence: Set<ContentEvidenceKind>,
)

data class ContentIdentityBatch(
    val identities: Map<Long, ContentIdentity>,
) {
    fun singleKindOrNull(): ContentKind?
}

interface ContentIdentityService {
    fun classify(manga: Manga): ContentIdentity
    fun classify(mangas: Collection<Manga>): ContentIdentityBatch
    fun classifyByIds(mangaIds: Collection<Long>): ContentIdentityBatch
    fun compatibleSources(kind: ContentKind, sources: Iterable<CatalogueSource>): List<CatalogueSource>
}

data class ContentLabels(
    @StringRes val sectionTitle: Int,
    @StringRes val singularName: Int,
    @StringRes val pluralName: Int,
    @PluralsRes val migrateQuestion: Int,
    @PluralsRes val copyQuestion: Int,
    @PluralsRes val migratedToast: Int,
)

interface ContentLabelResolver {
    fun forKind(kind: ContentKind): ContentLabels
}
```

`ContentIdentityService` should derive from current facts first. No new authoritative library or progress table is introduced.

```kotlin
sealed interface SourceIconModel {
    data class DrawableIcon(val drawable: Drawable) : SourceIconModel
    data class RemoteIcon(
        val url: String,
        val headers: Headers = Headers.headersOf(),
    ) : SourceIconModel
    data object Empty : SourceIconModel
}

data class SourceImageRequestPolicy(
    val client: Call.Factory,
    val headers: Headers,
    val cacheKeySalt: String? = null,
)

interface SourceVisuals {
    fun icon(source: Source): SourceIconModel
    fun bindIcon(target: ImageView, source: Source)
    fun coverRequest(source: Source?, manga: Manga, url: String): SourceImageRequestPolicy
}

interface SourceCoverRequestContributor {
    fun coverRequest(url: String, manga: Manga): SourceImageRequestPolicy?
}
```

`NovelPluginSource` can implement `SourceCoverRequestContributor` or be adapted by an internal `NovelPluginSourceVisualContributor`. `HttpSource` becomes one contributor rather than the fetcher's only source-specific path.

```kotlin
enum class StoragePurpose {
    Downloads,
    AutomaticBackups,
}

enum class StorageAccessMode {
    SafTree,
    AppOwnedExternalFiles,
    LegacySharedExternalFile,
    ManualDocumentFile,
}

data class ResolvedStorage(
    val purpose: StoragePurpose,
    val configuredUri: Uri,
    val root: UniFile,
    val mode: StorageAccessMode,
    val displayName: String,
    val requiresManageExternalStorage: Boolean,
) {
    fun requireWritableDirectory(): UniFile
}

sealed interface StorageCheck {
    data class Writable(val storage: ResolvedStorage) : StorageCheck
    data class NotWritable(
        val reason: StorageFailureReason,
        @StringRes val userMessage: Int,
    ) : StorageCheck
}

enum class StorageFailureReason {
    MissingPermission,
    MissingTree,
    NotDirectory,
    CreateFailed,
    LegacyPathRequiresManageExternalStorage,
}

interface ScopedStorageGateway {
    fun defaultLocation(purpose: StoragePurpose): Uri
    fun resolve(purpose: StoragePurpose): ResolvedStorage
    fun checkWritable(purpose: StoragePurpose): StorageCheck
    fun persistPickedTree(purpose: StoragePurpose, uri: Uri, flags: Int): ResolvedStorage
    fun displayName(purpose: StoragePurpose): String
}
```

Defaults should be app-owned external files directories on Android 11+:

```text
downloads: context.getExternalFilesDir(null)/downloads
backups:   context.getExternalFilesDir(null)/backup
```

Existing configured SAF trees remain valid. Existing configured raw shared-external paths remain supported as legacy locations but must report `requiresManageExternalStorage = true` on Android 11+.

```kotlin
data class NovelAssetResponse(
    val stream: InputStream,
    val mimeType: String,
    val originUrl: String?,
)

fun interface NovelDownloadAssetResolver {
    suspend fun open(reference: String): NovelAssetResponse?
}

data class DownloadedAssetV2(
    val path: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val mimeType: String,
    val originUrl: String?,
)

data class NovelDownloadManifestV2(
    val schemaVersion: Int = 2,
    val sourceId: Long,
    val chapterUrl: String,
    val contentType: String,
    val documentSize: Long,
    val documentSha256: String,
    val assets: List<DownloadedAssetV2>,
    val unavailableAssets: List<String>,
)

interface NovelOfflinePackageStore {
    suspend fun save(
        sourceId: Long,
        chapterUrl: String,
        document: NovelDocument,
        resolver: NovelDownloadAssetResolver,
    ): NovelDownloadManifestV2

    fun openAsset(sourceId: Long, chapterUrl: String, assetPath: String): NovelAssetResponse?
}
```

The asset contract returns MIME and origin metadata with the stream. The manifest path remains an offline hashed path, but the WebView no longer has to guess MIME from a hash.

```kotlin
data class PageGeometrySnapshot(
    val chapterId: Long,
    val progressPercent: Int,
    val pageNumber: Int,
    val pageCount: Int,
    val atStart: Boolean,
    val atEnd: Boolean,
)

data class ChapterPageRange(
    val chapterId: Long,
    val originPx: Float,
    val stridePx: Float,
    val pageCount: Int,
    val direction: ReadingDirection,
)

interface NovelPagedGeometryBridge {
    fun locationScript(activeChapterId: Long): String
    fun seekScript(activeChapterId: Long, progressPercent: Int): String
    fun stepScript(activeChapterId: Long, deltaPages: Int): String
}
```

Every paged operation uses the same measured `ChapterPageRange`. Step, seek, page count, and edge detection must not each invent their own stride.

```kotlin
@Serializable
data class SourceFilterSnapshot(
    val sourceId: Long,
    val schemaFingerprint: String,
    val capturedAtEpochMillis: Long,
    val entries: List<FilterStateEntry>,
)

@Serializable
data class FilterStateEntry(
    val path: List<Int>,
    val kind: FilterStateKind,
    val value: FilterStateValue,
)

enum class FilterStateKind {
    CheckBox,
    TriState,
    Text,
    Select,
    Sort,
}

sealed interface FilterRestoreReport {
    data object Restored : FilterRestoreReport
    data class Partial(val skippedPaths: List<List<Int>>) : FilterRestoreReport
    data object SchemaMismatch : FilterRestoreReport
}

interface SourceFilterSnapshotStore {
    fun restoreLastApplied(sourceId: Long, filters: FilterList): FilterRestoreReport
    fun saveLastApplied(sourceId: Long, filters: FilterList)
    fun clear(sourceId: Long)
}
```

```kotlin
data class PreviewGridSpec(
    val columns: Int,
    val cellWidthPx: Int,
    val cellHeightPx: Int,
    val outerPaddingPx: Int,
    val columnSpacingPx: Int,
    val rowSpacingPx: Int,
) {
    val requiredWidthPx: Int
        get() = outerPaddingPx * 2 + columns * cellWidthPx + (columns - 1) * columnSpacingPx
}

interface PreviewGridGeometry {
    fun forContainerWidth(widthPx: Int, density: Float): PreviewGridSpec
}
```

Both the inline source details preview and the full page preview should use the same spec. The current `columns()` only divides by cell width and ignores horizontal padding and spacing.

## Signatures by subsystem

### `ContentIdentityService`

```kotlin
class DatabaseBackedContentIdentityService(
    private val database: DatabaseHelper,
    private val sourceManager: SourceManager,
) : ContentIdentityService
```

Batch classification should query side-data once:

```kotlin
private fun DatabaseHelper.novelEvidenceFor(mangaIds: Collection<Long>, sourceIds: Collection<Long>): Map<Long, Set<ContentEvidenceKind>>
```

Evidence rules:

- `LiveNovelSource` if `sourceManager.getOrStub(manga.source).isNovelSource()`.
- `LegacyGenre` if `Manga.hasNovelGenreIdentity()`.
- `NovelPluginSourceRow` if `hayai_novel_plugin_sources.source_id = manga.source`.
- `NovelQuote` if quotes exist for `manga_id`.
- `NovelHighlight` if highlights exist for `manga_id`.
- `NovelStatistics` if stats exist through the manga's chapters.

Classification is novel when any evidence exists. If no evidence exists, it is manga.

### `SourceVisuals`

```kotlin
class HayaiSourceVisuals(
    private val app: Application,
    private val extensionManager: ExtensionManager,
    private val callFactory: Call.Factory,
) : SourceVisuals
```

Contributor order:

1. Novel plugin icon URL and novel plugin cover headers.
2. Extension-installed APK icon and `HttpSource.headers`.
3. Bundled icons from `BundledSourceIconResolver`.
4. Local manga and local novel fallback icons.
5. Empty.

The policy should clear/dispose stale image requests before loading a new icon into a recycled holder.

### `ScopedStorageGateway`

```kotlin
class AndroidScopedStorageGateway(
    private val context: Context,
    private val preferences: PreferencesHelper,
) : ScopedStorageGateway
```

Rules:

- `content://` tree URI: call `takePersistableUriPermission` when picked, resolve with `UniFile.fromUri`, never require `MANAGE_EXTERNAL_STORAGE`.
- App-owned file URI under `context.getExternalFilesDirs`: resolve with `UniFile.fromUri`, never require `MANAGE_EXTERNAL_STORAGE`.
- Legacy file URI under shared external storage on Android 11+: resolve only if available, but mark `requiresManageExternalStorage = true`.
- Manual backup file URI from `ACTION_CREATE_DOCUMENT`: treat as `ManualDocumentFile`, not a directory and not a default backup root.

The existing invalid location message should be reserved for actual failures such as revoked tree permission or failed directory creation.

### Novel offline package

```kotlin
class NovelOfflinePackager(
    private val assetReferenceParser: NovelAssetReferenceParser,
    private val limits: NovelOfflineLimits,
)
```

```kotlin
data class NovelOfflineLimits(
    val maxAssetCount: Int = 256,
    val maxAssetBytes: Long = 50L * 1024L * 1024L,
    val maxCssDepth: Int = 4,
    val maxRedirects: Int = 5,
)
```

The parser should:

- collect document `img`, `source`, `video poster`, `link rel=stylesheet`, scripts if already referenced by the chapter document, inline style `url(...)`, and style block `url(...)`;
- fetch text/css assets, preserve their MIME, and recursively collect `@import` and `url(...)`;
- rewrite CSS references relative to the CSS asset URL, not the chapter base URL;
- record blocked or failed assets in `unavailableAssets`;
- avoid crawling arbitrary remote pages, user custom reader CSS, or non-referenced assets.

### Paged geometry

The JavaScript bridge should expose one object:

```javascript
window.HayaiPages = {
  measure(chapterId) {},
  location(chapterId) {},
  seek(chapterId, progressPercent) {},
  step(chapterId, deltaPages) {},
}
```

`measure()` returns the same stride used by `location`, `seek`, and `step`. In paged mode, `step(chapterId, 1)` advances exactly one page. If the next page would cross the active chapter range, the renderer returns an edge event and `NovelReaderViewer` asks J2K to load the next or previous chapter through the existing chapter navigation path.

### Saved filters

`SourceFilterSnapshotStore` should compute a schema fingerprint from:

- filter kind;
- filter name;
- option labels and option count for `Select` and `Sort`;
- group nesting and child order.

It should not include the current state in the fingerprint. It should store primitive states for `CheckBox`, `TriState`, `Text`, `Select`, `Sort`, and the same primitives inside `Group`. It should bound text values and total stored JSON size.

## Module map

| Module | New Hayai files | Existing adapter seams |
| --- | --- | --- |
| Content identity | `dev/ahmedmohamed/hayai/content/ContentIdentityService.kt`, `ContentLabels.kt` | `NovelMigrationPolicy`, `NovelJ2kIntegration`, `HayaiLibraryPolicy`, migration controllers |
| Source visuals | `dev/ahmedmohamed/hayai/source/visual/SourceVisuals.kt`, `NovelPluginVisualContributor.kt` | `SourceHolder`, migration source holders, `MangaCoverFetcher.Factory` |
| Scoped storage | `dev/ahmedmohamed/hayai/storage/ScopedStorageGateway.kt`, `StoragePurpose.kt` | `PreferencesHelper`, `SettingsDownloadController`, `SettingsBackupController`, `DownloadProvider`, `DownloadCache`, `Downloader`, `BackupCreatorJob`, `BackupCreator` |
| Offline novel package | `dev/ahmedmohamed/hayai/novel/offline/NovelOfflinePackager.kt`, `NovelOfflineManifestV2.kt` | `NovelDownloadStore`, `NovelReaderSession`, `NovelAssetWebViewClient`, `HtmlAssetRewriter` |
| Paged geometry | `dev/ahmedmohamed/hayai/novel/reader/NovelPagedGeometry.kt` if pure Kotlin helpers are useful | `NovelHtmlDocumentBuilder`, `WebNovelRenderer`, `NovelReaderViewer` |
| Saved filters | `dev/ahmedmohamed/hayai/source/filter/SourceFilterSnapshotStore.kt` | `BrowseSourcePresenter`, source filter sheet apply and clear actions |
| Preview grid | `dev/ahmedmohamed/hayai/source/preview/PreviewGridGeometry.kt` | `SyPagePreviewLayout`, `SourceDetailsHost`, `SourcePreviewController` |

Register these in `AppModule` through Injekt:

```kotlin
addSingletonFactory<ContentIdentityService> { DatabaseBackedContentIdentityService(get(), get()) }
addSingletonFactory<SourceVisuals> { HayaiSourceVisuals(app, get(), get<NetworkHelper>().client) }
addSingletonFactory<ScopedStorageGateway> { AndroidScopedStorageGateway(app, get()) }
addSingletonFactory<SourceFilterSnapshotStore> { PreferencesSourceFilterSnapshotStore(HayaiPreferences(get()), get()) }
```

`NovelMigrationPolicy` can remain as a compatibility wrapper for current callers while delegating classification and source compatibility to `ContentIdentityService`.

## Rationale

### Problem

The current implementation has four different kinds of seam leaks:

- Novel identity is durable in migration but live-source-only in Library and stats. Stale or orphaned source rows can therefore show as manga in one place and novels in another.
- Source presentation has a remote icon path in Browse but migration rows only call `source.icon()`, so JavaScript novel source icons disappear in migration screens.
- Cover loading for J2K image surfaces only gets source headers when the source is an `HttpSource`. Novel plugin cover URLs often need source headers or referer logic but still travel through the manga cover fetcher.
- Downloads and automatic backups still mix legacy raw external defaults with scoped-storage SAF picks. On Android 11+, the app can reject valid locations or demand broad storage access even when a content URI or app-owned directory is writable.

The selected issue slices add smaller leaks in the same category: offline novel assets lose MIME and nested CSS dependencies, paged mode uses inconsistent width math, preview grid math ignores padding and gaps, and browse filters are only in memory.

### Shape

Candidate B uses derived services, not new durable models:

- Content kind is derived from live source capability and existing Hayai side-data evidence.
- Source visuals are derived from source capability, plugin metadata, installed extension metadata, and bundled icons.
- Storage writability is derived from the configured URI and Android API level.
- Offline asset metadata is durable only inside the existing novel offline manifest, because that data belongs to the downloaded chapter package.
- Filter state is durable in preferences, keyed by source ID and schema fingerprint, because it is UI state rather than library data.

This keeps J2K as the single source of truth for library, chapters, downloads, backups, and browse paging.

### Synthesis decision

For this candidate, the strongest architectural move is to avoid persisting a separate content-kind table. The current repo already has enough durable evidence to classify novels. A new table would introduce a second identity owner and migration repair burden.

The proposed synthesis point is:

```text
J2K model stays authoritative
Hayai gateways answer Hayai-specific questions at the edges
```

The edge questions are "is this J2K manga row a novel", "how do I load this source image", and "can this configured URI be written".

### Tradeoffs accepted

- Classification remains derived and may be recomputed. This is acceptable because it prevents drift between a new table and J2K rows.
- `ContentIdentityService` reads multiple evidence sources. This is slightly broader than the existing `NovelMigrationPolicy`, but it centralizes a policy already spread across migration, Library, and stats.
- `MangaCoverFetcher` changes internally. This is a sensitive J2K seam, but keeping `.data(manga)` preserves all image-surface call sites.
- Legacy raw shared-external paths still work only with broad storage permission on Android 11+. The repair is not to bypass Android storage rules. The repair is to default and guide users to SAF or app-owned locations.
- Filter snapshots can become stale after source filter schema changes. The fingerprint refuses unsafe restores instead of guessing.

### Alternatives considered

1. Persist `hayai_content_identity`.
   - Rejected for the first slice. It would be a second source of truth for a J2K row and would need migrations, repair jobs, and conflict semantics.

2. Rename or fork J2K manga migration classes into novel migration classes.
   - Rejected. Visible labels can be fixed through resource indirection while preserving one migration flow.

3. Teach every holder about `NovelPluginSource.iconUrl`.
   - Rejected. Browse already did this once and migration missed it. A binder prevents the same bug in the next source row.

4. Add a novel cover fetcher and update every cover call site.
   - Rejected. The whole value of J2K cover loading is that all surfaces use `.data(manga)`. The source-aware fetcher preserves that.

5. Request `MANAGE_EXTERNAL_STORAGE` for all downloads.
   - Rejected. It is unnecessary for SAF trees and app-owned directories, and it makes Android 11+ behavior worse for ordinary users.

6. Crawl every CSS import and remote dependency reachable from a chapter.
   - Rejected. Offline packaging should include referenced chapter assets with limits, not become a web crawler.

7. Named filter presets in the first pass.
   - Rejected. The issue asks for filters to save. Last-applied filters are the smallest useful vertical slice.

8. Append all adjacent novel chapters in paged mode.
   - Rejected for the first repair. Page geometry should be correct for one active chapter before continuous preloading adds complexity.

### Open questions and risks

- Novel plugin cover headers need a concrete source API. If `NovelPluginSource` already exposes image request init data, adapt it. If not, add a minimal Hayai-owned contributor around plugin metadata rather than widening `NovelSource`.
- The exact source of quote and highlight tables should be confirmed before implementation. The design assumes the same evidence already used by `DatabaseNovelMigrationIdentity`.
- App-owned external files directories are user-visible through Android file managers on some devices and less visible on others. SAF should remain available from settings for users who want a shared folder.
- Some `UniFile` implementations can report weak metadata for content URIs. Storage checks should prove writability by creating and deleting a tiny probe file inside the resolved directory, then avoid repeated probes in hot paths.
- Existing downloaded image manga under raw shared external storage should not be moved automatically. Offer repair guidance or leave legacy location if permission exists.
- Filter text fields may contain user-entered sensitive strings. Keep data local, bounded, and clearable. Do not include it in exported Hayai payloads unless the backup contract explicitly opts in later.

## Tests

### Content identity

- `ContentIdentityServiceTest`
  - live novel source classifies as `Novel`;
  - legacy genre `Light Novel` classifies as `Novel`;
  - fuzzy genre `Novel adaptation` remains `Manga`;
  - plugin source row classifies stale-source rows as `Novel`;
  - quote, highlight, and stats evidence classify rows as `Novel`;
  - batch classification returns the same result as single classification;
  - `compatibleSources(ContentKind.Novel, sources)` returns only novel catalogue sources.

- Adapter tests
  - `HayaiLibraryPolicy` includes or hides stale-source novels according to the novel library selector;
  - `NovelJ2kIntegration.libraryStatistics` counts stale-source novels;
  - migration source grouping labels stale-source novels under novels.

### Source visuals and cover headers

- `SourceVisualsTest`
  - `NovelPluginSource.iconUrl` returns `RemoteIcon`;
  - bundled local novel source returns local novel icon;
  - APK `HttpSource` still returns installed extension icon and headers;
  - `coverRequest` for `HttpSource` preserves existing headers;
  - `coverRequest` for novel plugin source adds expected referer or plugin image headers.

- Holder smoke test or focused Robolectric test
  - migration source holder binds a remote novel plugin icon rather than leaving a recycled drawable.

- Fetcher test
  - `MangaCoverFetcher` builds network requests with `SourceVisuals.coverRequest` headers for non-`HttpSource` novel source.

### Storage

- `ScopedStorageGatewayTest`
  - SAF tree URI is writable and does not require `MANAGE_EXTERNAL_STORAGE`;
  - app-owned external files default is writable and does not require `MANAGE_EXTERNAL_STORAGE` on Android 11+;
  - legacy shared-external file URI on Android 11+ requires `MANAGE_EXTERNAL_STORAGE`;
  - revoked SAF tree returns `MissingPermission`;
  - automatic backup path creates `automatic/` under a SAF tree;
  - download provider creates manga directories under SAF and app-owned roots.

- Downloader focused test
  - image download does not stop at the Android 11 all-files check when storage mode is SAF or app-owned.

### Offline novel assets

- `NovelOfflinePackagerTest`
  - HTML document with linked CSS, nested `@import`, background image, and font stores every allowlisted referenced asset;
  - stored manifest v2 includes MIME and origin URL for every asset;
  - WebView asset response returns manifest MIME for hashed offline paths;
  - CSS cycle, byte limit, asset count limit, and redirect limit produce unavailable assets instead of hanging;
  - v1 manifest asset opens with fallback MIME behavior.

### Paged geometry

- `NovelPagedGeometryTest`
  - `step(1)` moves one measured page, not `.85 * innerWidth`;
  - page number and page count use the active chapter range;
  - RTL/reverse direction inverts movement without changing page count;
  - seeking to 0 and 100 lands on first and last page of the active chapter;
  - crossing before first or after last page reports a boundary event for J2K chapter navigation.

- Manual emulator smoke
  - tap zones, swipes, volume keys, slider, resume, rotation, and chapter edges in paged mode.

### Saved filters

- `SourceFilterSnapshotStoreTest`
  - captures and restores `CheckBox`, `TriState`, `Text`, `Select`, `Sort`, and nested group children;
  - schema fingerprint mismatch refuses restore;
  - partial restore skips only invalid paths;
  - large text values and oversized snapshots are bounded.

### Source preview geometry

- `PreviewGridGeometryTest`
  - required width never exceeds container width;
  - inline and full preview use the same columns for the same content width;
  - very narrow width still returns one column;
  - spacing and outer padding are included in the column count.

## J2K seams

Expected J2K-file adapter touches:

- `eu/kanade/tachiyomi/AppModule.kt`
  - register the new Hayai gateways.
- `eu/kanade/tachiyomi/ui/library/LibraryPresenter.kt`
  - construct or inject `HayaiLibraryPolicy` with `ContentIdentityService`.
- `eu/kanade/tachiyomi/ui/migration/BaseMigrationPresenter.kt`
  - group through `ContentIdentityService` or wrapper `NovelMigrationPolicy`.
- `eu/kanade/tachiyomi/ui/migration/SearchPresenter.kt`
  - keep novel-to-novel and manga-to-manga source filtering through the wrapper.
- `eu/kanade/tachiyomi/ui/migration/manga/design/PreMigrationController.kt`
  - target source compatibility and remote source icon binding.
- `eu/kanade/tachiyomi/ui/migration/manga/process/MigrationListController.kt`
  - novel-specific copy/migrate questions and migrated toast when the selected batch is novel.
- `eu/kanade/tachiyomi/ui/migration/SourceHolder.kt`
  - `SourceVisuals.bindIcon`.
- `eu/kanade/tachiyomi/ui/migration/manga/design/MigrationSourceHolder.kt`
  - `SourceVisuals.bindIcon`.
- `eu/kanade/tachiyomi/data/image/coil/MangaCoverFetcher.kt`
  - replace `HttpSource`-only header lookup with `SourceVisuals.coverRequest`.
- `eu/kanade/tachiyomi/data/preference/PreferencesHelper.kt`
  - scoped-storage-aware defaults for downloads and backup directories.
- `eu/kanade/tachiyomi/ui/setting/SettingsDownloadController.kt`
  - display and persist resolved SAF or app-owned download locations.
- `eu/kanade/tachiyomi/ui/setting/SettingsBackupController.kt`
  - display and persist resolved automatic backup locations.
- `eu/kanade/tachiyomi/data/download/DownloadProvider.kt`
  - resolve root through `ScopedStorageGateway`.
- `eu/kanade/tachiyomi/data/download/DownloadCache.kt`
  - resolve root through `ScopedStorageGateway`.
- `eu/kanade/tachiyomi/data/download/Downloader.kt`
  - gate `MANAGE_EXTERNAL_STORAGE` only for legacy shared-external roots.
- `eu/kanade/tachiyomi/data/backup/BackupCreatorJob.kt`
  - resolve automatic backup root through `ScopedStorageGateway`.
- `eu/kanade/tachiyomi/data/backup/BackupCreator.kt`
  - create automatic backup under resolved root and produce better failures.
- `eu/kanade/tachiyomi/ui/source/browse/BrowseSourcePresenter.kt`
  - restore and save last-applied source filters.
- `eu/kanade/tachiyomi/ui/source/SourceHolder.kt`
  - optional convergence to `SourceVisuals.bindIcon` so Browse and migration share the same presentation path.

Protected files from the contributor contract:

- No change expected in `App.kt`.
- No change expected in `MainActivity.kt`.
- No new `ReaderActivity` seam expected. Paged geometry should stay inside the existing novel viewer and renderer callbacks hosted by J2K `ReaderActivity`.

If implementation touches additional J2K files, update `docs/architecture/j2k-reset.md` and `.audit/j2k-reset.tsv` with the seam.

## Sequencing

1. Add `ContentIdentityService` and make `NovelMigrationPolicy` delegate to it.
   - Update Library and stats to use the same identity.
   - Add novel-specific migration labels only where visible text still says manga or uses manga-only resources.
   - Close the novel/manga separation issue slice after tests and manual smoke.

2. Add `SourceVisuals`.
   - Convert migration source holders first, because that fixes the missing novel source images directly.
   - Convert Browse source holder second to remove duplicate icon logic.
   - Replace `MangaCoverFetcher` header selection with `SourceVisuals.coverRequest`.

3. Add `ScopedStorageGateway`.
   - Change Android 11+ defaults to app-owned external files directories.
   - Keep SAF picker and persistable permissions.
   - Update DownloadProvider, Downloader, automatic backups, and settings summaries.
   - Test SAF and app-owned paths before touching legacy path behavior.

4. Upgrade novel offline assets to manifest v2.
   - Add MIME and origin metadata.
   - Add recursive CSS dependency packaging with strict limits.
   - Keep v1 read compatibility.

5. Repair paged geometry.
   - Replace separate stride math with one measured active-chapter range.
   - Wire edge events to existing J2K chapter navigation.
   - Defer adjacent DOM appending unless the measured single-chapter mode still fails a continuous-reader use case.

6. Repair preview geometry.
   - Introduce `PreviewGridGeometry`.
   - Use it in inline details preview and full source preview.

7. Add last-applied source filter persistence.
   - Restore after `source.getFilterList()`.
   - Save on apply.
   - Add clear action only if the existing filter UI has an obvious home for it.

8. Issue close pass.
   - Reply to each completed issue with plain human wording and no AI attribution.
   - Close only issues whose implemented slice was verified.
   - For issue #30 and #37, close after smoke verification if no code change is needed.

## Explicit rejected scope

- No second library, chapter, history, download, backup, or reader model.
- No new novel migration flow separate from J2K migration.
- No new reader activity.
- No automatic migration of existing user download folders.
- No forced all-files storage permission for SAF or app-owned locations.
- No legacy code copy from `legacy/hayai-pre-j2k`.
- No broad rename of J2K `Manga` internal class names.
- No named filter preset manager in this slice.
- No backup of downloaded offline novel assets.
- No crawler for arbitrary CSS, custom theme CSS, or non-chapter-linked assets.

## Next implementation step

Implement the first vertical slice:

1. Add `ContentIdentityService` under Hayai.
2. Register it in `AppModule`.
3. Make `NovelMigrationPolicy`, `HayaiLibraryPolicy`, and `NovelJ2kIntegration` use it.
4. Add focused identity tests.
5. Update only visible migration strings that still render manga-specific wording for novel batches.

This gives the rest of the work a stable content-kind primitive before source visuals, storage, and issue slices build on it.
