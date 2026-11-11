# Spike OQ-3: choose the 3D rendering approach

Story 1.8. Throwaway prototypes of the `CubeRenderer` port in SceneView and in custom OpenGL ES 3.0, measured against the decision rule in the story spec. Spike code compiles only with `-Pcubentia.spike3d=true`. Story 1.11 removed the SceneView prototype code (its source sets, the `cubentia.spike3d.sceneview` switch and the `sceneview` catalog entry) after reference-device timing confirmed OpenGL ES; the SceneView numbers below are kept as the historical record, and the flags they name no longer exist.

**Decision: use custom OpenGL ES 3.0 for the Epic 5 3D renderer.** SceneView fails hard criterion 3 (release APK delta ≤ 10 MB): it adds 30,390,600 bytes to the universal release APK and about 10.7 MB even to an arm64-only APK. The OpenGL ES prototype passes all four hard criteria and adds 65,672 bytes. A `bmad-architecture` update is requested to replace the spine's `SceneView 4.62.0 (Filament 1.72.x) [ASSUMPTION: OQ-3 spike]` row.

## Time box

| | |
|---|---|
| Assumed budget | 3 days (week-2 spike) |
| Start | 2026-10-30 02:15 CST (`chore(sprint): start story 1.8`, 8b66dd5) |
| End | 2026-10-30 03:00 CST (about 45 minutes; review fixes after that) |

## Environment and devices

- Host: Linux (Kali, kernel 7.1.5), 20 cores, 62 GB RAM, KVM. Intel Iris Xe (ADL GT2, Mesa 26.1.6) drives the display; an NVIDIA RTX A2000 is also present.
- Toolchain: JDK 25 running Gradle 9.8.2, AGP 9.5.0, Kotlin 2.4.21, compileSdk 37 / minSdk 29.
- SceneView: `io.github.sceneview:sceneview:4.63.0`, the latest version on Maven Central on 2026-10-30 (published 2026-10-29). Filament 1.72.1, gltfio 1.72.1, filament-utils 1.72.1, kotlin-math 1.8.0. Its API was checked against `sceneview-4.63.0-sources.jar`, not from memory. `arsceneview` and ARCore are not used.
- Devices: `adb devices` listed **no physical device**. Frame time was measured on one AVD only:
  - `spike3d_api29`: `system-images;android-29;default;x86_64` (Android 10, the assumed minimum OS), pixel_4 profile (1080×2280, 60 Hz vsync), emulator 37.2.13.
  - Run 1, `-gpu host`: GLES calls are translated to the host Intel GPU.
  - Run 2, `-gpu swiftshader_indirect`: CPU software rendering. This is a stress proxy only, not a device.
- **All frame-time numbers below come from an emulator. They are not reference-device evidence.**

## Measurements

Method: release build (R8 minify and resource shrinking, as in `release`) signed with the debug key. `Spike3dActivity` autorun does 40 alternating R / R' quarter turns 500 ms apart, then one burst of 4 queued turns. Each turn logs its frame intervals under the `Spike3d` tag. Both engines take the frame time from `System.nanoTime()` on their own render callback. "Worst p90" is the highest p90 of any single turn, the strictest reading of criterion 4.

### Frame time during a 250 ms quarter turn (emulator only)

| Engine | GPU mode | Turns | Frames | p50 (median turn) | p90 (median turn) | p90 (worst turn) | p99 (worst turn) | Max | Frames > 33.3 ms | Turn elapsed (median) |
|---|---|---|---|---|---|---|---|---|---|---|
| OpenGL ES 3.0 | host | 44 | 669 | 16.65 ms | 17.10 ms | 17.93 ms | 25.40 ms | 25.40 ms | 0 | 251.5 ms |
| SceneView 4.63.0 | host | 44 | 674 | 16.63 ms | 17.23 ms | 18.40 ms | 27.79 ms | 27.79 ms | 0 | 265.1 ms |
| OpenGL ES 3.0 | swiftshader | 44 | 671 | 16.66 ms | 17.15 ms | 19.33 ms | 21.61 ms | 21.61 ms | 0 | 263.7 ms |
| SceneView 4.63.0 | swiftshader | 44 | 123 | 116.71 ms | 116.97 ms | 166.73 ms | 166.73 ms | 166.73 ms | 123 (all) | 334.1 ms |

**Limitations.** These numbers are intervals between frame callbacks, so they show vsync pacing, not each frame's CPU or GPU cost. On host GPU both engines meet vsync, so these rows cannot tell the engines' headroom apart. The engines were also set up differently. SceneView ran its default lit PBR pipeline with IBL and two directional lights. The OpenGL ES prototype uses a simple-lit vertex-color shader. That difference is part of the reason for the swiftshader gap. SwiftShader is excluded from "each measured device" in criterion 4; its rows are a stress note only.

