# PC spike vs app: continuous Quick Scan parity audit (2026-11-26)

Question (PO): the plan was to implement the app as close as possible to the PC spike, so that good PC results would carry over. Is it, and which differences could explain why the app recognises the cube worse?

Compared: the PC spike in `_bmad-output/planning-artifacts/research/technical-continuous-facelet-scan-2026-11-24/pc_spike/` (`run_real_video.py`, `lattice_fit.py`, `mapper.py`, `litert_center/eval_litert_center.py`), against `main` at 79c948e5 (`app/src/camera/continuous/`, `ScanFrameSource`, `core/domain/.../recognition/continuous/`). The comparison is by reading the code side by side; no clip was run through both.

## Summary

The algorithm survived the port almost intact. The detector model, peak decoding, lattice fitter, colour model, 8-per-colour assignment, tracking, regrip merge, global anchoring and invariant reopening are line-by-line ports with the same constants. The differences are concentrated in three places:

1. **What reaches the detector**: the crop shape, the resize, the colour conversion, and the missing center-square retry.
2. **Four small mapper deviations**, never re-validated.
3. **The conditions**: a phone camera at a lower analysed frame rate, against a 30 fps webcam.

Nothing yet runs the same footage through both pipelines, so none of these is measured. #413 is the experiment that decides.

Context for expectations: even on the PC, the spike's real-webcam benchmark (`README.md`, v2 INT8) completed 54/54 on 1 of 5 held-out clips and 3 of 10 tuning clips, with 4.7-4.8 of 6 sides on average. The PC is better, but it is not a finished reader either.

## Component by component

| Stage | PC spike | App | Same? | Issue |
|---|---|---|---|---|
| Model | `sticker_centers_v2_int8.tflite` | same file (SHA-256 d18fb2c2…) | yes | — |
| Detector input | full webcam frame (usually 4:3), BGR→RGB, Pillow `BILINEAR` letterbox (antialiased) | CameraX analysis crop shaped like the PreviewView (`setViewPort`), YUV_420_888 → fixed-point BT.601 full-range RGB, 2×2 bilinear letterbox with no antialiasing | **no** | #408 |
| Retry on tall/wide frames | fewer than 6 peaks and >1.35:1 → re-run on the center square, keep the better result | none | **no** | #410 |
| Peak decode | 3×3 local max, threshold 0.45, max 36 | same | yes | — |
| Interpreter threads | 4 | 2 (benchmark choice) | timing only | — |
| Lattice fit | `lattice_fit.py` | `FaceLatticeFitter`; a counter-clockwise projected quad is rejected instead of reversed (rare) | yes (minor) | — |
| Face sampling | `cv2.warpPerspective` (bilinear) on the full-resolution BGR frame | own warp straight from YUV: bilinear luma, nearest-neighbour chroma from the half-resolution planes; same ROIs and quality formulas | mostly | #408 (colour path) |
| Mapper | `mapper.py` | `ContinuousFaceletMapper`; 4 deviations (twisted-face observations, AWB bound, AWB on merge, attach guard in AWB space) | mostly | #411 |
| Frame rate | 30 fps webcam, every frame | keep-only-latest; Lenovo detector 33.7-49.3 ms plus fit/sample/map | **no** | #412 |
| Debug view | sticker dots, fitted quads, labels, counts, fps | face outlines only | **no** | #409 |
| Validation | 7 synthetic scenarios + 15 real webcam sessions | Kotlin re-implementation of the synthetic scenarios; detector peak parity on stills only; no shared golden replay | **no** | #411, #413 |

## Recommended order

1. #409 (sticker dots on device) and #406 (timings): cheap, and they make everything else visible.
2. #413 (same phone clips through both pipelines): it tells port problems from phone-input problems.
3. Then, as #413 shows: #410 and #408 for the input path, #411 for the mapper, #412 and #407 for motion and resolution.
