# Candidate B: one durable translated-document repository

## Caller usage

Reader settings use the same 1-percent domain in both surfaces:

```kotlin
percentageSlider(preferences.novelAutoLoadNextChapterAt, range = 50..100)
percentageSlider(preferences.novelMarkAsReadThreshold, range = 50..100)
```

The viewer reports the final outgoing state, then uses J2K's chapter transition:

```kotlin
renderer.isShort { short ->
    progress.reportReady(shortChapter = short && preferences.novelMarkShortChapterAsRead.get())
}

suspend fun navigateTo(chapter: Chapter, reason: ChapterAdvanceReason) {
    progress.flushCurrent()
    playback.noteTransition(reason)
    activity.loadChapter(chapter)
}
```

Document replacement owns the customization lifecycle:

```kotlin
renderer.replaceVariant(ContentVariant.Translated(result.text))
renderer.replaceVariant(ContentVariant.Original)
// Web renderer replaces nodes, then reruns the configured custom JS once.
```

Chapter-list actions enqueue durable translation work without coordinating storage details:

```kotlin
translationBatches.enqueue(
    NovelTranslationBatchRequest(mangaId, chapterIds, settings.snapshot()),
)

val translated = translations.get(locator, targetLanguage)
```

## Types and signatures

```kotlin
@JvmInline value class Percent(val value: Int) // factory requires 0..100

enum class ChapterAdvanceReason { User, AutoNext, TtsAutoNext }
enum class ContentVariant { Original, Translated }

interface NovelReaderProgressPort {
    fun report(position: Percent)
    fun reportReady(shortChapter: Boolean)
    fun flushCurrent()
}

sealed interface TtsHandoff {
    data object None : TtsHandoff
    data class ResumeAtStart(val targetChapterId: Long) : TtsHandoff
}

interface NovelDocumentVariantRenderer {
    fun replaceVariant(variant: ContentVariant, text: String? = null)
}

data class NovelTranslationLocator(
    val sourceKey: String,       // stable source namespace/id, not display name
    val mangaUrl: String,
    val chapterUrl: String,
)

data class StoredNovelTranslation(
    val targetLanguage: String,
    val sourceLanguage: String,
    val engineId: String,
    val sourceHash: String,
    val translatedContent: String,
    val createdAt: Long,
)

interface NovelTranslationRepository {
    suspend fun get(locator: NovelTranslationLocator, targetLanguage: String): StoredNovelTranslation?
    suspend fun put(locator: NovelTranslationLocator, value: StoredNovelTranslation)
    suspend fun remove(locator: NovelTranslationLocator, targetLanguage: String)
    suspend fun moveNovel(from: NovelIdentity, to: NovelIdentity, chapterMap: Map<String, String>): MoveResult
    suspend fun exportFor(manga: Collection<MangaIdentity>): Sequence<PortableTranslation>
    suspend fun import(items: Sequence<PortableTranslation>): ImportReport
}

data class NovelTranslationBatchRequest(
    val mangaId: Long,
    val chapterIds: List<Long>,
    val settings: NovelTranslationSettings,
)

interface NovelTranslationBatchCoordinator {
    fun enqueue(request: NovelTranslationBatchRequest): BatchId
    fun cancel(id: BatchId)
    fun observe(id: BatchId): Flow<NovelTranslationBatchProgress>
}
```

`NovelTranslationRepository` is the deep boundary: callers never know filenames, scratch-file recovery, collision rules, archive formats, or migration locking. `NovelTranslationBatchCoordinator` hides WorkManager, queue persistence, retries, rate pacing, and per-chapter source loading.

## Module map

- `novel/reader`
  - `NovelReaderViewer`: detects one-viewport content, reports normalized progress, requests navigation.
  - `NovelReaderAttachment`: holds the one-shot `TtsHandoff`; after the target document is ready, installs paragraphs and calls `play()` exactly once.
  - `NovelHtmlDocumentBuilder`/`WebNovelRenderer`: central `replaceActiveContent()` primitive; DOM mutation, progress restoration, custom-JS rerun, highlight restoration, and ready callback occur in that order.