On host GPU, both engines hold 60 FPS during the turn. Under software rendering, SceneView's default lit PBR pipeline (IBL, two directional lights) falls to about 8.5 FPS, and the fast-forwarded turn ran in a single 133 ms frame, so its animation was skipped. The OpenGL ES prototype stays at vsync. SwiftShader is not a phone GPU, but the gap shows how much per-frame work each engine does. Turn elapsed time is quantized to whole frames, so it reads 250 to 267 ms at 60 Hz.

### Release APK size delta

Measured as (spike release APK) − (default release APK) on the same commit. Both builds are R8-minified, universal (all ABIs) and unsigned. Sizes come from file size, and `apkanalyzer` gives the download size.

| Build | APK bytes | Delta bytes | Download size (apkanalyzer) |
|---|---|---|---|
| Default release (baseline) | 2,126,832 | 0 | 1,233,235 |
| Spike, OpenGL ES only (`-Pcubentia.spike3d.sceneview=false`, a switch removed in Story 1.11) | 2,192,504 | **+65,672** | 1,265,408 |
| Spike, SceneView + OpenGL ES | 32,517,432 | **+30,390,600** | 17,673,335 |

Where SceneView's bytes go (stored sizes inside the APK):

| Entry | Baseline | With SceneView |
|---|---|---|
| `lib/arm64-v8a` (libfilament-jni, libgltfio-jni, libfilament-utils-jni) | 10,096 | 6,720,584 |
| `lib/armeabi-v7a` | 7,252 | 5,154,348 |
| `lib/x86_64` | 10,760 | 7,229,400 |
| `lib/x86` | 9,284 | 7,353,176 |
| `assets` (default IBL/skybox `.ktx`, 15 `.filamat` materials; 10.9 MB uncompressed) | 7,041 | 3,006,350 |
| `classes.dex` | 1,491,064 | 2,383,024 |

Native libraries are stored uncompressed (minSdk 29). An arm64-only APK, which is what a Play split install would deliver, works out to about **+10,680,972 bytes** for SceneView. This figure is derived by removing the other three ABIs' library bytes. It is still above the 10 MB threshold. Part of the asset bytes (unused materials, the skybox) could be excluded, but that was not tried or proven. `gltfio` (about 3 MB per ABI) is a hard dependency of SceneView's loaders.

### Animation control

| | OpenGL ES 3.0 | SceneView 4.63.0 |
|---|---|---|
| Start | Code: `SpikeTurnDriver` starts a turn on the next `onDrawFrame`. | Code: the same driver, from the `SceneView(onFrame = …)` callback. |
| Per-frame progress | Yes. Progress = elapsed / 250 ms. Each cubie's model matrix is written as a uniform every frame. | Yes. Each cubie `Node.transform` is written every frame from the same matrices (`rotation(axis, θ) · base`). The spike did not use a pivot node, so SceneView's node-hierarchy and pivot animation was not exercised. |
| Completion | Exact: `commit()` snaps by an integer 90° matrix. Each turn logs `readbackOk=true`. | Same, with `readbackOk=true`. |
| Fast-forward (3 or more queued behind, as in `Net2DRenderer`) | Yes: a 62 ms turn (250 / 4, integer milliseconds), measured 67.2 ms (4 frames). | Yes: measured 66.5 ms (4 frames) on host GPU. Under swiftshader the turn finished in one frame. |
| Notes | Rendering and the queue run on the GL thread. `submit` and `renderedFacelets` are thread-safe through the driver lock. | `FrameRatePolicy.Continuous()` is required. The default `OnDemand` parks the loop, and its KDoc warns that `onFrame` cannot keep the loop awake. The default `SurfaceType.Surface` blanked the sibling Compose buttons on the API 29 emulator, so the spike uses `SurfaceType.TextureSurface`. |

Neither engine uses its own animation system. The engine only draws matrices that the shared driver produces, so control is the same in both and fully in code.

### `renderedFacelets()` feasibility

Feasible for both engines, and done the same way. `SpikeCubeModel` keeps 27 cubies, each with a home position, an exact integer 3×3 rotation, and stickers on local faces. `facelets()` rotates each sticker's normal to find its face, and rotates its position to find the row and column on the Kociemba net. It never echoes `PresentationItem.target`. The renderers draw from the same model, so the readback is the geometry that is drawn. JVM tests (`SpikeCubeModelTest`, 18 tests) cover:

