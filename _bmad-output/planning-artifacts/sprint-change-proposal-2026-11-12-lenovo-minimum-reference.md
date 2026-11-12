---
title: 'Sprint Change Proposal: Lenovo Tab M10 Plus 3rd Gen as the minimum reference device, and the preparing copy reworded'
date: 2026-11-12
status: approved
approved_by: hebelmx (PO), 2026-11-12 (copy A; Story 12.1 tablet pass on the Galaxy Tab S7 FE)
trigger: 'New device evidence (docs/benchmarks.md § "Additional device: Lenovo Tab M10 Plus 3rd Gen", docs/evidence/lenovo-tb128xu/, PR #120), GitHub issue #119, and PO decisions of 2026-11-12'
scope: Minor
mode: batch (non-interactive; PO decisions taken from the request)
---

# Sprint Change Proposal: Lenovo Tab M10 Plus 3rd Gen as the minimum reference device, and the preparing copy reworded

## 1. Issue Summary

On 2026-11-11 and 2026-11-12, the Story 3.8 and Story 12.2 methods were repeated on a **Lenovo Tab M10 Plus 3rd Gen** at revision `95d708f`. The device is a TB128XU with a Snapdragon 680 (SM6225), 4 GB RAM, Android 13 / API 33, a 1200 × 2000 display at 240 dpi and 60 Hz, and a 256 MiB Java heap. The evidence is in `docs/benchmarks.md` § *Additional device: Lenovo Tab M10 Plus 3rd Gen* and in `docs/evidence/lenovo-tb128xu/` (PR #120). The limitations are in issue #119.

This tablet is slower than the Galaxy Tab S7 FE 5G. It is a better floor for "minimum" than the two Samsung devices, which ARCH-OQ-1 itself recorded as "neither truly low-end" (architecture `.memlog.md:59`). The PO made these decisions on 2026-11-12:

1. The Lenovo **replaces** the Galaxy Tab S7 FE 5G as the **minimum** reference device. The Galaxy S24 FE stays the mid-range device.
2. On this device, AC3 (rotation) may be run with the window-manager orientation override off (`wm set-ignore-orientation-request false`, restored afterwards). This is an **accepted method**. Making the harness work with default settings is a follow-up in #119.
3. The solver preparing copy (`msg.solve.preparing`, approved in SCP 2026-11-11, PR #116) is reworded again. "A few seconds" is not true on the new minimum device, where preparation takes 12.6 s per process start on the debug build.
4. The architecture spine names the reference devices (ARCH-OQ-1). It is final, so its edits are routed (§4.5) and not made here.
5. #104–#107 stay deferred to v1.1.

**What happens to the Galaxy Tab S7 FE 5G (decided here, as recommended in the request):** it stays as an **additional Samsung compatibility device**. It is not a reference device any more. Its Story 3.8, 1.11 and 12.2 results stay in the docs as **dated evidence** from when it was the minimum device, and nothing is re-run on it. It remains useful because it is the only physical Samsung tablet, the only physical API 31 device, and the device where the Samsung-only StrictMode findings (#107) reproduce. "Reference devices" now means the Lenovo (minimum) and the S24 FE (mid).

### Lenovo results against every NFR threshold

The thresholds are the ones the reference devices were held to. The minimum-device threshold applies.

| Requirement | Threshold (minimum device) | Lenovo TB128XU | Pass |
|---|---|---|---|
| NFR-1 Responsiveness | face-turn input acknowledged ≤ 100 ms | input → frame p90 53.39 ms, max 57.52 ms (face turns); p90 20.76 ms, max 30.94 ms (View Rotation) | **pass** |
| NFR-2 Rendering | ≥ 30 FPS during face turns and View Rotation | median 61.96 FPS (face turns), 59.69 FPS (View Rotation); worst window 57.87 FPS; frame-interval p90 18.35 / 19.66 ms | **pass**. 5 of 1,990 View Rotation intervals were over 33.3 ms (max 40.2 ms), against none on the Samsung devices (#119 item 4). The 60 FPS mid-range target does not apply to the minimum device. |
| AD-4 / Story 1.11 renderer gate | frame-interval p90 ≤ 33.3 ms | 18.35 / 19.66 ms | **pass**. The OpenGL ES choice holds on the new minimum device. |
| NFR-3 Solver performance | ≥ 95% of the corpus within 3 s; median, p95, memory and failure rate reported | 10,101 / 10,101 (100%), 0 failures, median 27.0 ms, p95 138.7 ms, max 1,561 ms, peak heap 32.7 MiB of 256 MiB (`benchmark` build) | **pass**. The slowest case leaves 1.44 s of margin. |
| NFR-13 Compatibility | phones and tablets, portrait and landscape; rotation keeps state and layout follows the window class (12.2 AC3) | 0/7 flows as shipped, because the harness cannot rotate the app (Android 13 `ignore-orientation-request=true`). 7/7 flows and 43/43 surfaces with the accepted override. Adds physical API 33. | **pass** with the accepted method (decision 2). |
| Story 3.8 StrictMode | no main-thread disk access | 0 violations in both processes, and 0 on all 15 harness runs | **pass**. No waiver is needed. #107 is Samsung-only. |
| Story 3.8 delivery rule | first-run generation allowed only if G ≤ 5 s (this decides delivery; it is not a pass criterion) | G = 14.35 s | Bundled is selected here too. The 3.8 decision stands. |
| #104 per-process preparation | accepted for v1.0, no threshold | 12.6 s debug (median, n = 5); ≤ 10.5 s release-equivalent (coarse upper bound) | n/a. About 1.5× the Tab S7 FE. The copy is reworded (§4.3). The speed-up stays #104 (v1.1). |
| NFR-11 App size | < 30 MB installed | not re-measured | n/a. The installed size depends on the build, not the device tier. It is checked again in 12.3 and 7.8 on the release AAB. |

No threshold fails, and **nothing becomes a new v1.0 blocker.** The open items are in #119:

- Item 1: the rotation harness under default settings (follow-up, not v1.0-blocking; see §2).
- Item 2: 12.6 s preparation (#104, v1.1; copy fixed in §4.3).
- Item 3: 14.4 s first-time generation (fallback path only).
- Item 4: View Rotation dropped frames (within the threshold).
- Item 5: face-turn latency p90 53 ms (within the threshold).
- Item 6: Macrobenchmark reports `cpuLocked: true` by mistake on this kernel.
- Item 7: the release timing is coarse.
- Item 8: CRLF line endings in `tools/*.sh`.

## 2. Impact Analysis

| Area | Impact |
|---|---|
| Epics | No epic opens or closes, and no story is added, removed or reordered. Closed Stories 1.11, 3.8 and 12.2 name the old device or ran on it. Each gets an **annotation**, and their ACs are not rewritten. The Epic 12 header gets a reference-device note. NFR-3 and AR-22 in the requirements inventory are updated. The Story 3.7 AC quotes the preparing copy, which changes (pending PO approval). Stories 12.3, 12.4 and 7.8 do not name a device, so they do not change. |
| PRD | The PRD never named a device. §5 left the set to the architecture. It gets a "Resolved" note naming the new set. NFR-3 says "minimum" explicitly, to match Story 12.2 AC2 and the PRD's own NFR-2. The §11 assumption gets an annotation, and §10 gets a decision line. NFR-1, NFR-2 and NFR-13 name no device and do not change. |
| Architecture | The spine's *Reference devices* row (`:442`) and the *Deferred* performance bullet (`:525`) name or describe the old set. They are routed to `bmad-architecture` update (§4.5). No AD changes. |
| UX | EXPERIENCE.md names no device. Only the State Patterns preparing row (`:130`) changes, with the copy (pending PO approval). |
| Code | One content string, `msg.solve.preparing` in `core/domain/src/main/resources/content/strings/en.json:3`. There is no logic change. The two-state preparation from #112 stays as built. This is handed off to a build (§5) and is not done here. |
| Test harness | `KeyFlowHarness` rotates only through `Activity.requestedOrientation` (`KeyFlowHarness.kt:46,289`). `.gitattributes` forces LF only for `gradlew`. Both are #119 items 1 and 8. |
| Docs | `docs/benchmarks.md:468` says the Lenovo is "Not a reference device". Its summary table (`:632-635`) labels the FPS rows NFR-1 and the input rows NFR-2, but the PRD has them the other way round. `docs/ARCHITECTURE.md:200` names the old set. `docs/emulators.md` describes "the two reference devices" as S24 FE and Tab S7 FE. These go into the #119 build (§5). |

### Is #119's harness fix (default-settings rotation) v1.0-blocking? No.

No planning text requires the rotation suite to run under the tablet's default window-manager settings:

- Story 12.2 AC3 requires every Key Flow to run in portrait and landscape, with layouts following the window class and rotation never changing session state. It names no method.
- NFR-13 and AD-17 name no method either.
- The release gates (PRD §7, Story 12.4) do not mention rotation.

The product is not affected. The app declares no `screenOrientation` and never sets `requestedOrientation` (`app/src/main`: no matches). So `ignore-orientation-request=true` changes nothing for a Learner, who rotates the tablet through the system as on any tablet. Only the harness relies on orientation requests.

The accepted override (decision 2) therefore closes AC3 on this device for v1.0. The harness fix stays a follow-up. Item 8 (`*.sh text eol=lf`) is a one-line hygiene fix, so it is cheap to ship with the same build.

### Story 12.1 physical pass (in `review`)

The pass in `docs/accessibility-manual-check.md:7` says "the reference phone and tablet". It names no device, so no text change is needed. The reference phone is still the S24 FE. The reference tablet is now the Lenovo. Accessibility results (TalkBack, Switch Access, 200% type, themes) do not depend on the performance tier. Both tablets land in MEDIUM portrait and EXPANDED landscape windows (`docs/benchmarks.md:602-603`).

**PO decision (2026-11-12):** the Story 12.1 tablet pass runs on the Galaxy Tab S7 FE. The Lenovo run was a repeatability check of the performance measurements; the Lenovo is the minimum reference for the performance NFRs, not for the 12.1 accessibility pass. Either way, the device goes in the table's *Device / Android* column. This is listed for PO confirmation (§5). It is not a blocker.

### Checklist summary

- [x] 1 Trigger and context: new device evidence (PR #120) and #119. Category: new requirement from a stakeholder (the PO changes the minimum device), backed by measurements.
- [x] 2 Epic impact: annotations on closed Stories 1.11, 3.8 and 12.2. A header note on Epic 12. The copy in the Story 3.7 AC. No future story's ACs change (12.3, 12.4 and 7.8 name no device). No epic is invalidated.
- [x] 3 Artifact conflicts: PRD, epics.md and EXPERIENCE.md are edited here. The spine is routed (§4.5). The docs go to the build.
- [x] 4 Path: Direct Adjustment. Rollback [N/A]. MVP review [N/A]. Every NFR passes on the new device.
- [x] 5 Proposal components: §4.
- [x] 6 Approval: the PO approved the proposal on 2026-11-12, with copy A (§4.3). The Story 12.1 tablet pass stays on the Galaxy Tab S7 FE (§2).

## 3. Recommended Approach

**Direct Adjustment.** Rename the reference set in the planning text, annotate the closed stories, reword one string, and route the spine and docs edits.

- **Effort:** small. The planning text is edited here. The code is one string plus doc annotations, under an hour. The harness fix in #119 is a few hours, and it is not on the v1.0 path.
- **Risk:** low. Every threshold passes on the new device. Only the copy is user-visible, and it removes a claim that was untrue.
- **Timeline:** none. The copy change is pre-release, before 7.8. The harness fix can come later.

## 4. Detailed Change Proposals

### 4.1 PRD (`prds/prd-RubikXplorer-2026-10-29/prd.md`) — applied

**§5 intro (`:562`).** The `[ASSUMPTION]` is kept, and a note is appended:

```
*Resolved: the architecture named the set (ARCH-OQ-1, PO 2026-10-30). Since 2026-11-12 the minimum reference device is the Lenovo Tab M10 Plus 3rd Gen (TB128XU: Snapdragon 680, 4 GB RAM, Android 13), and the mid-range device stays the Samsung Galaxy S24 FE. The Samsung Galaxy Tab S7 FE 5G, the minimum device until then, is kept as an additional compatibility device. API 29–30 coverage is emulator-only. (SCP 2026-11-12)*
```

**NFR-3 (`:568`).**

- OLD: "… solved within 3 s on the reference device. …"
- NEW: "… solved within 3 s on the minimum reference device. … *(SCP 2026-11-12: "minimum" made explicit)*"
- Rationale: there are two reference devices. Stories 3.8 and 12.2 have always held NFR-3 on the minimum device. The new wording states the current practice, and the requirement does not get stricter.

**§10 decisions (`:728`).** Add the line "Reference devices: minimum Lenovo Tab M10 Plus 3rd Gen, mid Samsung Galaxy S24 FE (PO, 2026-11-12, SCP 2026-11-12). The Galaxy Tab S7 FE 5G, the minimum from 2026-10-30 (ARCH-OQ-1), is kept as an additional compatibility device."

**§11 assumptions index (`:744`).** Append "*(Resolved: ARCH-OQ-1, 2026-10-30; minimum replaced by the Lenovo Tab M10 Plus 3rd Gen, SCP 2026-11-12. See §5.)*"

NFR-1, NFR-2 and NFR-13 are unchanged. They name no device, and "minimum reference device" in NFR-2 now resolves to the Lenovo through §5.

### 4.2 Epics (`epics.md`) — applied

| Location | Change |
|---|---|
| NFR-3 inventory (`:71`) | "on the reference device" → "on the minimum reference device *(SCP 2026-11-12)*" (mirrors §4.1) |
| AR-22 (`:109`) | "reference devices ARCH-OQ-1" → "reference devices ARCH-OQ-1 (closed 2026-10-30; minimum replaced by the Lenovo Tab M10 Plus 3rd Gen, SCP 2026-11-12)" |
| Epic 12 header (new `:237`, after *Device batch*) | New paragraph **Reference devices (SCP 2026-11-12)**: minimum Lenovo (TB128XU, API 33), mid S24 FE. The Tab S7 FE is an additional compatibility device, and its 3.8, 1.11 and 12.2 results are dated evidence. The Lenovo meets every threshold. Its limitations are in #119. |
| Story 1.11 (done), after the last AC (new `:511`) | Annotation: the AC names the old minimum, and its results stay dated evidence. The Lenovo frame-interval p90 is 18.35 / 19.66 ms, within 33.3 ms, so AD-4 holds. The 2D net baseline is still #106. |
| Story 3.7 (done), AC 2 (`:853`) | Preparing copy replaced with the recommended text, marked "SCP 2026-11-12, pending PO approval", quoting the text it replaces (§4.3). The first-use copy and the timeout copy are unchanged. |
| Story 3.8 (done), after the last AC (new `:899`) | Annotation: the AC names the Tab S7 FE as minimum, its evidence stays dated, and bundled delivery stands. Lenovo figures: G = 14.35 s, preparation 12.6 s debug / ≤ 10.5 s release (#104), StrictMode 0 with no waiver (#107 is Samsung-only). k = 16.94 was paired with the Tab S7 FE debug build, so recalibrating against the new minimum belongs to #105 (v1.1). |
| Story 12.2 (done), after AC3 (new `:1827`) | Annotation: the Lenovo meets AC1 and AC2 (figures quoted). For AC3 the PO accepted the `wm set-ignore-orientation-request false` method (7/7 flows, 43/43 surfaces). Default-settings rotation is a #119 harness follow-up, not a v1.0 gate. |

The ACs of Stories 1.11 and 3.8 still read "minimum Galaxy Tab S7 FE 5G". They are closed, so they are annotated and not rewritten (decision 1).

### 4.3 Preparing copy (`msg.solve.preparing`) — candidates and recommendation

**The constraints:**

- It must be true on the Lenovo, where preparation takes 12.6 s debug and up to 10.5 s release-equivalent, on the first solve after each process start.
- It must also be true on the S24 FE, which is faster, and the Tab S7 FE (about 8 s).
- It must not promise a duration that a slower supported device (anything at minSdk 29 and above) or a later change could break.
- It must stay distinguishable from the first-use copy, "Setting up the solver for first use. This can take a little while." That copy has a different verb, a different trigger (first use against every app start) and a vaguer length.

| # | Candidate | True on the Lenovo? | Assessment |
|---|---|---|---|
| **A (recommended)** | "Preparing the solver. The first solve after the app starts takes longer. Later solves are faster." | Yes. It is relative, with no duration. Later solves in the same process skip preparation (the second Accept went straight to playback), and corpus solves have a p95 of 139 ms. | It is true on every device, it tells the Learner why this solve is slow and that the wait will not repeat, and it shares no wording with the first-use line ("Preparing" / "takes longer" against "Setting up" / "a little while"). |
| B | "Preparing the solver. This can take a while the first time after the app starts." | Yes | This is the smallest change from the PR #116 text. But "a while" nearly repeats the first-use "a little while", so the two states read almost the same. It also gives the Learner no sign that later solves are quick. |
| C | "Preparing the solver. The first solve after the app starts can take up to 15 seconds." | Yes today (12.6 s debug, ≤ 10.5 s release) | This repeats the mistake #112 fixed: it promises a number. A device slower than the minimum, a busy or thermally throttled device, or a future change could break it, and #104 would need a copy change when it lands. Not recommended. |

**Approved: A** (PO, 2026-11-12). It is applied to the Story 3.7 AC (`epics.md:853`) and to EXPERIENCE.md (`:130`), marked **pending PO approval**. If the PO picks B or C instead, only those two quotes and the build string change.

`msg.solve.preparing-first-use` stays as approved in SCP 2026-11-11. It is still true on the Lenovo, where generation takes 14.4 s and happens once per install on the fallback path only (#119 item 3).

### 4.4 EXPERIENCE.md (`ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md:130`) — applied

OLD:
```
| Solver preparing (first solve after the app starts) | Solve | "Preparing the solver. This can take a few seconds the first time after the app starts." Shown on the first solve in each app process, …
```

NEW:
```
| Solver preparing (first solve after the app starts) | Solve | "Preparing the solver. The first solve after the app starts takes longer. Later solves are faster." (SCP 2026-11-12, pending PO approval; replaces "Preparing the solver. This can take a few seconds the first time after the app starts.") Shown on the first solve in each app process, …
```

The rest of the row and the first-use row are unchanged. EXPERIENCE.md and DESIGN.md name no device, so nothing else changes.

### 4.5 Architecture spine — route to `bmad-architecture` update (do not hand-edit)

`ARCHITECTURE-SPINE.md:442`, the *Reference devices* row in the technology table:

- OLD: `| Reference devices | minimum Samsung Galaxy Tab S7 FE 5G, mid Samsung Galaxy S24 FE (ARCH-OQ-1, PO 2026-10-30; supersedes the PRD §5 device assumption). API 29–30 coverage is emulator-only. |`
- NEW: `| Reference devices | minimum Lenovo Tab M10 Plus 3rd Gen (TB128XU, Snapdragon 680, 4 GB, Android 13 / API 33), mid Samsung Galaxy S24 FE (ARCH-OQ-1, PO 2026-10-30; minimum replaced 2026-11-12, sprint-change-proposal-2026-11-12-lenovo-minimum-reference; supersedes the PRD §5 device assumption). The Galaxy Tab S7 FE 5G (minimum until 2026-11-12) is an additional compatibility device. API 29–30 coverage is emulator-only. |`

`ARCHITECTURE-SPINE.md:525`, the *Deferred* performance bullet:

- Replace "Neither reference device is truly low-end." with: "The minimum reference device is a budget tablet (Lenovo Tab M10 Plus 3rd Gen). Its Story 1.11 and 12.2 methods were re-run on 2026-11-12: frame-interval p90 18.35 / 19.66 ms, so the AD-4 choice holds, and the full NFR-1/2/3/13 pass is in `docs/benchmarks.md` § Additional device. Story 1.11's and 12.2's Galaxy Tab S7 FE results are dated evidence from when it was the minimum device."
- Keep the rest of the bullet, including the #106 waiver.

Architecture `.memlog.md`: append, with `uv run _bmad/scripts/memlog.py append` and not by hand, a `(decision by user)` entry: "ARCH-OQ-1 revised by PO 2026-11-12: minimum = Lenovo Tab M10 Plus 3rd Gen (TB128XU), mid = Galaxy S24 FE; Tab S7 FE kept as additional compatibility device; Lenovo meets NFR-1/2/3/13 and the AD-4 p90 gate; AC3 rotation measured with `wm set-ignore-orientation-request false` (PO-accepted); harness follow-up #119."

No AD text changes. The AD-17 rule is method-neutral.

## 5. Implementation Handoff

**Scope: Minor.** The planning text (§4.1, §4.2, §4.4) is applied in this SCP's PR.

| Item | Route | What |
|---|---|---|
| Preparing copy | **build on a new pre-release issue** (recommended over #119) | `en.json:3`: set `msg.solve.preparing` to the approved text from §4.3. Only the string changes. Keep the key, the `solve:preparing` tag, Cancel and the two-state preparation. Annotate the Known issues bullet in `docs/pruning-tables.md` ("reworded again by SCP 2026-11-12"). Tests pin the tag, not the text (SCP 2026-11-11 §2), so no test changes are expected. A string test may be added. |
| #119 | **build** (not v1.0-blocking) | Item 1: make `KeyFlowHarness` rotate the display itself (e.g. `UiDevice.setOrientation*`, or `user_rotation` with auto-rotate off), then re-run on the Lenovo with default settings. Item 8: add `*.sh text eol=lf` to `.gitattributes`. Items 2–7: record them in the reference-device docs, by updating `docs/benchmarks.md` (§2 Docs row: the heading at `:468` becomes the minimum reference device, the Tab S7 FE becomes a compatibility device, and the swapped NFR-1/NFR-2 labels at `:632-635` are corrected), `docs/ARCHITECTURE.md:200` and `docs/emulators.md`. |
| Spine | `bmad-architecture` update (Architect) | §4.5 edits and the memlog append. |
| #104, #105, #107 | PO/SM issue comments only, v1.1 unchanged | #104: on the new minimum device, preparation is 12.6 s debug and ≤ 10.5 s release (the title says "~8 s"). #105: recalibrate k against the Lenovo, the new minimum, on a release-equivalent build. #107: Samsung-only, not reproduced on the minimum device. #106: still unmeasured on any device. |
| PRD memlog | PM, via `memlog.py append` | A `(change)` entry for §4.1. |
| Sprint status | SM, after merge | No story key changes. Optionally note this SCP against `12-2` and `3-8` (both stay `done`). |

**Why a new issue for the copy, not #119:**

- #119 is test-infrastructure and docs work. Its *Done when* section covers the harness, the line endings and the docs. Its item 1 is explicitly not v1.0-blocking.
- The copy is Learner-visible and must ship before 7.8. That is the same gating as #112, which was labeled `pre-release`.
- If the copy rode on #119, it would wait for the harness work, or #119 would close half-done.
- A small separate issue labeled `pre-release`, linking this SCP and #119 item 2, keeps both clean.

**Questions for the PO at approval (neither blocks drafting):**

1. Approve copy A (§4.3), or choose B or C.
2. Confirm the Story 12.1 tablet. Recommended: a pass already recorded on the Tab S7 FE counts, and a pass not yet started uses the Lenovo.

**Success criteria:**

- No planning text calls the Tab S7 FE the current minimum device, except the dated, annotated ACs of closed stories.
- The PRD, epics and spine (after the architecture update) name the Lenovo as minimum and the S24 FE as mid.
- The shipped `msg.solve.preparing` matches the approved text, and the 3.7 AC and EXPERIENCE.md quote it with the "pending" marker removed.
- `docs/benchmarks.md` no longer says the Lenovo is "Not a reference device".
- CI is green.
