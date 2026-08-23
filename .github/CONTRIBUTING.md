# Contributing to Hayai

Search [existing issues](https://github.com/HayaiApp/hayai/issues) and read the repository `AGENTS.md`, [architecture](../docs/architecture/j2k-reset.md), and [feature audit](../docs/architecture/upstream-feature-audit.md) before changing code.

Hayai keeps TachiyomiJ2K as its architectural parent. TachiyomiSY and Tsundoku are behavior references, not merge parents. New application code belongs under `dev.ahmedmohamed.hayai`; changes to J2K files must stay limited to documented adapter seams.

For bug reports, include the Hayai version and build channel, Android version, steps to reproduce, expected and actual behavior, and relevant logs. Extension and source reports are welcome when the failure is in Hayai's manager, compatibility layer, reader, or integration behavior.

For code changes, implement a complete vertical slice, add focused tests, keep interface strings localizable with `hayai_` keys, update the feature audit honestly, and run the validation commands listed in `AGENTS.md` before handoff.
