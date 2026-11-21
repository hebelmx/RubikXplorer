# Cubentia Quick Scan 7-Keypoint Corner Model (`tools/corner-model/`)

Reproducible training and evaluation pipeline for `app/src/camera/corner/assets/corner_keypoints.tflite` (Story 10.18, AD-9 / SCP 2026-11-20).

## Contract

- **Input:** `[1, 128, 128, 3]` `float32` RGB in `[0, 1]`, upright crop centered on `CornerGuide` with half-size `guide.radius * 1.65` (matching `CornerModelTransform.kt`).
- **Output:** `[1, 15]` `float32` in `(0, 1)`:
  - `output[0]`: presence probability (`>= 0.55` accepted).
  - `output[1..14]`: normalized `(u, v)` coordinates of the 7 visible cube corners `[T, UL, LL, B, LR, UR, C]`.
- **Post-processing:** mapped back into camera frame coordinates via `CornerModelTransform.cropToFrame`, verified by `CornerLocator.isValidCubeGeometry`, and smoothed by `CornerOverlaySmoother`.

## Requirements

- **Linux x86_64 with an NVIDIA GPU + CUDA:** `pyproject.toml` pins `tensorflow[and-cuda]`, whose CUDA wheels exist only for Linux. On macOS/Windows (or without a GPU) swap the dependency for plain `tensorflow` with the same version range; training then runs on CPU and is much slower, and the exported bytes may differ.
- Python 3.11–3.12 via `uv` (the committed model was built with Python 3.12.13, TensorFlow 2.19.1 and its bundled `TFLiteConverter`, NumPy 2.1.3, Pillow 11.3.0).

## Commands

Train and export `app/src/camera/corner/assets/corner_keypoints.tflite`:

```bash
uv run --project tools/corner-model python tools/corner-model/train_corner_model.py
```

Evaluate held-out synthetic 3D, real-palette warped `Fixtures/live/` frames, and dark/glossy & empty negatives:

```bash
uv run --project tools/corner-model python tools/corner-model/evaluate_model.py
```

## Real corner fixtures (`Fixtures/corner/`)

Real labeled Lenovo TB128XU and Galaxy S24 FE captures serve two purposes:

- **Acceptance (Story 10.18 AC2, the PO capture session's check):**

  ```bash
  uv run --project tools/corner-model python tools/corner-model/evaluate_model.py --fixtures-corner Fixtures/corner
  ```

  Evaluates only the real captures: recall, mean/median/p90 keypoint error (128 px crop) on positives and rejection on negatives, with the same presence threshold and geometry guard as the app. It exits nonzero when the directory is missing, holds no labeled capture, lacks positives or negatives, or falls below recall 92% / rejection 96%. `--json-out` writes the numbers for the evidence doc.
- **Fine-tuning (optional):** `train_corner_model.py --fixtures-corner` (default `Fixtures/corner/`) appends the captures to training. Evaluate on captures that were not trained on.

Each `*.json` file:

```json
{ "frame": "lenovo_stka_0001.png", "label": "positive", "keypoints": [[0.50, 0.12], [0.18, 0.31], [0.20, 0.72], [0.50, 0.90], [0.80, 0.72], [0.82, 0.31], [0.50, 0.50]] }
```

- `frame` (next to the JSON) must already be the model input crop, not a whole camera frame: square, upright, centred on `CornerGuide.vertices().last()` with half-size `guide.radius * 1.65`, and rotated by the sensor rotation exactly as `CornerModelTransform.cropToFrame` maps it. It is resized to 128×128.
- `label` is `"positive"` with exactly 7 `keypoints` `[T, UL, LL, B, LR, UR, C]`, each `[u, v]` normalized to that crop (`[0, 1]`), or `"negative"` with no keypoints. A sample is a negative only when labeled so.
- A malformed file (bad JSON, missing/non-square frame, missing label, wrong keypoint count or out-of-crop keypoint) stops training with its path; nothing is skipped silently.
