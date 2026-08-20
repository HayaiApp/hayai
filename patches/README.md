# Hayai patch stack

This directory is a generated, reviewable copy of the commits above the recorded TachiyomiJ2K base. Git remains the source of truth.

`refresh-patchset.ps1` writes:

- `UPSTREAM_BASE`: the exact J2K commit used as the patch base;
- `series`: patch filenames in apply order;
- `*.patch`: binary-safe `git format-patch` output.

The exporter requires a clean worktree and a J2K base that is an ancestor of `HEAD`. It deletes only existing `*.patch` files inside this directory before regeneration.

To replay the series, start from the commit in `UPSTREAM_BASE` with a clean worktree, then run `tools/apply-patchset.ps1`. The replay script uses `git am --3way` and stops on the first conflict.

The current reset worktree is not exported until its commits are finalized. Do not hand-edit generated patch files.