- solved;
- all 12 quarter turns against `CubeState.SOLVED.apply(move)`;
- mid-animation (progress 0.5 still reads the pre-turn facelets);
- the mid-turn matrix;
- a 25-move random sequence with a pinned seed;
- the driver's 250 ms and fast-forward timing;
- Snap.

On the emulator, all 176 measured turns logged `readbackOk=true`.

### Default build isolation

- With the property unset, the release APK is 2,126,832 bytes. It is byte-identical (same SHA-256) to a release APK built from the pre-spike `app/build.gradle.kts` and `libs.versions.toml` with the spike source folders present.
- The flag-off release and debug APKs contain no SceneView, Filament or spike classes, no Filament native libraries, and no spike activity (`apkanalyzer dex packages` / `manifest print`).
- `./gradlew build --dry-run` lists no spike task.
- With `-Pcubentia.spike3d=true`, `checkReleaseMergedManifest`, `checkDebugMergedManifest`, `checkReleaseSdkDenylist` and `checkDebugSdkDenylist` pass, both with and without SceneView.

## Decision against the rule

Stress note (not criterion evidence): under swiftshader, OpenGL ES worst p90 was 19.33 ms and SceneView median p90 was 116.97 ms.

Rule: confirm SceneView unless it fails a hard criterion that the OpenGL ES prototype passes.

