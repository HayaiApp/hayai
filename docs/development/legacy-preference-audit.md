# Legacy preference compatibility audit

This audit compares the read-only `legacy/hayai-pre-j2k` preference accessors with the J2K-reset tree. It covers preferences consumed during startup, shortcut creation, navigation, reader setup, and settings rendering. It does not treat dormant SharedPreferences keys as executable behavior.

## Result

- Legacy accessors inspected: 186.
- Current accessors inspected: 197.
- Removed or structurally changed legacy accessors: 25.
- Same-key enum incompatibilities capable of throwing during a read: theme values and `group_chapters_history_type=BySource`.
- Other current enum preferences retain the legacy member names used by reader navigation inversion, reader chrome hiding, secure-screen behavior, and Recents download badges.

All eight enum-backed accessors in `PreferencesHelper` now use the Hayai-owned compatible decoder. It resolves current names first, then a declared legacy alias, then the accessor's normal default. It never calls `Enum.valueOf`, and it does not rewrite unknown stored data.

`BySource` maps to `BySeries` until the full source-header and source-section behavior is rebuilt against current J2K Recents. Removed legacy themes (`DOKI`, `SAKURA`, `PINK_ROMANCE`, `SUMI_E`, `KIMONO`, `WAGASHI`, `NORDIC`, and `ROSE`) safely resolve to the current light or dark theme default. This is a compatibility fallback, not a claim that those visual themes were ported.

## Dormant removed keys

The reset no longer reads the following legacy keys, so leaving them in SharedPreferences cannot trigger application code: raw date-format state, library update parallelism, smart-update flags, source grid/display state, legacy source filters, pinned manga and novel catalogues, last-used source state, last browse source type, migration parallelism, theme detail overrides, and reduced-motion state.

These values remain untouched so a later complete behavior port can migrate them deliberately. Wrong primitive types are independently guarded by `TypeSafeSharedPreferences`.

## Regression contract

`CompatibleEnumPreferenceTest` verifies current-value round trips, alias precedence, future unknown values, and every removed legacy theme name. Any new enum preference added to `PreferencesHelper` must use `getCompatibleEnum` rather than FlowPreferences' throwing `getEnum`.
