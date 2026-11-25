# RubikXplorer sprint change proposal — record and replay continuous Quick Scan fixtures

**25 November 2026 · Draft for product-owner review · Planning only**

Keep the ability to record a failing scan and replay it offline when more evidence is needed. After the continuous Quick Scan pipeline is finished, migrate the developer recorder and its evaluation workflow to the same pipeline used by the app. Retire the old two-corner code only where that migration removes its last consumer.

## 1. Trigger and evidence

Story 10.23 (shadow sticker-reader comparison) used two distinct tools. The debug Android fixture recorder captures labelled examples. The offline Gradle task `exportKotlinStickerReads` reads those examples and calls `QuickScanClassification.report` and `.read` to produce the old baseline. Story 10.31 (continuous detector integration) already retired the shadow model and its Android hook. The capability to carry forward is capture and replay. [Recorder entry point](../../app/src/camera/debug/AndroidManifest.xml) · [Offline exporter](../../core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/sticker/KotlinStickerReads.kt) · [Story 10.23 evidence inventory](../../docs/evidence/10-23/inventory.md)

The recorder currently saves two prescribed holds. Those stills can test detection on an individual image, but they cannot reproduce the order in which faces appeared, a regrip or a conflict developing across frames. Story 10.32 (live continuous Quick Scan) explicitly limits its cleanup to code without a recorder or Case recognition consumer. That existing condition is sufficient for the current story. [Recorder export](../../app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt) · [Epic 10 acceptance criteria](epics.md#story-1032-continuous-quick-scan-live-viewfinder-growing-net-guided-fallback-and-review-hand-off)

## 2. Impact and sequencing

| Pipeline prerequisite | Evidence required before recorder implementation |
| --- | --- |
| Story 10.30 — continuous lattice fitting and cube mapping | Completed in the merged sprint record. |
| Story 10.31 — on-device sticker-center detector and pipeline integration | Completed in the merged sprint record. |
| Story 10.32 — live viewfinder, growing net and Guided fallback | Completed and merged, including its existing hands-on checks. Its story, branch and cleanup decisions are outside this proposal. |
| Story 10.33 — physical-device matrix and product-owner BETA decision | Completed, with the device report and recorded BETA or OFF decision. Either decision can justify later diagnostic fixtures. |

**Earliest start:** after all four stories are done. At handoff, check the merged sprint record for any subsequently added stories required to finish this pipeline. An open prerequisite keeps the recorder work deferred. This follow-up cannot gate the device matrix or the camera release. Story 10.33 evidence comes from the main app’s Solve tab. [Sprint record](../implementation-artifacts/sprint-status.yaml) · [Approved pipeline sequence](sprint-change-proposal-2026-11-24-continuous-quick-scan.md)

The change belongs in Epic 10 (camera recognition). The product requirement for continuous scanning and the learner experience already describe the desired pipeline. Architecture decision AD-9 (camera and frame handling) permits a debug-only fixture writer and prohibits persistence of learner frames in release and benchmark builds. The current debug exception describes a downscaled frame per face. For the future story, propose a narrow addition to the AD-9 frames paragraph allowing an operator to retain a bounded sequence of cube frames in local debug storage. This lets a future detector run against the same pixels. The writer belongs solely to the developer recorder. [Product requirements](prds/prd-RubikXplorer-2026-10-29/prd.md) · [Architecture decisions](architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md) · [Scan experience](ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md)

## 3. Recommended approach

Schedule one deferred follow-up in Epic 10. It covers the recorder, offline replay and retirement of verified dead code, in that order. Estimated effort is medium. Replay fidelity and shared legacy callers are the main risks. Reverting pipeline work would reintroduce the fixed holds. Revising the product requirements would add scope without addressing either tool. Fixture collection beyond the examples needed to verify the migration is driven by a specific evidence gap.

## 4. Proposed backlog change

**Today:** the completed recorder, label checks and shadow-reader evaluation describe their original fixture formats. There is no post-pipeline story tying continuous capture, offline replay and retirement together.

**Proposed addition after the dependency check:** *Record and replay a continuous Quick Scan session in the developer fixture recorder.* Assign the next available story number when scheduling. As a developer investigating a Quick Scan failure, I can deliberately record a scan while turning the cube, preserve the order of observations, and replay it against independently verified labels to reproduce where the result diverged.

| Acceptance | Demonstration |
| --- | --- |
| Use the app’s finished pipeline | Launch the existing debug recorder via its activity, start a session, turn the cube naturally, then stop or discard. Feed the same model, lattice fitter, sampler and stateful mapper used by the Solve-tab scan. Preserve Guided single-face fixture capture. With Case recognition disabled, continuous recording must work without loading the corner detector. |
| Produce a usable local fixture | A versioned group records the session ID, device, cube, lighting, model/build identity, frame order and timing, rotation/crop geometry, and sampled observations needed by the replay stage. For an image fixture, retain the ordered, bounded detector inputs with their preprocessing metadata so an updated model can be tested. Record any sampling or dropped frames. Preserve loss of tracking, empty observations and partial or failed sessions. Save to app-specific local storage only on operator request. An interrupted write cannot be mistaken for a complete group. Keep the writer out of release and benchmark artifacts. |
| Keep truth independent | Store operator-confirmed reference stickers separately from predicted colors, with unknowns explicit. Apply Story 10.28 (ground-truth quality checks) to a fully labelled group, including nine of each color and review provenance. A partial or deliberately invalid example stays available for diagnosis with an explicit outcome. Keep a capture session and its derivatives together in the chosen tuning or held-out partition. |
| Replay offline | Supply a documented replacement for the continuous use case of the old exporter. Replay ordered observations with a fresh mapper per session. For a known complete example, reproduce the recorded net in canonical face order and compare against operator truth. For a partial or failed example, reproduce its last confirmed net and cue or conflict without filling unknowns. For an image fixture, replay the saved inputs through the recorded detector version, check the detections against the captured baseline, then run the same fixture with a candidate detector. Report the replay starting stage, observed coverage and label mismatches. |
| Preserve historical evidence | Existing `Fixtures/live` and `Fixtures/corner` examples, held-out membership, historical reports and labels remain readable as their original formats. Document the archived revision/command for reproducing legacy scores. Verify at least one old fixture and one new continuous group before retiring a legacy command. |
| Retire only proven dead code | On the final merged pipeline, inventory Android, domain, evaluation, Gradle, fixture-decoder and test callers. Move each remaining consumer to its replacement or document its continuing owner. Remove or deprecate the old path only after the replacement capture and replay demonstrations pass. Verify Case recognition, Guided capture and old fixture decoding still pass. |

**Cleanup candidates to verify at execution.** The recorder uses `QuickScanPlan`, `SideLight` and two-corner capture helpers. `exportKotlinStickerReads` uses `QuickScanClassification`. Audit the separate `evaluateQuickScan` measurement task on its own inputs before retiring it. Case capture uses `CornerHold`; the camera adapter also has shared `CornerLocator`, `CornerReader` and `CornerGuide` consumers. A disabled release flag is insufficient evidence of dead code. Use the post-pipeline tree for the actual deletion list. [Recorder rules](../../app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt) · [Case capture](../../core/domain/src/main/kotlin/dev/cubentia/domain/recognition/CaseCapture.kt) · [Evaluation commands](../../core/domain/build.gradle.kts)

## 5. Handoff and review

- **Product owner** reviews this proposal as planning. After the dependency gate, identify the first real capture need from the matrix or a reproducible failure and select a representative complete and incomplete example.
- **Developer** rechecks callers and the final pipeline interface, implements capture and replay together, and records the operator procedure, fixture format and historical replay instructions. Validate the debug recorder on a physical device, exercise the compatibility fixtures, and run the repository build and privacy/source-set checks before removing the last legacy dependency.
- **Scope:** moderate, confined to developer tooling and its evaluation workflow. The proposed change to the backlog is one future story. Its operator instructions and format belong with the tooling when delivered. The learner PRD, learner UX, release settings and Story 10.32 require no edits for this plan.

**Correct Course checklist:** trigger and evidence reviewed; epic, architecture, UX and release impacts assessed; direct adjustment, rollback and product-scope alternatives evaluated; acceptance and handoff drafted. Product-owner review is pending. Scheduling and implementation are deferred.
