# New issue and J2K audit, 2026-09-01

## Result

Four actionable issues were opened after nightly r6671. Three are regressions with strong code-level causes and one is a larger feature request. The fetched `j2k/master` is still commit `8df100d6e616851e329b274964c726dcef0556b6`, which is already the recorded Hayai base and an ancestor of Hayai `master`. There is no J2K update to merge.

## Recommended order

1. Fix #42, the deterministic Novel Reader settings crash.
2. Fix both defects reported in #44, short chapters not being marked read and TTS not resuming after automatic chapter transition.
3. Fix #45 by defining and implementing the custom-script lifecycle after translated content replaces the chapter DOM.
4. Design #43 as durable, batch offline translation rather than extending the disposable reader cache.
5. Leave #38 open until its long-chapter transition behavior is confirmed on a current nightly.

## Findings

| Issue | Complaint | Evidence and likely cause | Disposition |
|---|---|---|---|
| [#42](https://github.com/HayaiApp/hayai/issues/42) | Reader settings crashes when scrolling near the bottom | The attached r6671 crash log rejects stored value `72` for a Material Slider configured from 50 to 100 in steps of 5. The in-reader settings sheet lets the same auto-load and mark-read preferences move in steps of 1, while `NovelSettingsController` defines steps of 5. | Deterministic regression, fix first |
| [#43](https://github.com/HayaiApp/hayai/issues/43) | Translate all chapters for offline reading like Tsundoku | Hayai translates only the current reader document. Results are written under `cacheDir/hayai/novel-translations`, so they are on-demand cache entries rather than durable downloaded content. | Worth adding after regressions, requires a storage and batch-work contract |
| [#44](https://github.com/HayaiApp/hayai/issues/44) | Some chapters are not marked read; TTS does not continue into the automatically loaded next chapter | Both renderers implement `isShort`, and a short-chapter preference exists, but no caller consumes either. A one-viewport chapter can remain at 0 percent and never cross the default read threshold. TTS sets playback false at completion, advances the reader, then replaces paragraphs on document ready without restoring playback intent. | Two credible reader regressions, fix together with focused tests |
| [#45](https://github.com/HayaiApp/hayai/issues/45) | Custom CSS and JS stop working after translating | The supplied script replaces quote characters with custom DOM elements and the CSS styles those elements. `showTranslation` replaces the active block with new plain paragraph nodes, removing the generated elements. Custom JS runs at initial document construction only and is not rerun after replacement. | Concrete DOM lifecycle regression |
| [#38](https://github.com/HayaiApp/hayai/issues/38) | Paginated reader behavior and long-chapter transitions | r6671 claims fixes for viewport movement, taps, volume keys, seeking, and alignment. The issue remains open pending a real long-source confirmation and has no later reporter result. | Validation needed, not a newly proven regression |

## J2K status

- Fetch performed on 2026-09-01.
- Fetched tip: `8df100d6e616851e329b274964c726dcef0556b6`.
- Recorded Hayai J2K base: `8df100d6e616851e329b274964c726dcef0556b6`.
- `git diff` between the recorded base and fetched `j2k/master`: empty.
- `git merge-base --is-ancestor j2k/master HEAD`: success.
- Conclusion: no merge, rebase, or patch-stack replay is needed.

## Sources

| Source | What it established |
|---|---|
| GitHub issues API and issue pages for [#42](https://github.com/HayaiApp/hayai/issues/42), [#43](https://github.com/HayaiApp/hayai/issues/43), [#44](https://github.com/HayaiApp/hayai/issues/44), and [#45](https://github.com/HayaiApp/hayai/issues/45) | Exact reports, versions, devices, timestamps, comments, and attachments |
| [#42 crash log](https://github.com/user-attachments/files/31612640/tachiyomi_crash_logs.txt) | Exact Material Slider exception and r6671 build identity |
| [#45 custom CSS](https://github.com/user-attachments/files/31623666/quote.css) and [custom JS](https://github.com/user-attachments/files/31623667/quote.js) | The JS-created quote elements expected by the CSS |
| Local commit `5194bc3117e5a1f2419e09594160fb8743be7d2b` | Slider definitions, progress handling, TTS chapter handoff, translation DOM replacement, and cache location |
| Fetched `j2k/master` | Exact upstream tip and absence of new commits |

## Parallel research

- `new_issues_j2k_research`: independently checked the issue timeline, issue bodies, attachments, current code paths, and J2K status.
- `new_issues_j2k_research/issues_primary`: independently confirmed the four new reports, the exact crash, the short-chapter and TTS leads, the translation DOM mechanism, and the priority order.

No application code, GitHub issue, branch, or git history was changed during this audit.
