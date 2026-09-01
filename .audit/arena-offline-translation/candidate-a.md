# Candidate A: one reader handoff contract and one durable translation ledger

## Caller usage

```kotlin
// Both settings surfaces accept every persisted percentage value.
percentSlider(preferences.novelAutoLoadNextChapterAt, 50..100)
percentSlider(preferences.novelMarkAsReadThreshold, 50..100)

// NovelReaderViewer: every direct boundary crossing has one ordered handoff.
actions.navigateChapter(
    target = next.chapter,
    outgoing = NovelReaderSnapshot(currentProgress, fitsViewport),
    continuation = if (ttsAutoNext) ChapterContinuation.ResumeTts(next.chapter.id!!) else ChapterContinuation.None,
)

// NovelReaderAttachment, once the replacement document is ready.
tts.onChapterReady(chapterId, paragraphs) // resumes only an armed, matching handoff

// Web bridge: the same user script lifecycle runs initially and after either replacement.
replaceActiveBlock(translatedNodes)
runCustomDocumentScript(CustomScriptCause.TranslationShown)

restoreOriginalBlock()
runCustomDocumentScript(CustomScriptCause.OriginalShown)

// Details selection action: SQLite is the truth; WorkManager only wakes the drain loop.
translationCoordinator.enqueue(
    manga = manga,
    chapters = selectedChapters,
    settings = translationSettings.snapshotWithoutSecret(),
)

// Reader translation: durable hit first, provider second, atomic success write last.
translationStore.findCompleted(chapterId, targetLanguage, sha256(originalText))
    ?: translationCoordinator.translateNow(chapterId, originalText, settings)
```

## Types and signatures

```kotlin
data class NovelReaderSnapshot(val progress: Int, val fitsViewport: Boolean)

sealed interface ChapterContinuation {
    data object None : ChapterContinuation
    data class ResumeTts(val expectedChapterId: Long) : ChapterContinuation
}

interface NovelReaderActionHost {
    fun navigateChapter(target: Chapter, outgoing: NovelReaderSnapshot, continuation: ChapterContinuation)
}

data class NovelProgressInput(
    val progress: Int,
    val markReadAt: Int,
    val fitsViewport: Boolean,
    val markShortAsRead: Boolean,
)

fun NovelReaderProgress.update(input: NovelProgressInput): NovelProgressUpdate

interface NovelTtsChapterHandoff {
    fun arm(expectedChapterId: Long)
    fun onChapterReady(chapterId: Long, paragraphs: List<String>)
    fun cancel()
}

enum class CustomScriptCause { InitialDocument, TranslationShown, OriginalShown }

// Implemented inside the generated bridge; exceptions still reach the existing renderer error path.
fun runCustomDocumentScript(cause: CustomScriptCause)

enum class TranslationWorkState { Queued, Running, Completed, Failed, Cancelled }

data class TranslationRequest(
    val batchId: String,
    val chapterId: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val engineId: String,
    val providerConfigHash: String,
    val priority: Int,
    val position: Int,
    val force: Boolean,
)

data class StoredNovelTranslation(
    val chapterId: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val originalHash: String,
    val translatedContent: String,
    val detectedLanguage: String?,
    val engineId: String,
    val createdAt: Long,
)

interface NovelTranslationStore {
    fun enqueue(requests: List<TranslationRequest>)
    fun claim(now: Long, leaseMillis: Long): ClaimedTranslationWork?
    fun complete(claim: ClaimedTranslationWork, result: StoredNovelTranslation)
    fun fail(claim: ClaimedTranslationWork, error: String, retryable: Boolean)
    fun findCompleted(chapterId: Long, targetLanguage: String, originalHash: String): StoredNovelTranslation?
    fun exportCompleted(): List<NovelTranslationBackup>
    fun restoreCompleted(entries: List<NovelTranslationBackup>): RestoreReport
}

class NovelOfflineTranslationCoordinator(
    private val store: NovelTranslationStore,
    private val loader: NovelDocumentLoader,
    private val service: NovelTranslationService,
) {
    fun enqueue(manga: Manga, chapters: List<Chapter>, settings: TranslationSettingsSnapshot): String
    suspend fun drainOneBatch(): DrainResult
    suspend fun translateNow(chapterId: Long, original: String, settings: NovelTranslationSettings): StoredNovelTranslation
}
```

