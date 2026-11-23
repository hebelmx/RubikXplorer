# Release device session: evidence

Session 2026-11-23, main `dfc5c3d6`, worktree `RubikXplorer-device-session-2` (branch `docs/device-session-2026-11`). Sitting order changed by the PO: D (Story 10.20 recording, Lenovo done, Galaxy pending) → Block 1 + Block 5 → rest. Agent drove both devices through adb (taps, UI dumps, screenshots, logcat); PO watched.

## Devices and builds

| Device | Android | Build under test | Notes |
|---|---|---|---|
| Lenovo TB128XU (USB `HA1S28MM`) | 13 | benchmark (release code, R8, debug-signed), `app-benchmark.apk` from `dfc5c3d6` | app data cleared by the Block 5 run (test APKs uninstall the app); files backed up first |
| Galaxy S24 FE (SM-S721U1, wireless adb) | 17 | benchmark, fresh install (uninstall + install, then `pm clear` before 1.1) | screen timeout and lock delay set to 24 h for the session (was 30 s / never-set); restore at the end |

Benchmark build checks: not debuggable (`pkgFlags=[HAS_CODE ALLOW_CLEAR_USER_DATA]`); `aapt2 dump permissions` lists only the app's own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.

## Block 1: Release build smoke (blocks #127)

| # | Result | Evidence |
|---|---|---|
| 1.1 App starts, content decodes under R8 | ✅ PASS (Galaxy) | Welcome after `pm clear` (`shots/1-1-galaxy-welcome-fresh.png`); Learn lists 7 Stages, 23 Lessons (3/5/4/4/4/3 + practice Stage 7), equal to `core/domain/src/main/resources/content/stages/*.json`. The run sheet's "12 Lessons" is stale. No crash buffer entries. |
| 1.2 A Stage 2 Lesson completes, progress kept after restart | ✅ PASS | Lenovo: Stage 1 *I already know this* check (scramble fixed with F) → Stage 2 *First cross* Lesson 1, *Guide me*, F2 → "Objective met" → ticked; after `am force-stop` + relaunch Stage 1 *Skipped*, Stage 2 *In progress*, Lesson 1 ticked, Lesson 2 unlocked. Galaxy portrait (1080 px @ 450 dpi = 384 dp, narrower than 411 dp): teaching-mode switcher fits inside the margins (`shots/1-2-galaxy-mode-switcher.png`); *Let me try* wraps to two lines. |
| 1.3 Works offline on release code | ✅ PASS (Lenovo, airplane mode, `Active default network: none`) | Learn, a Lesson check, Stage 2 Lesson, Library (OLL cards draw), *Enter colors* → one-move state → Accept → Solution Playback "1 move", U' plays to solved. No network message, no hang. Run on the Lenovo because the Galaxy's adb is wireless. |
| 1.4 No camera in release | ✅ PASS (both) | No Android permission requested at all (`dumpsys package`: none; `aapt2`: none but the app's own); Solve shows only *Enter colors*. |

S3 notes from Block 1: (a) Solution Playback's 3D view is blank for ~1–3 s on the Lenovo before the cube draws; (b) after a restart, Learn's *Continue* card points to the Lesson already finished instead of the next one; (c) the run sheet's Lesson count needs updating.

## Block 5: Automated device tests

| # | Result | Evidence |
|---|---|---|
| 5.1 `Cube3DRenderSyncTest` (Lenovo) | ❌ 1 of 4 fails | `tenThousandRandomStepsKeepTheCubeEqualToTheVirtualState` times out at 300 s twice (first run and isolated re-run). `physicalSwitchKeepsTheStepControlsAndQuickCheckEvidence` failed once ("No compose hierarchies found") and passed in isolation: flaky. The other two pass. JUnit: `block5/5-1-2-lenovo-junit.xml`, `block5/5-1-rerun-lenovo-junit.xml`. Galaxy not run yet. |
| 5.2 `EndToEndSyncVerificationTest` (Lenovo) | ✅ PASS | same JUnit file |
| 5.3–5.5 | ⏭ NOT RUN | |
