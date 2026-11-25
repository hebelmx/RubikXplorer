# PC Continuous-Scan Algorithm Spike

Standalone Python prototype proving the core continuous-scanning algorithm end-to-end on PC before touching Android stories or choosing a mobile detector.

## What this spike tests (honesty contract)

The synthetic 3D renderer (`synth_video.py`) passes the mapper (`mapper.py`) **only**:
- raw `640×480` BGR frames, and
- unordered, randomly cyclically shifted 2D clockwise face quads (`[p0, p1, p2, p3]`) with Gaussian corner jitter, random drops, and false-positive clutter quads.

The mapper receives **no** 3D pose, **no** camera intrinsics, **no** face names (`U/R/F/D/L/B`), **no** quarter-turn orientation, and **no** color labels.

## Core algorithm (`mapper.py`)

1. **Intrinsics-free 2D shared-edge topology graph:** Tracks face quads frame-to-frame and stitches adjacent faces strictly by shared 2D edges, assembling the 6-face cube graph even on solved or symmetric faces without `solvePnP` or camera intrinsics.
2. **Regrip recovery with symmetry guard:** After tracking breaks or a hand regrip blackout, builds a pending local fragment and merges it over the 24 proper SO(3) cube rotations only when top-1 beats top-2 by a clear margin (never locking quarter-turn rotation on a single symmetric/solved face).
3. **Shading-invariant + AWB-compensated deferred global color anchoring:** Tracks per-frame chromaticity AWB drift in log-RGB across overlapping faces, cancels scalar Lambertian shading via L2-normalized RGB before CIELAB conversion, and solves global 6-center orientation over 24 Western SO(3) rotations (working on pastel cubes, warm/cool lighting shifts, logo centers, and starting on Yellow/Orange/Blue with no White reference).
4. **Graded observation quality & local invariants:** Combines 2D foreshortening, Laplacian sharpness, specular glare detection, and patch heterogeneity to weight observations without deadlocking under overhead frontal glare; enforces immediate edge/corner cubie invariants (including corner cyclic chirality, `≤ 9` per color, duplicate-free cubies) with evidence-weighted conflict reopening and nonrigid mid-scan layer-twist detection.

## Run

From this directory (`pc_spike/`):

```sh
# Run all 7 stress scenarios (prints scored table)
uv run --no-project --with opencv-python-headless --with numpy python run_spike.py

# Render side-by-side MP4 replays + 2x3 contact sheets to /tmp/pc_spike_renders
uv run --no-project --with opencv-python-headless --with numpy python run_spike.py --render-dir /tmp/pc_spike_renders

# Run unit + scenario verification tests
uv run --no-project --with opencv-python-headless --with numpy python test_spike.py
```

## Clean-room Cubentia LiteRT sticker-center models (`litert_center/`)

Cubentia's own clean-room MobileNetV2 inverted-residual FPN heatmap model (255K params, pure LiteRT built-in ops, **0 extra runtime `.so` bytes** on existing `litert:2.3.2`) was trained purely on synthetic 1–3 visible-face renders + non-held-out `Fixtures/live` textures + verified cube-free tuning backgrounds (**zero** real `Fixtures/corner/` photos or held-out video clips in training).

- **v1 (`192×192 → 48×48`):** `sticker_centers.tflite` (511.6 KB FP16, `1.1–1.4 ms` PC CPU) — proved the architecture on still fixtures, but arm's-length webcam side faces collapsed to ~1.3 heatmap cells (`3.5%` held-out 2-face frames).
- **v2 (`256×256 → 64×64`, recommended):** same 255K-param backbone with wider arm's-length scale (`tz=4.0..11.2`), dim/bright exposure (`0.26..1.42`), checkerboard textures, 4th webcam-stickerless palette, motion blur, and warm multi-finger hand hard negatives:
  - **v2 FP16 (`sticker_centers_v2.tflite`):** **523,812 bytes (511.5 KB)**, `SHA-256 9514ca4f...`, `2.1–2.4 ms` PC CPU.
  - **v2 INT8 (`sticker_centers_v2_int8.tflite`):** **378,288 bytes (369.4 KB)** — **smaller than the existing 406 KB `corner_keypoints.tflite` it replaces (`-36.6 KB` net)** — `SHA-256 d18fb2c2...`, `1.2–1.8 ms` PC CPU.