## Durable table

Add schema generation 5 and `hayai_novel_chapter_translations`, owned by Hayai but foreign-keyed to J2K chapters:

```sql
CREATE TABLE hayai_novel_chapter_translations(
  chapter_id INTEGER NOT NULL,
  target_language TEXT NOT NULL,
  source_language TEXT NOT NULL,
  engine_id TEXT NOT NULL,
  provider_config_hash TEXT NOT NULL,
  original_hash TEXT,
  translated_content TEXT,
  detected_language TEXT,
  state TEXT NOT NULL CHECK(state IN ('queued','running','completed','failed','cancelled')),
  batch_id TEXT NOT NULL,
  priority INTEGER NOT NULL DEFAULT 0,
  position_index INTEGER NOT NULL,
  force_retranslate INTEGER NOT NULL DEFAULT 0 CHECK(force_retranslate IN (0,1)),
  attempt_count INTEGER NOT NULL DEFAULT 0,
  lease_token TEXT,
  lease_until INTEGER,
  last_error TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  completed_at INTEGER,
  PRIMARY KEY(chapter_id, target_language),
  FOREIGN KEY(chapter_id) REFERENCES chapters(_id) ON DELETE CASCADE,
  CHECK((translated_content IS NULL) = (original_hash IS NULL))
);
CREATE INDEX hayai_novel_translation_work
  ON hayai_novel_chapter_translations(state, priority DESC, position_index);
```

The result columns describe the last complete value; enqueue/retry may change work columns without destroying it. A completed lookup additionally requires the current source hash, so a stale retained value is never displayed. Claim is one short transaction: reclaim expired `running` rows, choose one row, then conditionally update it with a random lease token. Provider calls happen outside the transaction. Completion requires the same token. WorkManager uses unique `KEEP` work and simply drains/re-enqueues while runnable rows remain; process death is recovered by the lease.

Do not persist API keys. `provider_config_hash` hashes engine/endpoint/model and is only cache provenance. Bound row count, translated text length, attempts, error length, batch size, and backup aggregate bytes.

## Module map

```text
novel/settings
  NovelSettingsController + NovelReaderSettingsSheet -> shared 1%-step range
novel/reader
  NovelReaderViewer -> measures viewport and requests ordered navigation
  NovelReaderAttachment -> progress/TTS handoff orchestration
  NovelTtsController + NovelTtsPlaybackService -> expected-chapter resume token
  NovelHtmlDocumentBuilder -> reusable custom-script runner after DOM replacement
novel/translation
  NovelTranslationStore -> all SQLite work/result transitions
  NovelOfflineTranslationCoordinator -> load/hash/translate/commit policy
  NovelOfflineTranslationWorker -> durable drain wake-up and notification
  NovelTranslationService -> provider transport only; remove file-cache ownership
migration
  HayaiSchema v5 + LegacyTranslationPromotion
backup
  HayaiBackupData v5 + HayaiBackupService completed-result export/restore
novel/integration
  NovelMigrationDataMover -> chapter-mapped completed translation transfer
```

## Exact J2K seams

1. `ReaderActivity.loadChapter(Chapter)` remains the single public novel-navigation adapter. Before its private load, call `viewModel.saveCurrentChapterReadingProgress()` after the Hayai snapshot has updated the current `NovelProgressPage`. Do not alter image-reader `loadChapter(ReaderChapter)` callers.
2. `ReaderViewModel.onPageSelected` keeps its existing `NovelProgressPage` branch, but passes `fitsViewport` and the stored `novelMarkShortChapterAsRead` flag to `NovelReaderProgress`. Completion may be true at position 0; J2K still owns `read`, tracking, deletion, duplicates, history, and the database write.
3. `MigrationProcessAdapter` keeps its one existing call to `NovelMigrationDataMover.transfer(...)`; the mover includes completed translation rows in the same caller-owned transaction after the one-to-one chapter map is proven. No second J2K migration call.
4. `MangaDetailsController` gets one novel-only selected-chapter action callback to enqueue offline translations and one presentation field for state. All queue/provider logic remains Hayai-owned.
5. Existing `BackupCreator`/`BackupRestorer` Hayai payload hooks are unchanged; only Hayai payload version/data changes. No new J2K backup field.

No changes to `App.kt` or `MainActivity.kt`. `ReaderActivity` is the already documented protected novel seam and receives only the ordered-save call above.

