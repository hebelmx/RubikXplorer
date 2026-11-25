# Sprint Change Proposal — solver preparation, scan handoffs, and fixture recording

**Status:** Draft for product-owner approval

**Date:** 2026-11-23 (America/Mexico_City)

## 1. Issue summary

The release device session surfaced three navigation gaps in Guided Scan and Placement, and a difference between Quick Check in Lessons and Solution Playback. The same device session showed scan review suggesting clearly different colours after a manual edit. A separate label review found 20 incorrect sticker labels across 23 corner fixture groups. The solver still rebuilds move tables after a cold process start, with a 12.6 s debug median on the Lenovo TB128XU. The solver preparation message now avoids promising a duration.

Sources: [release device session](../../docs/evidence/release-device-session/README.md), [corner fixture review](../../docs/evidence/10-27/issue-drafts-2026-11-23.md), [solver preparation #104](https://github.com/hebelmx/RubikXplorer/issues/104).

## 2. Impact

| Area | Proposed impact |
| --- | --- |
| Epic 3, solver preparation, and Epic 12, performance evidence | Add a measured cold-preparation follow-up to Story 3.8 (pruning table delivery). Use the current Lenovo baseline and the production Solve flow. Report cold preparation and search separately under the solver performance requirement. |
| Epic 10, recognition | Extend the Guided Scan experience and Editor handoff, and add a plausibility check to scan-review advice. Add a recorder safeguard linked to Story 10.27 (fixture label cleanup and pastel-colour plausibility). The existing approval to start Story 10.21 (side-face accuracy) with cleaned labels still applies. |
| Epic 11, Placement | Add a route from a solved-cube Placement result to the existing entry-level picker. The outcome offers the picker, Stage 1, and rescan. |
| Epic 10, Quick Check | Add the existing result presentation to physical Solution Playback via a shared component. Host state transitions continue through the host reducer and certainty comes from the existing assessment. This work was accepted for a later release. |

These changes fit the existing product requirements for solving, reviewing scan results, choosing a starting Stage, and using Quick Check. The Guided Scan and Placement changes need an experience update. The recorder proposal adds optional diagnostic evidence to debug fixture manifests. The proposed device paths run under the camera flags and the release privacy policy.

## 3. Recommended approach

Add the bounded stories below to the existing epics after approval. The Guided Scan controls and solved Placement action are small interaction changes. Transferring partial scans, sharing Quick Check across hosts, and blocking bad fixture labels have a larger regression surface. Measure the solver before choosing a mitigation. Ship the approved camera changes when their own device and accessibility checks pass. Schedule the fixture recorder work independently of the release decision.

Rolling back the scan, Placement, or recorder flow would remove behavior users already rely on. The existing interfaces can support the proposed follow-ups without resequencing the epics.

## 4. Detailed changes

| Issue | Current behavior and artifact | Proposed acceptance |
| --- | --- | --- |
| [#104, cold solver preparation](https://github.com/hebelmx/RubikXplorer/issues/104) | Story 3.8 records pruning-table delivery and timings. Move tables are rebuilt per process. The approved experience simply says “Preparing the solver.” | Add a performance follow-up under Epic 3. Record at least five cold process starts on the physical Lenovo in a release-equivalent build. Report move table preparation and search separately, then compare one bounded mitigation against the same baseline. Agree on a target from that baseline before claiming closure. Verify the solver corpus and keep preparation off the main thread. |
| [#313, hard-to-find Close](https://github.com/hebelmx/RubikXplorer/issues/313) and [#325, Guided capture discard](https://github.com/hebelmx/RubikXplorer/issues/325) | Guided Scan Close sits away from the other controls in tablet landscape. Leaving the capture view can discard collected faces immediately. The existing review-screen discard rule covers edits and retakes. | Track one Guided Scan interaction story and update the viewfinder flow. Make Close easy to find alongside the controls in tablet landscape. With zero faces, Close or Back leaves immediately. With captured faces, show “Discard the faces you captured?” and preserve those faces if the learner keeps scanning. Test both exits from the normal Welcome and Solve paths in portrait and landscape. The review screen applies its own discard rule for edits and retakes. |
| [#321, scanned colours lost on Editor fallback](https://github.com/hebelmx/RubikXplorer/issues/321) | The Solve experience sends incomplete or invalid scans to the Manual Cube Editor. The release session's reviewed scan opened an empty editor on fallback. | Add a separate Solve handoff acceptance case and update Experience Flow 2. Seed the editor with available colours and learner edits, keeping unknown cells visibly unfilled and preserving the home frame. Accept remains gated by normal Editor validation. Permission denial or no capture opens the normal blank-entry path. Direct scan Accept still goes straight to playback. Verify valid reviewed, partially read, and no-capture cases. |
| [#323, implausible scan-review suggestions](https://github.com/hebelmx/RubikXplorer/issues/323) | The wrong-counts ranking accepts up to three candidates even when all measurements are far from the missing colour. Existing tests intentionally rank exact reads of the over-counted colour as fallbacks. | Update the scan-review acceptance criteria so the hint appears only when the measured colour is plausibly close to the proposed centre. Document and validate the proximity rule against the real yellow/orange failure from #174 and the blue/white and blue/orange examples from the release session. Suppress the hint when no candidate qualifies and keep the count and piece diagnostics visible. Apply the new proximity rule only to the review hint. |
| [#324, solved-cube Placement choice](https://github.com/hebelmx/RubikXplorer/issues/324) | The Placement outcome specification lists only Scan again and Start Stage 1 for a solved cube. | Add “I can already solve it” to the solved outcome in Story 11.2 (start the Course from a scanned cube) and the Placement outcomes table. It opens the existing entry-level picker. Opening the picker alone grants no progress. Test all three actions and back navigation from the picker. |
| [#326, inconsistent Quick Check presentation](https://github.com/hebelmx/RubikXplorer/issues/326) | Physical Lessons show the result details and recovery actions. Solution Playback presents the capture control without equivalent result presentation. | Add a later-release story under Epic 10 referencing Story 10.7 (Quick Check and recovery). Share the result card and accessible certainty announcement. The Lesson routes the card actions through its step and recovery flow. Playback routes equivalent recovery actions through its own existing state transitions. Verify both hosts at a normal step and at completion with matches, probable mismatches, and unreadable results. |
| [#337, fixture labels copied from bad pre-fills](https://github.com/hebelmx/RubikXplorer/issues/337) | Story 10.27 will check existing groups offline. The debug Recorder can still save a new two-hold group with incorrect counts and can accept untouched side-face pre-fills. | Add a recorder follow-up linked to Story 10.27. Before finalizing a group, show each under- and over-counted colour and highlight pre-fills of over-counted colours. Refuse finalization until the counts are correct or a non-empty override reason is saved in the fixture manifest. Require an explicit confirmation or change for each pre-filled side-face sticker. Replay the 10 historical pre-correction groups and ensure each is refused. The offline piece check covers wrong stickers that happen to balance the counts. Test old manifests and incomplete or retaken holds. |

### Conditional proposal for saved calibration profiles

[#173, automatic calibration profiles](https://github.com/hebelmx/RubikXplorer/issues/173) already has a product-owner deferral. [#172, reuse a solve's calibration in Quick Check](https://github.com/hebelmx/RubikXplorer/issues/172) is closed after [PR #233](https://github.com/hebelmx/RubikXplorer/pull/233). The outstanding prerequisite is a cube-A-to-cube-B experiment on the physical devices. Promote the proposal only if that experiment shows harm and maintains zero false VERIFIED results. If promoted, revise FR-24 (Color Calibration) from one device calibration to bounded local profiles matched automatically, update the calibration glossary and reset behavior, and specify the database migration and single writer in the architecture update. Require an unmatched profile to offer calibration rather than report certainty. Queue implementation after the experiment and a separate approval.

## 5. Handoff and approval

The product owner reviews this draft and chooses which stories to track. The planning owner then records approved story and experience edits through BMAD. Developers implement the accepted stories and publish regression evidence. The device session owner records the live scan, TalkBack, and solver benchmark results. Link each accepted story to its GitHub issue and reconcile issue closure after verification.

The decision pending on this draft is whether to track the seven proposed changes. The calibration profile proposal remains conditional on the cross-cube evidence.
