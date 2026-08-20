# Hayai contributor contract

This repository is a direct TachiyomiJ2K fork. Keep it easy to update, safe to migrate, and honest about incomplete ports.

## Protect the baseline

- Treat `j2k/master` as the architectural parent.
- Treat `sy/master` and `tsundoku/main` as feature references, not merge parents.
- Keep `legacy/hayai-pre-j2k` read-only. Use it only to inspect old schemas, preference keys, branding, and user-data formats.
- Never copy application code from the legacy branch. Rebuild behavior from TachiyomiSY or Tsundoku behind a Hayai-owned contract.
- Put all new Kotlin in `app/src/main/java/dev/ahmedmohamed/hayai`.
- Run `tools/verify-upstream-boundary.ps1` before handoff. If a new J2K file must change, explain the seam in `docs/architecture/j2k-reset.md` and update the script allowlist in the same change.
- Do not modify `App.kt`, `MainActivity.kt`, or the J2K image `ReaderActivity.kt` without an approved architecture change and focused regression evidence.

## Preserve identity and provenance

- Keep the application ID `dev.ahmedmohamed.hayai`, the Hayai name, and the Hayai launcher assets.
- Keep the `j2k`, `sy`, and `tsundoku` remotes. Do not rewrite their histories.
- Preserve upstream license headers and record the source commit when rebuilding upstream behavior.
- Do not add AI attribution, generated-by text, robot emoji, or co-author trailers to commits, pull requests, release notes, or changelogs.

## Keep one source of truth

- J2K owns manga, chapters, categories, history, tracking, downloads, and image-reader state.
- Hayai tables store data that J2K cannot represent, such as quotes, novel repositories and statistics, E-Hentai state, and source metadata.
- Never add a second library, chapter, progress, or settings model that must synchronize with J2K.
- Route source-specific behavior through `SourceCapabilityRegistry`.
- Route chapter opening through `ReaderLauncher`. Do not add direct `ReaderActivity.newIntent` calls outside the router and the existing image-transition paths.

## Make migration changes defensively

- Never open legacy `tachiyomi.db` for writing.
- Keep `hayai-j2k.db` as the active database. The two files have unrelated migration histories.
- Import core tables in foreign-key order.
- Abort on identity conflicts. Never use conflict-ignore for migration rows.
- Preserve every legacy source row in `hayai_legacy_rows`, including rows from tables that also have typed destinations.
- Keep the import idempotent with a durable state marker.
- Refuse an unsafe migration and record a recoverable failure. Do not guess at merges between a non-pristine target and legacy IDs.
- Add a fixture or focused plan test for every new promoted table or column mapping.

## State feature status precisely

Use these terms consistently:

- **Foundation:** a boundary, schema, preference, or router exists and compiles.
- **Port:** the end-to-end production behavior is implemented and verified.
- **Audit:** upstream behavior has been located and mapped, but it is not implemented.

Update `docs/architecture/upstream-feature-audit.md` whenever a feature changes status. A switch, interface, or empty registry entry is not a completed port.

## Work in vertical slices

1. Inspect J2K and the relevant SY or Tsundoku source.
2. Define the Hayai contract and data ownership.
3. Implement the Hayai module.
4. Add the smallest J2K adapter call.
5. Add migration and backup behavior when the feature owns data.
6. Add focused tests.
7. Run the boundary check and the narrowest meaningful Gradle validation.
8. Update the architecture, feature audit, and `.audit/j2k-reset.tsv`.

Do not stop at scaffolding when the requested slice can be completed. Do not claim a full source or reader port when only its contract exists.

## Validate coherent batches

Do not run broad checks after each edit. Finish a coherent batch, then run:

```powershell
.\tools\verify-upstream-boundary.ps1
.\gradlew.bat :app:compileDevDebugKotlin :app:testDevDebugUnitTest -x :app:formatKotlin
git diff --check
```

The project pre-build formatter scans the full J2K tree and reports existing upstream exceptions. Run the explicit formatter only when formatting is the task. Do not “fix” unrelated J2K style warnings.

For a migration release, also test a copy of a real or sanitized legacy v36+ database on an emulator. Unit tests and compilation do not prove an on-device SQLite import.

## Maintain the patch stack

Keep fork changes in focused commits above `j2k/master`. Do not mix a J2K update with Hayai feature work.

After the worktree is clean and the commits are ready, run:

```powershell
.\tools\refresh-patchset.ps1
```

This command regenerates `patches/*.patch`, `patches/series`, and `patches/UPSTREAM_BASE`. Review the generated series and commit it with the changes it describes. Use `tools/apply-patchset.ps1` only on a clean checkout at the recorded base.

When J2K updates:

1. Fetch `j2k`, `sy`, and `tsundoku`.
2. Create an integration branch from the new `j2k/master`.
3. Replay the patch stack with `git am --3way` or rebase the Hayai commits.
4. Resolve only documented adapter seams.
5. Run the boundary, compile, unit-test, and migration checks.
6. Refresh the patch stack against the new base.

## Use the project skills deliberately

Read `docs/development/skills.md` before choosing a broad workflow. Use the narrowest matching skill. Poteto Mode governs foundational changes. Architecture, blast-radius, testing, review, merge-conflict, and technical-writing skills cover the common maintenance paths.

## Handoff completely

Report the implemented slice, the J2K files touched, the data migration effect, the commands run, their result, and the remaining entries in the feature audit. Never report a validation command as passed unless it ran successfully.
