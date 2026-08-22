# E-Hentai and ExHentai port

Hayai rebuilds the E-Hentai feature set behind `dev.ahmedmohamed.hayai.adult.eh`. TachiyomiJ2K continues to own manga, chapters, history, categories, downloads, and image reading. TachiyomiSY commit `14648c7cf0aa84e5a35d48de9dbf1386df6cca42` is the behavior reference, not an architectural parent.

The bundled source artwork is copied unchanged from TachiyomiSY. The E-Hentai density assets trace to commit `5499404267f6a197de322318500f40c51e5f38b4`; the ExHentai density assets trace to `21a4a935232df32444a11d2dbef640deff71869a`.

## Domain boundaries

- `domain` validates gallery IDs, tokens, normalized URLs, sites, cursors, search modes, and failure classes.
- `network` owns request construction, dynamic cookies, API calls, bounded traversal, and defensive HTML parsing.
- `source` translates typed results into the two J2K `HttpSource` instances at IDs `6901` and `6902`.
- `session` owns private credentials, WebView login, verification, logout, and cookie headers. Secrets never enter the database, backup, logs, or saved instance state.
- `persistence` owns only data J2K cannot represent: full source metadata, tags, titles, gallery aliases, the confirmed remote-favorites snapshot, and resumable operation journals.
- `favorites` plans three-way reconciliation from confirmed, local, and remote snapshots before performing mutations.
- `update` owns bounded WorkManager jobs for gallery revision maintenance.
- `ui` owns account settings, structured metadata, batch import, favorites sync, and worker status.

Both built-in sources remain registered even when adult discovery is disabled or the account is logged out. Discovery policy may hide them, but direct lookup must continue resolving existing library entries. ExHentai network calls return a typed authentication failure when no verified session exists.

## Identity and persistence

`(sourceId, normalizedMangaUrl)` is the stable local metadata identity available during source parsing. `(galleryId, galleryToken)` is the stable remote identity used for favorites and revision aliases. J2K database IDs may be used as foreign keys internally only after a row exists; backup and restore use stable source identity.

Legacy `search_metadata`, `search_tags`, and `search_titles` are promoted once through a versioned Hayai sidecar migration. The original imported tables and `hayai_legacy_rows` remain provenance. A sidecar migration ledger is separate from J2K `user_version` and the one-time legacy database-import state, so existing reset installations receive later Hayai schema changes.

## Safety rules

- Parent and page traversal have visited-key detection and hard hop/page limits.
- Pagination cursors are typed. Toplist pages cannot be confused with gallery-ID cursors.
- Empty or challenge documents are authentication or document failures, never silent empty snapshots.
- All responses close on success, parser failure, and cancellation.
- Image quota placeholders and expired image tokens are distinct outcomes. Refresh and bounded retry stay in the source network layer, not J2K's reader lifecycle.
- Cookie values reject control characters and header separators. Cookie headers are rebuilt for every request.
- Remote profile configuration only replaces profiles owned by Hayai and converges after partial two-site failures.
- Favorites mutations are journaled before remote writes, remotely confirmed, then applied to J2K state in one local transaction.
- Background work uses unique WorkManager jobs, bounded retries, and ordinary network/charging constraints without manual wake or Wi-Fi locks.

## Completion gate

The port is complete only when browse, search filters, details, revisions, pages, URL import, login/logout, remote configuration, metadata backup, batch import, favorites synchronization, and gallery updates work end to end. Pure fixtures cover compact and extended result layouts, malformed/challenge pages, revision cycles, page quota, filter encoding, cookies, metadata promotion, backup compatibility, sync conflicts, interruption, and retry. The final boundary check must leave `App.kt` and `MainActivity.kt` unchanged. The only approved `ReaderActivity` change is the documented initial-page extra read owned by `ReaderLauncher`.
