# Hayai on TachiyomiJ2K

Hayai is rooted directly at TachiyomiJ2K commit `57935d373fab209da0104d1a6ce2cb14ffd762dd`. The previous mixed Hayai/Rokku tree is preserved at `legacy/hayai-pre-j2k`; none of its Kotlin code is part of the new baseline.

## Boundaries

J2K owns manga, chapters, history, categories, tracking, downloads, image reading, and normal extension loading. New behavior lives under `dev.ahmedmohamed.hayai`:

- `adult`: adult classification and the global/tri-state library policy.
- `source`: typed source capabilities and enhanced-source families.
- `novel`: the text-source contract and dedicated reader.
- `migration`: read-only legacy import and typed Hayai side tables.
- `preferences`: stable Hayai and TachiyomiSY-compatible preference keys.

`tools/verify-upstream-boundary.ps1` rejects unreviewed Kotlin edits outside this namespace. `App.kt`, `MainActivity.kt`, and the J2K image `ReaderActivity.kt` are protected explicitly.

## Database reset and migration

Legacy Hayai and current J2K both called their database `tachiyomi.db`, but their schema histories are unrelated (legacy SQLDelight v36+ versus J2K StorIO v20). Hayai therefore uses `hayai-j2k.db` as the active J2K database and treats an existing `tachiyomi.db` as a read-only source.

On first database open, an idempotent importer validates the legacy schema and requires the fresh J2K target to be pristine. It imports compatible J2K core rows in dependency order, imports quotes/novel/E-Hentai/search data into typed tables, and also archives every source row with table and row identity in `hayai_legacy_rows`. The raw archive preserves columns J2K cannot represent. Inserts abort on any identity conflict; a non-pristine target is refused rather than silently merged or overwritten. The importer checks foreign keys and records a durable result. The source database is never modified. A failed import rolls back and records a recoverable diagnostic without entering an app-start retry loop.

## Reader and source architecture

Extensions remain standard J2K `Source`/`SourceFactory` extensions. `SourceCapabilityRegistry` adds behavior by stable source ID or recognized family name instead of scattering source checks through presenters.

`NovelSource` returns one `NovelDocument` per chapter. `ReaderLauncher` centralizes all current direct chapter-launch paths: recognized `NovelSource` implementations open `NovelReaderActivity`, while image sources continue into the untouched J2K reader. Library, recents, notifications, search, and manga details call this router. No production novel source implementation or Tsundoku compatibility bridge ships in this foundation yet; those are explicit ports in the feature audit.

The text reader owns its lifecycle. It loads text off the main thread, renders selectable HTML/text, and updates J2K history. TTS, typography profiles, quote capture, translations, downloads, and JS/custom/local source runtimes belong in this Hayai-owned vertical slice rather than in the image reader.

## Updating upstreams

```powershell
git fetch j2k sy tsundoku
git rebase j2k/master
.\tools\verify-upstream-boundary.ps1
```

TachiyomiSY and Tsundoku are feature references, not architectural parents. Ports are rebuilt behind Hayai contracts with provenance recorded in the feature audit.
