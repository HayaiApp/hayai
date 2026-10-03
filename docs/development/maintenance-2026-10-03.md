# October maintenance plan

The starting Hayai revision is `36f69f075787f86eb0b12492dff37378f7aeee15`. A backup branch preserves it. J2K advances from `c88c8e1b3d` to `b83defe42b`; Tsundoku advances from `fe54f05ae0` to `a3e32c8b60`. SY remains at `14648c7cf0`.

- [x] Read Poteto Mode's principles and the contributor contract.
- [x] Fetch the three references and replay Hayai's commits onto J2K's 31 updates.
- [x] Preserve legacy saved searches through J2K's canonical history and filter model, including default-filter activation and interrupted migration.
- [x] Repair extension installation, trust, cancellation, and applicable Tsundoku update behavior.
- [x] Repair reader lifecycle and navigation defects; map and port applicable Tsundoku reader and downloaded-text features.
- [x] Add Novel to the existing metadata editor and use the same durable identity for library classification and chapter routing.
- [x] Keep gallery tag searches in their source and match SY's tag completion behavior.
- [x] Add continuous previews and seeking through the existing preview screen.
- [x] Review all open GitHub issues and record the evidence and disposition.
- [x] Complete the related adapter documentation, translations, feature audit, and decision trail.
- [x] Run localization, boundary, compile, unit tests, APK assembly, and controlled emulator checks after the implementation batch.
- [x] Review the actual diff and validation artifacts and commit focused feature changes.
- [ ] Refresh and replay the patch series from its recorded J2K base.
- [ ] Push master and verify the resulting nightly release.

Completion requires coherent production paths, meaningful regression coverage, a replayable patch series based on current J2K, and a published nightly. An unreported-device crash or authenticated E-Hentai session remains unverified until exercised on the relevant device/session. A successful compile alone does not resolve those reports. The canonical decision trail is `.audit/j2k-reset.tsv`.

Parallel work uses disjoint file ownership in this integration checkout. Shared J2K adapters and architecture documents are coordinated by the integration owner. This avoids concurrent Git index changes and lets the final review inspect one coherent tree. Validation is grouped after substantive implementation as required by the contributor contract.

## Validation checkpoint

| Check | Result |
|---|---|
| `:app:compileDevDebugKotlin :app:testDevDebugUnitTest :app:assembleDevDebug :app:assembleDevDebugAndroidTest -x :app:formatKotlin` | Passed after the final reader change; 604 tests, zero failures, errors, or skips. |
| `tools/verify-novel-web-reader.cjs` | Passed production CSS/JavaScript containment, bidirectional chapter navigation, explicit seeks with custom margins, viewport anchors, pagination, snapping, and selection. |
| `tools/verify-localization.ps1 -WriteCoverage` | Passed: 1189 Hayai keys, 67 supported locales, 20 locales with Hayai translations; reviewed protocol literals remain in the explicit inventory. |
| `tools/verify-upstream-boundary.ps1` | Passed: 113 documented J2K adapter files. |
| `git diff --check` | Passed. |
| Dedicated `Hayai_Reader_Test` emulator flow | Passed sanitized v36 database import and row counts, native fixture content, J2K progress, TTS controls, all reader settings tabs, offline save/restart, full settings, Browse tabs, and final crash-log check. |
| Controlled reader idle | Exact final APK remained alive for 120 seconds in `emulator/20261003-042743`; the complete subsequent harness used idle zero to avoid repeating that check. |
| Published novel APK compatibility | Passed on the final debug APK: WTR-LAB 1.6.12 and Novel Phoenix 1.6.13 appear in Trust without restarting, load real settings/filters and live popular listings, and retain trust and source rows after cold restart. No linkage or application crash matches in captured per-PID logs. |
| Android regression suite | All 21 unique tests have passing evidence: 16 retained from the full run plus five passing targeted installer/adapter checks after correcting fixture timing and RecyclerView setup. Native selection/title/viewport, stale callbacks, sheets, artwork, Novel metadata/backup, and the manual Install notification passed. |

The first dependency build failed because JitPack had no artifacts for the pinned FlexibleAdapter commit. Source comparison and the published Maven switch preserve J2K's already-filtered datasets. A resource-link failure exposed an undefined Novel label, corrected by reusing the existing localized badge. The first complete unit run exposed a streaming cancellation exception; the cancellation and genuine network-failure regressions now pass. Actual emulator content exposed duplicated HTML chapter headings, and a stricter browser fixture exposed a short final chapter reporting its predecessor. Both production defects are corrected and covered.

The original Android run had two fixture failures: Android removes abandoned package sessions asynchronously, and the standalone library adapter was missing its production RecyclerView attachment. The final fixture uses J2K's `AutofitRecyclerView`, display preferences, and layout setup, and waits at most five seconds for the exact abandoned session to disappear. Search/query/header/placeholder and operation-owner assertions remain intact. `android-tests-fixture-host-targeted.log` reports `OK (5 tests)`; the original full run and intermediate failures are preserved.

Logs are retained under `artifacts/j2k-update-2026-10-03/`. The passing full flow is `emulator/20261003-043633`, with `emulator-final-harness.log`. Android 37 uses the existing APK's 16 KB compatibility mode. Scoped recovery dismisses only System UI ANRs and the platform compatibility prompt; application crash assertions remain active. Narrow-display gestures avoid action reorder handles, and the main settings route follows J2K's overflow menu. Authenticated EH checks are explicitly skipped without test credentials. Production extension, patch replay, and release verification results are recorded separately. Core/Hayai database schemas and legacy database write policy are unchanged. Existing custom metadata and app-settings backup preserve the Novel choice and migrated saved searches.

This checkpoint is committed before regenerating the patch series and publishing. The final replay comparison and signed nightly results belong to the shipping artifacts and task handoff; unchecked publication steps here do not claim a release that has not happened yet.
