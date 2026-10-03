# Tsundoku update mapping

The reference advanced from `fe54f05ae038cbc2ae72713c6ac68a2fbccb11af` to `a3e32c8b60` on October 3, 2026. All ten new commits were inspected. J2K remains the architectural parent.

| Tsundoku commit | Hayai disposition |
|---|---|
| `a3e32c8b60` downloaded word counts and density | Rebuilt in `NovelEntryContentTools` and `NovelWordDensity`, using the existing reader sheet and downloaded chapter store. Counts include coverage for unavailable or unreadable files. |
| `455cb456c1` downloaded-entry text search | Rebuilt over J2K chapter rows and local downloaded documents with case, word, and regular-expression options. Cancelled searches and changed queries cannot publish stale results. |
| `27b0cefd97` Android 8 PrecomputedText crash | Hayai's native renderer does not call platform `PrecomputedText`. Its selectable TextView path already avoids the unavailable API. |
| `c3c8a20861` font list scrolling and long labels | Hayai already uses a RecyclerView sheet for imported fonts and constrained labels in the existing spinner. No duplicate selector is added. |
| `cc36869a0b` JavaScript plugins in Update all | Existing Hayai Update all already includes plugins. Its operation queue is repaired to cancel queued or active downloads and clear failed/cancelled state. |
| `775508f3c6` upstream FlexibleAdapter | Ported the switch to published Maven Central core 5.1.0 and UI 1.0.0. The first integration build confirmed JitPack's pinned arkon commit has no artifacts. Existing adapter classes remain authoritative; no adapter tree is copied. |
| `b04f9a4d39` SourceTracker release ABI | Preserve the published optional interface at its binary package name in the Hayai compatibility directory, with release keep rules. This enables compatible APK linkage; optional source tracking callback dispatch remains Foundation. |
| `626397e15f` EPUB book title in chapter titles | Ported the title-selection behavior in Hayai's EPUB parser with title and sibling-heading fixtures. |
| `b9c23e2363` custom metadata reset on refresh | J2K's existing `CustomMangaManager` getters retain custom fields while `SManga.copyFrom` refreshes originals. The new Novel type uses that same favorite-entry custom-genre path. |
| `3cbd993ba6` paged WebView reader | Hayai already has paged WebView rendering through `NovelRenderPlan.Web(Paged)` and measured document geometry. Existing browser pagination regressions are retained. |

The source audit also found Novel Phoenix implementing the compile-only `RateLimited` interface. Its host ABI is restored beside SourceTracker; rate-limit storage and chapter queues remain Hayai/J2K owned. Missing host interfaces can prevent an installed APK from entering the trust list or publishing sources.

Verification results belong in the October maintenance plan and `.audit/j2k-reset.tsv`. A mapped equivalent does not imply every upstream behavior has been tested on every device.