- `novel/translation`
  - Keep `NovelTranslationService` as provider/chunking logic; replace its `cacheDir` cache as the reader's authoritative result with `NovelTranslationRepository` in `filesDir/hayai/novel-translations-v2`.
  - `FileNovelTranslationRepository`: Tsundoku-style filesystem repository, one directory per hashed portable locator and one file per target language. A versioned JSON manifest stores unsanitized identities and metadata; content lives separately. Writes use sibling `.saving` files, fsync, then rename; reads promote only complete recoverable scratch files.
  - `NovelTranslationQueueStore`: atomic JSON queue in `filesDir`; queued/running tasks return to queued after process death. No database table.
  - `NovelTranslationWorker`: unique foreground WorkManager job; resolves current J2K manga/chapter rows, prefers `NovelDownloadStore`, otherwise fetches through `NovelReaderSession`, translates, commits, and checkpoints after every chapter.
- `migration`
  - `LegacyChapterTranslationRecovery`: one-time, durable-marker promotion from archived `hayai_legacy_rows` into the file repository.
- `backup`
  - `HayaiBackupData` gains repeated portable translation payloads; `HayaiBackupService` streams them through the repository after core manga/chapter restore.

No new table is needed. J2K remains authoritative for manga/chapter identity and progress; Hayai files store content J2K cannot represent.

## Behavior details

### Percent sliders

Change `NovelSettingsController` entry values from `(50..100 step 5)` to `(50..100)` and keep `NovelReaderSettingsSheet.slider()` at `stepSize = 1f`. Centralize the range as `NovelReaderPercentages.COMPLETION_RANGE`; clamp old restored values before assigning either Material slider. Existing values such as 72 remain valid and no preference migration is required.

### Progress, short chapters, and TTS

`NovelReaderViewer.onReady` reports the requested position first, then calls `renderer.isShort`. When the preference is enabled and the renderer confirms one viewport, report `Percent(100)` through `activity.onPageSelected`; this reuses `ReaderViewModel.onPageSelected` for read state, tracking, duplicate handling, and deletion policy.

Before every direct chapter navigation, persist the outgoing in-memory page. The J2K seam is `ReaderActivity.loadChapter(Chapter)`: call the already-public `ReaderViewModel.saveCurrentChapterReadingProgress()` before delegating to its private `loadChapter(ReaderChapter)`. This covers both Hayai viewer buttons and `ReaderChapterSheet`; image transition holders remain untouched.

On TTS completion, set `TtsHandoff.ResumeAtStart(nextId)` before navigation. Do not infer intent from `tts.isPlaying`, because the service deliberately clears it at end-of-chapter. `onDocumentReady` consumes the handoff only if the loaded chapter id matches, installs new paragraphs, then calls `play()`. Manual navigation, disabled auto-next, load failure, pause, and close clear the token.

### DOM replacement

In the generated bridge, factor both `showTranslation` and `showOriginal` through `replaceActiveContent(mutate)`. After mutation: restore normalized scroll/page position, invoke the stored custom script with a lifecycle value such as `"content-replaced"`, then notify Android ready. Catch script exceptions and route them through the existing console/error policy; a custom script cannot prevent reader readiness. Native rendering needs no JS hook.

### Durable batch translation

The committed file key derives from stable source namespace/id + manga URL + chapter URL + target language; display names are metadata only. This improves on Tsundoku's human-readable title path by avoiding title/source-name rename dependence while retaining portable manifests. A source-content hash makes stale translations visible and lets `forceRetranslate` be explicit. Partial chunks remain `.tmp` and are never returned as complete reader content.

The reader queries the durable repository first, then may populate it through an on-demand translation. The old `NovelTranslationCache` becomes an import-only compatibility reader and is deleted after successful promotion; durable work never writes `cacheDir`.

## Legacy recovery, source migration, and backup

- Legacy import already preserves every `chapter_translation_cache` row in `hayai_legacy_rows`. After core rows exist, decode the archived payload fields (`manga_id`, `chapter_id`, languages, `original_hash`, content, engine, timestamp), resolve legacy ids through the import identity mapping, and build current URL-based locators. Validate language, size, hash shape, and unique chapter ownership.
- Promote each valid row idempotently. Identical destination content is success; conflicting destination content aborts that row and records a recoverable failure. Never guess when manga/chapter identity is missing. Keep the archive row regardless, and store a separate versioned recovery marker only after the full pass completes.
- Do not revive legacy `chapter_translation_queue`: its work may be stale and provider credentials/settings may differ. Preserve its archive rows, report the count, and let users re-enqueue.
- `NovelMigrationDataMover` extends its existing strict chapter map with repository `moveNovel`. For replace, move only after database planning succeeds and refuse a non-empty conflicting destination; for copy, copy verified files and leave source intact. Filesystem failure fails the migration slice rather than silently losing translations.
- Backup records use source id/namespace + manga URL + chapter URL, language, engine, source hash, timestamp, and bounded content. Restore occurs after J2K manga/chapters, resolves URLs to current ids, uses idempotent `put`, and reports unresolved/conflicting entries. Queue and scratch files are not backed up. Size/count limits belong in `HayaiBackupLimits`; protobuf fields are appended, never renumbered.

