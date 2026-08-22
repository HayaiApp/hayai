# Maintain Hayai translations

Hayai uses Android string resources for every user-visible label, message, hint, and accessibility description. The default resources contain English text. Android falls back to English when a locale does not contain a Hayai key.

Do not copy English text into a locale file to increase its coverage. A key counts as translated only when that locale contains a reviewed translation.

## Add or change interface text

1. Add a `hayai_` key to a file under `app/src/main/res/values`.
2. Replace the Kotlin or XML literal with the resource reference.
3. Preserve placeholders such as `%1$s` and `%2$d` in every translation.
4. Add reviewed translations to each locale that you can verify.
5. Run the localization check.

```powershell
.\tools\verify-localization.ps1 -WriteCoverage
```

Commit `docs/development/localization-coverage.md` when the key count or any translation changes.

Hayai reuses reviewed TachiyomiSY translations when the English source text is identical. Refresh those generated locale files after updating the `sy` remote.

```powershell
.\tools\import-sy-translations.ps1 -SyRef sy/master
```

The importer stops when either English string changes. Review the wording and the translation mapping before you run it again.

## Decide what to translate

Translate text that Hayai controls and shows to the user. This includes dialog text, menu items, setting labels, validation messages, content descriptions, and text around runtime values.

Do not translate source data or protocol values. Examples include manga titles, chapter titles, author names, URLs, CSS selectors, JavaScript, database keys, cookie names, HTTP values, and server-provided error bodies.

For mixed text, keep the runtime value unchanged and translate the surrounding grammar. Use a formatted resource such as `Downloaded %1$s` instead of string concatenation.

## Review translations regularly

The localization workflow runs for relevant pull requests, once each month, and on manual request. It rejects new hardcoded interface text and stale coverage data.

Before each stable release:

1. Open `docs/development/localization-coverage.md`.
2. Give translators the missing `hayai_` keys from the default resource files.
3. Review placeholder types, apostrophes, markup, and right-to-left layout behavior.
4. Run the localization check and commit the updated report.

## Update the J2K baseline

The gate permits exact hardcoded lines that already exist in the pinned J2K parent. That J2K exception list never applies under the Hayai namespace. The gate also rejects new interface literals in J2K adapter files.

The gate separately records every remaining Kotlin or Java literal under the Hayai namespace. This list contains reviewed protocol values, source data, persistence keys, selectors, and developer diagnostics. A new literal fails the check even when the focused interface detector does not recognize its call site.

After you classify every new literal and confirm that none is user-visible, refresh the Hayai baseline.

```powershell
.\tools\verify-localization.ps1 -WriteHayaiLiteralBaseline
```

Review `tools/localization/hayai-nonlocalizable-literals.txt`. Do not refresh this file to silence a failure.

After you rebase onto a reviewed `j2k/master`, regenerate the baseline.

```powershell
.\tools\verify-localization.ps1 -WriteUpstreamBaseline -UpstreamRef j2k/master
```

Review `tools/localization/upstream-hardcoded-ui.txt`. Commit the baseline with the J2K update, not with feature work.