## Legacy promotion, migration, and backup

- Parse archived `hayai_legacy_rows` where `table_name = 'chapter_translation_cache'`; preserve those archive rows unchanged.
- Map legacy `manga_id/chapter_id` only when both imported J2K rows exist and belong together. Insert `completed` rows with legacy source/target language, original hash, text, string engine ID, and timestamp. Exact duplicates are idempotent; differing rows at the same key abort promotion and leave its durable marker incomplete.
- Run promotion after `HayaiLegacyMigration` has archived rows for a fresh import, and as an idempotent post-schema-upgrade recovery for existing reset installs. Do not promote legacy queue rows: credentials/settings and source availability may have changed; archive remains provenance.
- During novel source migration, add translation-bearing chapter IDs to the required chapter map. Replace rebinds rows; copy inserts mapped rows. Exact target duplicates are no-ops; different target translations abort the encompassing migration transaction. Only completed results move; queued/running/failed work stays with the source and is cancelled on replace.
- Add `@ProtoNumber(13) novelTranslations` to `HayaiBackupData`, bump payload version to 5, and serialize only completed fields using `(sourceId, mangaUrl, chapterUrl)`. Restore resolves core identities after J2K restore, validates hash/language/size, accepts exact duplicates, and rejects conflicting content. Queue, leases, errors, endpoint hashes, API keys, and credentials are excluded.

## Focused tests

- Settings: persisted values 51, 72, and 99 bind in both settings surfaces; ranges and formatter match exactly.
- Progress/navigation: next and previous direct boundaries save outgoing progress before load; a one-viewport chapter marks read only when enabled; incognito/tracker behavior remains J2K-owned; multi-viewport threshold behavior is unchanged.
- TTS: completion arms only the expected adjacent chapter, document-ready resumes once, load failure/manual navigation/pause/stop does not resume, and stale callbacks cannot speak another chapter.
- DOM: initial, translated, and restored documents each run custom JS once; the quote-wrapper fixture is rebuilt; script exceptions surface without preventing replacement; translation text remains escaped through DOM text nodes.
- Store/worker: atomic claim, expired lease recovery, duplicate enqueue, force retranslate, retry ceiling, cancellation, stale hash miss, partial provider failure, and crash between provider response and commit.
- Legacy: exact v36 cache fixture promotes; missing/wrong chapter ownership and conflicting duplicates refuse; rerun is idempotent; raw archive remains byte-for-byte present.
- Migration/backup: replace/copy map completed rows, ambiguity rolls back, queued work does not move, v4 payload restores without translations, v5 round-trips stable identities, limits reject oversized content.
- Final batch: localization coverage, upstream boundary, narrow compile/unit tests, `git diff --check`, plus emulator process-kill/resume and sanitized legacy-v36 import.

## Implementation order

1. Fix the two slider ranges and add their regression test.
2. Introduce the reader snapshot/navigation contract; wire ordered save, short completion, then expected-chapter TTS resume.
3. Centralize custom-script execution in the Web bridge and invoke it after both DOM replacements.
4. Add schema v5/store and legacy promotion, then replace the disposable file cache with store-backed lookup.
5. Add coordinator/worker/UI action and bounded notifications/cancellation.
6. Extend source migration and Hayai backup, then update architecture/audit/boundary records and validate once.

## Alternatives rejected

- **Clamp old percentage values to five-percent ticks:** hides the crash but changes user choices; both callers should expose the preference's real 1% domain.
- **Save progress from each tap/swipe/TTS caller:** duplicates ordering logic and will miss future navigation paths; one action-host handoff is smaller.
- **Resume TTS whenever any document becomes ready:** races manual navigation and failed loads; an expected chapter ID makes continuation explicit and consumable once.
- **Dispatch only a DOM event after translation:** existing custom scripts do not subscribe to it; rerun the same registered script body and also expose the cause event for future scripts.
- **Keep JSON cache plus a SQLite queue:** creates two authorities and cannot back up/migrate results atomically. One ledger owns both last complete value and current work state.
- **Use WorkManager progress/output as the queue:** it is not relational user data, is awkward to inspect/migrate, and does not provide the required durable translation result contract.
- **Restore legacy pending queue rows:** provider configuration and credentials are not safely reproducible; only completed cache rows are durable user value.
