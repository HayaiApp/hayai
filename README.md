<div align="center">

<a href="https://github.com/HayaiApp/hayai">
    <img src="./.github/readme-images/app-icon.webp" alt="Hayai logo" height="200px" width="200px" />
</a>

# Hayai

</div>

<div align="center">

A free and open source manga & novel reader

[![CI](https://github.com/HayaiApp/hayai/actions/workflows/build_push.yml/badge.svg?labelColor=27303D)](https://github.com/HayaiApp/hayai/actions/workflows/build_push.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/HayaiApp/hayai?labelColor=27303D&color=0877d2)](/LICENSE)

## Download

[![Hayai Stable](https://img.shields.io/github/v/release/HayaiApp/hayai?maxAge=3600&label=Stable&labelColor=06599d&color=043b69&filter=v*)](https://github.com/HayaiApp/hayai/releases)
[![Hayai Beta](https://img.shields.io/github/v/release/HayaiApp/hayai?maxAge=3600&label=Beta&labelColor=2c2c47&color=1c1c39&filter=v*-b*)](https://github.com/HayaiApp/hayai/releases)
[![Hayai Nightly](https://img.shields.io/github/v/release/HayaiApp/hayai-nightly?maxAge=3600&label=Nightly&labelColor=B11A3B&color=1c1c39)](https://github.com/HayaiApp/hayai-nightly/releases)

*Requires Android 8.0 or higher.*

## About Fork

Hayai is a fork of [Yōkai](https://github.com/null2264/yokai), which itself is a fork of [TachiyomiJ2K](https://github.com/Jays2Kings/tachiyomiJ2K) and [Mihon](https://github.com/mihonapp/mihon) (formerly Tachiyomi). It cherry-picks pieces from across the Tachiyomi ecosystem and adds first-class novel support.

Hayai also incorporates extension-ecosystem maintenance and stability fixes from [Rokku](https://github.com/rokku-app/rokku). Thanks to Thiago Rocha and the Rokku contributors for keeping the shared Yokai codebase compatible with current Keiyoushi extensions.

## Features

<div align="left">

<details open="">
    <summary><h3>From Hayai</h3></summary>

* **Performance & accessibility**
  * **Reduce motion** toggle (Settings → Appearance → Motion) — disables screen-transition animations, in-app fades, image crossfades, and skeleton-loader pulses across the whole app. Aimed at low-end devices, motion-sensitive users, and battery-saver scenarios. Wired centrally so every Conductor controller transition, Compose animation, Coil image load, and activity transition honors the toggle without per-screen opt-in.
  * **Tabbed library display** as an alternative to continuous scroll — each category on its own swipeable page with pinned tab strip and per-tab item counts. Separate pull-to-refresh per tab; whole-library update moved to toolbar menu. Layout choice is offered during onboarding.
* **Novels**
  * Native novel reader with JS plugin support, sourcing extensions from [LNReader](https://github.com/LNReader/lnreader-plugins).
  * Novel plugin manager with install, update, sort, and uninstall.
  * Novel repo validation and per-repo plugin cache.
  * QuickJS-based on-device runtime for novel source plugins.
  * **NovelUpdates and NovelList** sources for novel discovery and recommendations.
  * Browse infinite scroll: pages keep loading until a plugin returns an empty list (matching the LNReader convention).
  * `parsePage` support for plugins that paginate chapter listings (rewayatclub, lnmtl, sunovels, etc.) — full chapter list is now fetched, not just page 1.
  * Genre normalization: dedupes case-insensitively and tolerates plugins that emit `","` vs `", "`-separated lists (no more single-chip lumps containing every genre).
  * HTML stripped from plugin-emitted summaries that return raw markup.
  * **Continuous chapter reading** with smooth auto-scroll (pause/resume on touch, configurable speed), chapter transition cards, and persistent chapter state.
  * **Flexible progress slider positioning** (3×3 grid: top/center/bottom × left/center/right) per-reader for ergonomic thumbing on phones/tablets.
  * **Novel-specific orientation preference** separate from manga, with per-series overrides to respect portrait-preferred content vs manga's landscape optimizations.
  * **Overlay scrollbar** for WebView with visual feedback; native scrollbar disabled.
  * **Sentence-tap-to-TTS** handler for quick selection-free audio playback.
  * **Previous-chapter infinite-scroll** that prepends content without duplicates and pins a static card at the top.
* **Reader / browse**
  * Page preview improvements: bigger thumbnails, skeleton loading, infinite scroll.
  * Preload distance SeekBar in the reader.
  * E-Hentai / ExHentai category badges in browse (Doujinshi / Manga / Image Set / Artist CG / Cosplay / Game CG / Western / Non-H / Asian Porn / Misc), color-coded.
  * **NHentai tag assets** for local filtering and search without external API calls (artist, character, group, parody, language, categories).
  * Source navigation from recommendation card headers.
* **Recommendations**
  * Clicking an external recommendation (AniList / MyAnimeList / NovelUpdates / NovelList / etc.) triggers global search by title across installed sources.
  * **Content type badges** (Manga/Novel) on recommendation cards to disambiguate mixed-type recommendation lists.
  * **NovelList and NovelUpdates** sources for novel-specific recommendations.
* **Library & updates**
  * Library update report screen showing detailed per-title errors, skipped entries, and skip reasons. Accessible from Library settings, Recents tab, and update notifications.
  * Onboarding step to choose library display mode (continuous or tabbed) during initial setup.
* **Compose migrations**
  * Manga details metadata, continue/start reading UI, genre tags.
  * Grid and color filters.
  * E-Hentai filter UI and settings.
* **Stability / infrastructure**
  * Beta release channel alongside stable & nightly.
  * Firebase Crashlytics wired up for all build variants.
  * Share crash logs directly from the crash screen.
  * Migration parallelism with concurrency limits for faster batch source migration (1–10 concurrent, configurable in Advanced settings).
  * Fallback to system installer on MIUI devices.
  * Hardened JS plugin error propagation: per-plugin chapter/parse errors now surface as a retryable error state in the reader instead of crashing the app.

</details>

<details open="">
    <summary><h3>From <a href="https://github.com/jobobby04/TachiyomiSY">TachiyomiSY</a></h3></summary>

* First-class **E-Hentai / ExHentai** source with login, throttling, tag handling, and the Igneous cookie dialog.
* **EXH background update system** for galleries.
* **Recommendation sources** unified across MyAnimeList, AniList, MangaUpdates, and more, with batch processing and relevancy sorting.
* **DataSaver** image proxy support (including MangaPlus integration).
* **MergedSource** — combine chapters from multiple sources into a single entry.
* **FavoritesSync** — two-way sync of E-Hentai favorites with snapshot-based change detection.
* **GalleryAdder** — import E-Hentai/ExHentai galleries by URL.
* **MangaDex enhancements** — follows, login, similar manga, MangaPlus chapter handling.
* **Delegated sources** — custom logic for known external sources: MangaDex, NHentai, Lanraragi, EightMuses, HBrowse, Pururin, Tsumino.

</details>

<details open="">
    <summary><h3>From Yōkai</h3></summary>

* NSFW/SFW library filter (originally from TachiyomiSY).
* Backup compatibility fixes.
* New theme.
* Local Source chapters read ComicInfo.xml for chapter title, number, and scanlator.

</details>

<details open="">
    <summary><h3>From upstream (Tachiyomi/Mihon)</h3></summary>

* Local reading of downloaded content.
* A configurable reader with multiple viewers, reading directions and other settings.
* Tracker support:
  [MyAnimeList](https://myanimelist.net/),
  [AniList](https://anilist.co/),
  [Kitsu](https://kitsu.app/explore/anime),
  [Manga Updates](https://www.mangaupdates.com/),
  [Shikimori](https://shikimori.one),
  and [Bangumi](https://bgm.tv/) support.
* Categories to organize your library.
* Light and dark themes.
* Schedule updating your library for new chapters.
* Create backups locally to read offline or to your desired cloud service.

</details>

<details>
    <summary><h3>From J2K</h3></summary>

* UI redesign.
* New Manga details screens, themed by their manga covers.
* Combine 2 pages while reading into a single one for a better tablet experience.
* An expanded toolbar for easier one handed use (with the option to reduce the size back down).
* Floating searchbar to easily start a search in your library or while browsing.
* Library redesigned as a single list view: See categories listed in a vertical view, that can be collapsed or expanded with a tap.
* Staggered Library grid.
* Drag & Drop Sorting in Library.
* Dynamic Categories: Group your library automatically by the tags, tracking status, source, and more.
* New Recents page: Providing quick access to newly added manga, new chapters, and to continue where you left on in a series.
* Stats Page.
* New Themes.
* Dynamic Shortcuts: open the latest chapter of what you were last reading right from your homescreen.
* New material snackbar: Removing manga now auto deletes chapters and has an undo button in case you change your mind.
* Batch Auto-Source Migration (originally from [TachiyomiEH](https://github.com/NerdNumber9/TachiyomiEH)).
* Share sheets upgrade for Android 10.
* View all chapters right in the reader.
* A lot more Material Design You additions.
* Android 12 features such as automatic extension and app updates.

</details>

</div>

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

<div align="left">

<details><summary>Issues</summary>

**Before reporting a new issue, take a look at the [changelog](https://github.com/HayaiApp/hayai/releases) and the already opened [issues](https://github.com/HayaiApp/hayai/issues).**

</details>

<details><summary>Bugs</summary>

* Include version (**Settings → About → Version**).
  * If not latest, try updating, it may have already been solved.
* Include steps to reproduce (if not obvious from description).
* Include screenshot (if needed).
* If it could be device-dependent, try reproducing on another device (if possible).
* For large logs use [Pastebin](https://pastebin.com/) (or similar).
* Don't group unrelated requests into one issue.

</details>

<details><summary>Feature Requests</summary>

* Write a detailed issue, explaining what it should do or how.
  * Avoid writing just "like X app does"
* Include screenshot (if needed).

</details>

</div>

### Credits

Thank you to all the people who have contributed to Hayai and to the upstream projects it builds on: [Yōkai](https://github.com/null2264/yokai), [Mihon](https://github.com/mihonapp/mihon), [TachiyomiJ2K](https://github.com/Jays2Kings/tachiyomiJ2K), [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY), and [LNReader](https://github.com/LNReader/lnreader).

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 null2264
Copyright © 2026 Ahmed Mohamed

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>
</div>
