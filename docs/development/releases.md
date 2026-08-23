# Hayai releases

## Publish a nightly build

Every push to `master` runs `.github/workflows/nightly.yml`. The workflow reads the latest release from `HayaiApp/hayai-nightly` and chooses the larger of the Git commit count or the latest `rN` value plus one. A canceled or retried run therefore cannot lower the published version.

The workflow builds and tests `StandardNightly` and signs every ABI with the `build` environment. Before publication, it installs the exact signed/minified x86_64 APK on an Android 12 emulator and observes a cold launch for 20 seconds. `tools/verify-apk-startup.ps1` rejects fatal exceptions, the Hayai crash activity, a missing main activity, or a dead main process. Only a passing artifact is published to `HayaiApp/hayai-nightly`. Nightly builds use `dev.ahmedmohamed.hayai.nightly`, so they can remain installed beside stable Hayai. The updater checks only the nightly repository.

The `build` environment must define `SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, and `KEY_PASSWORD`. The repository must define `NIGHTLY_PAT` with release access to `HayaiApp/hayai-nightly`.

Run the workflow manually only when a push run did not publish. The monotonic number check makes a retry safe.

## Draft a stable release

Do not run this workflow for the J2K reset yet. No new stable Hayai release has been published.

When the stable checklist is complete, run `.github/workflows/build_push.yml` manually and enter the complete tag, such as `v1.15.0`. The workflow builds `StandardRelease`, signs the ABI APKs, and creates a draft in `HayaiApp/hayai`. Review the draft and its checksums before publishing it.

Stable releases use `dev.ahmedmohamed.hayai`. Their updater checks only `HayaiApp/hayai`.

## Check pull requests

`.github/workflows/build_check.yml` builds and tests `StandardRelease` for pull requests that change code or configuration. Documentation-only pull requests skip the Android build.
