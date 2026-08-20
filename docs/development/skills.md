# Skills for Hayai work

The installed skills cover architecture, implementation discipline, review, and documentation. No installed skill specializes in Android, Tachiyomi extensions, StorIO, or Android SQLite migration. Use repository contracts and upstream source for those areas.

## Use these skills now

| Skill | Use it for |
|---|---|
| `Poteto Mode` | Baseline resets, architecture changes, multi-upstream ports, and other foundational work. |
| `architect` | Define contracts, types, module boundaries, and J2K seams before a new feature family. |
| `blast-radius` | Check what an adapter, schema, source ABI, reader route, or preference change can break. |
| `tdd` | Implement classifiers, import plans, metadata parsers, and source delegates from focused tests. |
| `diagnosing-bugs` | Reproduce crashes, migration failures, reader regressions, and extension compatibility problems before fixing them. |
| `code-review` | Review a completed Hayai commit range against `j2k/master`. |
| `resolving-merge-conflicts` | Replay the Hayai patch stack after a J2K update. |
| `show-me-your-work` | Keep `.audit/j2k-reset.tsv` and decision evidence for long or unattended work. |
| `technical-writing` | Maintain `AGENTS.md`, architecture notes, feature audits, release notes, and migration instructions. |
| `how` and `why` | Explain unfamiliar J2K behavior or record why a fork seam exists before changing it. |

## Add verification when the app is runnable

`create-verification-skill` can generate a project-local Android verification workflow after the project has a stable emulator setup, fixture backup, and deterministic extension/source test data. The generated workflow should drive the real app with `adb`, capture screenshots and logs, verify database side effects, and retain evidence after cleanup.

Do not generate that skill against an unspecified personal emulator. First add a documented AVD/API level, a clean data directory, a sanitized legacy database fixture, and a controlled novel source fixture.

## Consider one project-specific skill

Use `skill-creator` when the migration fixture and emulator flow are stable. A `verify-hayai` skill should cover:

- installing the dev APK without replacing production Hayai;
- importing a sanitized legacy `tachiyomi.db` into `hayai-j2k.db`;
- checking library/category/history/quote counts;
- toggling hentai features and each lewd-filter state;
- opening one image chapter and one controlled novel chapter;
- collecting Logcat, screenshots, and database count reports;
- removing only the test app and its test data.

Until those prerequisites exist, use the compile, unit-test, boundary, and manual emulator checks in `AGENTS.md`.