### 1. Detector comparison on real Cubentia still fixtures (canonical holdout union strictly untouched)

| Detector | Model size | PC CPU latency | 3-face tuning hit (`n=14`, 378) | 3-face **held-out** hit (`n=16`, 432) | Frontal `Fixtures/live` (tuning / held-out) | Negative rejection (`n=73`) | `lattice_fit` 3-face recovery (tuning / held-out) |
|---|---:|---:|---:|---:|---:|---:|---:|
| **Cubentia v2 FP16 (`256→64`, clean-room)** | **511.5 KB** | **2.2–2.8 ms** | **98.1% (371/378)** (pastel `94.4%`) | **92.6% (400/432)** (`Lenovo/stl 94.4%`) | **99.3%** / **99.1%** | **94.5%** (5.5% FP) | **42/42 (100%)** / **48/48 (100%)** — **90/90 faces, 90/90 shared edges** |
| **Cubentia v2 INT8 (`256→64`, clean-room)** | **369.4 KB** | **1.5–1.9 ms** | **98.4% (372/378)** (pastel `96.3%`) | **92.6% (400/432)** | **98.9%** / **97.2%** | **94.5%** (5.5% FP) | **42/42 (100%)** / **48/48 (100%)** — **90/90 faces, 90/90 shared edges** |
| Cubentia v1 FP16 (`192→48`, baseline) | 511.6 KB | 1.1–1.4 ms | 97.1% (367/378) (pastel 90.7%) | 91.0% (393/432) (`Lenovo/stl 91.4%`) | 97.0% / 99.1% | 93.2% (6.8% FP) | 42/42 / 48/48 — 90/90 faces, 89/90 shared edges |
| `yush-io/cubeSolver` (read-only PC comparator, no license) | 6.25 MB `.pt` | ~63 ms | 89.9% (340/378) (pastel 70.4%) | Untested (holdout untouched) | 99.3% / — | — | 40/42 (95.2%) / — |
| `YORO` (read-only PC comparator, no license) | 6.40 MB `.pt` | ~65 ms | 26.5% (100/378) | Disqualified | 95.2% / — | — | Disqualified |
| `Super-Morse` (AGPL-3.0, poisoned generator) | 10.67 MB `.onnx` | ~15 ms | 46.3% (all 30; 320 dupes) | Disqualified | 96.8% (all 42) | 80.8% (19.2% FP) | Disqualified |

### 2. End-to-end `pixels -> LiteRT -> lattice_fit -> mapper` (7 stress scenarios, zero simulated detections)

Both **v2 FP16** and **v2 INT8** pass **7/7 continuous-scan stress scenarios** end-to-end from raw RGB pixels (**54/54 exact, 0 wrong, 0 transient wrong** on scenarios 1–6; mid-scan layer twist caught cleanly on Scenario 7; v2 FP16 `2.0–2.3 ms` det / `6.3–11.5 ms` map; v2 INT8 `1.2–1.6 ms` det / `5.8–11.9 ms` map on PC CPU).

### 3. Real handheld webcam benchmark (15 sessions in `captures/continuous-scan/manifest.json`: 10 tuning + 5 strictly untouched held-out)

Covers stickered (`stka`), vibrant stickerless (`stla`), and pastel stickerless (`stlb`) under high-cool, med-cool, low-cool (dim), and warm lighting, including checkerboards, scrambles, regrips, and intentional mid-scan twists (`thresh=0.45`):

