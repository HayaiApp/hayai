# Hayai releases

## Publish a nightly build

Every push to `main` runs `.github/workflows/nightly.yml`. The workflow reads the latest release from `HayaiApp/hayai-nightly` and chooses the larger of the Git commit count or the latest `rN` value plus one. A canceled or retried run therefore cannot lower the published version.

The workflow builds and tests `StandardNightly`, signs every ABI with the `build` environment, and publishes the APKs to `HayaiApp/hayai-nightly`. Nightly builds use `dev.ahmedmohamed.hayai.nightly`, so they can remain installed beside stable Hayai. The updater checks only the nightly repository.

The `build` environment must define `SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, and `KEY_PASSWORD`. The repository must define `NIGHTLY_PAT` with release access to `HayaiApp/hayai-nightly`.

Run the workflow manually only when a push run did not publish. The monotonic number check makes a retry safe.

## Draft a stable release

Run `.github/workflows/build_push.yml` manually and enter the complete tag, such as `v1.8.1`. The workflow builds `StandardRelease`, signs the ABI APKs, and creates a draft in `HayaiApp/hayai`. Review the draft and its checksums before publishing it.

Stable releases use `dev.ahmedmohamed.hayai`. Their updater checks only `HayaiApp/hayai`.

## Check pull requests

`.github/workflows/build_check.yml` builds and tests `StandardRelease` for pull requests that change code or configuration. Documentation-only pull requests skip the Android build.
