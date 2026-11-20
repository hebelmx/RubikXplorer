# Story 12.3 evidence: stability, size and privacy

Refs: NFR-5, NFR-8, NFR-9, NFR-11 · AD-13, AD-15, AD-16. Runbook for the human steps: [docs/release.md, "Before production"](../../release.md#before-production).

## AC1: size and privacy gates (local, 2026-11-20)

Run on the Kali dev host (JDK 25) at baseline `ae86a56e` plus this story's working tree, which changes only the androidTest harness, the host and CI scripts and docs (no `app/src/main`, manifest or dependency change).

| Command | Result |
|---|---|
| `./gradlew spotlessCheck build :app:checkReleaseSize` | BUILD SUCCESSFUL |
| `:app:checkReleaseSize` | Installed-size estimate 12,132,501 bytes, budget 30,000,000, headroom 17,867,499 (AAB 5,856,022; universal APK 3,175,101; uncompressed dex 2,934,600). Report copied to [release-size.txt](release-size.txt) |
| `:app:checkReleaseMergedManifest` (rerun) | Pass, empty violation report: no `INTERNET`, no runtime permission; the merged release manifest has `allowBackup="false"` and `dataExtractionRules`, and its only `uses-permission` is AndroidX's signature-level `com.exxerpro.cubentia.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| `:app:checkReleaseSdkDenylist` (rerun) | Pass, empty violation report: no denylisted SDK |
| `:app:checkPrivacyFacts` (rerun) | "Privacy facts match." ([docs/privacy-facts.json](../../privacy-facts.json): `internetPermission: false`, `runtimePermissions: []`, `denylistedSdks: []`, `dataLeavesDevice: false`) |

CI runs the same gates on every PR (`.github/workflows/ci.yml`) and on every release tag.

## AC2: Key Flows offline on an emulator (2026-11-20)

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
OFFLINE=1 EVIDENCE_DIR=docs/evidence/12-3/offline bash tools/run-keyflow-rotation.sh emulator-5554 avd-GalaxyS24FE_API36
```

AVD `GalaxyS24FE_API36` (Android 16, headless), debug build, each method from a cleared install (`pm clear`), airplane mode on for the whole run. Output: [offline/avd-GalaxyS24FE_API36/](offline/avd-GalaxyS24FE_API36/).

| Method | Key Flow | Result | Harness saw airplane mode on and no active network |
|---|---|---|---|
| `flow1FirstSolvePhysicalCube` | Flow 1: the Course | pass | `offline,flow=flow1,airplane=1,network=none` |
| `flow2TwoLookLastLayer` | Flow 2: Library / 2-look | pass | `offline,flow=flow2,airplane=1,network=none` |
| `flow3JustSolveIt` | Flow 3: cube entry and solve | pass | `offline,flow=flow3,airplane=1,network=none` |
| `flow6FiveMinuteEvaluation` | Flow 6: Library OLL, *Enter colors*, solve | pass | `offline,flow=flow6,airplane=1,network=none` |
| `flow7PracticeToMastery` | Flow 7 | pass | `offline,flow=flow7,airplane=1,network=none` |
| `flow8SkipAhead` | Flow 8 | pass | `offline,flow=flow8,airplane=1,network=none` |
| `flow9ResetProgress` | Flow 9 | pass | `offline,flow=flow9,airplane=1,network=none` |

`device.txt` records airplane mode before (0), during (1), after the run (1) and after restore (0, as found). Each flow's harness checks `settings get global airplane_mode_on` is 1 and that `dumpsys connectivity` shows `Active default network: none`. Also checked by hand: with `-e offline true` and airplane mode off, `flow8SkipAhead` fails with "flow8: device is not offline (airplane_mode_on=0)"; with airplane mode on but Wi-Fi turned back on, it fails with "flow8: network still active in airplane mode (Active default network: 104)"; and when the script fails after turning airplane mode on (missing APK), the EXIT trap restores it to 0 and exits 1.

The headless emulator's screen had gone to sleep after boot, which made the first attempt fail at "welcome never appeared" in every method. The tool now wakes the screen and dismisses the keyguard before the methods (best effort). One later attempt was cut short when the emulator itself crashed in its host-GPU layer (gfxstream, exit 139); the run recorded here is a clean rerun after a reboot.

Follow-up review rerun (2026-11-20) on AVD `GalaxyTabS7FE_API34` (Android 14, the same API level as the release-tag CI emulator), after the tool started waiting for no default network and waking the screen before each method: 7/7 pass, each logcat has `offline,flow=<flow>,airplane=1,network=none`, airplane mode restored to 0. Output: [offline/avd-GalaxyTabS7FE_API34/](offline/avd-GalaxyTabS7FE_API34/).

From now on every release tag runs the Key Flow suite offline (`.github/scripts/run-instrumentation.sh` sets `OFFLINE=1`; API 34 emulator).

The app declares no `INTERNET` permission, so this run shows the flows need no network (no wait on connectivity, Play services or downloadable resources), not that the app is blocked from one.

This is the debug build on an emulator. The release build on a physical reference device is a PO item below.

## PO items (human-only)

| Item | Ref | Date | Result / numbers | By |
|---|---|---|---|---|
| AC2 physical: fresh install of the release build from the Play internal or closed track on a reference device, airplane mode on, run the Course, Library, cube entry and solve, including cold start and resume | NFR-5 | | | |
| AC3: closed-testing build's Android vitals: user-perceived crash rate (<= 0.5%, read for NFR-9's >= 99.5% crash-free sessions), user-perceived ANR rate (below Play's bad-behavior threshold), crash and ANR counts, versionCode read | NFR-9, AD-16 | 2026-11-20 | **Pending.** Checked on the internal track (closed testing not active yet): "Not enough data", 0 crash and 0 ANR clusters; versionCode not recorded. Re-read on the closed-testing build before production | hebelmx (PO) |
| AC3: Play data-safety form declares "no data collected" and is saved against the registered privacy-policy URL (Story 13.6) | NFR-8, AD-13 | 2026-11-20 | **Pass.** Store listing preview shows "No data collection declared" and "No data shared with third parties"; privacy policy linked: https://hebelmx.github.io/cubentia-site/privacy/ | hebelmx (PO) |