| Model | Split | Mean `Q>=1` | Mean `Q>=2` (2-face) | Mean sides | Complete `54/54` (0 conf) | Notable held-out clips | False twists | Median det |
|---|---|---:|---:|---:|---:|---|---:|---:|
| v1 FP16 (`192→48`) | Tuning (`n=10`) | 79.9% | 9.9% | 2.60/6 | 0/10 | — | 0/9 | 1.40 ms |
| v1 FP16 (`192→48`) | **Held-out (`n=5`)** | 63.9% | 3.5% | 2.60/6 | 0/5 | `stlb_scramble` 0.0% 2-face (1/6); `stlb_low-cool` 0.9% 2-face (2/6) | 0/4 | 1.38 ms |
| **v2 FP16 (`256→64`)** | Tuning (`n=10`) | **90.8%** | **29.6% (+3.0×)** | **4.80/6** | **3/10** (+ `52/54` dim) | — | **0/9** | **2.24 ms** |
| **v2 FP16 (`256→64`)** | **Held-out (`n=5`)** | **93.6%** | **28.1% (+8.0×)** | **5.20/6** | **1/5** (+ `52/54` dim) | `stlb_scramble` **54/54 (0 conf)**; `stlb_low-cool` **52/54 (39.1% 2-face)** | **0/4** | **2.21 ms** |
| **v2 INT8 (`256→64`)** | Tuning (`n=10`) | **90.3%** | **28.5% (+2.9×)** | **4.70/6** | **3/10** | — | **0/9** | **1.81 ms** |
| **v2 INT8 (`256→64`)** | **Held-out (`n=5`)** | **93.3%** | **27.8% (+7.9×)** | **4.80/6** | **1/5** (+ `52/54` dim) | `stlb_scramble` **54/54 (0 conf)**; `stlb_low-cool` **52/54 (40.3% 2-face)** | **0/4** | **1.75 ms** |

> **Clean-room rule:** `yush-io/cubeSolver`, `YORO`, and `Super-Morse` are read-only PC comparators only — never bundled, distilled, or used for pseudo-labeling or training `sticker_centers*.tflite`.

### Train, evaluate, and run live on webcam or phone clip (LiteRT v2)

```sh
# 1. Re-train and export Cubentia's clean-room LiteRT v2 FP16 + INT8 models (~6 min on RTX A2000 GPU)
/mnt/e/Dynamic/IndFun/RubikXplorer/tools/corner-model/.venv/bin/python litert_center/train_center_model.py \
  --train-n 12000 --val-n 1000 --epochs 24 --batch-size 32 \
  --out /tmp/sticker_centers_v2.tflite --out-int8 /tmp/sticker_centers_v2_int8.tflite

# 2. Evaluate on real Cubentia fixtures + all 7 end-to-end continuous scenarios
uv run --no-project --python 3.12 --with ai-edge-litert --with opencv-python-headless --with numpy --with pillow \
  python litert_center/eval_end_to_end.py --model /tmp/sticker_centers_v2.tflite --split tuning --thresh 0.45

# 3. Benchmark across archived real-webcam sessions (tuning + untouched held-out)
uv run --no-project --python 3.12 --with ai-edge-litert --with opencv-python-headless --with numpy --with pillow \
  python litert_center/eval_real_clips.py --model /tmp/sticker_centers_v2_int8.tflite \
  --captures-dir /path/to/captures/continuous-scan --split all --thresh 0.45

# 4. Run on a recorded phone MP4 clip -> writes side-by-side MP4 replay + contact sheet
uv run --no-project --python 3.12 --with ai-edge-litert --with opencv-python-headless --with numpy --with pillow \
  python run_real_video.py --litert-model /tmp/sticker_centers_v2.tflite --video path/to/clip.mp4 --stride 1 --out-dir /tmp/real_replay

# 5. Run live on PC webcam (/dev/video0) with interactive window:
#    Keys: [R] reset scan, [V] toggle recording raw+HUD MP4 to /tmp/cubentia_live/,
#          [-]/[+] (or [/]) adjust webcam exposure, [A] auto-exposure, [Q/Esc] quit
#    Note: use `opencv-python` (not `-headless`) so cv2.imshow GUI support is enabled!
uv run --no-project --python 3.12 --with ai-edge-litert --with opencv-python --with numpy --with pillow \
  python run_real_video.py --litert-model /tmp/sticker_centers_v2.tflite --webcam 0 --show
```

## What stays open for physical Android devices

1. Benchmark `sticker_centers_v2_int8.tflite` (369.4 KB) and `sticker_centers_v2.tflite` (511.5 KB) on the physical Lenovo TB128XU, Galaxy Tab S7 FE 5G, and Galaxy S24 FE (replay + live camera) and verify `./gradlew :app:checkCameraSize` under NFR-11 (30 MB ceiling).
2. Add a 6-face visual progress/cue test on real devices (especially for symmetric checkerboard turns where a user rotates 90° without pausing on a 2-face bridge).
