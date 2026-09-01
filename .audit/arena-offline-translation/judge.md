# Offline translation architecture cross-judge

## Decision

Use Candidate A as the base, with the storage identity and lifecycle grafts listed below.

Candidate A fits Hayai's existing ownership model: J2K continues to own manga, chapters, progress, and reader behavior, while the active `hayai-j2k.db` stores novel-only translated content and its recovery metadata. More importantly, a relational store can make legacy promotion, chapter remapping, duplicate detection, backup selection, and migration refusal use the same identity and transaction rules already used by highlights and chapter statistics.

Candidate B has a clean repository boundary, but its file repository and separate JSON queue introduce two new recovery protocols. File moves cannot participate in the database transaction that proves a chapter map, so a failed source migration can leave the database and translated files on different sides of the operation. Its manifest, scratch-file, queue-checkpoint, collision, streaming-backup, and rollback machinery also costs more to implement and test than the expected benefit of keeping translated text outside SQLite.

## Scores

| Criterion | Candidate A | Candidate B |
| --- | ---: | ---: |
| Correctness | 8 | 7 |
| User outcome | 9 | 9 |
| Migration and backup integrity | 9 | 5 |
| J2K boundary safety | 7 | 8 |
| Implementation tractability | 7 | 5 |
| Recoverability | 9 | 7 |
| **Total** | **49/60** | **41/60** |

### Candidate A notes

- Strongest end-to-end ownership story. One Hayai store can enforce complete-only reads, stale-source rejection, bounded values, durable work, migration conflicts, and portable backups.
- The lease-token claim protocol is explicit and process-death safe. Provider calls remain outside transactions and completion is conditional on the lease, which prevents stale workers from committing over newer work.
- Legacy behavior is conservative: completed cache rows are promoted, pending queue rows remain archived, ambiguous identity aborts, and the durable marker is written only after a complete pass.
- The current proposed table needs revision. A single row should not use `state` for both the last completed translation and current replacement work. Enqueuing a retranslation must not make the last valid offline result disappear, and overlapping batches must not overwrite each other's work metadata.
- The table also needs the stable source identity described in its backup section. `chapter_id` is the local lookup key, but `source_id`, `manga_url`, and `chapter_url` are required for backup restore, source migration, and recovery when local IDs change.
- Its proposed `ReaderActivity` edit conflicts with the repository's protected-file rule. The save belongs in the narrow existing `ReaderViewModel.loadChapter(Chapter)` boundary, or in a Hayai-owned host if one already exposes the operation. The image-reader `ReaderChapter` path must remain untouched.

### Candidate B notes

- The repository API is good and prevents callers from learning storage details. Its URL locator is portable and robust against title and source display-name changes.
- The reader handoff, one-shot expected-chapter TTS state, and centralized DOM replacement order are clear and testable.
- The file format requires a second index and transaction model alongside J2K/Hayai database state. Atomic rename protects an individual file, but it does not make a multi-chapter source migration or restore atomic.
- A JSON queue must independently solve concurrency, leases, retry ownership, duplicate enqueue, cancellation, corruption, and process death. Candidate A's conditional SQL updates are materially safer and smaller.
- Streaming translated content through backup is possible, but it adds manifest and file validation paths that duplicate the checks needed in the queue and migration code.
- Its stated benefit of avoiding a schema migration is not decisive. This feature already requires durable legacy promotion and backup-version changes, so schema work is unavoidable at the product boundary even if the content itself lives in files.

## Required grafts into Candidate A

1. Graft Candidate B's `NovelTranslationLocator` into every durable row and API. Store `source_id`, `manga_url`, and `chapter_url` with the local `chapter_id`; add a unique stable-identity constraint for `(source_id, manga_url, chapter_url, target_language)`. Display names and titles are never identity.
2. Separate completed results from mutable work state. Use `hayai_novel_translations` for complete translations and `hayai_novel_translation_jobs` for queued, leased, failed, and cancelled attempts. A force-retranslate job leaves the last hash-matching completed result readable until a new result commits atomically. Both tables remain behind one `NovelTranslationStore` authority.
3. Graft Candidate B's document replacement ordering. The shared replacement primitive mutates nodes, restores reading position and highlights, reruns the configured custom script once, catches and reports script failures, and always sends the ready callback. The reporter quote-wrapper fixture must exercise both translated and restored content.
4. Graft Candidate B's explicit one-shot TTS handoff. It is armed with the expected adjacent chapter ID, consumed only after matching document readiness and paragraph installation, and cleared on manual navigation, pause, stop, close, mismatch, or load failure.
5. Graft Candidate B's stable portable backup records and bounded import reporting. Backup contains completed content only and resolves by stable source/manga/chapter URLs after J2K restore. Queue state, lease data, endpoint/provider hashes, errors, scratch state, and credentials are excluded.
6. Do not graft Candidate B's clamping language for valid restored percentages. Both settings surfaces should share the complete `50..100` one-percent domain so existing values such as 72 are preserved exactly. Only values outside the declared preference domain may be normalized defensively.
7. Do not edit protected `ReaderActivity.kt`. Put outgoing progress persistence at the public `ReaderViewModel.loadChapter(Chapter)` transition before viewer chapters are replaced, and document/allowlist that single J2K seam. Keep the image-reader overload and transition holders unchanged.

## Revised storage contract

The implementation should use two normalized tables behind one Hayai-owned store:

- `hayai_novel_translations`: local chapter ID, stable source/manga/chapter locator, source and target language, source hash, translated content, content format, engine ID, optional detected language, and timestamps. Only complete bounded results enter this table.
- `hayai_novel_translation_jobs`: job ID, batch ID, local/stable chapter identity, requested languages and engine snapshot without secrets, ordering, force flag, state, attempts, lease token/expiry, bounded last error, and timestamps.

Completing a job updates or inserts the result and marks the matching leased job complete in one transaction. A result is displayed offline only when its source hash matches the current normalized source text. Exact duplicate promotion/restore is idempotent; different content at the same stable identity is a reported conflict. Deleting or remapping a J2K chapter must be handled through the existing Hayai migration/cleanup boundary rather than relying solely on an implicit cascade.

## Implementation sequence

1. Land the slider, outgoing-progress, short-chapter, TTS handoff, and DOM lifecycle fixes with focused regression tests.
2. Add the completed-result table, store, stale-hash lookup, schema migration, and completed legacy cache promotion.
3. Replace the disposable `cacheDir` reader path with the durable result store, then extend Hayai backup and strict novel source migration.
4. Add the separate job table, coordinator, unique WorkManager drain, chapter-selection action, cancellation, retry bounds, and progress presentation.
5. Validate exact legacy fixtures, backup round trips, migration rollback/conflicts, process-death lease recovery, emulator reader flows, localization, upstream boundaries, Kotlin compilation/unit tests, and `git diff --check`.

This sequence preserves a useful vertical slice after each step and does not claim the full offline batch port before the queue, UI, recovery, and end-to-end checks exist.
