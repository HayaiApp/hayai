# Hayai

![Hayai app icon](./.github/readme-images/app-icon.webp)

Hayai is an Android manga and light-novel reader built on the TachiyomiJ2K architecture. J2K remains the source of truth for the library, chapters, tracking, downloads, and image reader. Hayai adds a text reader, novel sources and plugins, defensive legacy migration, and optional adult-source features through small, documented integration seams.

## Downloads

Development builds are published in [Hayai Nightly](https://github.com/HayaiApp/hayai-nightly/releases/latest). Nightlies use a separate application ID, may contain unfinished work, and should be installed only after making a backup.

There is no new stable Hayai release yet. The main repository will not publish one until the reset and migration paths have completed their release checks.

## Current capabilities

- J2K manga library, reader, downloads, tracking, categories, history, and migration
- Native light-novel reader with themes, pagination, continuous reading, downloads, EPUB export, text-to-speech, dictionaries, and translation providers
- Selected-text Define, Google Translate, and web-search actions using the signed-in browser in a resizable Custom Tab, with a secure in-app sheet fallback
- Manga and novel APK extension compatibility plus LNReader-style JavaScript novel plugins
- E-Hentai and ExHentai browsing, metadata, previews, favorites, and authenticated behavior rebuilt from TachiyomiSY contracts
- Defensive import from the archived pre-J2K Hayai database without writing to the legacy database

See the [architecture](docs/architecture/j2k-reset.md), [feature audit](docs/architecture/upstream-feature-audit.md), and [release policy](docs/development/releases.md) for precise implementation and verification status.

## Support and contributing

- [Report a bug](https://github.com/HayaiApp/hayai/issues/new?template=issue_report.yml)
- [Request a feature](https://github.com/HayaiApp/hayai/issues/new?template=feature_request.yml)
- [Read the wiki](https://github.com/HayaiApp/hayai/wiki)
- [Contributing guide](.github/CONTRIBUTING.md)

Include the Hayai version, build channel, Android version, reproduction steps, and logs with bug reports. Extension and source failures belong here when they involve Hayai's manager, compatibility layer, reader, or integration behavior.

## Credits and provenance

Hayai is a direct fork of [TachiyomiJ2K](https://github.com/Jays2Kings/tachiyomiJ2K), which is the architectural parent and upstream update base.

- [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY) is the behavior reference for E-Hentai and other adult-source features.
- [Tsundoku](https://github.com/tsundoku-otaku/tsundoku) is the behavior reference for the novel reader and novel APK compatibility.
- [LNReader](https://github.com/LNReader/lnreader) is the compatibility reference for JavaScript novel plugins.
- The archived Hayai, Rokku, and Yōkai line is used only to understand user-data formats, preferences, branding, and Hayai-specific behavior such as selected-text browser tools.
- Hayai also carries the work and licensing lineage of Tachiyomi, Mihon, and their contributors. Upstream copyright and license notices remain intact.

Upstream projects are credited as sources and references. Hayai is not affiliated with them or with any content provider.

## License

Copyright 2015 Javier Tomás and project contributors.

Licensed under the [Apache License 2.0](LICENSE). Unless required by applicable law or agreed to in writing, the software is provided without warranties or conditions of any kind.
