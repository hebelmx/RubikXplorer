# Reality-check review: tech claims in the 2026-10-31 spine update

- **Target:** `ARCHITECTURE-SPINE.md` diff against `main` (branch `docs/architecture-ad6-step-done`)
- **Lens:** were the changed tech claims verified (by the spike, the code or the web), or asserted from memory?
- **Date:** 2026-10-31
- **Claims checked:** Stack row "3D rendering", Stack row "Reference devices", the Deferred bullet on GL frame timing, and the tree's `render/` line. I also grepped the whole spine and `docs/ARCHITECTURE.md` for leftover SceneView/Filament mentions.

## Verdict

The rendering decision is well grounded: the Story 1.8 spike measured it, and the spike code matches the Stack row exactly. Two of the new claims overstate their evidence, though. The FrameMetrics fallback does not work on the chosen `GLSurfaceView`; the spike only proposed it for the TextureView path. And neither reference device can run API 29/30, so minSdk 29 has no physical-device coverage. `docs/ARCHITECTURE.md` still names SceneView in three places.

## Evidence

| Claim | Source checked | Result |
|---|---|---|
| Custom OpenGL ES 3.0, no dependency | `docs/spikes/oq-3-3d-rendering.md`: decision table; OpenGL ES APK delta +65,672 B; SceneView +30,390,600 B universal (about +10.68 MB arm64-only) against a 10 MB limit | Verified (measured) |
| on `GLSurfaceView` | `app/src/spike3d/kotlin/.../GlesCubeRenderer.kt`: `GLSurfaceView(context)` inside `AndroidView`, `setEGLContextClientVersion(3)`, `GLES30.*` calls throughout; `app/src/spike3d/AndroidManifest.xml` sets `glEsVersion="0x00030000"` | Verified (the code matches) |
| "no dependency" | `gradle/libs.versions.toml:17-18,34` still declares `sceneview = "4.63.0"`; `app/build.gradle.kts:50-64` adds it only when `-Prubikxplorer.spike3d=true` | True for the default build: the spike proved its release APK is byte-identical to a build without the spike. The catalog entry is leftover spike scaffolding (see F-4). |
| Production `render/` | `app/src/main/kotlin/dev/rubikxplorer/app/render/` contains only `CubeRenderer`, `MoveIntent`, `Net2DRenderer`, `PresentationItem` and `RenderStyle`. There is no GL renderer yet (Epic 5). | The tree line states a target, not current code. That is fine for a spine. |
| Tab S7 FE 5G supports GLES 3.0+ | Web: Snapdragon 750G with Adreno 619, which supports OpenGL ES 3.2 and Vulkan (Notebookcheck) | Verified |
| S24 FE supports GLES 3.0+ | Web: Exynos 2400e with Xclipse 940 (an underclocked Exynos 2400), which supports OpenGL ES 3.2 and Vulkan 1.1 (Notebookcheck, SamMobile) | Verified |
| Device OS compared with minSdk 29 / targetSdk 36 | Web: Tab S7 FE launched in 2021 on Android 11 (API 30) and was updated to Android 13, with a reported One UI 6.1 rollout (Android 14). S24 FE launched in 2024 on Android 14 / One UI 6.1.1, with 7 OS updates promised. | Neither device can run API 29 or 30 today (see F-2) |
| "Macrobenchmark does not see GL surface frames" | Android docs: `FrameTimingMetric` measures CPU time "across both the main application UI thread and the … RenderThread" and reports `frameOverrunMs` on API 31+ only. `TraceSectionMetric` (experimental) measures any `Trace.beginSection` / `trace {}` section. | Partly true (see F-3) |
| "…uses an in-app frame log or `FrameMetrics`" | `FrameMetrics` / `Window.addOnFrameMetricsAvailableListener` report the window's HWUI view-hierarchy frames. A `SurfaceView` (and so a `GLSurfaceView`) renders into its own surface from its own GL thread, outside those frames. The spike's own follow-up says "use `FrameMetrics` **on the TextureView path**". | Wrong for the chosen view (see F-1) |

## Findings

### F-1 [High]: the FrameMetrics fallback does not measure a GLSurfaceView

The Deferred bullet offers "an in-app frame log or `FrameMetrics`". The spine fixes `GLSurfaceView`, which is a SurfaceView. `FrameMetrics` only times the window's HWUI frames, so it would report a smooth UI while the GL thread drops frames. The spike made this suggestion only for "the TextureView path", and the spine dropped that qualifier. Story 1.10 could pick `FrameMetrics` and pass NFR-2 on numbers that don't include the cube at all.

**Fix:** In the Deferred bullet, replace "an in-app frame log or `FrameMetrics`" with: "the in-app per-frame log from the GL thread (`onDrawFrame` intervals, as in the Story 1.8 `Spike3d` log), optionally wrapped in a `Trace` section so Macrobenchmark `TraceSectionMetric` can collect it. `FrameMetrics` applies only if the renderer moves to `TextureView`."

### F-2 [Medium]: the reference devices leave minSdk 29 (and API 30) without physical coverage, and the PRD assumption is now contradicted

