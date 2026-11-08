# Epic 8 Context: Use my real cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can follow Lessons and Solution Playback on a physical cube. The app tracks only an honest Expected State, advanced by the Learner's confirmations, and handles mismatches by asking questions instead of guessing. v1.0 ships camera-free: the PO deferred all camera recognition (the former Epics 10 and 11) to v1.1 on 2026-11-08, so Physical-cube Mode must work fully without a camera and must never promise a camera check. This epic delivers the product principle "never claim what you don't know" on real hardware, and it is the main path to the first physical solve.

## Stories

- Story 8.1: Physical-mode transitions in the reducer
- Story 8.2: Follow a Lesson on my real cube
- Story 8.3: Report a mismatch honestly without a camera
- Story 8.4: Leave and resume physical mode safely
- Story 8.5: Follow a Solution on my real cube
- Story 8.6: Confirm the first physical solve and prove no desync
- Story 8.7: Camera-free Stage 6 prompt

## Requirements & Constraints

- **Expected State is only predicted.** In physical mode the Learner turns the real cube, then taps *Done* or enters the Move actually made. `expected` changes only on a confirmation or an accepted Full observation. It is always labeled EXPECTED. View Rotation never changes Notation or `expected`.
- **Mode switching** works mid-Lesson without a restart. Switching to physical asks the Learner to confirm the cube matches or to enter it. *Scan it* stays hidden while camera flags are OFF.
- **Physical undo** reverts `expected` and asks the Learner to do the inverse turn on the cube.
- **Honest mismatch handling.** A physical error is *detected* only when the Learner enters a Move or provides a Full observation as evidence. Otherwise Certainty is *unknown*. The information-request order is Quick Check → which Move → one face → full re-entry. In v1.0 the Quick Check rung is skipped.
- **Physical Solution Playback.** Each *Done* advances one step. The final step says the cube *should* be solved and never asserts it as observed.
- **Correctness.** There must be zero silent desync between engine, rendering and Expected State across Moves, confirmations, undo, mode switches, pause, kill and resume. The end-to-end suite proves this.
- **Persistence.** The latest Checkpoint survives forced process death.
- **Localizability.** All Learner-facing text lives in content strings or Lesson data (English only).
- **Privacy/offline.** The v1.0 release manifest declares no runtime permission, not even `CAMERA`, and no `INTERNET`. Release must contain no camera wording and no Quick Check entry.

## Technical Decisions

- **One session owner.** `LearningSession` is held by `SessionHost` and uses a pure `reduce(state, event) → Transition(state, effects, presentation)`. The normative `session/TRANSITIONS.md` defines what each event mutates, whether it can be undone, and whether it clears redo.
- **Sync State Machine:** `Virtual`, `PhysicalAwaitingConfirm`, `PhysicalPredicted`, `PhysicalVerified`, `PhysicalMismatch`. The minimum rules are:
  - In physical mode, `virtual` mirrors `expected`.
  - `ModeSwitched` and `CheckpointRestored` are undo barriers and clear redo.
  - Any `PhysicalConfirmed` marks a prior `observed` as stale.
  - `ModeSwitched(virtual)` sets `virtual := expected`.
- **Observations and Certainty.** The observation is `PhysicalObservation = Full | Partial`. A `Full` comes only through `CubeValidator`, as with `EditorEntered`. A `Partial` is never completed from `expected`.
  - Certainty comes only from `tutor/Assess`, using one `CandidateEnumerator(expected, maxDepth, moveSet)`.
  - `VERIFIED` requires exactly one consistent candidate (the `Diagnosis.verified(...)` smart constructor). Otherwise the result is `LIKELY` or `UNKNOWN`.
  - Certainty is categorical only, never numeric.
  - On a predicted state, a Harmful Diagnosis is at most `LIKELY`.
- **Restore rule.** A restored physical Checkpoint always lands in `PhysicalAwaitingConfirm`, with `observed` cleared and no effects resumed.
- **Playback.** One shared `PlaybackController`. Solution notes come only from `MoveNote.of(move)`.
- **Text keys.** Text is `Text(key, args)` in `content/strings/<locale>.json`. `strings.xml` holds static navigation chrome only. The content test fails on duplicate, missing or argument-mismatched keys.
- **Feature flags.** A `FeatureFlags` port maps the closed enum (`CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION`, …) to `OFF|BETA|GA`, set per build type in `build-logic`. Camera flags are OFF in release and GA in debug. No feature reads `BuildConfig` directly. Camera-dependent copy and entries are chosen from `CAMERA_SCAN`.
- **Build-enforced privacy.** A CI manifest check (`ManifestPolicy` in `build-logic`) enforces offline and privacy rules. Story 8.7 makes it fail on any runtime permission in the release manifest. Debug may still declare `CAMERA` for the opt-in spike source set.

## UX & Interaction Patterns

- **Physical controls:**
  - **Done** is always primary, even under a recovery prompt.
  - The secondary *I did something else* reveals the Move key bar.
  - The overflow menu holds *My cube doesn't match* and *I'm not sure what I did*.
  - The *Quick Check* camera icon appears only when `CAMERA_SCAN` ≠ OFF.
- **Frame and chip:**
  - The frame uses a dashed EXPECTED label for predicted states, with no Certainty chip until there is evidence.
  - The chip shows ✓ *Verified*, ≈ *Likely* (with the candidate shown) or ? *Unknown*. It can be tapped to explain its evidence and never shows a percentage.
- **Objective met (physical):** "If your cube matches the picture, this step is done." The frame stays EXPECTED, *Next* is enabled, and there is no celebration.
- **Mismatch ladder (v1.0):**
  1. "Which turn did you make?" with Move keys.
  2. "Look at the front face — which corner is white?" with a one-face picker.
  3. "Let's re-enter your cube" via the Manual Cube Editor.

  Each rung stays ? *Unknown* until it yields evidence.
- **Back to virtual:** "The on-screen cube now matches what we expected on yours." Undo stops there.
- **Resume after kill (physical):** the Confirm-your-cube card appears with *It matches* / *Enter it*.
- **First solve (end of Stage 6, physical):**
  - **v1.0 (`CAMERA_SCAN` OFF):** "Your cube should be solved now. Compare it with the picture, then tell me if it is." It offers *Yes, it's solved* and *I did something else*.
  - **`CAMERA_SCAN` BETA/GA:** "Your cube should be solved now. Check it with the camera, or tell me if it is."

  The milestone (≤ 1.2 s color burst, static under reduce motion) plays only after *Yes, it's solved* in v1.0.
- **Flag OFF behavior.** A flag set to OFF hides the entry point and every cross-link, with no dead buttons. The manual route is always one tap away.

## Cross-Story Dependencies

- Stories 8.1–8.6 are done. Story 8.7 builds on 8.6:
  - It splits the merged camera-promising key `msg.physical.first-solve-prompt` into two content-string variants. `ShippedContentTest` must cover both.
  - Any `strings.xml` duplicate must be removed or aligned.
  - The 8.6 milestone gating must stay unchanged.
- Story 8.7 must merge before Story 7.8's pilot build. Once the manifest check is live, 8.7 also removes the `AGENTS.md` Policy line on v1.0 runtime permissions.
- Builds on the Manual Cube Editor (Epic 3), Assess/Diagnosis/Guided recovery (Epic 4), Checkpoints and snapshots (Epic 6), and Stage 6 content and milestones (Epic 7).
- The Quick Check rung, the *Scan it* option and scan-based Full observations belong to the v1.1 camera work (`post-launch/v1.1-camera-recognition.md`). Story 12.3 relies on the zero-runtime-permission manifest that 8.7 enforces.
