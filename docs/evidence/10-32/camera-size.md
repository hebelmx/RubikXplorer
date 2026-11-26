# Story 10.32 Evidence — Continuous Quick Scan screen: camera size and pending device gate

**Date:** 2026-11-25
**Scope:** Story 10.32 (continuous Quick Scan live viewfinder, growing net, Guided fallback and Review hand-off; removal of the Quick-Scan-only two-corner code).

## 1. `:app:checkCameraSize` (NFR-11, `< 30,000,000 B`)

| Measure | Before (Story 10.31 baseline, `docs/evidence/10-31/litert-v2-int8-benchmark.md`) | After (Story 10.32) | Difference |
| --- | ---: | ---: | ---: |
| Debug camera classes (excluding `recorder/**`, `sticker/**`, `corner/**`) | 1,060,442 B | 1,151,016 B | +90,574 B |
| Classes as counted by the gate (× 3) | 3,181,326 B | 3,453,048 B | +271,722 B |
| CameraX + LiteRT runtime jars and arm64-v8a native libraries | 11,697,783 B | 11,697,783 B | 0 B |
| Camera model asset (`sticker_centers_v2_int8.tflite`) | 378,288 B | 378,288 B | 0 B |
| Default release estimate (`:app:checkReleaseSize`, camera flags OFF) | 13,025,179 B | 13,025,179 B | 0 B |
| **Projected camera release size** | **28,282,576 B** | **28,554,298 B** | **+271,722 B** |
| Headroom to the 30,000,000 B budget | 1,717,424 B | 1,445,702 B | −271,722 B |

Verdict: **PASS** (`Forbidden artifacts: none`, `LiteRT allowed: true`). After the follow-up review patches of 2026-11-25 the gate was run again: camera classes 1,151,930 B (+914 B), projected size 28,557,040 B, headroom 1,442,960 B, still **PASS**; the table shows the first measurement. The "before" column is the figure recorded for Story 10.31; it was not re-measured in this run.

### The camera class bytes are above the 10.31 baseline, by 90,574 B

The story's acceptance criterion says `:app:checkCameraSize` "confirms reclaimed class-byte headroom". The gate passes, but it does **not** show reclaimed headroom: counted camera class bytes are **higher than the 10.31 baseline, by 90,574 B** (8.5%), because the new screen adds more counted bytes than the two-corner removal takes out of the counted package. This is why.

- The gate counts only `dev/cubentia/app/camera/**` classes of `:app`. Most of the two-corner code this story removes (`QuickScan.kt`, `QuickScanDiagnosis.kt`, most of `CornerHold.kt`) lived in `:core:domain`, which this measure does not count, so its removal does not show up here.
- The new screen is counted. Its classes come to 129,068 B:

| New classes | Debug class bytes |
| --- | ---: |
| `ContinuousQuickScanScreenKt` and its lambdas (Compose screen: preview outlines, net, controls, both layouts, haptics) | 69,723 B |
| `ContinuousQuickScanFlow` and companions (screen rules, hand-over review) | 30,570 B |
| `OutlineKey`, `TrackedOutline`, `CountAnnouncement` | 9,438 B |
| `ContinuousScanEngine` port, factory, optional-binding module, `ContinuousFrame`, `ContinuousOverlayQuad` | 16,658 B |
| `PipelineEngine` (continuous source set adapter) | 2,679 B |
| **Total** | **129,068 B** |

