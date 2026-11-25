---
title: 'Sprint Change Proposal: close #104 / Story 3.9 with the measured baseline, no optimization'
date: 2026-11-25
status: approved
mode: batch
trigger: 'Story 3.9 baseline (PR #377): cold Accept → Solution median 3.51 s on the Lenovo TB128XU'
scope: Minor
---

# Sprint Change Proposal: close #104 / Story 3.9

## 1. Issue summary

Story 3.9 (#104) was meant to measure, then shorten, the cold solver preparation, which was recorded as 12.6 s (debug) and ≤ 10.5 s (release-equivalent, coarse) on the minimum device. On 2026-11-25 the production path was re-measured on the Lenovo TB128XU with the release-equivalent `benchmark` build at `e14a9fe0`: fresh process → *Enter colors* → Accept. The result is **3.51 s median** (3.39–5.76 s, 5 runs), from screen recordings (`docs/benchmarks.md` § Story 3.9 baseline, PR #377). The earlier 10.5 s was an artefact of the slow `uiautomator` polling method.

## 2. Decision (PO, 2026-11-25)

The PO accepts this for a very low-end minimum device and decides not to spend time on optimizations with little gain. Story 3.9 closes without a mitigation.

- **AC1:** the cold timing was measured (5 runs, median, min, max, conditions). Separate preparation/search timing is waived; search for the measured cube is negligible.
- **AC2:** the PO target is the measured baseline.
- **AC3, AC4:** not applicable, since no mitigation is made. AD-8 (bundled pruning tables, per-process move-table build) is unchanged. The copy "Preparing the solver." is unchanged.

## 3. Changes

- `epics.md`: close-104 annotations on the Epic 3 note and on Story 3.9. Dated annotations are kept.
- `sprint-status.yaml`: `3-9-measure-and-shorten-cold-solver-preparation: done`, `epic-3: done` (through `sprint_plan.py generate --set`). Validated.
- `docs/benchmarks.md`, `docs/pruning-tables.md`: the #104 lines now record the 3.51 s measurement, the PO acceptance and the closure.
- Architecture memlog: one decision line (through `memlog.py`).
- GitHub: #104 closes when the PR merges.

No requirement, AC behaviour, threshold, code or copy changes.