## Exact J2K seams

1. `dev/.../novel/settings/NovelSettingsController.kt`: 1-percent entry lists; this is Hayai-owned and needs no J2K edit.
2. `eu/.../ui/reader/ReaderActivity.kt`: persist current progress at the public direct-load boundary before replacing `ViewerChapters`; document this one generic reader seam in `j2k-reset.md` and the boundary allowlist.
3. `eu/.../data/backup/models/Backup.kt`, `BackupCreator.kt`, and `BackupRestorer.kt`: no new calls beyond the existing `hayaiData` seam; only the Hayai payload version/content changes.
4. The existing migration UI adapter calls `NovelMigrationDataMover`; pass the already-computed strict chapter map through that seam and keep filesystem logic out of J2K.

No changes to `App.kt`, `MainActivity.kt`, or image viewer/loader paths; the sole reader-shell edit is the documented direct-load persistence seam above. No direct `ReaderActivity.newIntent` calls.

## Tests

- Slider contract: every stored integer 50..100 binds in both settings surfaces; specifically 72 does not throw.
- Progress: direct next/previous saves outgoing normalized position before the chapter swap; incognito/tracker behavior remains J2K-owned.
- Short content: both native and Web `isShort=true` mark read only when enabled; long content and disabled preference do not.
- TTS state machine: completion + successful matching auto-next resumes once at paragraph 0; manual change, mismatch, error, pause, close, and disabled preference do not resume.
- DOM: translation and original replacement each rerun custom JS after nodes exist, preserve progress, and still signal ready when JS throws.
- Repository: atomic overwrite/recovery, corrupt manifest/hash rejection, language isolation, collision-safe locators, stale-source hash, limits, and cancellation.
- Worker: durable restart, completed-task checkpointing, partial retry, offline-first source loading, rate pacing, duplicate enqueue, cancellation, and failure summary.
- Legacy fixture: promoted cache rows, missing/ambiguous identities, conflict refusal, durable marker, retained archive rows, and ignored archived queue.
- Migration/backup: replace/copy semantics, destination conflict rollback, URL-based restore, idempotent second restore, unresolved reporting, and limits.
- Final validation: localization coverage, upstream boundary, focused unit tests plus `:app:compileDevDebugKotlin :app:testDevDebugUnitTest -x :app:formatKotlin`, then `git diff --check`.

## Implementation order

1. Fix percent domains and reader transition/TTS/DOM regressions with focused tests.
2. Implement the filesystem repository and replace the disposable reader cache path.
3. Add the persisted queue, foreground worker, chapter-list batch actions, and notifications.
4. Add legacy promotion, strict source-migration moves/copies, and backup/restore.
5. Update feature audit, architecture docs, boundary allowlist/audit, then run final validation and emulator checks.

## Alternatives rejected

- A `hayai_chapter_translations` table: convenient joins, but stores large documents in SQLite, recreates Tsundoku's abandoned cache model, complicates backup/migration, and adds a second chapter-content lifecycle. Files hide substantially more storage complexity behind the repository.
- Extending `cacheDir/hayai/novel-translations`: cannot promise offline durability, backup, process-safe batch recovery, or user-visible ownership because Android may purge it.
- Copying Tsundoku's source-name/title directory keys verbatim: human-readable but makes ordinary renames and source migrations correctness-critical. Stable URL locators in manifests remain portable without leaking display names into identity.
- Reusing the legacy translation queue: queued work is not user data, can reference obsolete ids/engines, and would require unsafe inference. Recover completed translations only.
- Adding a second reader/activity or writing chapter progress directly from Hayai: violates J2K ownership and bypasses tracking, incognito, duplicate-read, and deletion behavior.

## Synthesis decision

Choose a URL-keyed, file-only repository and explicit reader handoff state. It keeps the public surface small, preserves J2K as the single progress owner, makes completed translations durable and portable, and confines batch/recovery complexity to Hayai-owned modules without a schema migration.