- The classes that already existed are about 38,500 B smaller in total (the bytes the removal reclaimed in the counted package): the two-corner branches of `GuidedScanActivity`, `GuidedScanViewModel`, `GuidedScanScreenKt` and `GuidedScanFlow`, the corner overlay smoother, and the cube-outline metering, settle signal and exposure-lock code of `CameraXFrameSource` (only the removed corner capture used them) are gone, less the continuous wiring added to the same classes. The settle and exposure-lock helpers the debug fixture recorder shares (`SettleTracker`, `ExposureLocks`, the metering-region functions) stay.
- These are debug class bytes counted three times, a deliberately pessimistic projection. The real minified 0.11.0-profile size was re-measured: `-Pcubentia.release011=true :app:checkRelease011Size` gives **26,261,058 B** installed (AAB 16,643,555 B, universal APK 13,170,750 B, dex 4,312,236 B; headroom 3,738,942 B), against 26,079,010 B at Story 10.31 (+182,048 B). `checkRelease011ElfAlignment`, `checkRelease011MergedManifest`, `checkRelease011CameraClasspath` and `checkRelease011ShadowReader` pass.

### After the *Review and Correct* addition (PO clarification Q8, 2026-11-25)

The gate was run again after the mid-scan *Review and Correct* action was added (the action on the continuous screen, its availability rule, the early Scan Review with its Back and Close rules), and once more after that addition's review patches (one more argument on the debug run line; everything else in the patches is tests and comments). The table shows the last measurement:

| Measure | After the follow-up review pass | After the *Review and Correct* addition | Difference |
| --- | ---: | ---: | ---: |
| Debug camera classes (excluding `recorder/**`, `sticker/**`, `corner/**`) | 1,151,930 B | 1,177,683 B | +25,753 B |
| Classes as counted by the gate (× 3) | 3,455,790 B | 3,533,049 B | +77,259 B |
| Default release estimate (`:app:checkReleaseSize`, camera flags OFF) | 13,025,179 B | 13,025,179 B | 0 B |
| **Projected camera release size** | **28,557,040 B** | **28,634,299 B** | **+77,259 B** |
| Headroom to the 30,000,000 B budget | 1,442,960 B | 1,365,701 B | −77,259 B |

Verdict: **PASS** (`Forbidden artifacts: none`, `LiteRT allowed: true`). Against the Story 10.31 baseline the counted camera classes are now +117,241 B (1,060,442 B → 1,177,683 B). The addition's bytes are in `ContinuousQuickScanScreenKt` (the action and the paired last row of a short landscape panel), `ContinuousQuickScanFlow` (the rule and its two named fractions), `GuidedScanActivity` / `GuidedScanViewModel` (the tap, the return and the run-line argument) and `GuidedScanPhase.Review` (the early mark). Before the review patches the figure was 1,177,531 B (projected 28,633,843 B).

The default release estimate was measured again in the same build, not copied: `app/build/reports/cubentia/release-size.txt` was rewritten with AAB 6,345,280 B, universal APK 3,439,603 B, uncompressed dex 3,143,992 B, installed estimate 13,025,179 B. It is byte-identical to the earlier figures although the release now carries one more `en.json` string (the rebuilt APK holds it) and changed `ScanReview` / `ScanReviewScreen` code. Why the lengths did not move was not investigated; with the camera flags OFF the Scan Review code is not reachable in that build.

The minified 0.11.0-profile size was re-measured after the review patches: `-Pcubentia.release011=true :app:checkRelease011Size` gives **26,275,170 B** installed (AAB 16,649,451 B, universal APK 13,170,750 B, dex 4,316,940 B; headroom 3,724,830 B), +14,112 B over the 26,261,058 B measured before the addition. `checkRelease011ElfAlignment`, `checkRelease011MergedManifest`, `checkRelease011CameraClasspath` and `checkRelease011ShadowReader` pass.

**Second review pass of the addition (2026-11-25).** This pass changed tests, comments and this file only. `./gradlew spotlessCheck build :app:checkCameraSize :core:domain:compileEvaluationKotlin` was run again, and both size tasks executed in that build (neither was up-to-date; both report files were rewritten):

