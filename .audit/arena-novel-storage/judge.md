# Arena Cross-Judge Verdict

Base recommendation: **Candidate A**

## Scores

Scored `1-5` on each rubric item.

| Criterion | Candidate A | Candidate B | Judge notes |
|---|---:|---:|---|
| 1. Preserve one J2K source of truth and minimize J2K seams | 3 | 4 | B is cleaner here because it keeps identity derived and avoids a new durable content-kind table. A still respects the one-library rule, but it adds a new identity store and a wider seam set. |
| 2. Fix stale-source novel identity consistently without unnecessary schema risk | 4 | 2 | A is the only candidate that fully closes the stale-source hole by durably remembering proven novel rows. B avoids schema risk, but it does not durably remember rows proven only by a live novel source, so those can regress to manga later. |
| 3. Fix novel icons/covers centrally | 5 | 4 | Both centralize the bug instead of teaching each screen about novel sources. A is slightly stronger because its icon binding and cover-request policy are both explicit deep seams around the existing fetcher. |
| 4. Make downloads/backups work under scoped storage with safe recovery | 5 | 4 | A keeps the storage decision behind a deeper repository and treats revoked or unsafe locations as recoverable states. B is solid, but `requiresManageExternalStorage` and mode branching leak storage policy back to callers. |
| 5. Select bounded issue work that can be completed and verified in this run | 2 | 3 | Neither candidate is truly bounded as written. A is especially wide because it bundles identity, visuals, storage, pagination, offline assets, and filters into one plan. B is still broad, but its sequencing is a little more sliceable. |
| 6. Keep interfaces deep and maintainable | 4 | 3 | A generally hides policy behind domain-shaped interfaces. B has two red flags: `ScopedStorageGateway` leaks internal policy to callers, and `NovelPagedGeometryBridge` exposes JS-script transport instead of a deeper page-geometry model. |

**Total:** Candidate A `23/30`, Candidate B `20/30`

## Why Candidate A wins

The load-bearing part of this decision is rubric items 2 and 4. Candidate A is the only proposal that makes stale-source novel identity durable across source disappearance and backup/restore, and it gives downloads/backups a safer scoped-storage recovery model. Those are the hardest bugs to paper over later.

Candidate B is architecturally cleaner on the “no extra durable model” axis, but it under-fixes the stale-source case. Under this rubric, that is a correctness miss, not just a tradeoff. A’s biggest problem is not the shape of the core fix; it is that the plan tries to ship too many adjacent slices at once.

## Grafts To Take From Candidate B

1. Take B’s narrower execution discipline. Implement A as staged vertical slices, starting with identity plus Library/stats/migration integration before visuals, storage, reader, or filter work.
2. Take B’s resource-churn discipline. Only introduce new `hayai_` novel strings where the visible text is actually manga-specific; keep existing generic “series” wording when it already fits.
3. If preview-grid math is truly part of the current issue bundle, take B’s standalone `PreviewGridGeometry` idea as a separate follow-on slice instead of folding it into the core novel identity/storage batch.

## Red Flags And Missing Behavior

### Candidate A

- Overengineered for one run. The same proposal tries to land a new identity table, backup DTO changes, source visuals, scoped-storage repair, pagination repair, offline manifest v2, and filter persistence. That is too much to complete and verify coherently in one pass.
- The new `hayai_novel_identities` table is justified only if we keep it strictly monotonic and adapter-thin. If it starts carrying broader content state, it becomes a second truth owner.

### Candidate B

- Missing behavior: it does not durably remember novels proven only by a live source capability, so orphaned APK-source novels can still decay back into manga classification.
- Information leakage: `ResolvedStorage.requiresManageExternalStorage` pushes storage-policy branching back into callers instead of hiding it behind one recovery-oriented operation.
- Temporal/transport leakage: `NovelPagedGeometryBridge` exposes `locationScript()`, `seekScript()`, and `stepScript()` rather than a deeper geometry contract. That makes callers learn the implementation shape.
- The mixed-selection migration example falls back to `showMixedContentNotSupported()` instead of the safer generic-series wording that A already models.

## Recommendation

Use Candidate A as the base, but trim the first implementation batch aggressively. Keep the durable novel-identity fix, the central icon/cover seam, and the scoped-storage recovery model. Graft B’s narrower sequencing and lower-churn UI wording discipline so the first run stays finishable and verifiable.
