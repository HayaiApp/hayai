# Hayai

![Hayai app icon](./.github/readme-images/app-icon.webp)

Hayai is an Android reader built as a direct fork of [TachiyomiJ2K](https://github.com/Jays2Kings/tachiyomiJ2K). It preserves J2K's library and image-reader architecture while establishing Hayai-owned boundaries for novel support and opt-in adult/source enhancements inspired by [Tsundoku](https://github.com/tsundoku-otaku/tsundoku) and [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY).

This branch is the clean J2K reset. The former Hayai/Rokku implementation is retained only as an archival branch and migration source; its application code is not part of the new baseline.

This reset currently ships the migration, policy, source-contract, launch-routing, and basic text-reader foundation—not the complete SY/Tsundoku runtime. See [the reset architecture](docs/architecture/j2k-reset.md) and [the feature audit](docs/architecture/upstream-feature-audit.md) for the exact implemented/remaining split.

<!-- Upstream J2K feature list retained below. -->

![screenshots of app](./.github/readme-images/screens.gif)

## Features

Features of Tachiyomi include:
* Online reading from a variety of sources
* Local reading of downloaded content
* A configurable reader with multiple viewers, reading directions and other settings.
* [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.io/explore/anime), [Shikimori](https://shikimori.one), and [Manga Updates](https://www.mangaupdates.com/) support
* Categories to organize your library
* Automatic light and dark themes
* Schedule updating your library for new chapters
* Create backups locally to read offline or to your desired cloud service 

Plus some new features in this fork such as:
* New Manga details screens, themed by their manga covers
* Combine 2 pages while reading into a single one for a better tablet experience
* An expanded toolbar for easier one handed use (with the option to reduce the size back down)
* Floating searchbar to easily start a search in your library or while browsing
* Library redesigned as a single list view: See categories listed in a vertical view, that can be collasped or expanded with a tap
* Staggered Library grid
* Drag & Drop Sorting in Library
* Dynamic Categories: Group your library automatically by the tags, tracking status, source, and more
* New Recents page: Providing quick access to newly added manga, new chapters, and to continue where you left on in a series
* Stats Page
* New Themes
* Dynamic Shortcuts: open the latest chapter of what you were last reading right from your homescreen
* [New material snackbar](.github/readme-images/material%20snackbar.png): Removing manga now auto deletes chapters and has an undo button in case you change your mind
* Batch Auto-Source Migration (taken from [TachiyomiEH](https://github.com/NerdNumber9/TachiyomiEH))
* [Share sheets upgrade for Android 10](.github/readme-images/share%20menu.png)
* View all chapters right in the reader
* A lot more Material Design You additions
* Android 12 features such as automatic extension and app updates

## Issues, Feature Requests and Contributing

Please make sure to read the full guidelines. Your issue may be closed without warning if you do not.

<details><summary>Issues</summary>

1. **Before reporting a new issue, take a look at the [FAQ](https://mihon.app/docs/faq/general), the [changelog](https://github.com/jays2kings/tachiyomi/releases) and the already opened [issues](https://github.com/mihonapp/mihon/issues).**
2. If you are unsure, ask here: [![Discord](https://img.shields.io/discord/349436576037732353.svg)](https://discord.gg/mihon)

</details>

<details><summary>Bugs</summary>

* Include version (Setting > About > Version)
 * If not latest, try updating, it may have already been solved
 * Dev version is equal to the number of commits as seen in the main page
* Include steps to reproduce (if not obvious from description)
* Include screenshot (if needed)
* If it could be device-dependent, try reproducing on another device (if possible)
* For large logs use http://pastebin.com/ (or similar)
* Don't group unrelated requests into one issue

</details>

<details><summary>Feature Requests</summary>

* Write a detailed issue, explaning what it should do or how. Avoid writing just "like X app does"
* Include screenshot (if needed)

Source requests should be checked at https://mihon.app/docs/faq/browse/extensions, they do not belong in this repository.
</details>

## FAQ

[See our website.](https://mihon.app/)
You can also reach out to us on [Discord](https://discord.gg/mihon).

## License

    Copyright 2015 Javier Tomás

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

## Disclaimer

The developer of this application does not have any affiliation with the content providers available.