The PRD §5 assumption is that the "minimum reference device is a mid/low-range phone from about 2020, running Android 10". NFR-13 sets minSdk 29. The named minimum device is a 2021 tablet that shipped on Android 11 and is now on Android 13/14. The mid device runs Android 14 or later. So nothing physical exercises API 29/30 behaviour (for example scoped storage, CameraX quirks, or older GLES drivers). The spine also uses a tablet as the minimum for a phone-first, portrait/landscape app. The memlog does record "neither truly low-end", but the spine does not say what that means for NFR-1..3, and the PRD-wins rule means its §5 assumption needs an explicit note.

**Fix:** Add one sentence to the Reference devices row or the Deferred bullet: "API 29/30 coverage is emulator-only (the `spike3d_api29` AVD); neither device is a phone from about 2020 on Android 10, which supersedes the PRD §5 assumption (PO decision 2026-10-30)." Then either raise this with the PO as a PRD §5 / NFR-13 follow-up, or add a cheap API 29 phone to Story 1.10.

### F-3 [Low]: "Macrobenchmark does not see GL surface frames" is broader than the evidence

`FrameTimingMetric` does miss GL-thread frames, because it reads the UI thread and RenderThread. Macrobenchmark as a whole can still time them through `TraceSectionMetric` if `onDrawFrame` emits a trace section. This claim came from a spike observation on an emulator, and the spike did not check the docs.

**Fix:** Change the wording to "Macrobenchmark's `FrameTimingMetric` does not see GL-thread frames". If CI timing is wanted, mention `TraceSectionMetric` over a traced `onDrawFrame` (see F-1).

### F-4 [Low]: SceneView/Filament leftovers outside the spine

- `docs/ARCHITECTURE.md:31` still says `Renderer: 2D net → 3D SceneView`.
- `docs/ARCHITECTURE.md:188` still lists `SceneView 4.62 (Filament)` under "Stack (verified October 2026)".
- `docs/ARCHITECTURE.md:194` still lists "SceneView vs. custom OpenGL ES renderer" as open. Line 196 still lists "Reference devices … end of week 2" as open.
- `gradle/libs.versions.toml:17-18,34` and `app/build.gradle.kts:50-64`: the `sceneview` catalog entry and the spike wiring are still present. They are flag-gated and harmless. The spike says they are removed when the spike code is deleted, but nothing tracks that deletion.

The spine itself is clean: grep finds no SceneView or Filament mention left in `ARCHITECTURE-SPINE.md`.

**Fix:** Update `docs/ARCHITECTURE.md` §1 diagram, §11 and §12 to match the spine, in the same PR or a linked one. Add a tracked task (Story 1.10 or Epic 5's first story) to delete `src/spike3d*`, the `sceneview` catalog entries and the gradle block.

### F-5 [Low]: the production manifest does not yet declare GLES 3.0

Only the spike manifest has `<uses-feature android:glEsVersion="0x00030000" android:required="true"/>`. `app/src/main/AndroidManifest.xml` does not. As far as I know, the Android 10 CDD does not require ES 3.0 on every device, so without the declaration Play would offer the app to devices where the 3D view fails. The spine's Stack row should make the declaration part of the decision, so Epic 5 doesn't forget it.

**Fix:** Append "; manifest declares `glEsVersion 0x00030000` (required)" to the 3D rendering row, or put it in AD-4's 3D adapter notes.

### Note (not a finding)

PRD NFR-2 requires at least 30 FPS during face turns **and View Rotation**. The spike measured turns only. Story 1.10's re-measure should include View Rotation. The "p90 above 33.3 ms reopens" trigger in the Deferred bullet should also cover View Rotation.

## What was verified rather than asserted

- The engine choice, the APK deltas, the emulator frame times and the `GLSurfaceView`/GLES 3.0 choice all trace to measured spike output and to code. This is the strongest-evidenced row in the Stack.
- The devices' GPU API levels (ES 3.2 on both) and OS versions were web-checked here and support the GLES 3.0 requirement.
- The frame-timing tooling claim was not web-checked by the author. Docs only partly support it, and they contradict the `FrameMetrics` half for `GLSurfaceView`.

## Sources

- [Capture Macrobenchmark metrics (Android Developers)](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)
- [FrameTimingGfxInfoMetric (Android Developers)](https://developer.android.com/reference/androidx/benchmark/macro/FrameTimingGfxInfoMetric)
- [FrameMetrics (Android Developers)](https://developer.android.com/reference/android/view/FrameMetrics)
- [Galaxy Tab S7 FE Android 13 update (SamMobile)](https://www.sammobile.com/news/galaxy-tab-s7-fe-android-13-update/)
- [Galaxy Tab S7 FE One UI 5.1 (GSMArena)](https://m.gsmarena.com/samsung_galaxy_tab_s7_fe_galaxy_tab_a8_10_5_one_ui_5_1_update-amp-58081.php)
- [Galaxy S24 FE launched with Exynos 2400e (SamMobile)](https://www.sammobile.com/news/galaxy-s24-fe-launched-exynos-2400e-chip-worldwide/)
- [Galaxy S24 FE: 7 OS updates (FoneArena)](https://www.fonearena.com/blog/436423/samsung-galaxy-s24-fe-price-specifications.html)
- [Adreno 619 vs Xclipse 940 (Notebookcheck)](https://www.notebookcheck.net/Adreno-619-vs-Xclipse-940-vs-Adreno-640_10584_12422_9446.247598.0.html)