- `:app:checkCameraSize`: camera classes 1,177,689 B (+6 B; only comments changed in the counted classes), projected 28,634,317 B, headroom 1,365,683 B, **PASS**. The table above shows the measurement before this pass.
- `:app:checkReleaseSize`: AAB 6,345,276 B (−4 B), universal APK 3,439,603 B, uncompressed dex 3,143,992 B, installed estimate 13,025,179 B.
- Two lengths that did not move are recorded here and were not investigated: the default release's universal APK and dex are the same as before the addition, and the 0.11.0-profile universal APK (13,170,750 B) is the same before and after the addition although its dex grew by 4,704 B. Both installed estimates add three times the dex to the APK length, so the 0.11.0 estimate did move with the code (+14,112 B = 3 × 4,704 B).
- The 0.11.0 profile was **not re-measured** in this pass.

## 2. Other checks run for this story

- `./gradlew spotlessCheck build`: success (lint, JVM and Robolectric tests, Kover, `checkLearnerText`, boundary and manifest checks, debug and release APKs).
- `./gradlew :core:domain:exportKotlinStickerReads --dry-run :core:domain:evaluateQuickScan --dry-run` and `./gradlew :core:domain:compileEvaluationKotlin`: both tools still registered and compiling.
- No code reference to `QuickScanController`, `QuickScanDiagnosis` or `QuickScanEffect` is left in `app` or `core`.

## 3. Legacy replay retired by this story — how to reproduce it

This story deletes the two-corner burst replay with the code it exercised (`CornerHold`'s Quick Scan steady rule): `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanBurstReplayTest.kt`, its loader `RecordedBursts.kt` in the same directory, and the `quickScanBursts` test input of `core/domain/build.gradle.kts` (the `:core:domain:test` input on `docs/evidence/quick-scan-2026-11-22/bursts`).

- **Last revision that has them:** `52b66aefa63ab02f8ae6b08cc6a6e5d769756be8` (the parent of this story's implementation commit). `git ls-tree -r --name-only` at that revision lists both files, and its `core/domain/build.gradle.kts` declares the input.
- **Command, at that revision** (same environment as that revision's `AGENTS.md`):

  ```sh
  git worktree add ../replay-10-22 52b66aefa63ab02f8ae6b08cc6a6e5d769756be8 && cd ../replay-10-22
  export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk
  ./gradlew :core:domain:test --tests 'dev.cubentia.domain.recognition.QuickScanBurstReplayTest'
  ```

  The command was **not re-run** in this pass; the numbers the test asserts are the ones `docs/evidence/10-22/README.md` derives its thresholds from.
- **The recorded bursts stay in the repository**: the five `.jsonl` files under `docs/evidence/quick-scan-2026-11-22/bursts/` are not removed. Their remaining consumers are the `quickScanBursts` test input of `app/build.gradle.kts`, `ScanFrameSourcePolicyTest` (the settle replay reads `20261122-114155-still.jsonl`) and the source inventory of `tools/sticker-model/assemble.py`.

## 4. Pending — not claimed by this run

- **Human gate:** hands-on pass from the Solve tab's Quick Scan button on the Lenovo TB128XU and the Galaxy S24 FE (debug build, `QUICK_SCAN` at BETA): outlines in portrait and landscape, growing net, cues, haptics, TalkBack, torch (S24 FE only; the Lenovo has no flash), Reset, *Scan face by face*, *Enter colors instead*, completion into Scan Review and *Accept*. Since the Q8 addition, also *Review and Correct*: when it appears and hides (and whether the other buttons moving at that moment causes a wrong tap), the early Scan Review opening on the first conflicted sticker, filling and correcting stickers and *Accept* back to the Solve tab, a per-face retake there followed by Back, Back to the live scan, *Close* asking first, the action under a latched layer twist, TalkBack over the new action, 200% font, and the landscape-phone panel where the action shares the last row with *Enter colors instead* (there, also whether the action hiding makes a tap land on *Enter colors instead*, which leaves the scan for the Editor without asking). Also turning the cube while the early Scan Review is open and then pressing Back: whether the first frames after the pause misplace a face or start a layer-twist cue. **Pending.**
- **Story 10.33:** the physical-device matrix and the product owner's BETA call. **Pending.**
- No frame latency was measured in this story; the per-frame pipeline numbers remain those of Story 10.31.