| Hard criterion | OpenGL ES 3.0 | SceneView 4.63.0 |
|---|---|---|
| 1. Quarter turn code-controllable per frame, with completion | Pass | Pass |
| 2. `renderedFacelets()` derivable from geometry | Pass | Pass |
| 3. Release APK delta ≤ 10 MB [ASSUMPTION: one-third of NFR-11's 30 MB] | **Pass** (+65,672 B) | **Fail** (+30,390,600 B universal; about +10.68 MB arm64-only) |
| 4. Frame-time p90 ≤ 33.3 ms during the turn on each measured device (the API 29 emulator, host GPU; swiftshader excluded) | Pass (worst 17.93 ms) | Pass (worst 18.40 ms) |

SceneView fails criterion 3 and OpenGL ES passes it, so the rule selects **custom OpenGL ES 3.0**.

Tie-breaks, for the record:

- **Epic 5 implementation effort.** The OpenGL ES prototype is about 280 lines: a shader pair, one VBO, and a matrix per cubie. SceneView took about 110 lines. Production adds lifecycle handling, View Rotation and hit-testing for gestures to both. OpenGL ES needs its own ray–cubie picking, which is about 30 lines since cubies are axis-aligned boxes in a known matrix. SceneView has a collision system. The cube model, the queue and the readback are shared and engine-independent.
- **Color-blind glyphs.** SceneView has `TextNode` and `ImageNode`. OpenGL ES needs a glyph texture atlas (6 letters, rendered once from IBM Plex Sans through `Canvas`) and a textured sticker shader. This is the biggest extra cost of OpenGL ES, and it is bounded.
- **Highlight and Demonstration arrows.** In OpenGL ES, a highlight is a color or emissive uniform per sticker, and an arrow is a small extruded mesh drawn with the cube shader. SceneView has `LineNode`, `PathNode` and material parameters. Both are straightforward.
- **Other SceneView costs found.** Its manifest adds `INTERNET` and `VIBRATE`; the AD-13 manifest gate fails the build until they are removed with `tools:node="remove"`, which the spike overlay does. It brings `fuel` (an HTTP client) onto the runtime classpath. Its 4.x API is Compose-first and moves fast (4.62 to 4.63 within the planning window).

## Reference-device re-measurement (Story 1.11)

Run on 2026-11-10 at commit `4befc1f`: release spike build (`-Pcubentia.spike3d=true
-Pcubentia.spike3d.sceneview=false`, R8 as in `release`) signed with the debug key; `Spike3dActivity`
autorun `--es engine gles --ei turns 40 --ez burst true` (40 alternating R / R' turns 500 ms apart,
then one burst of 4 queued turns, the last of them fast-forwarded). Both devices ran at 60 Hz. Same
method and readings as the emulator table above; "worst turn" is the strictest reading of criterion 4.

| Device | OS | Turns | Frames | p50 (median turn) | p90 (median turn) | p90 (worst turn) | p99 (worst turn) | Max | Frames > 33.3 ms | Turn elapsed (median) | `readbackOk` |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Galaxy Tab S7 FE 5G (SM-T738U, minimum) | Android 12, SDK 31 | 44 | 654 | 16.73 ms | 18.34 ms | **20.48 ms** | 27.47 ms | 27.47 ms | 0 | 251.9 ms | true (44/44) |
| Galaxy S24 FE (SM-S721U1, mid) | Android 17, SDK 37 | 44 | 670 | 16.48 ms | 19.73 ms | **23.64 ms** | 29.51 ms | 29.51 ms | 0 | 258.4 ms | true (44/44) |

"Median turn" is the lower median of the 44 per-turn values; "p99 (worst turn)" is the highest
per-turn p99. The fast-forwarded turn took 66.3 ms (4 frames) on the Tab S7 FE 5G and 65.7 ms
(4 frames) on the S24 FE.

**Verdict: OpenGL ES confirmed.** The worst-turn p90 is at or below 33.3 ms on both reference devices
(20.48 ms and 23.64 ms), so criterion 4 holds on physical hardware and the OQ-3 decision stands. No
frame exceeded 33.3 ms. The SceneView prototype code was then deleted. As in Story 1.8, these are
frame-callback intervals at 60 Hz vsync, so they show pacing, not each frame's GPU cost; the
production `Cube3DRenderer` is a separate renderer and was not measured here.

Not covered by this measurement:
- **View Rotation.** The spike renderer has face turns only, so View Rotation frame timing (also part
  of NFR-2) was not measured on either device.
- **The 60 FPS mid-range target.** These runs only test the 30 FPS (33.3 ms) rendering-choice
  threshold. On the S24 FE the median-turn p90 is 19.73 ms, above the 16.7 ms frame at 60 Hz, so
  this measurement does not show the 60 FPS target being met on the mid device.

Raw evidence (timing-only `Spike3d` logcat for each device, and the script that produced the table)
is in [`story-1-11-device-evidence/`](story-1-11-device-evidence/): run
`python3 spike3d_stats.py spike3d-gles-tab-s7-fe.log` to reproduce a row.

## How to reproduce

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk
# Readback, driver and percentile tests
./gradlew -Pcubentia.spike3d=true :app:testDebugUnitTest --tests 'dev.cubentia.app.spike3d.*'
# APKs (manifest and SDK-denylist checks are part of `check`; run them too)
./gradlew :app:assembleRelease                                                                       # baseline
cp app/build/outputs/apk/release/app-release-unsigned.apk baseline-release.apk                       # keep it; the next build overwrites it
./gradlew -Pcubentia.spike3d=true :app:assembleRelease :app:assembleDebug \
  :app:checkReleaseMergedManifest :app:checkReleaseSdkDenylist                                        # OpenGL ES spike
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey \
  --out spike.apk app/build/outputs/apk/release/app-release-unsigned.apk
# On each device (adb -s <serial> when several are connected; on an emulator see docs/emulators.md):
adb install -r spike.apk          # on a signature mismatch: adb uninstall com.exxerpro.cubentia, then install
adb shell am force-stop com.exxerpro.cubentia
adb logcat -c
adb shell am start -W -n com.exxerpro.cubentia/dev.cubentia.app.spike3d.Spike3dActivity --es engine gles --ei turns 40 --ez burst true
# wait for "autorun done", then:
adb logcat -d -s Spike3d:I   # one line per turn, then "autorun done"
```

The activity also has manual buttons: an engine selector (now only `gles`), R, R' and "×4 queued". Log lines carry a turn number `n=` because logd's chatty filter collapses identical lines. The Story 1.8 emulator runs used `emulator -avd spike3d_api29 -no-window -no-audio -no-snapshot -gpu host` (or `-gpu swiftshader_indirect`) on a `system-images;android-29;default;x86_64` AVD with the pixel_4 profile.

## Follow-ups

- **Story 1.10 / 1.11:** the Story 1.8 frame times came from an emulator. Story 1.11 re-measured the OpenGL ES prototype on both reference devices (above) and confirmed the decision; the SceneView prototype was not re-measured and its code is gone.
- **Architecture:** `bmad-architecture` update to replace the spine's 3D row ("SceneView 4.62.0 (Filament 1.72.x) [ASSUMPTION: OQ-3 spike]") with custom OpenGL ES 3.0 (no dependency), and to close the OQ-3 deferral. The `sceneview` version catalog entry and the SceneView source sets were deleted in Story 1.11.
- **Epic 5:** build the production 3D adapter on OpenGL ES 3.0 behind `CubeRenderer`:
  - Reuse the spike's cubie model, readback approach and driver semantics; the readback becomes the `renderedFacelets()` source of truth.
  - Add a glyph atlas for color-blind glyphs.
  - Add per-sticker highlight and arrow meshes.
  - Add axis-aligned picking for gestures.
  - Build lifecycle and context-loss handling. The spike did not exercise either: it never forwards `GLSurfaceView.onPause()` / `onResume()` and never deletes an old program or VBO.
  - Move logging behind the `Logger` port.
